# Dhivehi Subtitles — ދިވެހި ސަބްޓައިޓަލް

An Android app to watch videos with Dhivehi (Thaana) subtitles, and to write, time and fix them on your phone.

## Download

Get the latest APK from **[Releases](https://github.com/chapsx/dhivehi-subtitles/releases/latest)** (file `DhivehiSubtitles.apk`).
Open it on your phone and allow "install unknown apps" when asked. Needs Android 7.0 or newer.

GitHub Actions (`.github/workflows/build-apk.yml`) builds every push and publishes a new release.

## Features

**Watch**
- Plays videos and audio from your phone, or a video link (MP4, MKV, WebM, HLS…)
- Shows Thaana subtitles right-to-left on the video, with your choice of size, colour, shadow, background and position
- Use any Thaana font (.ttf/.otf) such as Faruma, or the phone's built-in Noto Sans Thaana
- Full screen in landscape
- Sync − / Sync + to fix out-of-sync subtitles (tap for 0.1 s, hold for 1 s)

**Open**
- SRT, WebVTT, ASS/SSA
- Detects UTF-8, UTF-16 and older encodings
- Converts old ASCII-font Dhivehi (A_Faruma-style text that shows as Latin letters) to real Thaana, including files typed backwards

**Write and edit**
- Add a line at the current video time; set start and end while watching
- Thaana phonetic typing on any keyboard (h→ހ, a→ަ, q→ް …)
- Edit, split, merge, insert, delete, undo
- Shift all timings, find & replace, fix overlaps and empty lines
- Line length and reading speed shown while typing

**Save**
- Save back to the original file, save as SRT, export WebVTT, share
- UTF-8 with BOM option for TVs and Windows players
- Autosaved draft, so your work survives closing the app

**Thaana converter**: a notepad for phonetic typing and converting Latin/ASCII text.

**Dark mode**: choose Follow system, Light or Dark in Settings.

## Build it yourself

```
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. Needs JDK 17 and the Android SDK (platform 34).

## Signing

Add two repository secrets (Settings → Secrets and variables → Actions) and every build is signed
with the same key, so updates install over the old app:

- `SIGNING_KEYSTORE_BASE64`: the keystore file, base64-encoded
- `SIGNING_PASSWORD`: the keystore password (the key uses the same one)

The key alias defaults to `release`; set `SIGNING_KEY_ALIAS` if yours differs, or
`SIGNING_KEY_PASSWORD` if the key has its own password.

Make a key with:

```
keytool -genkeypair -keystore release.p12 -storetype PKCS12 -alias release \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Dhivehi Subtitles"
base64 -w0 release.p12   # paste the output into SIGNING_KEYSTORE_BASE64
```

Keep the keystore and password somewhere safe. Without them, future updates can't be signed
with the same key and people would have to uninstall first.

Without these secrets, builds fall back to a temporary debug key.
