#!/bin/bash
# Runs inside the Android emulator job: installs the app, opens a test video, records what happens.
set -x
PKG=mv.dhivehi.autosubs
MAIN=mv.dhivehi.subtitles.MainActivity
OUT="test-out/api${API}"
mkdir -p "$OUT"
adb install -r app.apk > "$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 0; }

# Put the test video in the phone's Movies folder and let the media library find it.
adb push test.mp4 /sdcard/Movies/dsubs_test.mp4
adb shell content call --uri content://media/ --method scan_volume --arg external_primary > /dev/null 2>&1
sleep 5
ID=$(adb shell content query --uri content://media/external/video/media --projection _id:_display_name \
     | tr -d '\r' | grep dsubs_test | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -1)
echo "media id: $ID" > "$OUT/files.txt"
URI="content://media/external/video/media/$ID"
adb logcat -c

# Same as "Open with Dhivehi Auto Subtitles" from the Gallery: the app gets temporary read access.
adb shell am start -W -a android.intent.action.VIEW -t video/mp4 -d "$URI" \
  --grant-read-uri-permission -n "$PKG/$MAIN" > "$OUT/start.txt" 2>&1

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

# Open the Style panel and capture the font picker.
tap_id() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  b=$(adb shell cat /sdcard/ui.xml | grep -o "resource-id=\"$PKG:id/$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1)
  [ -z "$b" ] && return 1
  nums=($(echo "$b" | grep -o '[0-9]*'))
  adb shell input tap $(( (nums[0]+nums[2])/2 )) $(( (nums[1]+nums[3])/2 ))
}
tap_id doneClose; sleep 2
tap_id styleButton && sleep 3 && adb exec-out screencap -p > "$OUT/style_panel.png"
tap_id fontGallery >/dev/null 2>&1; sleep 2
adb exec-out screencap -p > "$OUT/style_panel_fonts.png"
adb logcat -d -v time > "$OUT/logcat_all.txt"
grep "CAPTION_WORDS" "$OUT/logcat_all.txt" > "$OUT/caption_words.txt"
cp speech_truth.txt "$OUT/" 2>/dev/null
adb logcat -d -b crash > "$OUT/crash.txt"
adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt" 2>&1
adb shell ls -la "/sdcard/Movies/Dhivehi Subtitles/" > "$OUT/exported_files.txt" 2>&1
exit 0
