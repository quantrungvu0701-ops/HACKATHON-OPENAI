# Chỉ Đây: how it works

Chỉ Đây ("point here") is an Android app that sits on top of any other app as a floating
button. When the user is stuck, they tap it. The app reads the current screen, works out
which service and which step the user is on, circles the exact button to press, and says
what to do aloud in simple Vietnamese. It never taps for the user.

This document explains the mechanism, and in particular how the app can guide a user
through apps and websites it has never seen before.

## 1. The short answer to "how does it know every app?"

It does not need to know every app in advance. It needs two things at the moment the
user asks:

1. **What is on the screen right now.** Android already exposes this for every app
   through the accessibility service: a tree of every button, label and text field,
   with its text and its exact pixel rectangle. This is the same tree TalkBack uses.
   Websites in Chrome appear in the same tree, so apps and web are handled identically.
2. **What the correct procedure is.** This comes from a *playbook*: a small verified
   file describing the steps of one task ("register a metro account", "pay the
   electricity bill"). If no playbook exists, the model reasons from the screen and the
   user's spoken goal alone, and says so with lower confidence.

So the design is **inspect at runtime, not crawl in advance**.

### Why not crawl each app to map all its branches

| Problem with pre-crawling | Consequence |
|---|---|
| Public-service apps sit behind login, OTP and eKYC | A crawler cannot get past the first screen |
| Native apps have no URL structure to crawl | Only websites could be mapped at all |
| Portals change layout without notice | The map is stale and nobody knows |
| Submitting forms to explore branches | Creates real applications on real government systems |

The map is still built, but as a by-product of use (section 6), not by a crawler.

## 2. The loop

```
 tap bubble
     │
     ▼
 PERCEIVE ──► IDENTIFY ──► DECIDE ──► SHOW ──► user taps the real button
     ▲                                              │
     └──────────── screen changed (auto) ◄──────────┘
```

### 2.1 Perceive

The accessibility service walks the active window and produces a compact list:

```json
{ "package": "com.android.chrome",
  "nodes": [
    {"i": 0, "role": "button", "text": "Đăng nhập", "id": "btn_login",
     "clickable": true, "bounds": [820, 96, 1040, 180]},
    {"i": 1, "role": "edit", "hint": "Số định danh cá nhân", "bounds": [60, 420, 1020, 520]}
  ] }
```

Rules:
- Only visible nodes that carry text, a description, or are clickable/editable. Cap at
  about 150 nodes.
- Password fields are sent with their text removed. Digit runs of 6 or more are masked.
- Our own bubble and overlay are excluded.

**Screenshot is the fallback, not the default.** If the tree is nearly empty (Flutter
apps without semantics, canvas-drawn UIs, games), the service takes a screenshot,
draws numbered boxes on a grid or on detected regions, and sends the image to the
vision model. Tree-first is faster, cheaper, more private, and it still works on
screens where the app blocks screenshots.

### 2.2 Identify

The package name (or the website host shown in Chrome's address bar) plus the visible
text select candidate playbooks. A playbook step lists *signals*, short texts that
appear on that screen, so matching a screen to a step is mostly string matching, with
the model breaking ties.

### 2.3 Decide

One model call. Input: the node list, the matched playbook (if any), the user's goal,
and the last step shown. Output is strict JSON:

```json
{ "service": "Cổng Dịch vụ công",
  "step": 2, "total_steps": 6,
  "target": 0,
  "action": "tap",
  "say": "Bác bấm vào nút Đăng nhập ở góc trên bên phải.",
  "subtitle": "Bấm «Đăng nhập»",
  "caution": false,
  "confidence": 0.9 }
```

The key design decision: **the model returns a node index, never coordinates.** The
app looks up that node's real rectangle from the accessibility tree. The circle is
therefore pixel-exact, and a hallucinated target is caught because the index either
exists or it does not. Coordinates from the model are accepted only in screenshot
fallback mode.

Guardrails applied by the app after the model answers:
- Unknown index, or confidence below 0.5: show "Tôi chưa chắc, bác thử kéo xuống hoặc
  hỏi lại" rather than circling a guess.
- `caution: true` on payment, submit and delete buttons: the circle turns amber and
  the voice tells the user to check the details first.

### 2.4 Show

- A full-screen overlay that does not accept touches. It dims the screen and draws a
  pulsing ring around the target, with an arrow when the target is small.
- A subtitle card with a short text and "Bước 2/6".
- Vietnamese speech using Android's built-in text-to-speech.

Because the overlay is touch-transparent, the user's finger lands on the real button of
the real app. Chỉ Đây never performs a tap, never types, and never submits.

### 2.5 Follow

When the screen changes (the accessibility service is notified), the app waits for the
screen to settle and runs the loop again on its own. The user taps the bubble once and
is then walked through the whole task. Tapping the bubble again ends the session.

## 3. Playbooks

A playbook is one task in one service:

```json
{ "id": "dvc-dang-nhap-vneid",
  "service": "Cổng Dịch vụ công Quốc gia",
  "match": { "packages": ["com.android.chrome"], "hosts": ["dichvucong.gov.vn"] },
  "goal": "Đăng nhập bằng tài khoản VNeID",
  "verified": { "by": "team", "date": "2026-11-14" },
  "steps": [
    { "n": 1, "signals": ["Đăng nhập", "Đăng ký"],
      "do": "Bấm nút Đăng nhập", "target_hint": "Đăng nhập",
      "pitfall": "Đừng bấm Đăng ký nếu đã có tài khoản" }
  ] }
```

Playbooks keep the model honest: it is told the verified next step and only has to find
it on the screen. Three ways a playbook is created:

1. **Written by hand** for the launch services, and checked on a real phone.
2. **Converted from existing instructions.** A school or agency already has a PDF or a
   web page of steps. Paste or share it into Chỉ Đây; the model converts it to playbook
   JSON; a person reviews it. This turns "read the 12-step guide from the school" into
   on-screen guidance, and it does not depend on OS or device because matching is done
   on screen text, not on screenshots in the guide.
3. **Recorded by demonstration.** An expert switches on record mode and does the task
   once. The service logs each screen's signals and the node that was tapped, and
   produces a draft playbook. No typing needed. This is how coverage scales.

## 4. No playbook: generic mode

The user says the goal ("tôi muốn mua vé tháng"). The model gets the goal and the
screen and proposes the next action, with a visible "chưa kiểm chứng" label. Generic
mode sessions that end successfully are the raw material for new playbooks.

## 5. Document photo check

Inside the app: take or pick a photo of a document (ID card, form, portrait photo).
The vision model checks that it is the right document type, all four corners are in
frame, there is no glare or blur, and the text is readable. It answers in Vietnamese
with what to fix. This prevents the "application returned with no clear reason"
problem before submission.

## 6. The map builds itself

Every screen gets a fingerprint: package + window class + a hash of its stable view
ids and labels. Each guided step is an edge between two fingerprints. Aggregated over
users (fingerprints only, no content), this becomes the graph of each app's branches,
which is what crawling was supposed to give, but covering logged-in screens and
staying current. It also reveals where people get stuck, which is data agencies want.

## 7. Privacy and safety

- Nothing is read until the user taps the bubble; reading stops when the session ends.
- Tree-first means most requests contain labels, not images.
- Password text is never sent. Long digit runs are masked. Nothing is stored on a
  server by the app itself.
- Guide-only: the service never calls a click or type action.
- An always-visible indicator shows when a session is active.

## 8. Known limits

- **iOS**: no overlay and no cross-app screen reading. iOS would need a different
  product (screenshot share, or a guided in-app browser).
- **Screens that block capture**: screenshot fallback returns black; the tree usually
  still works.
- **Apps with no accessibility labels**: fall back to vision, lower accuracy.
- **Sideloaded installs on Android 13+**: the accessibility toggle is locked until the
  user allows restricted settings for the app.
- **Play Store**: accessibility-service apps go through extra review; the declared
  purpose (assisting older and low-literacy users) must be argued for.
- **Latency**: one model call per step; target under 3 seconds on tree-only requests.
