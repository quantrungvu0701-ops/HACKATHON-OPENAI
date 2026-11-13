# AGENTS.md: build brief for Chỉ Đây

Read `MECHANISM.md` first. This file tells a coding agent (Claude Code today, Codex
tomorrow) what to build, in what order, and how to know each part works.

## Context

- AI hackathon, demo on a **real Android phone**. Judging, 100 points:
  - 40: judges fully close the app, reopen it, and the main AI flow runs live.
    Browser, emulator, video or canned output scores 0.
  - 35: real user problem with evidence, AI helps directly.
  - 25: route to first users and to global scale.
- Therefore: **native Android, live model calls, no mock path in the demo build**, and
  everything must survive kill-and-reopen.
- The model API is provided by the host on demo day. Provider, base URL, model and key
  are unknown until then and must be enterable **inside the app** without a rebuild.

## Step 0: set up this computer (agent does this before any code)

The user has no Android tooling installed. Install it yourself; ask only for a password
or a click you cannot do.

1. Detect the OS and CPU (`uname -a`, or `systeminfo` on Windows).
2. Install **JDK 17** (Temurin):
   - macOS: `brew install --cask temurin@17`
   - Windows: `winget install EclipseAdoptium.Temurin.17.JDK`
   - Linux: `sudo apt install openjdk-17-jdk`
3. Install **Android Studio** (it brings the SDK and a GUI the user can open later):
   - macOS: `brew install --cask android-studio`
   - Windows: `winget install Google.AndroidStudio`
   - Linux: `sudo snap install android-studio --classic`
   Studio's first-run wizard needs clicks. Do not wait for it. Also install the
   **command-line tools** into `ANDROID_HOME` (`~/Library/Android/sdk` on macOS,
   `%LOCALAPPDATA%\Android\Sdk` on Windows, `~/Android/Sdk` on Linux) and run:
   ```
   sdkmanager --licenses            # answer y
   sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" \
              "emulator" "system-images;android-34;google_apis;<x86_64|arm64-v8a>"
   ```
   Pick `arm64-v8a` on Apple Silicon or ARM, `x86_64` otherwise.
4. Set `JAVA_HOME` and `ANDROID_HOME`, and add `platform-tools`, `emulator` and
   `cmdline-tools/latest/bin` to `PATH` in the user's shell profile.
5. Create an emulator:
   `avdmanager create avd -n chiday -k "system-images;android-34;google_apis;<abi>" -d pixel_6`
   and boot it with `emulator -avd chiday`.
6. Verify: `java -version` shows 17, `adb devices` lists `emulator-5554`.
7. Write what was installed and where to `PROGRESS.md`.

If the emulator will not start (no hardware virtualisation), say so and continue: CI
still builds the APK, and the phone tomorrow becomes the only test device.

## Test device: emulator tonight, real phone tomorrow

The demo phone belongs to a teammate and is not available tonight.

- **Tonight:** every "passes when" check runs on the emulator. Install the Vietnamese
  TTS voice on the emulator (Settings → Accessibility → Text-to-speech) and put Chrome
  on it. Sideloading over `adb install` on an emulator is not affected by the
  restricted-settings lock.
- **Never present the emulator as the demo.** The rubric scores it 0.
- **Tomorrow, first thing:** run `PHONE_CHECKLIST.md` (below) on the real phone before
  any new feature work. Anything that differs from the emulator gets fixed first.

