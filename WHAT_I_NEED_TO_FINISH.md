# What I need to finish the Voice Assistant

Status on 2026-11-18. Done and verified: the project builds (`assembleDebug`, `assembleRelease`), all 56 unit tests pass, and `lint` passes. Android Studio has the project open, but its first Gradle sync was still running.

Nothing has run on a real phone or headset yet. The rest of the specification can only be confirmed there.

---

## A. From you (I can't do these myself)

| # | What | Why | How |
|---|---|---|---|
| 1 | **DeepSeek API key** | No answers come back without it | Type it into the app: Settings → *DeepSeek API key* → Save. Or put it in `local.properties` as `DEEPSEEK_API_KEY=...` (debug builds only). **Don't paste keys into chat.** |
| 2 | **One-time speech-model download** (~326 MB, Wi-Fi recommended) | Speech is recognized offline on the phone with GigaAM v3; no STT key is needed | The app prompts on first *Start*, or Settings → *Local STT model* → *Download model*. |
| 3 | **An Android phone connected over USB** (Android 8.0 or newer) with *USB debugging* on | So I can install the app, read its logs (`adb logcat`) and confirm it works | Settings → About phone → tap *Build number* 7 times → Developer options → USB debugging. Connect the phone and accept the "Allow USB debugging" prompt. |
| 4 | **Your headset**: Bluetooth (must support calls, i.e. the hands-free profile) and/or wired with a mic | Bluetooth routing differs a lot between phones and headsets | Pair or plug it in before pressing *Start*. |
| 5 | **Your actual beep source**: what makes the beep, its pitch (Hz) and how long it lasts, if you know | The detector is tuned to 2000 Hz ± 50 Hz and 60–600 ms. A different beep needs different settings | Tell me the device or app, or send a short recording of the beep (WAV/MP3) and I'll measure it. |
| 6 | **Your choices on three permission prompts on the phone**: Microphone, Notifications, Nearby devices | Android only lets you grant these yourself | Tap *Allow* when the app asks. |
| 7 | *(Release only)* **A signing keystore**, or permission for me to create one | A release APK can't be published without it. Right now it's signed with the debug key | Create `keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. You enter the passwords yourself. |
| 8 | *(Public release only)* **A backend proxy** that holds the API keys | Any key built into the APK can be extracted | Decide whether you want this. I can write a small proxy if you say which hosting you'll use. |

## B. From me, once I have the phone (§A.3–A.5)

1. Install the debug APK with `adb install -r app/build/outputs/apk/debug/app-debug.apk` and watch `adb logcat`.
2. Walk through the spec's 18-step manual checklist (§18). Every step is listed in section D below.
3. Calibrate the detector to your beep, using the detected duration, volume and frequency shown on the Logs screen. Then adjust the defaults if needed.
4. Check where TTS audio actually plays on your phone (headset or phone speaker). If it isn't the headset, change the default *Playback stream* setting.
5. Run the device tests: `./gradlew connectedDebugAndroidTest` (Room history tests, which need a device).
6. Check background survival with the screen off and the app minimized. If your phone's battery manager kills the service, document the battery-optimization exception it needs.

## C. Gaps against the specification (still to do)

| Spec item | Current state | What's missing |
|---|---|---|
| §18 integration test "Foreground Service operation" | Not written | An instrumented test that starts `AudioCaptureService` and checks the notification and the stop action. Needs a device. |
| §18 integration test "Conversation history persistence" | Written (`ConversationDaoTest`), **not run** | Needs a device or emulator. |
| §18 manual tests on a physical device | Not done | See section D. |
| §22 "the app works on a real smartphone with a headset" | Not verified | Needs §A.3–A.5. |
| Android Studio Gradle sync | Started, not yet confirmed finished | I'll confirm once it completes. The shell build already succeeds. |
| The "Agent Mode" popup and "parallel sync" suggestion in Android Studio | I chose *Cancel* | Optional IDE preferences. Your call, nothing required. |

## D. Manual checklist (spec §18) — to tick on the phone

- [ ] 1. App launches
- [ ] 2. Permissions granted
- [ ] 3. Bluetooth headset connected
- [ ] 4. Headset microphone detected (main screen: "Microphone: … Bluetooth headset (HFP)")
- [ ] 5. Single beep detected
- [ ] 6. Recording starts
- [ ] 7. Terminating beep detected
- [ ] 8. Recording stops
- [ ] 9. Speech recognized
- [ ] 10. Text sent to DeepSeek
- [ ] 11. Answer received
- [ ] 12. Answer plays in the headset
- [ ] 13. Returns to listening
- [ ] 14. Double beep detected while listening
- [ ] 15. Last answer replayed **without** a new DeepSeek request (Logs: no new "DeepSeek reply" line)
- [ ] 16. Works while minimized / screen off
- [ ] 17. Headset disconnect and reconnect handled
- [ ] 18. Assistant stops manually (button and notification)

---

**Fastest path to done:** give me §A.1–A.5 (keys entered in the app, phone connected with USB debugging, headset paired, details of your beep). I'll then do section B and the device-dependent items in section C.
