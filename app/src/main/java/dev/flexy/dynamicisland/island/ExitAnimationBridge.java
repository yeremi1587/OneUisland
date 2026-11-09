package dev.flexy.dynamicisland.island;

import android.graphics.Rect;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;

import dev.flexy.dynamicisland.xposed.Refl;
import dev.flexy.dynamicisland.xposed.XLog;

final class ExitAnimationBridge {
    private static final String DESCRIPTOR = "com.android.systemui.shared.launcher.IOnGoingChipListener";
    private static final int TRANSACTION_ON_CHIP_INFO_CHANGED = 2;

    private final ClassLoader cl;
    private Object oaController;
    private Constructor<?> infoCtor;
    private String lastSignature;

    ExitAnimationBridge(ClassLoader cl) {
        this.cl = cl;
    }

    void setOngoingActivityController(Object controller) {
        oaController = controller;
        lastSignature = null;
    }

    void push(Collection<String> packages, Rect portrait, Rect landscape, boolean force) {
        if (oaController == null) return;
        String sig = packages + "|" + portrait + "|" + landscape;
        boolean changed = !Objects.equals(sig, lastSignature);
        if (!force && !changed) return;
        Object listener = Refl.get(oaController, "mChipInfoListener");
        Object remote = listener == null ? null : Refl.get(listener, "mRemote");
        if (!(remote instanceof IBinder)) return;
        try {
            if (infoCtor == null) {
                Class<?> cls = Class.forName("com.android.systemui.shared.launcher.OnGoingChipInfo", false, cl);
                infoCtor = cls.getConstructor(String.class, Rect.class, Rect.class);
            }
            ArrayList<Parcelable> list = new ArrayList<>();
            for (String pkg : packages) {
                list.add((Parcelable) infoCtor.newInstance(pkg, new Rect(portrait), new Rect(landscape)));
            }
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeTypedList(list, 0);
                ((IBinder) remote).transact(TRANSACTION_ON_CHIP_INFO_CHANGED, data, null, IBinder.FLAG_ONEWAY);
            } finally {
                data.recycle();
            }
            lastSignature = sig;
            if (changed) XLog.v("exit targets -> " + packages + " " + portrait + " / " + landscape);
        } catch (Throwable t) {
            XLog.e("exit target push failed", t);
        }
    }
}
