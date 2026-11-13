# PHONE_CHECKLIST: run this first on the real demo phone

Run every item top to bottom **before any new feature work**. Anything that behaves
differently from the emulator gets fixed first. The emulator is never the demo.

> **Phone: `__________ (brand)  __________ (model)  Android ____`**
> The brand, model and Android version were not given when this file was written, so the
> brand-specific items list every common Vietnamese-market brand. Fill the line above,
> then follow only your brand's row in items 3 and 6.

Commands run on the laptop, in a **new** terminal (so `adb` is on `PATH`), from the repo
folder. Tap paths are on the phone. Vietnamese menu names are given with the English name
in brackets; OEM skins differ slightly, so use the Settings search box if a name is missing.

---

## 1. Record the phone in PROGRESS.md

```
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell wm size
```

Write the five answers under a "Demo phone" heading in `PROGRESS.md`.
**Stop if SDK is below 30** (Android 11): the app's `minSdk` is 30 and it will not install.

- [ ] Done

## 2. Developer options + USB debugging

1. Cài đặt → Giới thiệu điện thoại (About phone) → tap **Số bản dựng** (Build number)
   7 times. On Xiaomi tap **Phiên bản MIUI / HyperOS** instead. On Samsung it is under
   Thông tin phần mềm (Software information).
2. Cài đặt → Hệ thống → Tùy chọn nhà phát triển (Developer options) → turn on
   **Gỡ lỗi USB** (USB debugging).
3. Plug in the cable, choose **Truyền tệp** (File transfer) if asked, and accept the
   "Cho phép gỡ lỗi USB?" prompt with "Luôn cho phép từ máy tính này" ticked.

```
adb devices
```

Passes when the phone's serial is listed with state `device` (not `unauthorized`, not
`offline`). If the emulator is also running, add `-s <serial>` to every `adb` command below.

- [ ] Done

## 3. Brand-specific install permissions

| Brand | What to switch on, in Developer options | Notes |
|---|---|---|
| **Xiaomi / Redmi / POCO** | **Cài đặt qua USB** (Install via USB) and **Gỡ lỗi USB (Cài đặt bảo mật)** (USB debugging (Security settings)) | Both need a signed-in **Mi account** and a SIM or mobile data the first time. Also turn **off** "Tối ưu hóa MIUI" only if installs still fail. |
| **Oppo / Realme / OnePlus (ColorOS)** | **Tắt giám sát quyền** (Disable permission monitoring) | Installs may ask for the lock-screen password or an account password on the phone; watch the screen during `adb install`. |
| **Vivo / iQOO (Funtouch / OriginOS)** | **Cài đặt qua USB** (Install via USB) and **Gỡ lỗi USB (Cài đặt bảo mật)** if present | A confirmation dialog appears on the phone with a countdown; tap "Cài đặt". |
| **Samsung (One UI)** | Nothing extra in Developer options | If **Auto Blocker** (Trình chặn tự động) is on, turn it off: Cài đặt → Bảo mật và riêng tư → Trình chặn tự động. It blocks `adb install` and sideloads. |
| **Pixel / Nokia / Motorola** | Nothing extra | |

- [ ] Done

## 4. Install the APK

Get `app-debug.apk` from the latest green run of the **Android** workflow on GitHub
(artifact `chiday-debug-apk`), or build it: `.\gradlew assembleDebug`.

