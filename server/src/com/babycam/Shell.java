package com.babycam;

import android.annotation.SuppressLint;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.hardware.camera2.CameraManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

// Lets plain app_process code (running as uid 2000 shell) use framework services
// that want a Context, by pretending to be com.android.shell.
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
public final class Shell extends ContextWrapper {
    public static final String PKG = "com.android.shell";
    static Object activityThread;
    static Shell instance;

    static synchronized Shell get() throws Exception {
        if (instance == null) {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Constructor<?> c = at.getDeclaredConstructor();
            c.setAccessible(true);
            activityThread = c.newInstance();
            Field cur = at.getDeclaredField("sCurrentActivityThread");
            cur.setAccessible(true);
            cur.set(null, activityThread);
            Field sys = at.getDeclaredField("mSystemThread");
            sys.setAccessible(true);
            sys.setBoolean(activityThread, true);
            Class<?> abd = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> bc = abd.getDeclaredConstructor();
            bc.setAccessible(true);
            Object bind = bc.newInstance();
            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = PKG;
            Field aif = abd.getDeclaredField("appInfo");
            aif.setAccessible(true);
            aif.set(bind, ai);
            Field bound = at.getDeclaredField("mBoundApplication");
            bound.setAccessible(true);
            bound.set(activityThread, bind);
            Method gsc = at.getDeclaredMethod("getSystemContext");
            instance = new Shell((Context) gsc.invoke(activityThread));
            // OnePlus camera code asks ActivityThread.currentApplication() for a context.
            android.app.Application app = new android.app.Application();
            Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            attach.invoke(app, instance);
            Field init = at.getDeclaredField("mInitialApplication");
            init.setAccessible(true);
            init.set(activityThread, app);
        }
        return instance;
    }

    private Shell(Context base) { super(base); }

    @Override public String getPackageName() { return PKG; }
    @Override public String getOpPackageName() { return PKG; }
    @Override public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(2000).setPackageName(PKG).build();
    }
    @Override public Context getApplicationContext() { return this; }

    static CameraManager cameraManager() throws Exception {
        Constructor<CameraManager> c = CameraManager.class.getDeclaredConstructor(Context.class);
        c.setAccessible(true);
        return c.newInstance(get());
    }
}
