package dev.flexy.dynamicisland.xposed;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

public final class XLog {
    private static final String TAG = "DynamicIsland";

    /** Set to true locally to see the chatty logs that are normally dropped. */
    public static boolean DEBUG = false;

    private XLog() {
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
    }

    /** Chatty informational lines that only matter when debugging. */
    public static void v(String msg) {
        if (!DEBUG) return;
        i(msg);
    }

    public static void d(String msg) {
        if (!DEBUG) return;
        Log.d(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        try {
            XposedBridge.log(TAG + ": " + msg + ": " + Log.getStackTraceString(t));
        } catch (Throwable ignored) {
        }
    }
}
