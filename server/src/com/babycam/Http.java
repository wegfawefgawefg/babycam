package com.babycam;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

// Tiny HTTP server: the viewer page, the live stream and the control API.
//   /          page (assets/index.html inside our APK)
//   /live.mp4  live fragmented MP4, also plays in mpv/VLC
//   /info      codec + state for the page
//   /api?c=..  run a control command, returns status JSON
final class Http {
    static final int PORT = 8090;

    static void start() {
        new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(PORT)) {
                ss.setReuseAddress(true);
                while (true) {
                    Socket s = ss.accept();
                    new Thread(() -> handle(s), "http").start();
                }
            } catch (Exception e) { Server.log("http died " + e); System.exit(5); }
        }, "http-accept").start();
    }

    static void handle(Socket s) {
        try (Socket sock = s) {
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(15000);
            InputStream in = sock.getInputStream();
            String request = readHeaders(in);
            String line = request.split("\r\n", 2)[0];
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String path = parts[1], query = "";
            int q = path.indexOf('?');
            if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }
            OutputStream out = sock.getOutputStream();
            switch (path) {
                case "/":
                    send(out, 200, "text/html; charset=utf-8", page());
                    break;
                case "/info":
                    send(out, 200, "application/json", ("{\"up\":" + (Live.init != null) + ",\"codec\":\"" + Live.codec()
                        + "\",\"width\":" + Live.width + ",\"height\":" + Live.height + ",\"viewers\":" + Live.viewers.size() + "}")
                        .getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api": {
                    String c = "status";
                    for (String kv : query.split("&"))
                        if (kv.startsWith("c=")) c = URLDecoder.decode(kv.substring(2), "UTF-8");
                    // Viewers on the LAN get camera controls only; phone-admin commands stay on the
                    // allowlisted control port.
                    String word = c.trim().split("\\s+")[0];
                    String reply = (word.equals("adb") || word.equals("allow") || word.equals("quit"))
                        ? "{\"error\":\"not allowed from the page\"}" : Server.command(c.trim());
                    send(out, 200, "application/json", reply.getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case "/live.mp4":
                case "/live":
                    if (Live.init == null) { send(out, 503, "text/plain", "starting".getBytes()); break; }
                    sock.setSoTimeout(0);
                    out.write(("HTTP/1.0 200 OK\r\nContent-Type: video/mp4\r\nCache-Control: no-store\r\n"
                        + "Access-Control-Allow-Origin: *\r\n\r\n").getBytes());
                    Server.log("viewer joined " + sock.getInetAddress().getHostAddress());
                    try { Live.serve(out); } finally { Server.log("viewer left " + sock.getInetAddress().getHostAddress()); }
                    break;
                default:
                    send(out, 404, "text/plain", "not found".getBytes());
            }
        } catch (Exception e) { /* client went away */ }
    }

    static String readHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, state = 0;
        while ((c = in.read()) >= 0 && b.size() < 16384) {
            b.write(c);
            state = (c == '\r' || c == '\n') ? state + 1 : 0;
            if (state == 4) break;
        }
        return b.toString("UTF-8");
    }

    static void send(OutputStream out, int code, String type, byte[] body) throws Exception {
        out.write(("HTTP/1.0 " + code + " OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
            + "\r\nCache-Control: no-store\r\n\r\n").getBytes());
        out.write(body);
        out.flush();
    }

    static byte[] page() throws Exception {
        try (ZipFile z = new ZipFile(System.getProperty("java.class.path"))) {
            try (InputStream in = z.getInputStream(z.getEntry("assets/index.html"))) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return b.toByteArray();
            }
        }
    }
}
