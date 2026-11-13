# Chỉ Đây

An Android app that floats over any other app as an orange button. When the user is stuck
they tap it: Chỉ Đây reads the screen, circles the exact button to press, and says what to
do in simple Vietnamese. It never taps, types or submits for the user.

- How it works: [MECHANISM.md](MECHANISM.md)
- Build brief, milestones and rules for coding agents: [AGENTS.md](AGENTS.md)
- What works right now and what is next: [PROGRESS.md](PROGRESS.md)
- First thing to do on the real demo phone: [PHONE_CHECKLIST.md](PHONE_CHECKLIST.md)

## Get the app without building

Every push to `main` builds a debug APK. Open the repo on GitHub → **Actions** → latest
green **Android** run → download the artifact `chiday-debug-apk-<commit>`, unzip, then:

```
adb install -r app-debug.apk
```

The Home screen shows "Bản dựng <commit>" at the bottom, so you can always tell which
build is on a device.

## Build on your own machine

You need JDK 17 and the Android SDK (platform 34, build-tools 34.0.0, platform-tools).
`AGENTS.md` → "Step 0" has the install commands for Windows, macOS and Linux; a coding
agent can run them for you.

```
git clone https://github.com/quantrungvu0701-ops/HACKATHON-OPENAI.git
cd HACKATHON-OPENAI
./gradlew assembleDebug          # Windows: .\gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gradle finds the SDK from `ANDROID_HOME`, or from a `local.properties` file with
`sdk.dir=...` (that file is per-machine and is not committed).

Turn the service on: open Chỉ Đây → "Mở Cài đặt trợ năng" → switch Chỉ Đây on. On an
emulator you can skip the taps:

```
adb shell settings put secure enabled_accessibility_services vn.chiday.app/vn.chiday.app.GuideService
adb shell settings put secure accessibility_enabled 1
```

## Testing without an API key

`tools/stub/stub_server.py` is a dev-only stand-in for the model API (it is not part of
the app). Run `python tools/stub/stub_server.py`, then in the app's Settings choose
"OpenAI / tương thích", base URL `http://10.0.2.2:8787/v1` (emulator) or
`http://<your-laptop-ip>:8787/v1` (phone on the same Wi-Fi), any model name, any key.
`curl "http://localhost:8787/pick?q=<regex>"` chooses which on-screen element it points at.

`bash tools/kill_reopen_test.sh 5` runs the kill-and-reopen check.

## Team workflow (several laptops, several accounts, one `main`)

Everyone pushes to `main`, so keep it building and keep pushes small.

1. **Before you start and before every push:** `git pull --rebase origin main`.
   Set it once so plain `git pull` does the right thing:
   ```
   git config pull.rebase true
   git config rebase.autoStash true
   ```
2. **Commit small and often**, one topic per commit, conventional messages
   (`feat: ...`, `fix: ...`, `docs: ...`). Push as soon as it builds
   (`./gradlew assembleDebug`). Long-lived local work is what causes painful conflicts.
3. **Say what you are touching.** Two people editing the same file at the same time is the
   usual source of conflicts. The files most likely to collide are `GuideService.kt`,
   `HomeScreen.kt` and `strings.xml`: tell the team before a big edit to any of them, and
   add new strings at the end of their section rather than reordering.
4. **Something risky or half-done?** Use a short-lived branch and a pull request instead of
   `main`: `git switch -c feat/<topic>`, push, open a PR, merge when CI is green.
5. **Never `git push --force` to `main`.** If a rebase goes wrong: `git rebase --abort`.
6. **`PROGRESS.md` is append-only** and is merged with `merge=union` (see
   `.gitattributes`), so two people adding lines never conflict. Add a line after each
   milestone or notable fix; do not rewrite other people's lines.
7. **API keys never go in the repo**, not even in a test file or a screenshot. Keys live
   only in the app's settings on a device.

### One signing key for everybody

`keystore/debug.keystore` is committed on purpose and every debug build (all laptops and
CI) is signed with it. That way an APK built by anyone installs over an APK built by anyone
else with `adb install -r`, and the API key saved on the demo phone survives. It is a
throwaway **debug** key with the standard `android` password: it protects nothing, so there
is nothing to leak. Do not use it for a Play Store release.

If a device still has a build from before this key existed, the first install fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Run `adb uninstall vn.chiday.app` once, install again,
and re-enter the settings.

### Giving a teammate access

The repo is private. The owner adds each teammate's GitHub account under
**Settings → Collaborators → Add people** (or `gh api -X PUT
repos/quantrungvu0701-ops/HACKATHON-OPENAI/collaborators/<username>`); the teammate accepts
the email invitation and can then clone, pull and push.
