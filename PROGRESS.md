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
