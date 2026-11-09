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

/** Gaming island: optional compact temperature pill while a game from the configured
 *  list is in the foreground. Off by default. */
final class GameSource {
    /** The temperature pill refreshes on this cadence while a game is in the foreground. */
    private static final long GAME_TICK_MS = 5_000;
    /** Idle foreground probe — checks the game list only, no island refresh. */
    private static final long IDLE_POLL_MS = 5_000;

    private static final int GREEN = 0xFF30D158;
    private static final int AMBER = 0xFFFF9F0A;
    private static final int RED = 0xFFFD453A;

    private final Context ctx;
    private final OaSource oa;
    private final Handler main;
    private final Runnable onChanged;

    /** The configured list is read into this field whenever the settings change, so the
     * poll never re-parses it. */
    private Set<String> packages = IslandSettings.gamePackages();
    private boolean enabled;

    private boolean scheduled;
    private Set<String> lastPackagesState;

    GameSource(Context ctx, OaSource oa, Handler main, Runnable onChanged) {
        this.ctx = ctx;
        this.oa = oa;
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
            enabled = IslandSettings.gameTemperature();
            if (!enabled) unschedule();
            return;
        }
        lastPackagesState = next;
        packages = next;
        enabled = IslandSettings.gameTemperature();
        if (!enabled) unschedule();
        else schedule();
    }

    /** Called on every controller refresh; appends the temperature pill when a game runs. */
    void feed(List<IslandItem> out) {
        refreshPackages();
        if (!enabled) return;
        schedule();
        String fg = oa.foregroundPackage();
        if (fg == null || !packages.contains(fg)) return;
        IslandItem temp = temperatureItem(fg);
        if (temp != null) out.add(temp);
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
