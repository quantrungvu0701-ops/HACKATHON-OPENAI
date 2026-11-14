# HANDOFF: state of Chỉ Đây, demo day (2026-11-14)

Read `AGENTS.md` first, then this file, then the end of `PROGRESS.md`. One person/agent works at
a time now. Work directly on `main`: no new branches, no PRs. `git pull --rebase origin main`
before every push.

## What is on `main` (all merged, CI green at the time of writing)

- Milestones 1-9, playbooks, DocCheck, pasted-instructions-to-playbook.
- UI redesign (ink navy + highlighter yellow), voice goal on Home.
- Target resolver + tap feedback (`guide/TargetResolver.kt`, 16 unit tests).
- Privacy filter (`screen/PrivacyFilter.kt`): typed text never read, numbers/e-mails masked.
- Real model verified on the emulator: OpenAI, base URL `https://api.openai.com/v1`,
  model `gpt-4.1` (1-2.6 s per step, valid JSON, vision works for DocCheck).

## To do, in order (demo is the priority)

1. **Bubble on/off** (owner's request: the bubble could only be removed by switching the
   accessibility service off). Plan: a persisted flag in SharedPreferences `bubble` key
   `enabled`; long-press on the bubble hides it; Home gets a button to turn it off, and
   "Bắt đầu" turns it on. Files: `overlay/BubbleWindow.kt`, `GuideService.kt`,
   `ui/HomeScreen.kt`, `MainActivity.kt`, `strings.xml`. Check this file's "Done since" list
   below to see whether it landed.
2. **QA pass** on the emulator: `bash tools/follow_mode_test.sh`, `bash tools/kill_reopen_test.sh 3`,
   `bash tools/privacy_filter_test.sh` (stub: `python tools/stub/stub_server.py`, base URL
   `http://10.0.2.2:8787/v1`; note the emulator is currently set to the REAL OpenAI key, so the
   stub tests need the stub settings re-entered, or just test with the real model).
3. **Three pitch use cases**, live with the real model:
   - VNPost (`vnpost-tim-buu-cuc`, 4 steps; steps 1-2 verified with the real model).
   - Dịch vụ công (`dichvucong-ban-sao-khai-sinh`, 3 steps; verified on the phone with the stub).
   - CapCut: no playbook yet. Wanted use case: cut out the middle of a video (split twice,
     delete the middle clip). Needs CapCut installed and a node dump per screen
     (`adb logcat -s ChiDay` lines `node {...}` in a debug build). CapCut draws much of its UI
     itself: if the dump is nearly empty, a playbook cannot work; say so honestly and demo
     Samsung Settings (`samsung-phong-to-chu`) as the third, native-app case.
4. **Phone**: install the newest APK (GitHub → Actions → latest green **Android** run →
   artifact `chiday-debug-apk-<commit>`, then `adb install -r app-debug.apk`), enter the key in
   Cài đặt (OpenAI / tương thích, `https://api.openai.com/v1`, `gpt-4.1`), press
   "Kiểm tra kết nối", run `PHONE_CHECKLIST.md`, then the three flows on the venue Wi-Fi.
5. Append results to `PROGRESS.md` (append-only).

## Things that will bite

- The API key lives only in the app's DataStore. Never put it in the repo, a commit or a log.
  The test key expires 2026-11-15 04:15 UTC.
- `adb shell uiautomator dump` hangs the accessibility service. If the bubble vanishes:
  `adb shell settings put secure enabled_accessibility_services vn.chiday.app/vn.chiday.app.GuideService`.
- `adb shell am force-stop vn.chiday.app` switches the accessibility service off (Android rule).
- The bubble moves off the ringed element during a session: scripts must read its live position
  (`bubble_centre` in `tools/follow_mode_test.sh`), not a fixed coordinate.
- DocCheck: the generated sample card is rejected by the real model as a fake. Demo with a
  real card, numbers covered.
- Stale remote branches (`chore/test-plumbing`, `claude/*`, `feat/privacy-filter`) are fully
  merged or obsolete; ignore them.

## Done since this file was first written

(append one line per finished item)
- Bubble on/off: hold the bubble 0.7 s or press «Tắt nút nổi» on Home to hide it; «Bắt đầu» / «Bật lại nút nổi» shows it again. Saved in SharedPreferences, checked on the emulator.
- CapCut draft playbook `capcut-cat-doan-giua.json` (5 steps: New project, Add, Split, Split, Delete). UNVERIFIED: written from CapCut's known labels, never run; package names `com.lemon.lvoverseas` / `com.lemon.lv` unchecked. Needs the phone: `adb shell pm list packages | grep -i -e capcut -e lemon`, then a node dump per screen.
- Same-screen steps: after a press on the ring, if the playbook's next step is on the same screen the guide re-analyses after 1.5 s (`GuideEngine.nextStepIsHere`). Untested on a real app.
- Follow mode no longer cancels a model call in flight; the answer is placed on the fresh screen. Goal of another service no longer overrides the playbook of the app on screen. Both checked on the emulator with gpt-4.1 on vnpost.vn.
- dichvucong.gov.vn does not load from the dev computer's network: check that flow on the phone only.
- Model calls: 7 s timeout + one retry on a fresh connection, HTTP/2 pings, warm-up on service start and «Bắt đầu». kill_reopen_test 4/5 with gpt-4.1 on the emulator.
- 14:42 All three pitch cases verified on the phone with gpt-4.1 (see PROGRESS.md). Phone settings: OpenAI, https://api.openai.com/v1, gpt-4.1 (was pointing at the old stub). CapCut playbook rebuilt from real screens and verified; kill-and-reopen 3/3 on the phone. Remaining: DocCheck with a real card, rehearse the demo, revoke the test key after the event.
