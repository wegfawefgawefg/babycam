#!/bin/bash
# Watch + listen in mpv. Usage: ./watch.sh PHONE_IP   (or BABYCAM_PHONE=...)
PHONE=${1:-$BABYCAM_PHONE}
[ -n "$PHONE" ] || { echo "usage: $0 PHONE_IP"; exit 1; }
exec mpv --profile=low-latency --cache=no --untimed --audio-buffer=0.15 \
  --demuxer-lavf-probesize=32 --demuxer-lavf-analyzeduration=0 --video-latency-hacks=yes \
  --title=babycam "http://$PHONE:8090/live.mp4"
