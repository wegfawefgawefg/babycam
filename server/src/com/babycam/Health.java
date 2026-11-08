package com.babycam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Phone health for the page: temperatures, throttling, battery and charging. Polled from
// dumpsys (we run as shell) every 20 s.
final class Health {
    static volatile double battTemp = -1, skinTemp = -1, cpuTemp = -1;
    static volatile int throttle = 0, level = -1;
    static volatile boolean charging = false;

    static void start() {
        new Thread(() -> {
            while (true) {
                try { poll(); } catch (Throwable e) { Server.log("health " + e); }
                try { Thread.sleep(20_000); } catch (InterruptedException e) { return; }
            }
        }, "health").start();
    }

    static String run(String... cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(cmd);
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        p.waitFor();
        return b.toString();
    }

    static void poll() throws Exception {
        String t = run("dumpsys", "thermalservice");
        int start = t.indexOf("Current temperatures from HAL");
        int end = t.indexOf("Current cooling devices", Math.max(start, 0));
        if (start >= 0) {
            String sec = t.substring(start, end > start ? end : t.length());
            Matcher m = Pattern.compile("mValue=([0-9.\\-]+), mType=(\\d+), mName=([^,]+), mStatus=(\\d+)").matcher(sec);
            double cpu = -1;
            int worst = 0;
            while (m.find()) {
                double v = Double.parseDouble(m.group(1));
                int type = Integer.parseInt(m.group(2));
                String name = m.group(3);
                worst = Math.max(worst, Integer.parseInt(m.group(4)));
                if (type == 2 && name.equals("battery")) battTemp = v;
                if (type == 3 && name.equals("skin")) skinTemp = v;
                if (type == 0) cpu = Math.max(cpu, v);
            }
            cpuTemp = cpu;
            throttle = worst;
        }
        String b = run("dumpsys", "battery");
        Matcher lv = Pattern.compile("level: (\\d+)").matcher(b);
        if (lv.find()) level = Integer.parseInt(lv.group(1));
        charging = b.contains("AC powered: true") || b.contains("USB powered: true") || b.contains("Wireless powered: true");
    }

    static String json() {
        return "{\"battery\":" + Settings.r(battTemp) + ",\"skin\":" + Settings.r(skinTemp) + ",\"cpu\":" + Settings.r(cpuTemp)
            + ",\"throttle\":" + throttle + ",\"level\":" + level + ",\"charging\":" + charging + "}";
    }
}
