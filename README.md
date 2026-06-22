# SleepTalk 🌙🎙️

An Android app that listens through the microphone all night and keeps only the
moments where you actually made a sound (talking, mumbling, snoring…). Those
chunks are stitched together into a **single WAV file** so in the morning you can
listen to a short highlight reel instead of 8 hours of silence.

## How it works

- Tap **Start** before sleeping. Recording runs in a **foreground service** with a
  wake lock, so it keeps going with the screen off.
- Audio is analysed in real time. The app tracks the ambient **noise floor** and
  only writes audio that rises above it (with **0.8 s lead-in** and **1.5 s
  lead-out** padding so words aren't clipped). Pure silence is discarded.
- Tap **Stop & analyze** in the morning. The session is finalised into one WAV
  containing every detected segment, back to back.
- Each session shows up in the list with its **duration, segment count and size**.
  You can **play, share or delete** it.

The **Sensitivity** slider controls how quiet a sound has to be to count
(higher = catches quieter sounds). Watch the level bar: when it turns to
"🔊 Sound detected", that audio is being kept.

## Specs

- Format: 16 kHz mono 16-bit PCM WAV
- minSdk 26 (Android 8.0+), targetSdk 34
- Files live in `Android/data/com.sleeptalk.app/files/sessions/`

## Install the prebuilt APK

A ready-to-install debug APK is committed at the repo root:
`SleepTalk-v1.0-debug.apk`. Copy it to your phone and open it. You'll need to
allow **"Install unknown apps"** for whichever app opens it (browser / file
manager). After install, grant the **microphone** and **notifications**
permissions, and tap **Disable battery optimization** for reliable all-night
recording.

## Build it yourself

### Option A — GitHub Actions (no local tooling)
Every push builds the APK. Open the repo's **Actions** tab → latest **Build APK**
run → download the **SleepTalk-debug-apk** artifact.

### Option B — Android Studio
Open this folder in Android Studio (Hedgehog or newer), let it sync, then
**Run** on a device, or **Build → Build APK(s)**.

### Option C — command line
```sh
# Requires JDK 17+ and the Android SDK (set ANDROID_HOME / sdk.dir)
./gradlew :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Notes & tuning

- It's a **debug** build (unsigned for the Play Store, but installs fine via
  sideloading). 
- If you catch too much/too little, adjust the **Sensitivity** slider — it can be
  changed live while recording.
- Detection parameters (frame size, padding, threshold curve) live in
  `RecorderService.kt` if you want to tweak them.
