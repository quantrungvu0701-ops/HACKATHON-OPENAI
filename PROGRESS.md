# PROGRESS

The next agent starts here. Newest milestone line is at the bottom.

## Dev machine setup (Step 0, done 2026-11-14)

Windows 11 Home x64, AMD Ryzen 7 7735HS, 15.6 GB RAM. WHPX is usable, so the emulator
runs with hardware acceleration.

| What | Version | Where |
|---|---|---|
| JDK | Temurin 17.0.21 | `C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot` (`JAVA_HOME`) |
| Android Studio | 2026.2.2.8 | `C:\Program Files\Android\Android Studio` (first-run wizard not done; not needed) |
| Android SDK | cmdline-tools 12.0, platform-tools, platforms;android-34, build-tools;34.0.0, emulator, system-images;android-34;google_apis;x86_64 | `F:\Android\Sdk` (`ANDROID_HOME`) |
| AVD | `chiday`, Pixel 6, Android 14 (API 34), google_apis x86_64, 1080x2400 @420dpi | `F:\Android\avd` (`ANDROID_AVD_HOME`) |
| Gradle cache | wrapper 8.7 | `F:\Android\gradle-home` (`GRADLE_USER_HOME`) |

**Deviation from AGENTS.md:** the SDK, AVD and Gradle cache live on `F:` instead of
`%LOCALAPPDATA%\Android\Sdk`, because `C:` had only ~20 GB free. All five variables above
plus the `PATH` entries (`platform-tools`, `emulator`, `cmdline-tools\latest\bin`, JDK `bin`)
are set for the Windows user account. Git Bash: `. /f/Android/env.sh`.

Everyday commands (new terminal, so the variables are loaded):

```
emulator -avd chiday -no-boot-anim          # boot the emulator
.\gradlew assembleDebug                     # build
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell settings put secure enabled_accessibility_services vn.chiday.app/vn.chiday.app.GuideService
adb shell settings put secure accessibility_enabled 1     # emulator shortcut for the toggle
adb logcat -s ChiDay                        # app log tag
```

Emulator notes:
- Chrome's first-run screen is skipped with a debug command line
  (`/data/local/tmp/chrome-command-line` + `am set-debug-app --persistent com.android.chrome`).
  This is an emulator convenience only; on the phone Chrome is already set up.
- Google TTS (`com.google.android.tts`) and Chrome ship in the `google_apis` image.

## Milestones

- **M1 (2026-11-14) PASS on emulator.** Project skeleton, CI workflow, `GuideService` with
  draggable bubble (`TYPE_ACCESSIBILITY_OVERLAY`). Bubble floats over Settings and over
  Chrome on dichvucong.gov.vn; `adb shell input swipe` drags it and it snaps to the nearest
  edge; position is remembered. Home shows the accessibility check, the restricted-settings
  steps and "Bắt đầu". Known broken: nothing. Next: M2 ScreenReader.
- **M2 (2026-11-14) PASS on emulator, with one caveat.** `ScreenReader` turns the focused app
  window into the MECHANISM §2.1 node list (roles, text/desc/hint, view id, bounds, Chrome
  host from the URL bar; password text removed, digit runs of 6+ masked, our own windows
  skipped, cap 150). Bubble tap logs it under tag `ChiDay`. Verified in Chrome on
  chinhphu.vn (20 clean nodes, Vietnamese labels). **Caveat:** dichvucong.gov.vn did not
  load tonight: TCP 443 to 14.238.3.76 times out from this PC's network as well as from the
  emulator, so the reader was only checked there against Chrome's error page (host was
  detected correctly). Re-check the portal on the phone / venue network (PHONE_CHECKLIST
  item 10). Next: M3 Settings + LlmClient.
