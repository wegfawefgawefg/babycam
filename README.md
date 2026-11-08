# babycam

Turns an old Android phone into a baby monitor you can watch from any browser on your home
network: live video, sound, and remote controls (camera, zoom, pan, rotate, exposure,
black and white, screen off). No cloud, no account, no app on the viewing side.

Built for a OnePlus 8 Pro taped to a wall. That phone has a 5 MP "color filter" camera with
no infrared-cut filter, so with an 850 nm IR light it sees in a dark room. Normal apps can't
open that camera; code running as the adb shell user can, which is why the server runs the
way it does. Any Android 10+ phone works for the regular cameras.

## How it works

- `server/` is one APK, `com.babycam`. It contains:
  - a small app: a GO button, status, and a boot hook;
  - the camera server, which is started over adb as the shell user
    (`app_process` with the APK on the classpath), the same trick scrcpy uses. Shell has
    `SYSTEM_CAMERA`, so it can open hidden cameras.
- The server opens a camera, runs every frame through one GL pass (rotate, zoom/pan crop,
  black and white, digital gain), encodes H.264 in hardware, encodes the mic to AAC, and
  serves fragmented MP4 over HTTP on port 8090:
  - `/` viewer page with controls (works in desktop/mobile browsers and TV browsers)
  - `/live.mp4` the live stream, also plays in mpv or VLC
  - `/api?c=COMMAND` control commands, returns JSON status
- Its own auto exposure: shutter first, then ISO, then digital gain, lowering the frame rate
  in the dark. The IR camera bypasses the phone's image pipeline, so the camera's own auto
  exposure does nothing there.
- Camera switches happen inside the running server, so viewers never reconnect (about 0.5 s).
- `starter/starter.py` runs on a computer that has adb paired with the phone. When the
  server isn't running and the phone has Wireless debugging on (tap GO, or after a reboot
  the app turns it on), it starts the server and turns Wireless debugging back off.

Delay from capture to a browser is roughly 0.3 to 0.6 s.

## Build

Needs a JDK, Android build-tools, and an API 33 `android.jar`. No Gradle.

    ANDROID_BUILD_TOOLS=/path/to/build-tools/37.0.0 \
    ANDROID_JAR=/path/to/platforms/android-33/android.jar \
    server/build.sh

The script signs with a local debug key it creates (`server/debug.keystore`, not committed).

## Setup, once

1. On the phone: Developer options on, then turn on **USB debugging** and **Wireless
   debugging**. Pair this computer with "Pair device with pairing code"
   (`adb pair IP:PORT CODE`).
2. Install and grant the one permission the app needs (it lets GO and the boot hook switch
   Wireless debugging on):

       adb install server/out/babycam.apk
       adb shell pm grant com.babycam android.permission.WRITE_SECURE_SETTINGS

   OnePlus/Oppo: `pm grant` fails until you turn on Developer options > **Disable
   permission monitoring**.
3. Open the app once (Android doesn't run boot hooks for apps never opened).
4. Run the starter on a machine that stays on, or at least is on when you need a restart:

       starter/starter.py PHONE_IP

   Give the phone a fixed address on your router.
5. Keep USB debugging on. The server is a child of adbd; with USB debugging off, turning
   Wireless debugging off stops adbd and Android kills the server with it.

Then open `http://PHONE_IP:8090/` anywhere on the network. Tap once for sound. Double-click
the picture to hide the buttons. `watch.sh PHONE_IP` opens it in mpv.

## Controls

The page buttons cover everyday use. Everything is also a command on `/api?c=...`:

    cam N            switch camera (OnePlus 8 Pro: 0 main, 2 wide, 3 tele, 4 IR)
    rot 0|90|180|270 rotate (restarts the server, about 2 s)
    zoom X           digital zoom 1-8
    pan X Y / nudge DX DY
    ae auto|cam|manual, iso N, exp MS, fps N, maxexp MS, target 0-1, ev N
    mono 0|1, gain X, gamma X
    screen 0|1       panel off/on without letting the phone sleep (no OLED burn-in)
    status

Settings persist on the phone in `/data/local/tmp/babycam.properties`.

## Security

There is no password. Anyone on your network can watch and use the controls. That is the
whole security model, so:

- Don't forward port 8090 (or anything) to the phone from your router. If you want to watch
  away from home, use a VPN into your network such as WireGuard or Tailscale.
- The admin commands (`adb`, `allow`, `quit`) are refused from the page. They only work on
  the control port 8703, which accepts the phone itself and the computer that started the
  server.
- Wireless debugging is only on for the few seconds it takes to start the server, and adb
  needs a paired key anyway.

## Phone quirks found on the way (OnePlus 8 Pro, OxygenOS 13)

- Packages named `com.oplus*` or `com.oppo*` can't be installed unless OnePlus signed them,
  so you can't borrow the camera allowlist's package names.
- Don't `adb tcpip 5555` on this phone. It drops Wireless debugging and plain 5555
  connections stay offline. Use the Wireless debugging port.
- OnePlus camera code calls `ActivityThread.currentApplication()`, so the shell-side code
  needs a fake Application (see `Shell.java`).
- The hardware H.264 encoder only sends SPS/PPS in front of keyframes here, not as a
  separate config buffer.
