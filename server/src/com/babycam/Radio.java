package com.babycam;

import android.content.Context;
import android.net.wifi.WifiManager;

// Keeps the wifi radio fully awake while the server runs. Without this the phone's wifi
// power saving dozes between packets and an idle link answers in 250-500 ms.
final class Radio {
    static WifiManager.WifiLock perf, lowLatency;

    static void keepAwake() {
        try {
            WifiManager wm = (WifiManager) Shell.get().getSystemService(Context.WIFI_SERVICE);
            perf = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "babycam-perf");
            perf.setReferenceCounted(false);
            perf.acquire();
            lowLatency = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "babycam-lowlat");
            lowLatency.setReferenceCounted(false);
            lowLatency.acquire();
            Server.log("wifi locks held: high-perf " + perf.isHeld() + ", low-latency " + lowLatency.isHeld());
        } catch (Throwable e) {
            Server.log("wifi lock failed: " + e);
        }
    }
}