```
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Passes when it prints `Success`. Common failures:
- `INSTALL_FAILED_USER_RESTRICTED`: item 3 is not done, or the phone showed a dialog nobody tapped.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: an older build signed by another machine is installed.
  Run `adb uninstall vn.chiday.app`, then install again (this wipes the saved API key).

- [ ] Done

## 5. Turn on the accessibility service

1. Open **Chỉ Đây**. Home shows "Quyền trợ năng: Chưa bật". Tap **Mở Cài đặt trợ năng**.
2. Find **Chỉ Đây** (under "Ứng dụng đã tải xuống" / Downloaded apps; on Samsung:
   Hỗ trợ → Ứng dụng đã cài đặt) and switch it on, then **Cho phép** (Allow).
3. **If the switch is greyed out** or a "Cài đặt bị hạn chế" (Restricted setting) dialog
   appears: Home → **Mở thông tin ứng dụng** → **⋮** (top right) →
   **Cho phép cài đặt bị hạn chế** (Allow restricted settings) → confirm with the screen
   lock → go back and repeat step 2. The ⋮ menu only appears *after* the blocked dialog
   has been shown once.

Check from the laptop:

```
adb shell settings get secure enabled_accessibility_services
```

Passes when the output contains `vn.chiday.app/vn.chiday.app.GuideService`, the orange
bubble is floating, and Home shows a green check for "Quyền trợ năng".

- [ ] Done

## 6. Battery and autostart (so the OEM does not kill the service)

All brands: Cài đặt → Ứng dụng → Chỉ Đây → Pin (Battery) → **Không hạn chế**
(Unrestricted / No restrictions).

| Brand | Extra |
|---|---|
| **Xiaomi / Redmi / POCO** | Ứng dụng → Chỉ Đây → **Tự khởi chạy** (Autostart) on; Tiết kiệm pin → **Không hạn chế**. In recents, long-press the Chỉ Đây card → tap the **lock** icon. |
| **Oppo / Realme / OnePlus** | Ứng dụng → Chỉ Đây → Sử dụng pin → allow **Tự khởi chạy** (Auto-launch) and **Hoạt động nền** (Background activity). In recents: ⋮ on the card → **Khóa**. |
| **Vivo / iQOO** | Cài đặt → Pin → Mức tiêu thụ pin nền cao (High background power consumption) → allow Chỉ Đây; i Manager → Quản lý ứng dụng → **Tự khởi chạy** on. |
| **Samsung** | Pin → Giới hạn sử dụng nền → add Chỉ Đây to **Ứng dụng không bao giờ ngủ** (Never sleeping apps). |

```
adb shell dumpsys deviceidle whitelist | findstr chiday
```

Passes when `vn.chiday.app` is listed.

- [ ] Done

## 7. Vietnamese voice

1. Cài đặt → Trợ năng (or Hệ thống → Ngôn ngữ) → **Đầu ra văn bản sang giọng nói**
   (Text-to-speech output). Engine: **Dịch vụ giọng nói của Google** (Speech Services by
   Google). Samsung phones default to the Samsung engine: switch to Google's, or install
   the Vietnamese voice inside the Samsung engine.
2. ⚙ next to the engine → **Cài đặt dữ liệu giọng nói** (Install voice data) →
   **Tiếng Việt (Việt Nam)** → download. Needs Wi-Fi.
3. Set Ngôn ngữ (Language) to Tiếng Việt and tap **Phát** (Play).
4. Media volume up: the demo room is loud.

Passes when the sample is spoken in Vietnamese and Home shows a green check for
"Giọng đọc tiếng Việt".

- [ ] Done

## 8. The host's API key

Chỉ Đây → **Cài đặt** → pick the provider, type base URL, model and key →
**Kiểm tra kết nối**.

Passes when the test call shows a reply from the model and Home shows a green check for
"Kết nối AI". If it fails, the screen shows the HTTP status and body: 401 = wrong key,
404 = wrong base URL or model name, timeout = the venue Wi-Fi blocks the host (use a
mobile hotspot).

- [ ] Done

## 9. Kill-and-reopen, 5 times

Repeat 5 times:
1. Open recents, **swipe Chỉ Đây away**.
2. Reopen Chỉ Đây from the launcher: Home shows **three green checks** with no re-setup.
3. Tap **Bắt đầu**, open the target service, tap the orange bubble.
4. The ring appears on a button and the instruction is spoken in Vietnamese.

Then once more with **Force stop** instead of the swipe: Cài đặt → Ứng dụng → Chỉ Đây →
**Buộc dừng**. Android switches the accessibility service off after a force stop on most
phones; Home must show one button that opens accessibility settings, and after switching
the service back on everything works with the saved key.

```
adb shell am force-stop vn.chiday.app
adb shell settings get secure enabled_accessibility_services
```

- [ ] 5 swipes pass  - [ ] Force stop recovers

## 10. Demo targets open without login or OTP

For each target the presenter will show, on the demo phone, on the venue network:

| Target | Opens with no login / OTP | Guided 3 to 4 screens work | Checked at |
|---|---|---|---|
| Chrome → https://dichvucong.gov.vn (public search and procedure lookup, not "Đăng nhập") | [ ] | [ ] | |
| ______________________ | [ ] | [ ] | |
| ______________________ | [ ] | [ ] | |

Stop the flow **before** any screen that asks for VNeID, a password or an OTP.
Re-check the night before: portals change layout without notice.

---

## Differences from the emulator

Write anything that differed here and in `PROGRESS.md`, and fix it before feature work.

-
