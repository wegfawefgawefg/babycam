package com.babycam;

import android.os.IBinder;
import java.lang.reflect.Method;

// Turns the phone's panel off/on without putting the phone to sleep (like scrcpy -S).
// The system still thinks the screen is on, so nothing dozes; pressing power restores it.
final class Screen {
    static String set(boolean on) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            long[] ids = (long[]) sc.getMethod("getPhysicalDisplayIds").invoke(null);
            Method tok = sc.getMethod("getPhysicalDisplayToken", long.class);
            Method mode = sc.getMethod("setDisplayPowerMode", IBinder.class, int.class);
            for (long id : ids) mode.invoke(null, tok.invoke(null, id), on ? 2 : 0);
            return "screen " + (on ? "on" : "off");
        } catch (Throwable e) {
            return "screen failed: " + e;
        }
    }
}
