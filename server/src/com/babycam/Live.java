package com.babycam;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

// Live fMP4 stream shared by every viewer. Video and audio both get timestamps on our own
// clock (System.nanoTime), so camera switches with different sensor clocks don't matter.
final class Live {
    static byte[] sps, pps, aacConfig;
    static volatile byte[] init;
    static int width, height;
    static int seq = 1;
    static final List<Mp4.Sample> pendingAudio = new ArrayList<>();
    static long vBase = Long.MIN_VALUE, lastVideoTime = -1, lastPts = -1;
    static int lastDuration = Mp4.VIDEO_SCALE / 15;
    static long aNext = -1;
    static final long T0 = System.nanoTime() / 1000;

    static final class Viewer {
        final BlockingQueue<byte[]> q = new ArrayBlockingQueue<>(240);
        volatile boolean started, dead;
        String ip = "", what = "";
        final long since = System.currentTimeMillis();
    }

    // "who is watching" for the page: device kind from the User-Agent, plus address.
    static String describe(String ua) {
        String u = ua.toLowerCase();
        if (u.contains("web0s") || u.contains("webos") || u.contains("smarttv") || u.contains("smart-tv")) return "TV";
        if (u.contains("mpv") || u.contains("lavf") || u.contains("ffmpeg")) return "mpv";
        if (u.contains("vlc")) return "VLC";
        String browser = u.contains("firefox") ? "Firefox" : u.contains("edg/") ? "Edge" : u.contains("chrome") ? "Chrome"
            : u.contains("safari") ? "Safari" : "player";
        if (u.contains("iphone") || u.contains("ipad")) return browser + " on iPhone/iPad";
        if (u.contains("android")) return browser + " on Android";
        if (u.contains("windows")) return browser + " on Windows";
        if (u.contains("mac os")) return browser + " on Mac";
        if (u.contains("linux") || u.contains("x11")) return browser + " on Linux";
        return browser;
    }

    static String watchersJson() {
        StringBuilder b = new StringBuilder("[");
        long now = System.currentTimeMillis();
        for (Viewer v : viewers) {
            if (b.length() > 1) b.append(',');
            b.append("{\"ip\":\"").append(v.ip).append("\",\"what\":\"").append(v.what.replace("\"", ""))
             .append("\",\"secs\":").append((now - v.since) / 1000).append('}');
        }
        return b.append(']').toString();
    }

    static final List<Viewer> viewers = new CopyOnWriteArrayList<>();

    static long nowUs() { return System.nanoTime() / 1000 - T0; }

    static synchronized void setVideoConfig(byte[] s, byte[] p, int w, int h) {
        sps = s; pps = p; width = w; height = h;
        Server.log("video config " + w + "x" + h);
        makeInit();
    }

    static synchronized void setAudioConfig(byte[] c) {
        aacConfig = c;
        Server.log("audio config " + c.length + " bytes");
        makeInit();
    }

    static void makeInit() {
        if (sps != null && pps != null && aacConfig != null && init == null) {
            init = Mp4.init(sps, pps, width, height, aacConfig);
            Server.log("live stream ready " + width + "x" + height + " " + codec());
        }
    }

    static String codec() {
        return sps == null ? "" : String.format("avc1.%02X%02X%02X", sps[1], sps[2], sps[3]);
    }

    // Encoded H.264 access unit (Annex-B) with the encoder's timestamp.
    static synchronized void onVideo(byte[] annexB, long ptsUs) {
        if (init == null) return;
        long now = nowUs();
        if (vBase == Long.MIN_VALUE || Math.abs(vBase + ptsUs - now) > 300_000) vBase = now - ptsUs;
        long t = (vBase + ptsUs) * Mp4.VIDEO_SCALE / 1_000_000;
        if (t <= lastVideoTime) t = lastVideoTime + 1;
        if (lastPts >= 0 && ptsUs > lastPts && ptsUs - lastPts < 1_000_000)
            lastDuration = (int) ((ptsUs - lastPts) * Mp4.VIDEO_SCALE / 1_000_000);
        lastPts = ptsUs;
        lastVideoTime = t;
        boolean[] key = {false};
        byte[] sample = lengthPrefixed(annexB, key);
        List<Mp4.Sample> audio = new ArrayList<>(pendingAudio);
        pendingAudio.clear();
        broadcast(Mp4.fragment(seq++, new Mp4.Sample(sample, t, lastDuration, key[0]), audio), key[0]);
    }

    // One AAC frame (1024 samples).
    static synchronized void onAudio(byte[] frame) {
        if (init == null) return;
        long now = nowUs() * Mp4.AUDIO_SCALE / 1_000_000;
        if (aNext < 0 || Math.abs(aNext - now) > Mp4.AUDIO_SCALE / 5) aNext = now;
        pendingAudio.add(new Mp4.Sample(frame, aNext, 1024, true));
        aNext += 1024;
        // If video stalls, don't let audio pile up.
        if (pendingAudio.size() >= 12) {
            List<Mp4.Sample> audio = new ArrayList<>(pendingAudio);
            pendingAudio.clear();
            broadcast(Mp4.fragment(seq++, null, audio), false);
        }
    }

    // Annex-B -> 4-byte length prefixed NALs, dropping SPS/PPS (they live in the init segment).
    static byte[] lengthPrefixed(byte[] a, boolean[] key) {
        Mp4.Out o = new Mp4.Out();
        int n = a.length, i = 0, start = -1;
        while (i <= n) {
            boolean sc = i + 2 < n && a[i] == 0 && a[i + 1] == 0 && a[i + 2] == 1;
            if (sc || i == n) {
                if (start >= 0) {
                    int end = i;
                    while (end > start && a[end - 1] == 0 && sc) end--;
                    int type = a[start] & 0x1f;
                    if (type == 5) key[0] = true;
                    if (type != 7 && type != 8 && end > start) {
                        o.i32(end - start);
                        o.bytes(java.util.Arrays.copyOfRange(a, start, end));
                    }
                }
                if (i == n) break;
                start = i + 3;
                i += 3;
            } else {
                i++;
            }
        }
        return o.get();
    }

    static void broadcast(byte[] frag, boolean key) {
        for (Viewer v : viewers) {
            if (!v.started) {
                if (!key) continue;
                v.started = true;
            }
            if (!v.q.offer(frag)) {
                v.dead = true;
                viewers.remove(v);
                Server.log("dropped a slow viewer");
            }
        }
    }

    // Blocks, streaming to one HTTP viewer until it disconnects.
    static void serve(OutputStream out, String ip, String ua) throws Exception {
        byte[] in = init;
        if (in == null) throw new IllegalStateException("not streaming yet");
        out.write(in);
        out.flush();
        Viewer v = new Viewer();
        v.ip = ip;
        v.what = describe(ua);
        viewers.add(v);
        Server.requestKeyframe();
        try {
            while (!v.dead) {
                byte[] f = v.q.poll(10, TimeUnit.SECONDS);
                if (f == null) break;
                out.write(f);
                out.flush();
            }
        } finally {
            viewers.remove(v);
        }
    }
}
