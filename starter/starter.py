#!/usr/bin/env python3
"""Babycam starter: keeps the camera server running on the phone.

The phone serves the page and stream itself; this only matters after the phone reboots or
the server is stopped. When the server isn't answering, look for the phone's Wireless
debugging port (the app's GO button or a reboot turns it on), start the server over adb
from the installed app, then turn Wireless debugging back off.

Usage: starter.py PHONE_IP     (or BABYCAM_PHONE=...)"""
import os
import re
import socket
import subprocess
import sys
import time

ADB = os.environ.get('BABYCAM_ADB', 'adb')
HERE = os.path.dirname(os.path.abspath(__file__))
APK = os.path.join(HERE, '..', 'server', 'out', 'babycam.apk')
KEEP_ADB = os.environ.get('BABYCAM_KEEP_ADB') == '1'


def adb(serial, *args, timeout=20, stdin=None):
    r = subprocess.run([ADB, '-s', serial, *args], capture_output=True, timeout=timeout, input=stdin)
    return r.stdout.decode(errors='replace').strip()


def local_ip(peer):
    """Our address as the phone sees it."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect((peer, 9))
        return s.getsockname()[0]


def server_up(ip):
    try:
        with socket.create_connection((ip, 8703), timeout=8):
            return True
    except OSError:
        return False


def mdns_ports(ip):
    """Wireless debugging announces its port over mDNS; asking costs nothing. avahi first:
    adb's own mDNS list sometimes comes back empty while avahi sees the phone."""
    ports = []
    try:
        r = subprocess.run(['avahi-browse', '-rpt', '_adb-tls-connect._tcp'], capture_output=True, timeout=10)
        for line in r.stdout.decode(errors='replace').splitlines():
            f = line.split(';')
            if len(f) > 8 and f[0] == '=' and f[7] == ip and f[8] not in ports:
                ports.append(f[8])
    except (OSError, subprocess.TimeoutExpired):
        pass
    try:
        r = subprocess.run([ADB, 'mdns', 'services'], capture_output=True, timeout=10)
        for port in re.findall(r'_adb-tls-connect\._tcp\s+' + re.escape(ip) + r':(\d+)', r.stdout.decode()):
            if port not in ports:
                ports.append(port)
    except subprocess.TimeoutExpired:
        pass
    return ports


def scan_ports(ip):
    # Fallback when mDNS doesn't answer. Gentle: this goes over wifi, and a fast scan floods
    # the band for everyone. A slow phone can make nmap take minutes.
    try:
        r = subprocess.run(['nmap', '-n', '-Pn', '-p', '30000-50000', '--max-rate', '300',
                            '--max-retries', '1', ip], capture_output=True, timeout=600)
    except subprocess.TimeoutExpired:
        return []
    return re.findall(r'^(\d+)/tcp open', r.stdout.decode(), re.M)


SCAN_EVERY = 600  # seconds; the scan is wifi traffic, mDNS is the normal way in
last_scan = 0.0


def connect(ip):
    """adb serial for the phone, or None. adb's mDNS list can hold a stale port, so scan when
    none of its ports answer, but rarely: while the phone is off this runs all night."""
    global last_scan
    serial = try_ports(ip, mdns_ports(ip))
    if serial or time.monotonic() - last_scan < SCAN_EVERY:
        return serial
    last_scan = time.monotonic()
    return try_ports(ip, scan_ports(ip))


def try_ports(ip, ports):
    for port in ports:
        serial = f'{ip}:{port}'
        try:
            r = subprocess.run([ADB, 'connect', serial], capture_output=True, timeout=4).stdout.decode()
        except subprocess.TimeoutExpired:
            continue  # not an adb port
        if r.startswith('connected') or 'already connected' in r:
            if adb(serial, 'get-state', timeout=5) == 'device':
                return serial
        subprocess.run([ADB, 'disconnect', serial], capture_output=True, timeout=5)
    return None


def install(serial, log):
    log('installing Babycam app')
    print(subprocess.run([ADB, '-s', serial, 'install', '-r', APK], capture_output=True, timeout=120).stdout.decode())
    adb(serial, 'shell', 'pm', 'grant', 'com.babycam', 'android.permission.WRITE_SECURE_SETTINGS')


def start(serial, ip, log):
    if not adb(serial, 'shell', 'pm', 'path', 'com.babycam'):
        install(serial, log)
    # With USB debugging off, switching Wireless debugging off stops adbd and Android kills
    # everything it started, our server included. USB debugging on keeps adbd alive.
    # Turning it on restarts adbd, so reconnect on the next round.
    if adb(serial, 'shell', 'settings', 'get', 'global', 'adb_enabled') != '1':
        log('turning USB debugging on so the server survives (adbd restarts)')
        adb(serial, 'shell', 'settings', 'put', 'global', 'adb_enabled', '1')
        return False
    with open(os.path.join(HERE, '..', 'server', 'babycam.sh'), 'rb') as f:
        adb(serial, 'shell', 'cat > /data/local/tmp/babycam.sh.new && chmod 755 /data/local/tmp/babycam.sh.new'
            ' && mv /data/local/tmp/babycam.sh.new /data/local/tmp/babycam.sh', stdin=f.read())
    me = local_ip(ip)
    adb(serial, 'shell', f'BABYCAM_ALLOW={me} setsid nohup sh /data/local/tmp/babycam.sh > /dev/null 2>&1 < /dev/null &')
    for _ in range(30):
        if server_up(ip):
            return True
        time.sleep(0.5)
    return False


def log(*a):
    print(time.strftime('%H:%M:%S'), *a, flush=True)


def watch(get_ip, log, state):
    down_since = None
    while True:
        ip = get_ip()
        if server_up(ip):
            down_since = None
            if state.get('phone') != 'running':
                log('camera server is running')
            state['phone'] = 'running'
            time.sleep(5)
            continue
        down_since = down_since or time.monotonic()
        # Slow wifi can look like "down"; only act after 30 s of no answer at all.
        if time.monotonic() - down_since < 30:
            time.sleep(5)
            continue
        if state.get('phone') != 'waiting':
            log('camera server is down; waiting for GO on the phone (Wireless debugging)')
        state['phone'] = 'waiting'
        try:
            serial = connect(ip)
            if serial:
                state['phone'] = 'starting'
                log('phone debug port found, starting camera server', serial)
                if start(serial, ip, log):
                    log('camera server started')
                    if not KEEP_ADB:
                        adb(serial, 'shell', 'settings', 'put', 'global', 'adb_wifi_enabled', '0')
                        subprocess.run([ADB, 'disconnect', serial], capture_output=True, timeout=5)
                        log('wireless debugging turned off')
                else:
                    log('camera server did not come up; see /data/local/tmp/babycam.log on the phone')
        except Exception as e:
            log('phone manager:', e)
        time.sleep(30)


if __name__ == '__main__':
    phone = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('BABYCAM_PHONE')
    if not phone:
        sys.exit('usage: starter.py PHONE_IP')
    log(f'babycam starter watching {phone}; viewer page http://{phone}:8090/')
    watch(lambda: phone, log, {})
