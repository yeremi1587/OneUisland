package dev.flexy.dynamicisland.island;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class AppInfo {
    private static final Map<String, Drawable.ConstantState> ICONS = new HashMap<>();
    private static final Map<String, String> LABELS = new HashMap<>();
    /** Remember packages with no icon so a missing package is not re-queried on every event. */
    private static final Set<String> NO_ICON = new HashSet<>();

    private AppInfo() {
    }

    static Drawable icon(Context ctx, String pkg) {
        if (pkg == null) return null;
        Drawable.ConstantState cached = ICONS.get(pkg);
        if (cached != null) return cached.newDrawable().mutate();
        if (NO_ICON.contains(pkg)) return null;
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            if (d == null) {
                NO_ICON.add(pkg);
                return null;
            }
            if (d.getConstantState() != null) ICONS.put(pkg, d.getConstantState());
            return d.mutate();
        } catch (Throwable t) {
            NO_ICON.add(pkg);
            return null;
        }
    }

    static String label(Context ctx, String pkg) {
        if (pkg == null) return "";
        String cached = LABELS.get(pkg);
        if (cached != null) return cached;
        String label = pkg;
        try {
            ApplicationInfo info = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            label = String.valueOf(ctx.getPackageManager().getApplicationLabel(info));
        } catch (Throwable ignored) {
        }
        LABELS.put(pkg, label);
        return label;
    }

    static String clip(CharSequence text, int max) {
        if (text == null) return "";
        String s = text.toString().trim();
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)).trim() + "\u2026";
    }
}
