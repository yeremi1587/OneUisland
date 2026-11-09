package dev.flexy.dynamicisland.island;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.util.List;
import java.util.Set;

import dev.flexy.dynamicisland.xposed.XLog;

/** Gaming island. Two optional settings, both off by default:
 *  - a compact temperature pill while a game from the configured list is in front;
 *  - a short session summary (duration, battery spent) after the game closes. */
final class GameSource {
    /** Both features refresh on this cadence while a game is in the foreground. */
    private static final long GAME_TICK_MS = 5_000;
    /** Idle foreground probe — checks the game list only, no island refresh. */
    private static final long IDLE_POLL_MS = 5_000;
    private static final long MIN_SESSION_MS = 60_000;
    private static final long SUMMARY_SHOW_MS = 5_000;

    private static final int GREEN = 0xFF30D158;
    private static final int AMBER = 0xFFFF9F0A;
    private static final int RED = 0xFFFD453A;

    private final Context ctx;
    private final OaSource oa;
    private final AlertSource alerts;
    private final Handler main;
    private final Runnable onChanged;

    /** The configured list is read into this field whenever the settings change, so the
     * poll never re-parses it. */
    private Set<String> packages = IslandSettings.gamePackages();
    private boolean enabled;

    private String inGame;
    private long startedAt;
    private int startBattery = -1;
    private boolean scheduled;
    private Set<String> lastPackagesState;

    GameSource(Context ctx, OaSource oa, AlertSource alerts, Handler main, Runnable onChanged) {
        this.ctx = ctx;
        this.oa = oa;
        this.alerts = alerts;
        this.main = main;
        this.onChanged = onChanged;
    }

    private final Runnable poll = this::poll;

    private void poll() {
        scheduled = false;
        if (!enabled) {
            return;
        }
        String fg = oa.foregroundPackage();
        boolean game = fg != null && packages.contains(fg);
        if (game) {
            // Full island refresh every GAME_TICK_MS while the temperature pill is shown.
            onChanged.run();
            main.postDelayed(poll, GAME_TICK_MS);
            scheduled = true;
        } else {
            // Idle: only the cheap foreground probe, no island work.
            main.postDelayed(poll, IDLE_POLL_MS);
            scheduled = true;
        }
    }

    private void schedule() {
        if (!scheduled) {
            scheduled = true;
            main.postDelayed(poll, IDLE_POLL_MS);
        }
    }

    private void unschedule() {
        scheduled = false;
        main.removeCallbacks(poll);
    }

    private void refreshPackages() {
        Set<String> next = IslandSettings.gamePackages();
        if (next.equals(lastPackagesState)) {
            enabled = IslandSettings.gamingEnabled();
            if (!enabled && inGame == null) unschedule();
            return;
        }
        lastPackagesState = next;
        packages = next;
        boolean wasEnabled = enabled;
        enabled = IslandSettings.gamingEnabled();
        if (!enabled) {
            if (inGame != null) leaveGame(inGame);
            inGame = null;
            unschedule();
        } else if (!wasEnabled) {
            schedule();
        }
    }

    /** Called on every controller refresh; appends the temperature pill when a game runs. */
    void feed(List<IslandItem> out) {
        refreshPackages();
        XLog.i("game feed: enabled=" + enabled + " fg=" + oa.foregroundPackage()
                + " packages=" + packages + " temp=" + IslandSettings.gameTemperature()
                + " session=" + IslandSettings.gameSession());
        if (!enabled) return;
        schedule();
        String fg = oa.foregroundPackage();
        boolean game = fg != null && packages.contains(fg);
        if (game) {
            if (inGame == null) {
                enterGame(fg);
            } else if (!inGame.equals(fg)) {
                leaveGame(inGame);
                enterGame(fg);
            }
        } else if (inGame != null) {
            leaveGame(inGame);
        }
        if (inGame != null && IslandSettings.gameTemperature()) {
            IslandItem temp = temperatureItem(inGame);
            if (temp != null) out.add(temp);
        }
    }

    private void enterGame(String pkg) {
        inGame = pkg;
        startedAt = android.os.SystemClock.elapsedRealtime();
        startBattery = batteryCapacity();
        XLog.i("game enter: " + pkg);
    }

    private void leaveGame(String pkg) {
        inGame = null;
        long duration = android.os.SystemClock.elapsedRealtime() - startedAt;
        XLog.i("game leave: " + pkg + " " + (duration / 1000) + "s");
        if (duration < MIN_SESSION_MS || !IslandSettings.gameSession() || startBattery < 0) {
            return;
        }
        int now = batteryCapacity();
        int spent = Math.max(0, startBattery - now);
        IslandItem it = new IslandItem("game:session");
        it.alert = true;
        it.kind = IslandItem.Kind.LIVE;
        it.packageName = pkg;
        it.icon = AppInfo.icon(ctx, pkg);
        it.iconIsImage = it.icon != null;
        it.overline = AppInfo.clip(AppInfo.label(ctx, pkg), 40);
        it.title = durationText(duration) + " · " + spent + "%";
        it.accent = GREEN;
        it.noExpand = true;
        it.postTime = Long.MAX_VALUE;
        alerts.showShort(it, SUMMARY_SHOW_MS);
        XLog.i("game session summary shown: " + it.title);
    }

    /** Battery percent via the sticky ACTION_BATTERY_CHANGED broadcast (no receiver leak). */
    private int batteryCapacity() {
        try {
            Intent stick = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (stick == null) return -1;
            int level = stick.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = stick.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
            if (level < 0 || scale <= 0) return -1;
            return level * 100 / scale;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String durationText(long ms) {
        long min = (ms + 30_000) / 60_000;
        if (min >= 60) {
            long h = min / 60;
            return h + " h " + (min % 60) + " min";
        }
        return min + " min";
    }

    /** Battery temperature read from the sticky battery broadcast; the color follows the
     *  thermal status. A separate chip temperature readout is not available without
     *  special permissions, so the battery reading stands in. */
    private IslandItem temperatureItem(String gamePkg) {
        try {
            Intent stick = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int tenth = stick == null ? -1 : stick.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            if (tenth < -100 || tenth > 900) return null;
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            int status = pm == null ? 0 : pm.getCurrentThermalStatus();
            IslandItem it = new IslandItem("game:temp");
            it.kind = IslandItem.Kind.LIVE;
            it.packageName = gamePkg;
            it.icon = AppInfo.icon(ctx, gamePkg);
            it.iconIsImage = it.icon != null;
            it.title = Math.round(tenth / 10f) + "°C";
            it.accent = status >= PowerManager.THERMAL_STATUS_SEVERE ? RED
                    : status >= PowerManager.THERMAL_STATUS_MODERATE ? AMBER : GREEN;
            it.noExpand = true;
            it.postTime = Long.MAX_VALUE - 1;
            return it;
        } catch (Throwable t) {
            return null;
        }
    }
}
