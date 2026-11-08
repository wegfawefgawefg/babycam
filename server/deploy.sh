#!/bin/bash
# Install a new build on the phone and restart the camera server.
# Usage: deploy.sh PHONE_IP    (needs adb paired with the phone; ADB=... to override)
set -e
PHONE=${1:?usage: deploy.sh PHONE_IP}
ADB=${ADB:-adb}
cd "$(dirname "$0")"
ctl() { echo "$1" | timeout 8 nc -q1 "$PHONE" 8703; }
ctl 'adb 1'
trap "ctl 'adb 0' >/dev/null; $ADB disconnect >/dev/null 2>&1 || true" EXIT
SERIAL=""
for _ in $(seq 20); do
  # The phone announces its Wireless debugging port over mDNS; no port scan needed.
  for addr in $($ADB mdns services 2>/dev/null | grep -oP "_adb-tls-connect\._tcp\s+\K$PHONE:\d+"); do
    if timeout 6 $ADB connect "$addr" | grep -qE '^(connected|already)'; then
      [ "$($ADB -s "$addr" get-state 2>/dev/null)" = device ] && SERIAL=$addr && break 2
    fi
  done
  sleep 1
done
[ -n "$SERIAL" ] || { echo "phone's debug port not found"; exit 1; }
$ADB -s "$SERIAL" install -r out/babycam.apk | tail -1
ctl restart
