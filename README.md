# Voice Assistant (Android)

A hands-free voice AI assistant for Bluetooth or wired headsets. You control it with **beeps** (short tones at ~2 kHz) that the headset microphone picks up:

| State | Signal | Action |
|---|---|---|
| Listening | 1 short beep | Start recording |
| Listening | 2 short beeps | Replay the last DeepSeek answer (no STT, no new request) |
| Recording | 1 beep | Stop recording → recognize → ask DeepSeek → speak the answer |
| Processing / Speaking / Replaying | beep | Ignored |

When the answer has been spoken, the app goes back to listening on its own.

## Requirements

- Android Studio (2026.2 or newer). The bundled JBR (JDK 25) works.
- Android SDK Platform **37** (`compileSdk = 37`, `targetSdk = 37`), `minSdk = 26`.
- A physical phone for real testing. Emulators don't give you headset or SCO routing.
- API key: **DeepSeek** only (https://platform.deepseek.com). Speech recognition runs on the phone (GigaAM v3 via sherpa-onnx) and needs no key.
- The sherpa-onnx AAR in `app/libs/` (not on Maven Central). Fetch it once with `bash scripts/download-sherpa-onnx.sh`.

## Setup

1. Open the project folder in Android Studio and let Gradle sync. The wrapper downloads Gradle 9.4.1.
2. `local.properties` must point to your SDK (`sdk.dir=...`). Android Studio writes this file for you.
3. API keys. Pick one:
   - **In the app (recommended):** Settings → *DeepSeek API key* → Save. Keys are encrypted with AES-GCM using an Android Keystore key.
   - **Debug builds only:** add them to `local.properties` (git-ignored):
     ```properties
     DEEPSEEK_API_KEY=sk-...
     ```
     These end up in `BuildConfig` of **debug** builds only. Anyone can extract a key that is compiled into an APK. Never ship one that way.
4. **Public distribution:** use a backend proxy that keeps the keys on the server. Set *Settings → DeepSeek API base URL* to your proxy.
5. **Speech model:** on first start the app asks to download GigaAM v3 (~326 MB, SHA-256 verified) into `filesDir/models/gigaam-v3/`. You can also do it from *Settings → Local STT model*. After that, recognition works in airplane mode.

## Build commands

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew test
./gradlew lint
```

On Windows, use `gradlew.bat ...` (or `./gradlew` in Git Bash).

- The debug APK goes to `app/build/outputs/apk/debug/app-debug.apk`.
- `assembleRelease` uses `keystore.properties` if it exists (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without it, the release APK is signed with the **debug** key so you can install it for testing. Do not publish that build.
- `./gradlew connectedDebugAndroidTest` runs the instrumented Room tests on a connected device.

## Install & first run

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Pair and connect the Bluetooth headset (or plug in a wired headset with a mic).
2. Open the app → **Start assistant** → grant *Microphone*, *Notifications*, and *Nearby devices* (Bluetooth).
3. Accept the notice that audio is uploaded for recognition.
4. Status shows **Listening for a beep**. A persistent notification has a **Stop** action.
5. Beep once, ask your question, beep once more. The answer plays in the headset.
6. Beep twice to hear the last answer again.

## Tuning the beep detector

Everything is under Settings → *Beep control*:

- **Frequency / tolerance:** defaults are 2000 Hz ± 50 Hz. Use FFT 1024 + Hann + parabolic interpolation.
- **Detection threshold:** default −30 dBFS. Lower it (e.g. −40) if beeps are missed. Raise it if you get false triggers.
- **Tone purity:** the share of the frame's energy that must sit in the target band. This is what rejects speech and noise.
- **Min/Max beep duration**, **double-beep interval** (default 450 ms) and **debounce** (default 350 ms).
- *Microphone processing:* keep **Voice recognition**. "Voice call" turns on echo/noise suppression, which can erase pure tones.

The Logs screen shows each detected beep with its duration, level and frequency, which helps with calibration.

## Architecture

```
app/src/main/java/com/helper/voiceassistant/
├── assistant/   AssistantController (workflow), AssistantStateMachine, AssistantPorts (interfaces)
├── detector/    BeepDetector (FFT/tonality/hysteresis), BeepEventProcessor (single vs double, debounce)
├── recorder/    AudioBuffer (ring buffer), VoiceRecorder, WavFileWriter
├── processor/   AudioProcessor (empty / too short / silent checks)
├── data/
│   ├── audio/     AudioStreamProcessor — the only AudioRecord, fans PCM out to buffer/recorder/detector
│   ├── stt/       OfflineTranscriber (sherpa-onnx + GigaAM v3), SttModelManager (download/verify)
│   ├── deepseek/  DeepSeekApiClient (POST /chat/completions, retries/backoff, cancellation)
│   ├── tts/       TtsSpeaker (TextToSpeech, voices, rate, volume, routing attributes)
│   ├── net/       OkHttp await + retry policy
│   ├── local/     Room DB, Keystore-encrypted secret store
│   └── repository/ DataStore settings, conversation repository
├── manager/     AudioDeviceManager (headsets, SCO/communication device, callbacks), AudioSessionManager (mode, focus)
├── service/     AudioCaptureService (foreground service, type=microphone)
├── presentation/ Compose screens (Main, Settings, History, Logs), ViewModels, navigation, theme
├── di/          Hilt modules
└── util/        FFT, log buffer, redaction, speech text cleanup
```

**How the beeps are kept out of recordings.** Every sample has an absolute stream index. The detector reports a conservative start and end for each tone. A recording starts at `beep1.end + guard`. Speech captured during the double-beep confirmation delay is back-filled from the ring buffer, so the start of your question is never lost. The file is truncated at `beep2.start − guard`.

## Known platform limitations

- Android 14+ only allows a **microphone foreground service** to start while the app is visible. Start the assistant from the app. It does not restart after force-stop, a reboot, or the system killing the process.
- Background behaviour varies by vendor (battery optimizers). If the assistant stops in the background, exclude the app from battery optimization.
- Bluetooth: the headset microphone works only over **HFP/SCO** (or LE Audio). A2DP has no microphone. Under SCO, playback quality is "phone call" quality. If TTS does not reach the headset on your phone, change *Settings → Playback stream*.
- Recognition is offline: audio never leaves the phone. Only the recognized text goes to DeepSeek, which needs the network. GigaAM v3 is Russian-only. The model takes ~400 MB of RAM while loaded; only ARM ABIs are packaged (no x86 emulators).

## Privacy

- Recordings are deleted right after recognition unless *Keep recordings* is on.
- History (last 20 exchanges by default) stays on the device. *Delete local data* wipes history, recordings, keys and logs.
- Logs are redacted. API keys are never written to logs, history, or settings.
