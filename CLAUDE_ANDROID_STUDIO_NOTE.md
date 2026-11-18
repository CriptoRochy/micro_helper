# Note for Claude: building and running this project in Android Studio

Instructions for a Claude session that works on this repository (`C:\MY_Files\Project\helper_v2`). They cover opening, building, testing, and installing the Voice Assistant app with Android Studio and Gradle on this Windows machine.

## 1. Environment facts (verified 2026-11-18)

| Item | Value |
|---|---|
| Android Studio | `C:\Program Files\Android\Android Studio` (AI-262, 2026.2.2) |
| JDK for Gradle | Android Studio's JBR: `C:\Program Files\Android\Android Studio\jbr` (JDK 25). System `java` is Temurin 25, which also works |
| Android SDK | `C:\Users\bator\AppData\Local\Android\Sdk` (platform `android-37.0`, build-tools 36/37) |
| Gradle | wrapper 9.4.1 (`gradle/wrapper/gradle-wrapper.properties`) |
| AGP / Kotlin | AGP 9.2.1 (built-in Kotlin, so **do not** apply `org.jetbrains.kotlin.android`), Kotlin 2.3.21, KSP 2.3.14 |
| Key libraries | Compose BOM 2026.09.00, Hilt 2.60.1, Room 2.8.5, DataStore 1.3.0, OkHttp 4.12.0 (+ MockWebServer 4.12.0) |
| Versions file | `gradle/libs.versions.toml` (the only place to bump versions) |

## 2. Build from the shell (preferred, fastest)

Use the Bash tool (Git Bash). Always set `JAVA_HOME` first:

```bash
cd /c/MY_Files/Project/helper_v2
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # JVM unit tests (detector, state machine, recorder, API clients, controller)
./gradlew lint                 # report: app/build/reports/lint-results-debug.html
./gradlew assembleRelease      # minified; debug-signed unless keystore.properties exists
```

- The first build downloads dependencies and can take 10+ minutes. Run it with `run_in_background` and redirect output to a log file in the scratchpad.
- If you write `.kts` files through a bash heredoc, check that regex backslashes survived (`"com\\.android.*"`). This already broke `settings.gradle.kts` once. Prefer the Write tool for source files.

## 3. Working in the Android Studio GUI

Android Studio is granted to computer-use only at the **"click" tier**: screenshots and left-clicks work, but typing and key presses don't. That means:

1. Launch it with `open_application("Android Studio")`. If the welcome screen shows, click **Open**. Selecting the folder needs typing, so either ask the user to pick `C:\MY_Files\Project\helper_v2`, or open it via the recent-projects list if it's there.
2. After the project opens, click **Sync Now** (or the elephant "Sync Project with Gradle Files" icon) and wait for "Gradle sync finished".
3. Running on a device: the user connects the phone (USB debugging on), then click the green **Run ▶** button for the `app` configuration. Alternatively install from the shell:
   ```bash
   "/c/Users/bator/AppData/Local/Android/Sdk/platform-tools/adb.exe" devices
   "/c/Users/bator/AppData/Local/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. Do every edit with the file tools, never through the IDE editor. Android Studio picks up changes on disk automatically.

## 4. API keys: never commit or log them

- **Debug:** `local.properties` (git-ignored) may contain `DEEPSEEK_API_KEY=`. It is injected into debug `BuildConfig` only.
- **Runtime:** Settings screen → stored encrypted by `KeystoreSecretStore` (Android Keystore AES-GCM).
- Never ask the user to paste keys into chat. Never put keys in source files, tests, or logs. `Redactor` scrubs `sk-...` and `Bearer ...`.

## 5. Current API facts (fetched with agent-reach from official docs, 2026-11-18)

- **DeepSeek:** `POST https://api.deepseek.com/chat/completions`, Bearer auth.
  - Models: `deepseek-flash` (default in the app) and `deepseek-v4-pro`. The legacy names `deepseek-v4-flash*` are retired aliases.
  - Thinking mode is **enabled by default** server-side. The app sends `{"thinking":{"type":"disabled"}}` unless *Settings → Thinking mode* is on, which keeps voice latency low.
- **Speech recognition is on-device:** sherpa-onnx 1.13.0 AAR (`app/libs/`, via `scripts/download-sherpa-onnx.sh`) + GigaAM v3 E2E RNNT INT8 (`modelType = "nemo_transducer"`), downloaded at runtime by `SttModelManager` from the govorun-lite `model-gigaam-v3` GitHub release with pinned SHA-256. No OpenAI/cloud STT.
- Re-check both pages before changing defaults: `https://api-docs.deepseek.com/quick_start/pricing`, `https://api-docs.deepseek.com/guides/thinking_mode`.

## 6. Where things live (for changes)

| Concern | File |
|---|---|
| Beep FFT detection | `app/src/main/java/com/helper/voiceassistant/detector/BeepDetector.kt` |
| Single vs double beep, debounce | `detector/BeepEventProcessor.kt` |
| The single AudioRecord + fan-out | `data/audio/AudioStreamProcessor.kt` |
| Beep exclusion / ring buffer | `recorder/VoiceRecorder.kt`, `recorder/AudioBuffer.kt` |
| Workflow + recovery | `assistant/AssistantController.kt`, `assistant/AssistantStateMachine.kt` |
| Headset routing (SCO, communication device) | `manager/AudioDeviceManager.kt` |
| Foreground service + notification | `service/AudioCaptureService.kt` |
| Settings model/defaults | `domain/model/AssistantSettings.kt`, persisted by `data/repository/DataStoreSettingsRepository.kt` |

The controller depends only on the interfaces in `assistant/AssistantPorts.kt`. Keep it that way: `AssistantControllerTest` drives the whole loop with fakes on the JVM.

## 7. What cannot be verified without the user's phone

Real headset routing (HFP/SCO, wired), whether TTS actually reaches the headset, beep detection with the user's actual beep source, background survival under the vendor's battery manager, and the 18-step manual checklist in the specification (§18). Ask the user to run these and to share the **Logs** screen (Settings → Technical logs) when something misbehaves. Beep lines there include duration, dBFS and Hz for calibration.
