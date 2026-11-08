package com.babycam;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

// All camera settings. Saved to a file so restarts (camera/rotation/size changes,
// crashes) come back the same way.
final class Settings {
    static final String FILE = "/data/local/tmp/babycam.properties";

    // Structural: changing these restarts the server process.
    static volatile String cam = "4";
    static volatile int rot = 0;          // degrees clockwise, 0/90/180/270
    static volatile int width = 1280;     // encoded width before rotation (4:3)

    // Live.
    static volatile String ae = "auto";   // auto = ours, cam = camera's own, manual
    static volatile int iso = 400;
    static volatile double exp = 60;      // ms
    static volatile int fps = 15;
    static volatile double maxExp = 125;  // ms, longest shutter our auto exposure may use
    static volatile double target = 0.40; // auto exposure target brightness 0..1
    static volatile double maxGain = 4;   // digital gain our auto exposure may add
    static volatile int ev = 0;           // exposure compensation for the camera's own AE
    static volatile double zoom = 1;
    static volatile double panX = 0, panY = 0; // -1..1 of available travel, viewer axes
    static volatile boolean mono = true;
    static volatile double gain = 1;      // digital gain (auto exposure drives it in auto)
    static volatile double gamma = 1;
    // Only these addresses may use the control port (the PC starter). The phone itself is always allowed.
    static volatile String allow = "";

    static void load() {
        loadFile();
        // The PC starter passes its own address when it starts us.
        String extra = System.getenv("BABYCAM_ALLOW");
        if (extra != null && !extra.isEmpty() && !("," + allow + ",").contains("," + extra + ",")) {
            allow = allow.isEmpty() ? extra : allow + "," + extra;
        }
    }

    static void loadFile() {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(FILE)) { p.load(in); } catch (Exception e) { return; }
        cam = p.getProperty("cam", cam);
        rot = Integer.parseInt(p.getProperty("rot", "" + rot));
        width = Integer.parseInt(p.getProperty("width", "" + width));
        ae = p.getProperty("ae", ae);
        iso = Integer.parseInt(p.getProperty("iso", "" + iso));
        exp = Double.parseDouble(p.getProperty("exp", "" + exp));
        fps = Integer.parseInt(p.getProperty("fps", "" + fps));
        maxExp = Double.parseDouble(p.getProperty("maxExp", "" + maxExp));
        target = Double.parseDouble(p.getProperty("target", "" + target));
        maxGain = Double.parseDouble(p.getProperty("maxGain", "" + maxGain));
        ev = Integer.parseInt(p.getProperty("ev", "" + ev));
        zoom = Double.parseDouble(p.getProperty("zoom", "" + zoom));
        panX = Double.parseDouble(p.getProperty("panX", "" + panX));
        panY = Double.parseDouble(p.getProperty("panY", "" + panY));
        mono = Boolean.parseBoolean(p.getProperty("mono", "" + mono));
        gain = Double.parseDouble(p.getProperty("gain", "" + gain));
        gamma = Double.parseDouble(p.getProperty("gamma", "" + gamma));
        allow = p.getProperty("allow", allow);
    }

    static synchronized void save() {
        Properties p = new Properties();
        p.setProperty("cam", cam); p.setProperty("rot", "" + rot); p.setProperty("width", "" + width);
        p.setProperty("ae", ae); p.setProperty("iso", "" + iso); p.setProperty("exp", "" + exp);
        p.setProperty("fps", "" + fps); p.setProperty("maxExp", "" + maxExp); p.setProperty("target", "" + target);
        p.setProperty("maxGain", "" + maxGain); p.setProperty("ev", "" + ev); p.setProperty("zoom", "" + zoom);
        p.setProperty("panX", "" + panX); p.setProperty("panY", "" + panY); p.setProperty("mono", "" + mono);
        p.setProperty("gain", "" + gain); p.setProperty("gamma", "" + gamma); p.setProperty("allow", allow);
        try (FileOutputStream o = new FileOutputStream(FILE)) { p.store(o, "babycam"); } catch (Exception e) { Server.log("save " + e); }
    }

    static String json() {
        return "{\"cam\":\"" + cam + "\",\"rot\":" + rot + ",\"width\":" + width + ",\"ae\":\"" + ae + "\",\"iso\":" + iso
            + ",\"exp\":" + r(exp) + ",\"fps\":" + fps + ",\"maxExp\":" + r(maxExp) + ",\"target\":" + r(target)
            + ",\"maxGain\":" + r(maxGain) + ",\"ev\":" + ev + ",\"zoom\":" + r(zoom) + ",\"panX\":" + r(panX)
            + ",\"panY\":" + r(panY) + ",\"mono\":" + mono + ",\"gain\":" + r(gain) + ",\"gamma\":" + r(gamma)
            + ",\"brightness\":" + r(Gl.lastMean) + ",\"cams\":\"" + Server.camList + "\",\"allow\":\"" + allow
            + "\",\"viewers\":" + Live.viewers.size() + "}";
    }

    static String r(double d) { return String.format(java.util.Locale.ROOT, "%.3f", d); }
}
