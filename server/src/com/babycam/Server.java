package com.babycam;

import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Collections;

// Baby cam server, runs as shell via app_process (see babycam.sh).
// Ports: 8090 HTTP (viewer page, /live.mp4, /api; see Http), 8703 text control for the
//        phone app and the PC starter (allowlisted), one command per line, see command().
// Structural changes (cam/rot/width) save settings and exit 10; babycam.sh restarts us.
public final class Server {
    static final int CTRL_PORT = 8703;
    static final int RESTART = 10;

    static CameraDevice camera;
    static CameraCaptureSession session;
    static CameraCharacteristics chars;
    static Handler camHandler;
    static MediaCodec encoder;
    static Gl gl;
    static volatile String camList = "";
    static CameraManager cm;
    static Handler glHandler;

    static void log(String s) { System.out.println("[babycam] " + s); System.out.flush(); }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((th, e) -> {
            log("CRASH in " + th.getName() + ": " + android.util.Log.getStackTraceString(e));
            System.exit(1);
        });
        Settings.load();
        Looper.prepareMainLooper();
        Shell.get();
        cm = Shell.cameraManager();
        StringBuilder cl = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            try {
                CameraCharacteristics c = cm.getCameraCharacteristics("" + i);
                float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                cl.append(i).append(':').append(c.get(CameraCharacteristics.LENS_FACING) == 0 ? "front" : "back")
                  .append('/').append(fl != null && fl.length > 0 ? fl[0] : 0).append("mm ");
            } catch (Exception e) { /* hidden or missing */ }
        }
        camList = cl.toString().trim();
        log("cameras " + camList);

        chars = cm.getCameraCharacteristics(Settings.cam);
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size src = pickSource(map.getOutputSizes(android.graphics.SurfaceTexture.class));
        int w = Settings.width & ~15, h = (w * 3 / 4) & ~15;
        // Android already turns camera frames by the sensor's mounting angle (90 on most back
        // cameras) before we see them, so the picture's shape depends on both that and our rotation.
        Integer mount = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        boolean sideways = (Settings.rot % 180 == 90) != ((mount == null ? 0 : mount) % 180 == 90);
        int outW = sideways ? h : w, outH = sideways ? w : h;
        log("cam " + Settings.cam + " source " + src + " output " + outW + "x" + outH + " rot " + Settings.rot);

        startEncoder(outW, outH);
        HandlerThread ct = new HandlerThread("cam");
        ct.start();
        camHandler = new Handler(ct.getLooper());
        HandlerThread gt = new HandlerThread("gl");
        gt.start();
        glHandler = new Handler(gt.getLooper());
        Object lock = new Object();
        glHandler.post(() -> {
            gl = new Gl(glHandler, encoderSurface, outW, outH, src.getWidth(), src.getHeight());
            gl.onMeasure = Server::autoExposure;
            synchronized (lock) { lock.notifyAll(); }
        });
        synchronized (lock) { while (gl == null) lock.wait(); }

        startControl();
        Http.start();
        Mic.start();
        openCamera();
        Looper.loop();
    }

    // Largest 4:3 source up to ~5 MP, so zoom has real pixels to crop into.
    static Size pickSource(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() * 3 != s.getHeight() * 4 || s.getWidth() > 2600) continue;
            if (best == null || s.getWidth() > best.getWidth()) best = s;
        }
        return best != null ? best : sizes[0];
    }

    static android.view.Surface encoderSurface;

    static void startEncoder(int w, int h) throws Exception {
        MediaFormat f = MediaFormat.createVideoFormat("video/avc", w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41);
        f.setInteger(MediaFormat.KEY_LATENCY, 1);
        f.setInteger(MediaFormat.KEY_PRIORITY, 0);
        f.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000);
        encoder = MediaCodec.createEncoderByType("video/avc");
        encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface();
        encoder.start();
        new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int i = encoder.dequeueOutputBuffer(info, 1_000_000);
                if (i < 0) continue;
                ByteBuffer b = encoder.getOutputBuffer(i);
                byte[] data = new byte[info.size];
                b.position(info.offset);
                b.get(data);
                encoder.releaseOutputBuffer(i, false);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (Live.sps == null) {
                    // This encoder may only put SPS/PPS in front of keyframes.
                    byte[] sps = null, pps = null;
                    for (byte[] u : nals(data)) {
                        int t = u[0] & 0x1f;
                        if (t == 7) sps = u; else if (t == 8) pps = u;
                    }
                    if (sps != null && pps != null) Live.setVideoConfig(sps, pps, w, h);
                }
                if (config) continue;
                Live.onVideo(data, info.presentationTimeUs);
            }
        }, "enc").start();
    }

    // Close the current camera and open another, feeding the same GL surface and encoder,
    // so the stream never breaks and viewers don't reconnect.
    static void switchCamera(String id) {
        long t0 = System.nanoTime();
        camHandler.post(() -> {
            try {
                gl.firstFrameHook = null;
                synchronized (Server.class) {
                    if (session != null) { session.close(); session = null; }
                    if (camera != null) { camera.close(); camera = null; }
                }
                Settings.cam = id;
                chars = cm.getCameraCharacteristics(id);
                Size src = pickSource(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(android.graphics.SurfaceTexture.class));
                Object done = new Object();
                boolean[] ok = {false};
                glHandler.post(() -> {
                    gl.st.setDefaultBufferSize(src.getWidth(), src.getHeight());
                    synchronized (done) { ok[0] = true; done.notifyAll(); }
                });
                synchronized (done) { while (!ok[0]) done.wait(); }
                log("switching to cam " + id + " source " + src + ", closed old in " + ms(t0));
                switchStart = t0;
                openCamera();
                Settings.save();
            } catch (Exception e) { log("switch failed " + e); System.exit(RESTART); }
        });
    }

    static volatile long switchStart = 0;
    static String ms(long t0) { return (System.nanoTime() - t0) / 1_000_000 + " ms"; }

    static java.util.List<byte[]> nals(byte[] a) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        int start = -1;
        for (int i = 0; i + 2 < a.length; i++) {
            if (a[i] == 0 && a[i + 1] == 0 && a[i + 2] == 1) {
                if (start >= 0) {
                    int end = i;
                    while (end > start && a[end - 1] == 0) end--;
                    out.add(java.util.Arrays.copyOfRange(a, start, end));
                }
                start = i + 3;
                i += 2;
            }
        }
        if (start >= 0 && start < a.length) out.add(java.util.Arrays.copyOfRange(a, start, a.length));
        return out;
    }

    static void requestKeyframe() {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(b);
        } catch (Exception ignored) {}
    }

    static void openCamera() throws Exception {
        cm.openCamera(Settings.cam, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice d) {
                camera = d;
                if (switchStart != 0) log("opened at " + ms(switchStart));
                try {
                    d.createCaptureSession(Collections.singletonList(gl.camSurface), new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            log("streaming" + (switchStart != 0 ? " at " + ms(switchStart) : ""));
                            long st0 = switchStart;
                            if (st0 != 0) gl.firstFrameHook = () -> log("first frame at " + ms(st0));
                            switchStart = 0;
                            apply();
                            Bundle b = new Bundle();
                            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                            encoder.setParameters(b);
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) { log("configure failed"); System.exit(2); }
                    }, camHandler);
                } catch (Exception e) { log("session " + e); System.exit(2); }
            }
            @Override public void onDisconnected(CameraDevice d) { if (d == camera || camera == null) { log("camera disconnected"); System.exit(3); } }
            @Override public void onError(CameraDevice d, int e) { log("camera error " + e); System.exit(4); }
        }, camHandler);
    }

    static Range<Long> expRange() { return chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE); }
    static Range<Integer> isoRange() { return chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE); }

    // Send the current settings to the camera.
    static synchronized String apply() {
        if (session == null) return "starting";
        try {
            CaptureRequest.Builder r = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            r.addTarget(gl.camSurface);
            if (Settings.ae.equals("cam")) {
                r.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                r.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, Settings.ev);
                r.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, lowestFpsRange());
            } else {
                long exp = (long) (Settings.exp * 1_000_000);
                exp = Math.max(expRange().getLower(), Math.min(expRange().getUpper(), exp));
                int iso = Math.max(isoRange().getLower(), Math.min(isoRange().getUpper(), Settings.iso));
                r.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                r.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
                r.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
                r.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(exp, 1_000_000_000L / Math.max(1, Settings.fps)));
            }
            r.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            session.setRepeatingRequest(r.build(), null, camHandler);
            return Settings.json();
        } catch (Exception e) { log("apply " + e); return "error " + e; }
    }

    static Range<Integer> lowestFpsRange() {
        Range<Integer>[] rs = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Range<Integer> best = rs[0];
        for (Range<Integer> r : rs)
            if (r.getLower() < best.getLower() || (r.getLower().equals(best.getLower()) && r.getUpper() > best.getUpper())) best = r;
        return best;
    }

    // Our auto exposure, run on the GL thread every 4th frame. Total light T = exp * iso * gain;
    // spend it on shutter first (up to maxExp, which lowers fps), then ISO, then digital gain.
    static void autoExposure() {
        if (!Settings.ae.equals("auto") || session == null) return;
        double mean = Math.max(0.004, Gl.lastMean);
        double seen = Math.min(1, mean * Settings.gain);
        double f = Settings.target / seen;
        if (Math.abs(Math.log(f)) < 0.08) return;
        f = Math.max(0.33, Math.min(3.0, Math.pow(f, 0.8)));
        double expMin = expRange().getLower() / 1e6, expMax = Math.min(expRange().getUpper() / 1e6, Settings.maxExp);
        double isoMin = isoRange().getLower(), isoMax = isoRange().getUpper();
        double total = Settings.exp * Settings.iso * Settings.gain * f;
        double exp = Math.max(expMin, Math.min(expMax, total / isoMin));
        double iso = Math.max(isoMin, Math.min(isoMax, total / exp));
        double gain = Math.max(1, Math.min(Settings.maxGain, total / (exp * iso)));
        Settings.exp = exp;
        Settings.iso = (int) iso;
        Settings.gain = gain;
        apply();
    }

    static void startControl() {
        new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(CTRL_PORT)) {
                ss.setReuseAddress(true);
                while (true) {
                    Socket s = ss.accept();
                    if (!Net.allowed(s)) { s.close(); continue; }
                    new Thread(() -> {
                        try (Socket c = s) {
                            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream()));
                            PrintWriter out = new PrintWriter(c.getOutputStream(), true);
                            String line;
                            while ((line = in.readLine()) != null) out.println(command(line.trim()));
                        } catch (Exception e) { /* client gone */ }
                    }, "ctrl-client").start();
                }
            } catch (Exception e) { log("ctrl died " + e); System.exit(5); }
        }, "ctrl").start();
    }

    static void restart() {
        Settings.save();
        log("restarting for new camera/rotation/size");
        new Thread(() -> {
            try { Thread.sleep(200); } catch (Exception ignored) {}
            System.exit(RESTART);
        }).start();
    }

    static String command(String line) {
        String[] p = line.split("\\s+");
        try {
            switch (p[0]) {
                case "status": return Settings.json();
                case "clock": return "{\"boot\":" + android.os.SystemClock.elapsedRealtimeNanos() / 1000
                    + ",\"mono\":" + System.nanoTime() / 1000
                    + ",\"tsSource\":" + chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) + "}";
                case "cam":
                    Settings.cam = p[1];
                    Settings.mono = p[1].equals("4");
                    Settings.ae = p[1].equals("4") ? "auto" : "cam";
                    Settings.gain = 1;
                    Settings.zoom = 1; Settings.panX = 0; Settings.panY = 0;
                    switchCamera(p[1]); return Settings.json();
                case "rot": Settings.rot = ((Integer.parseInt(p[1]) % 360) + 360) % 360 / 90 * 90; restart(); return "rotating";
                case "width": Settings.width = Integer.parseInt(p[1]); restart(); return "resizing";
                case "ae": Settings.ae = p[1]; if (!p[1].equals("auto")) Settings.gain = Settings.ae.equals("cam") ? 1 : Settings.gain; break;
                case "iso": Settings.iso = Integer.parseInt(p[1]); Settings.ae = "manual"; break;
                case "exp": Settings.exp = Double.parseDouble(p[1]); Settings.ae = "manual"; break;
                case "fps": Settings.fps = Integer.parseInt(p[1]); break;
                case "maxexp": Settings.maxExp = Double.parseDouble(p[1]); break;
                case "target": Settings.target = Double.parseDouble(p[1]); break;
                case "maxgain": Settings.maxGain = Double.parseDouble(p[1]); break;
                case "ev": Settings.ev = Integer.parseInt(p[1]); break;
                case "zoom": Settings.zoom = Math.max(1, Math.min(8, Double.parseDouble(p[1]))); break;
                case "pan": Settings.panX = clamp1(Double.parseDouble(p[1])); Settings.panY = clamp1(Double.parseDouble(p[2])); break;
                case "nudge": Settings.panX = clamp1(Settings.panX + Double.parseDouble(p[1])); Settings.panY = clamp1(Settings.panY + Double.parseDouble(p[2])); break;
                case "mono": Settings.mono = !p[1].equals("0"); break;
                case "gain": Settings.gain = Double.parseDouble(p[1]); break;
                case "gamma": Settings.gamma = Double.parseDouble(p[1]); break;
                case "allow": Settings.allow = p[1]; break;
                case "screen": return Screen.set(!p[1].equals("0"));
                case "restart": restart(); return "restarting";
                case "adb": {
                    // We run as shell, which may change this setting: lets the PC re-enable
                    // Wireless debugging for updates without touching the phone.
                    Process pr = Runtime.getRuntime().exec(new String[] {"settings", "put", "global", "adb_wifi_enabled", p[1].equals("0") ? "0" : "1"});
                    pr.waitFor();
                    return "wireless debugging " + (p[1].equals("0") ? "off" : "on");
                }
                case "quit": Settings.save(); System.exit(0);
                default: return "commands: status | cam N | rot 0/90/180/270 | width N | ae auto/cam/manual | iso N | exp MS | fps N"
                    + " | maxexp MS | target 0-1 | maxgain X | ev N | zoom X | pan X Y | nudge DX DY | mono 0/1 | gain X | gamma X"
                    + " | allow IP,IP | screen 0/1 | quit";
            }
        } catch (Exception e) { return "bad command: " + e; }
        Settings.save();
        return apply();
    }

    static double clamp1(double v) { return Math.max(-1, Math.min(1, v)); }
}