- **M3 (2026-11-14) PASS on emulator against the dev stub.** Settings screen (provider,
  base URL, model, key, "Kiểm tra kết nối") backed by DataStore; `OpenAiCompatClient`
  (`{baseUrl}/chat/completions`, also used for Gemini's OpenAI endpoint) and `AnthropicClient`
  (`{baseUrl}/v1/messages`). A key typed into the app returned text through both clients;
  Home shows a green "Kết nối AI" check and updates live when the service binds.
  **Not tested against a real provider:** no real key was available tonight, so the test
  used `tools/stub/stub_server.py` (dev tool, not in the APK) at `http://10.0.2.2:8787`.
  First thing with the host's key: PHONE_CHECKLIST item 8. Cleartext HTTP is allowed in
  the manifest because the host's base URL is unknown and may be a plain-http gateway.
  The emulator still holds the stub settings (key `test-key`). Next: M4.
- **M4 (2026-11-14) PASS on emulator against the dev stub.** `GuideEngine` (prompt contract,
  lenient JSON parse that strips code fences, validation: target must be an existing index,
  confidence >= 0.5) and `HighlightWindow` (full-screen `FLAG_NOT_TOUCHABLE` overlay: dim,
  pulsing ring, arrow for small targets, instruction card). In Chrome on chinhphu.vn the ring
  landed exactly on the "English" link (node rect 462,440-616,485), an `adb` tap at the ring
  went through to Chrome (navigated to vietnam.gov.vn), and the ring cleared itself when the
  target left the screen. `target=null` shows "Tôi chưa chắc…". Bubble: tap = analyse,
  tap while thinking = cancel, tap while a ring is up = end session. If the user scrolls,
  the ring follows the same element. Home has a goal field ("Bác muốn làm việc gì?") that is
  sent as GOAL. **Which element a real model picks is untested** (stub picks by regex).
  Testing note: `adb shell uiautomator dump` suspends accessibility services while it runs,
  so do not use it during a guide session; use screenshots and `adb logcat -s ChiDay`.
  Next: M5.
- **M5 (2026-11-14) PASS on emulator.** `Speaker` uses the phone's TextToSpeech with
  `Locale("vi","VN")` at rate 0.92; the instruction is spoken (Google TTS dispatched voice
  `vi-vn-x-vic-seanet-embedded`, `speech started`/`speech done` in the log, speech
  AudioTrack active) and shown on the card with "Bước n/m". `caution=true` turns ring and
  card amber and adds "Bác kiểm tra kỹ thông tin trước khi bấm." to card and speech. If the
  Vietnamese voice is missing nothing is spoken, the card still shows, and Home's third check
  "Giọng đọc tiếng Việt" links to voice-data install. Home now shows three checks. Speech was
  verified from logs and the audio system, not by ear: listen on the phone (checklist item 7).
  Next: M6.
- **M6 (2026-11-14) PASS on emulator.** `bash tools/kill_reopen_test.sh 5`: 5/5 rounds of
  swipe away from recents, reopen, open Chrome, tap bubble gave ring + speech with no
  re-setup; Home showed three green checks each time. Also checked:
  (a) `kill -9` of the app process: Android rebound the service under a new pid, bubble came
  back, saved key still worked; (b) **Force stop**: Android clears the accessibility setting
  (`enabled_accessibility_services` became `null`), Home shows a red "Quyền trợ năng" row and
  one "Mở Cài đặt trợ năng" button (which opens the accessibility settings page); after
  switching the service back on the key and voice checks were still green and guiding worked.
  **Emulator caveat:** on AOSP the recents swipe removes the task but the process survives
  because the accessibility service is bound, so round 1 to 5 did not exercise a process
  death; (a) did. OEM skins (Xiaomi, Oppo, Vivo) often kill the process and sometimes the
  service on swipe, which is what PHONE_CHECKLIST items 6 and 9 are for.

## State after milestones 1 to 6

Works on the emulator: bubble, screen reading, settings + both model clients, ring + card,
Vietnamese speech, kill-and-reopen. CI builds the debug APK on every push to `main`.

Not yet proven, in order of risk for the demo:
1. **No real model has been called.** Everything after M2 ran against
   `tools/stub/stub_server.py`. With the host's key: run "Kiểm tra kết nối", then check that
   the model picks sensible targets and answers within about 3 seconds. `AnthropicClient`
   sends `anthropic-version: 2026-10-21` and no sampling parameters; `OpenAiCompatClient`
   sends only `model` and `messages`.
2. **Nothing has run on a real phone.** Run PHONE_CHECKLIST.md top to bottom first.
3. **dichvucong.gov.vn was unreachable** from this network tonight (see M2).
4. Screenshot fallback for apps with an empty accessibility tree is not built (the manifest
   already declares `canTakeScreenshot`). A nearly empty tree currently gives "Tôi chưa chắc".

Next milestones: M7 follow mode (replace "clear the ring" in `GuideService.recheckTarget`
with "analyse again"; the 800 ms settle and one-request-in-flight pieces are already there),
M8 playbooks (`GuideEngine.userMessage` sends `PLAYBOOK: none` today), M9 DocCheck (both
clients already accept `imageJpeg`).

Dev stub: `python tools/stub/stub_server.py`, then in the app set provider "OpenAI / tương
thích", base URL `http://10.0.2.2:8787/v1`, any model name, any key. Choose what it points
at with `curl "http://localhost:8787/pick?q=<regex>"`.
- **Team setup (2026-11-14).** Repo renamed to `HACKATHON-OPENAI`. Debug builds on every
  machine and in CI are signed with the committed `keystore/debug.keystore`, so APKs from
  different laptops install over each other; a device with an older build needs one
  `adb uninstall vn.chiday.app` first. `PROGRESS.md` is union-merged. Home shows the commit
  id of the build. Team rules are in `README.md`.
- **M7 (2026-11-14) PASS on emulator against the dev stub.** Follow mode: one bubble tap
  starts a session; after each screen change the service waits for 800 ms of quiet (2.5 s at
  most, for pages that never go quiet), skips screens whose node hash is unchanged or where
  the ringed element is still present (the ring follows it instead), and otherwise analyses
  again, one request in flight, at most one automatic call per 1.5 s. A second tap ends the
  session; a session with no analysis for 5 minutes ends itself. Bubble is green while a
  session is active. `bash tools/follow_mode_test.sh`: 4 rings over 4 Settings screens,
  1 bubble tap, 3 automatic re-analyses, 4 spoken. The same instruction is not spoken twice
  in a row, and an unsure answer in follow mode is shown without speech.
- **M8 (2026-11-14) PASS on emulator against the dev stub; phone check pending.** Playbook
  schema + `PlaybookStore` (assets + `filesDir/playbooks`), two hand-written flows for
  vnpost.vn in Chrome, written from the live site's accessibility tree:
  `vnpost-tim-buu-cuc` (4 steps over 2 screens) and `vnpost-theo-doi-buu-gui` (3 steps on one
  screen; the user types the code and the captcha themself, the guide stops at the
  "Theo dõi" button). Home lists playbook goals as one-tap buttons. The engine picks the
  playbook by app/host + goal, sends it with `LIKELY_STEP` (signal matching, ties broken by
  session progress), forces `total_steps` and `caution` from the playbook, and labels
  non-playbook steps "chưa kiểm chứng". Verified live: one bubble tap, step 1 ring on
  "Tìm Bưu Cục", tap, step 2 on the next page; and for tracking, typing in a field moved the
  guide to the next step each time (1 -> 2 -> 3). The stub follows the playbook literally, so
  it rings the "Tỉnh/Thành phố" label rather than the unlabeled dropdown under it; whether a
  real model picks the dropdown is untested. dichvucong.gov.vn is still unreachable from this
  network, so no playbook targets it yet.
- **Fixes found while testing M7/M8 (2026-11-14).**
  (1) `guide_service.xml` now sets `android:isAccessibilityTool="true"`. On Android 14 a
  window protected against tap-jacking (`filterTouchesWhenObscured` /
  `accessibilityDataSensitive`; e.g. Settings > Data Saver > Unrestricted data, and likely
  login and payment screens of real services) returned `root=null` to our service, so the
  guide was blind there. With the flag the same page reads normally. Play Store reviews this
  flag strictly: the app has to genuinely be an accessibility tool for the listing.
  (2) The ringed element is now matched by role, text, desc, id **and hint**: before, typing
  into a text field made the ring jump to the other empty text field.
  (3) While a ring is up a quick pass (250 ms quiet, 600 ms cap) only re-positions it, so it
  keeps up with scrolling and layout shifts (vnpost's banner changes height); a model call
  still waits for the normal 800 ms settle.
  (4) The first automatic "not sure" in a row is silent (usually a page still loading), and
  repeated automatic misses back off 1.5 s, 3 s ... 24 s so a carousel page cannot turn into
  a stream of model calls.
  (5) When no app window can be read the log prints every window's type and root under tag
  `ChiDay` ("no app root. windows=..."): first thing to check if the phone shows
  "Tôi chưa đọc được màn hình này".
- **M9 (2026-11-14) PASS on emulator against the dev stub; real vision model untested.**
  DocCheck screen (Home > "Kiểm tra ảnh giấy tờ"): pick the kind (Căn cước / Ảnh chân dung /
  Giấy tờ khác), "Chụp ảnh" (camera app via FileProvider, no camera permission) or "Chọn ảnh
  có sẵn" (system photo picker, no storage permission). The photo is rotated per EXIF, scaled
  to 1568 px, sent as JPEG with the prompt in `DocChecker`, and the verdict
  (`{ok, document, problems, advice}`) is shown above the photo and read aloud. With
  `tools/make_test_docs.py` photos: sharp one "Ảnh dùng được", blurred one "Ảnh cần chụp lại:
  Ảnh bị mờ, chữ không đọc được" + advice. "Chụp ảnh" opens the emulator camera and returns
  without a crash; a real camera capture was not run. **The stub decides by measuring
  sharpness (needs `pip install pillow`); it proves the plumbing, not the model's judgement.**
  The host's model must accept images for this feature: check it with the real key.
- **M10, first half (2026-11-14) PASS on emulator against the dev stub.** Home > "Tạo hướng
  dẫn mới từ văn bản": service name, website, goal, pasted steps -> one model call
  (`PlaybookDrafter`) -> draft shown step by step for review -> "Lưu hướng dẫn này" writes
  `filesDir/playbooks/<id>.json` (never marked verified). The running service picks it up
  at once (shared cache), the goal appears on Home, and the guide used it on chinhphu.vn
  with "chưa kiểm chứng" on the card. Saved drafts can be deleted from the same screen.
  The stub turns each line into one step; how well a real model converts a real 12-step
  guide is untested. **Record mode (second half of M10) is not built.**
- **Emulator hang, seen once (2026-11-14).** After about 5 hours of continuous use the
  emulator showed "Chỉ Đây isn't responding" twice in a row. The ANR trace
  (`/data/anr/anr_2026-11-14-07-42-19-931`) has the app's main thread waiting in
  `HardwareRenderer.setStopped` for the RenderThread, and the RenderThread blocked in
  `eglCreateWindowSurface -> egl_window_surface_t::init -> qemu_pipe_read`, i.e. inside the
  emulator's host-GPU pipe, not in app code. A cold restart of the emulator cleared it and it
  did not come back in the regressions below. A real phone has no qemu pipe, but the app was
  creating more window surfaces than needed, so that was reduced anyway:
  `HighlightWindow` is now attached once (before the bubble, so the bubble stays on top) and
  only toggles visibility; the bubble is no longer removed and re-added for every ring.
  If the emulator hangs again: `adb emu kill`, start it again, and close Chrome tabs.
- **Regression on the final build, fresh emulator (2026-11-14):**
  `tools/follow_mode_test.sh` PASS (4 rings, 1 bubble tap, 3 automatic re-analyses);
  `tools/kill_reopen_test.sh 3` 3/3; both vnpost playbooks and the pasted-draft flow ran
  as described above; DocCheck sharp/blurred verdicts as in M9.

## State after milestones 7 to 10 (before the phone arrives)

Built and passing on the emulator: everything in milestones 1 to 9 and the paste-to-playbook
half of 10. Still true from the list above: **no real model has been called and nothing has
run on a real phone.** Not built: record mode, screenshot fallback for apps with an empty
accessibility tree, the self-building map (MECHANISM section 6).

Order of work when the phone and the host's key arrive:
1. PHONE_CHECKLIST.md items 1 to 10, then 11 to 14.
2. With the real key: "Kiểm tra kết nối"; then one bubble tap on vnpost.vn with the goal
   "Tìm bưu cục gần nhà". Watch `adb logcat -s ChiDay`: `decision ... in N ms` is the latency,
   and `ring on node` shows what the model picked. If the model's JSON does not parse, the
   log says `guide reply did not parse` with the reason.
3. If the model is slow (target: under 3 s per step) the first knobs are in the app, not the
   prompt: `ScreenReader.MAX_NODES` (150) and the playbook JSON sent with every call.
4. If the host's model has no vision, DocCheck fails with the provider's HTTP error on
   screen; say so in the demo instead of faking it.
5. Pick the two or three demo targets and re-verify their playbooks on the phone the night
   before (checklist item 10). dichvucong.gov.vn needs a playbook written from its real
   screens once it is reachable: open it, tap the bubble, read the `node` lines in logcat.

## Demo phone

| What | Value |
|---|---|
| Manufacturer / model | samsung SM-A566B (Galaxy A56 5G) |
| Android | 16 (SDK 36), One UI, system language English |
| Screen | 1080x2340 |
| Network during test | mobile data (LTE), no Wi-Fi |

- **First run on the real phone (2026-11-14), build `dd32b8d` (CI artifact), against the dev
  stub, no real model key yet.** The stub ran on a Windows laptop and reached the phone with
  `adb reverse tcp:8787 tcp:8787`; base URL in the app `http://127.0.0.1:8787/v1`.
  Checklist results:
  item 1 recorded above; 2 and 3 done (Samsung: nothing extra was needed); 4 `Success`;
  5 service on, bubble floating, Home green; 7 Google TTS Vietnamese voice present, Home green
  (log shows speech started/done; still to be confirmed by ear); 8 PASS against the stub only
  (79 ms), **real key still pending**; 9 PASS: 5/5 rounds of removing the task from recents,
  reopening (three green checks, saved goal kept) and getting ring + speech; a `kill -9` of the
  app process made Android restart the service under a new pid and the bubble and guide worked
  with no re-setup; **Force stop** set `enabled_accessibility_services` to `null` and Home
  showed the red row with "Mở Cài đặt trợ năng", key and voice stayed green; 11 PASS for
  steps 1 and 2 of `vnpost-tim-buu-cuc` (ring on «Tìm Bưu Cục», "Bước 1/4", then follow mode
  moved to "Bước 2/4" on the next page by itself, decision 29 to 59 ms with the stub);
  12 PASS: Data saver > "Apps that can always use data" was read (40 nodes), no
  "chưa đọc được"; 13 PASS with the picker and the two `make_test_docs.py` photos (sharp
  "Ảnh dùng được", blurred "Ảnh cần chụp lại: Ảnh bị mờ..." read aloud); the real camera was
  not used; 14 Home shows "Bản dựng dd32b8d". Item 6 (battery "Không hạn chế" / never-sleeping
  apps) and item 10 still to do by hand.
- **Differences from the emulator, found on the phone (2026-11-14).**
  (1) vnpost.vn is slow on mobile data: about 30 s before the home page renders, and the
  "Tìm Bưu Cục" page stayed half-loaded for minutes (raw `{{ ... }}` templates, province list
  not clickable), so steps 3 and 4 of the playbook could not be run. A bubble tap before the
  page has rendered gives "Tôi chưa chắc". For the demo: open the page well before, or use
  Wi-Fi / a hotspot, and check it on the venue network.
  (2) Android 16 prints window frames as `frame=[l,t][r,b]` inside the window block, so the
  `bubble_centre` helper in `tools/follow_mode_test.sh` and `tools/kill_reopen_test.sh` found
  no bubble. Both now fall back to the new format.
  (3) Samsung recents are laid out sideways and the bubble floats above them, so the recents
  swipe in `kill_reopen_test.sh` hit another app's card or dragged the bubble. The script now
  also removes the task with `am stack remove <task>` (what a recents swipe does).
  (4) The process survives a recents removal on Samsung too (bound accessibility service).
  (5) UI: the bubble's default spot (right edge, mid-screen) covers the "Hiện" button of the
  key field in Settings and the right end of "Bắt đầu" on Home. Not fixed; drag the bubble
  away or move its default position.
  (6) The Samsung photo picker is Google Photos with a "Done" button: one extra tap compared
  with the emulator's picker. It shows the owner's whole gallery, so do the demo with a
  sample card, not someone's real ID.
