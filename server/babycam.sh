#!/system/bin/sh
# Phone-side loop (pushed by the starter): keeps the camera server running, using the classes
# inside the installed Babycam app. Exit 0 ("quit") stops; exit 10 is a requested restart.
cd /data/local/tmp
while true; do
  [ -f babycam.log ] && [ "$(stat -c %s babycam.log)" -gt 2000000 ] && mv babycam.log babycam.log.1
  APK=$(pm path com.babycam | head -n1 | cut -d: -f2)
  if [ -z "$APK" ]; then echo "[babycam.sh] app not installed" >> babycam.log; sleep 10; continue; fi
  CLASSPATH=$APK app_process / com.babycam.Server >> babycam.log 2>&1
  code=$?
  echo "[babycam.sh] server exited $code" >> babycam.log
  [ "$code" = 0 ] && break
  [ "$code" = 10 ] || sleep 1
done
