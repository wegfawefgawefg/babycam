package com.babycam;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.json.JSONObject;

// The phone-side face of the babycam: GO (ask the PC to start the camera), status, screen off.
// Everything else is controlled from the web page the server serves on port 8090.
public class MainActivity extends Activity {
    static final int BG = Color.rgb(10, 10, 10), TEXT = Color.rgb(200, 194, 180),
        DIM = Color.rgb(122, 117, 107), ACCENT = Color.rgb(224, 176, 96);

    TextView status, detail;
    Button go;
    final Handler ui = new Handler(Looper.getMainLooper());
    volatile boolean visible;
    long askedTime = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BG);
        int pad = dp(28);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text("Babycam", 30, TEXT);
        root.addView(title);
        status = text("checking", 20, ACCENT);
        status.setPadding(0, dp(24), 0, dp(6));
        root.addView(status);
        detail = text("", 15, DIM);
        detail.setPadding(0, 0, 0, dp(36));
        root.addView(detail);

        go = button("GO", 34, true);
        go.setOnClickListener(v -> start());
        root.addView(go, new LinearLayout.LayoutParams(dp(220), dp(220)));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(36), 0, 0);
        Button off = button("Screen off", 16, false);
        off.setOnClickListener(v -> send("screen 0"));
        row.addView(off);
        root.addView(row);
        setContentView(root);
    }

    void start() {
        if (WifiDebugJob.enable(this)) {
            askedTime = System.currentTimeMillis();
            status.setText("starting");
            detail.setText("Asked the PC to start the camera");
        } else {
            status.setText("needs setup");
            detail.setText("Permission missing. Run the setup step from the PC once.");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        new Thread(this::poll, "poll").start();
    }

    @Override protected void onPause() {
        super.onPause();
        visible = false;
    }

    void poll() {
        while (visible) {
            String s = query("status");
            ui.post(() -> show(s));
            try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
        }
    }

    void show(String s) {
        JSONObject j = null;
        try { if (s != null) j = new JSONObject(s); } catch (Exception ignored) {}
        if (j == null) {
            boolean waiting = System.currentTimeMillis() - askedTime < 60_000;
            status.setText(waiting ? "starting" : "off");
            detail.setText(waiting ? "Waiting for the PC to start the camera" : "Tap GO to start the camera");
            go.setEnabled(true);
            go.setAlpha(1f);
            return;
        }
        String[] names = {"Main", "Front", "Wide", "Tele", "IR"};
        String cam = j.optString("cam");
        int ci = cam.length() == 1 ? cam.charAt(0) - '0' : -1;
        String name = ci >= 0 && ci < names.length ? names[ci] : "camera " + cam;
        int viewers = j.optInt("viewers");
        status.setText("running");
        detail.setText(name + " camera" + (viewers > 0 ? ", " + viewers + " watching" : "")
            + "\nWatch at http://" + wifiIp() + ":8090");
        go.setEnabled(false);
        go.setAlpha(0.35f);
    }

    static String wifiIp() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.getName().startsWith("wlan")) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address) return a.getHostAddress();
            }
        } catch (Exception ignored) {}
        return "this-phone";
    }

    void send(String cmd) {
        new Thread(() -> query(cmd)).start();
    }

    static String query(String cmd) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 8703), 500);
            s.setSoTimeout(1500);
            PrintWriter out = new PrintWriter(s.getOutputStream(), true);
            out.println(cmd);
            s.shutdownOutput();
            return new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
        } catch (Exception e) {
            return null;
        }
    }

    TextView text(String t, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    Button button(String t, int sp, boolean round) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTextColor(round ? BG : TEXT);
        GradientDrawable d = new GradientDrawable();
        d.setColor(round ? ACCENT : Color.rgb(30, 30, 30));
        if (round) d.setShape(GradientDrawable.OVAL); else d.setCornerRadius(dp(10));
        b.setBackground(d);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        return b;
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