Create `PHONE_CHECKLIST.md` in milestone 1 with these items, each with the exact
command or tap path:
1. Phone model and Android version recorded in `PROGRESS.md`.
2. Developer options + USB debugging on; `adb devices` shows the phone as `device`.
3. Brand-specific install permissions (Xiaomi: "Install via USB" and "USB debugging
   (Security settings)", needs a Mi account; Oppo/Realme/Vivo: "Disable permission
   monitoring" or equivalent).
4. `adb install -r app-debug.apk` succeeds.
5. Accessibility toggle for Chỉ Đây can be turned on (if greyed out: App info → ⋮ →
   "Cho phép cài đặt bị hạn chế").
6. Battery: set Chỉ Đây to "Không hạn chế" / "No restrictions", and enable Autostart
   on Xiaomi/Oppo/Vivo.
7. Vietnamese TTS voice installed and speaks a test sentence.
8. The host's API key works via "Kiểm tra kết nối".
9. Kill-and-reopen 5 times: swipe from recents, reopen, tap bubble, ring + voice appear.
10. Each demo target service opens without personal login or OTP.

## Stack (do not change without a reason written in the PR)

- Kotlin, single Gradle module `app`, package `vn.chiday.app`
- minSdk 30 (needed for `AccessibilityService.takeScreenshot`), targetSdk 34, JDK 17
- Jetpack Compose for `MainActivity` screens; plain custom `View` + `WindowManager`
  for the overlay
- OkHttp + kotlinx.serialization, coroutines, DataStore for settings
- No DI framework, no backend. The phone calls the model API directly.
- CI: `.github/workflows/android.yml` runs `./gradlew assembleDebug` and uploads the APK

## Architecture

```
MainActivity (Compose)
  ├─ Home: status checklist (service on? key set? Vietnamese voice?) + "Bắt đầu"
  ├─ Settings: provider, base URL, model, key, "Kiểm tra kết nối"
  └─ DocCheck: camera/gallery → vision model → verdict in Vietnamese

GuideService : AccessibilityService        ← the core; one permission does everything
  ├─ BubbleWindow     draggable button, TYPE_ACCESSIBILITY_OVERLAY, touchable
  ├─ HighlightWindow  full screen, FLAG_NOT_TOUCHABLE, ring + arrow + subtitle
  ├─ ScreenReader     rootInActiveWindow → List<UiNode>
  ├─ GuideEngine      session state, debounce, calls LlmClient, validates result
  └─ Speaker          TextToSpeech, Locale("vi","VN")

llm/
  ├─ LlmClient (interface): suspend fun complete(system, user, imageJpeg?): String
  ├─ OpenAiCompatClient   POST {baseUrl}/chat/completions   (covers OpenAI, most
  │                        gateways, and Gemini's OpenAI-compatible endpoint)
  └─ AnthropicClient      POST {baseUrl}/v1/messages
playbook/
  ├─ Playbook.kt (schema from MECHANISM.md §3), PlaybookStore (assets + filesDir)
  └─ assets/playbooks/*.json
```

## Details that will bite

1. **Accessibility config** (`res/xml/guide_service.xml`):
   `canRetrieveWindowContent="true"`, `canTakeScreenshot="true"`,
   events `typeWindowStateChanged|typeWindowContentChanged`,
   flags `flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews`.
   Manifest service needs `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`
   and `android:exported="true"`.
2. **Use `TYPE_ACCESSIBILITY_OVERLAY`** for both windows, created with the service's
   context. No `SYSTEM_ALERT_WINDOW`, no MediaProjection, no foreground service.
3. **Coordinates.** `getBoundsInScreen` is in absolute screen pixels. Make the
   highlight window `MATCH_PARENT` with `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`
   and subtract `getLocationOnScreen()` of the view before drawing, or the ring is off
   by the status-bar height.
4. **Exclude ourselves.** Skip nodes whose package is ours. Hide the highlight before
   reading the tree or taking a screenshot.
5. **Never call `performAction` or `dispatchGesture`.** Guide-only is a product promise.
6. **Model output is untrusted.** Parse JSON leniently (strip code fences), then
   validate: `target` must be an existing index; otherwise show the "not sure" message.
7. **Debounce follow mode.** After a window event wait ~800 ms of quiet, compare a
   hash of the node list with the last analysed one, and skip if unchanged. One
   request in flight at a time; cancel the old one.
8. **Privacy filter** in `ScreenReader`: blank text where `isPassword`; mask digit
   runs of 6+.
9. **TTS.** Check `isLanguageAvailable(Locale("vi","VN"))`; if missing, show the
   subtitle only and a Home checklist item linking to voice-data install.
10. **Restricted settings (Android 13+).** Sideloaded APKs get a greyed-out
    accessibility toggle. Home must detect "service not enabled" and show steps:
    App info → ⋮ → "Cho phép cài đặt bị hạn chế", then enable the service. Installing
    with `adb install` usually avoids this; verify on the demo phone.
11. **Kill-and-reopen.** Settings live in DataStore. `MainActivity.onResume` re-checks
    service state. If the OEM killed the service, Home shows one button that opens
    the accessibility settings page. Test by swiping the app away from recents, and
    separately by Force stop.

## Prompt contract

System prompt (keep in `GuideEngine`, in English; output text in Vietnamese):

> You guide a Vietnamese user who is not confident with phones through a
> public-service app. You see a list of on-screen elements with indices. Choose the
> ONE element the user should act on next. Address the user as "bác". Use at most two
> short sentences, everyday words, no technical terms, describe where the button is on
> the screen. If a playbook is given, follow it. If the next action is payment,
> submission or deletion set caution=true. If you cannot tell, set target=null.
> Reply with JSON only: {service, step, total_steps, target, action, say, subtitle,
> caution, confidence}

User message: goal, last step, playbook JSON or "none", node list JSON.

## Milestones, in order. Do not start N+1 until N passes on the test device (emulator tonight, phone tomorrow).

Milestone 3 cannot use the host's key tonight. If the user has any personal key, use it
to test; otherwise test against a local stub server that returns a fixed valid JSON
reply. The stub is a dev tool only and must not ship in the app.

| # | Build | Passes when |
|---|---|---|
| 1 | Project skeleton, CI, GuideService with bubble | Bubble floats over Chrome and Settings and can be dragged |
| 2 | ScreenReader | Tapping the bubble logs a sensible node list for Chrome on dichvucong.gov.vn |
| 3 | Settings + LlmClient + "Kiểm tra kết nối" | A test call returns text with a key typed into the app |
| 4 | GuideEngine + HighlightWindow | Tap bubble → ring lands exactly on the right button, tap passes through |
| 5 | Speaker + subtitle card | Instruction is spoken in Vietnamese and shown |
| 6 | Kill-and-reopen pass | Swipe away, reopen, tap bubble: works with no re-setup. Repeat 5 times |
| 7 | Follow mode | A 4-step flow is guided with a single bubble tap |
| 8 | Playbooks: 2 hand-written flows, verified on the phone | Guided flow matches the playbook steps |
| 9 | DocCheck | A blurred ID photo is rejected with a clear reason, a good one passes |
| 10 | Stretch: paste-instructions → playbook; record mode | Draft playbook produced and usable |

Milestones 1–6 are the demo. 7–9 raise the score. 10 is for the pitch only if stable.

## Demo script the build must support

1. Judge closes the app from recents. Presenter reopens it: Home shows three green checks.
2. Presenter opens the target service, taps the bubble, says or picks a goal.
3. Ring + voice guide through 3–4 screens live.
4. Back in the app: photograph a document, get a live verdict.

Choose demo targets that need **no personal login and no OTP** on stage, and confirm
each one on the demo phone the night before.

## Working rules for agents

- Small commits, conventional messages, push to `main` after each passing milestone.
- After each milestone append one line to `PROGRESS.md`: what works, what is known
  broken, what is next. The next agent starts by reading it.
- Never commit API keys. Keys exist only in the phone's DataStore.
- UI strings in Vietnamese in `strings.xml`; code and comments in English.
- If a build or install fails twice the same way, stop and write the error to
  `PROGRESS.md` instead of trying variants.
