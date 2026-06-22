# Unsleep 🌅🎙️

> узнай, что ты говоришь во сне

An Android app that listens through the microphone all night, throws away the
hours of silence and stitches only the moments where there was a sound into one
short track — ready for the morning.

## Screens

1. **Готов слушать** — tap the sun to start. Shows last night's recap.
2. **Слушаю** — live timer, equalizer, running count of detected moments.
   Runs in a foreground service with a wake lock, so it keeps going with the
   screen off.
3. **Вырезаю тишину** — on stop, the night is finalised into the glued track.
4. **Доброе утро** — stats (total sound, sleep length, loudest moment), a player
   for the combined track, and a list of every moment with its time, length and
   mini-waveform. Share / replay / delete.

## Whisper calibration 🤫

Tap **Калибровка** on the home screen. Hold the phone ~50 cm away; the app
measures the room's silence, then your whisper, and sets the detection threshold
a safe **40 % below** that whisper so even quiet sleep-talk is caught. The value
is saved and used for every following night.

## How detection works

- 16 kHz mono PCM, analysed in real time in 100 ms frames.
- The app tracks the ambient **noise floor** and keeps audio that rises above the
  threshold (calibrated whisper level, or adaptive if you never calibrated),
  with **0.8 s lead-in** and **1.5 s lead-out** so words aren't clipped.
- Each kept chunk becomes a "moment" with its own time/length/waveform; all
  chunks are concatenated into one WAV.

Files live in `Android/data/com.sleeptalk.app/files/sessions/` (`.wav` + a
`.json` sidecar with the metadata).

## Install the prebuilt APK

`Unsleep-v1.0-debug.apk` is committed at the repo root. Copy it to your phone and
open it; allow **"Install unknown apps"** for the opener. On first launch grant
**microphone** + **notifications**, and accept the **battery-optimization**
prompt so the night recording isn't killed.

## Build it yourself

- **GitHub Actions:** every push builds the APK — Actions tab → latest run →
  download the `Unsleep-debug-apk` artifact.
- **Android Studio:** open the folder, sync, Run.
- **CLI:** `./gradlew :app:assembleDebug` (needs JDK 17+ and the Android SDK).

## Notes

- Debug build (sideload-only, not signed for the Play Store).
- Detection tuning (frame size, padding, threshold curve) lives in
  `RecorderService.kt`; the whisper test in `Calibrator.kt`.
- Fonts: Newsreader + Mulish (bundled). Built with Kotlin, AGP 8.5, minSdk 26.
