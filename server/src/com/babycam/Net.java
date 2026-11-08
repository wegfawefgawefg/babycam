package com.babycam;

import java.net.Socket;

final class Net {
    // Control-port allowlist: loopback (the phone app) and Settings.allow (the PC starter).
    static boolean allowed(Socket s) {
        java.net.InetAddress a = s.getInetAddress();
        if (a.isLoopbackAddress()) return true;
        String ip = a.getHostAddress();
        for (String ok : Settings.allow.split(",")) if (ok.trim().equals(ip)) return true;
        Server.log("refused " + ip);
        return false;
    }
}
