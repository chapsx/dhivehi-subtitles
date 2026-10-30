#!/bin/bash
# Runs inside the Android emulator job: installs the app, opens a test video, records what happens.
set -x
PKG=mv.dhivehi.autosubs
MAIN=mv.dhivehi.subtitles.MainActivity
OUT="test-out/api${API}"
mkdir -p "$OUT"
adb install -r app.apk > "$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 0; }

# Start once so the app's own folder exists, then copy the test video into it.
adb shell am start -W -n "$PKG/$MAIN"
sleep 5
adb shell mkdir -p "/sdcard/Android/data/$PKG/files"
adb push test.mp4 "/sdcard/Android/data/$PKG/files/test.mp4"
adb shell ls -la "/sdcard/Android/data/$PKG/files/" > "$OUT/files.txt" 2>&1
adb shell am force-stop "$PKG"
adb logcat -c

# Same path as sharing a video into the app from the Gallery.
adb shell am start -W -a android.intent.action.SEND -t video/mp4 \
  --eu android.intent.extra.STREAM "file:///sdcard/Android/data/$PKG/files/test.mp4" \
  -n "$PKG/$MAIN" > "$OUT/start.txt" 2>&1

exported=0
for i in $(seq 1 28); do
  sleep 15
  adb exec-out screencap -p > "$OUT/shot_$(printf %02d "$i").png"
  pid=$(adb shell pidof "$PKG" | tr -d '\r')
  top=$(adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | tr -d '\r')
  echo "t=$((i*15))s pid=${pid:-DEAD} $top" >> "$OUT/timeline.txt"
  # When subtitles are ready (editor visible, no busy panel), tap Export once.
  if [ "$exported" = 0 ] && [ -n "$pid" ]; then
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
    adb shell cat /sdcard/ui.xml > "$OUT/ui_last.xml" 2>/dev/null
    if grep -q 'resource-id="mv.dhivehi.autosubs:id/captionList"' "$OUT/ui_last.xml" && \
       ! grep -q 'resource-id="mv.dhivehi.autosubs:id/busyText"' "$OUT/ui_last.xml"; then
      b=$(grep -o 'resource-id="mv.dhivehi.autosubs:id/exportButton"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' "$OUT/ui_last.xml" | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
      if [ -n "$b" ]; then
        nums=($(echo "$b" | grep -o '[0-9]*'))
        adb shell input tap $(( (nums[0]+nums[2])/2 )) $(( (nums[1]+nums[3])/2 ))
        echo "t=$((i*15))s TAPPED EXPORT" >> "$OUT/timeline.txt"
        exported=1
      fi
    fi
  fi
  if grep -q 'id/doneTikTok' "$OUT/ui_last.xml" 2>/dev/null; then echo "EXPORT DONE" >> "$OUT/timeline.txt"; break; fi
  [ -z "$pid" ] && [ "$i" -gt 2 ] && { echo "APP NOT RUNNING" >> "$OUT/timeline.txt"; break; }
done

adb exec-out screencap -p > "$OUT/shot_final.png"
adb logcat -d -v time > "$OUT/logcat_all.txt"
adb logcat -d -b crash > "$OUT/crash.txt"
adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt" 2>&1
adb shell ls -la "/sdcard/Movies/Dhivehi Subtitles/" > "$OUT/exported_files.txt" 2>&1
exit 0
