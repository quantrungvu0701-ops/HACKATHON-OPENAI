# Handoff: các phần còn thiếu của Chỉ Đây

Viết ngày 2026-11-14, lúc `main` đang ở `8c457ff` ("ScreenReader: read Chrome pages after
scrolling"). Repo: `quantrungvu0701-ops/HACKATHON-OPENAI`.

Đọc trước, theo thứ tự: `AGENTS.md`, `MECHANISM.md`, phần cuối `PROGRESS.md`, rồi file này.
`AGENTS.md` thắng về luật làm việc. File này thắng về phạm vi việc của bạn.

## 1. Bức tranh chung

Chấm điểm 100: 40 điểm cho luồng AI chạy thật trên điện thoại sau khi tắt hẳn và mở lại app,
35 điểm cho vấn đề người dùng có bằng chứng, 25 điểm cho đường tới người dùng đầu tiên.
Trình duyệt, máy ảo, video hay kết quả dựng sẵn được 0 điểm ở phần 40.

Ba demo trên sân khấu, ưu tiên hơn mọi thứ khác:

| # | Demo | Loại | Playbook | Tình trạng |
|---|---|---|---|---|
| 1 | VNPost | web trong Chrome (`vnpost.vn`) | `vnpost-tim-buu-cuc` (4 bước), `vnpost-theo-doi-buu-gui` (3 bước) | Máy ảo chạy hết. Trên điện thoại mới chạy bước 1 và 2 của `tim-buu-cuc`, vì mạng di động quá chậm. Cần Wi-Fi. |
| 2 | Dịch vụ công | web trong Chrome (`dichvucong.gov.vn`) | `dichvucong-ban-sao-khai-sinh` (3 bước) | Cả 3 bước chạy trên Galaxy A56 (Android 16, Wi-Fi) với stub. |
| 3 | CapCut | app native | chưa có | Chưa bắt đầu. Xem mục 4.3. |

Đã có và đã qua trên máy ảo: milestone 1 tới 9 và nửa đầu milestone 10 (dán hướng dẫn thành
playbook nháp). Trên điện thoại thật (Galaxy A56, SM-A566B, 1080x2340): checklist mục 1 tới 5,
7, 8 (chỉ với stub), 9, 11 (bước 1 và 2), 12, 13 (ảnh mẫu, chưa dùng camera thật), 14.

## 2. Ba rủi ro lớn nhất, chưa ai xử lý

1. **Chưa có lần gọi model thật nào.** Mọi thứ sau milestone 2 chạy với
   `tools/stub/stub_server.py`. Stub tìm đích bằng so chuỗi, model thật có thể chọn khác,
   chậm hơn, hoặc trả JSON lệch. Đây là rủi ro trực tiếp cho 40 điểm.
2. **Vòng khoanh đôi khi rơi vào chỗ bấm không ăn** (chữ thường, đích bị che, đích bị cắt ở
   mép màn hình) và app im lặng sau khi người dùng bấm trượt. Đang sửa, xem mục 3.
3. **CapCut có thể không đọc được.** CapCut tự vẽ phần lớn giao diện, nên cây accessibility
   có thể gần như rỗng. App chưa có đường lùi bằng ảnh chụp màn hình: cây rỗng thì hiện
   "Tôi chưa chắc".

## 3. Việc đang chạy, bạn đừng đụng

Một phiên điều phối đang chia việc cho các agent. Trước khi sửa file nào dưới đây, chạy
`git fetch origin` và xem nhánh tương ứng đã merge vào `main` chưa.

| Gói | Nhánh | File bị khoá | Tình trạng lúc viết |
|---|---|---|---|
| 3. Test plumbing | `chore/test-plumbing` | `.github/workflows/android.yml`, `tools/stub/stub_server.py` | Đã đẩy 2 commit (`f716a59` thêm `testDebugUnitTest` vào CI, `fa44196` cho stub trả `action: scroll`). Còn một commit sửa nữa đang làm. Chưa merge. |
| 1. Target resolution | `fix/target-resolver` | `guide/TargetResolver.kt` (mới), `guide/GuideEngine.kt`, `screen/ScreenReader.kt`, `app/build.gradle.kts`, `app/src/test/**` | Đang viết, chưa đẩy gì. |
| 2. Tap feedback | `fix/tap-feedback` | `GuideService.kt`, `res/xml/guide_service.xml`, `res/values/strings.xml`, `overlay/HighlightWindow.kt`, `overlay/BubbleWindow.kt` | Chưa bắt đầu. Chờ gói 1 merge. |

Thứ tự merge: gói 3, rồi gói 1 (rebase lên `main` để CI chạy cả unit test), rồi gói 2, cuối
cùng là một mục trong `PROGRESS.md`.

### Hợp đồng giữa các gói (để bạn đọc code không bị lạc)

- `TargetResolver` là Kotlin thuần, không import `android.*`, nhận danh sách node với bounds
  dạng `[l,t,r,b]` và trả một trong bốn kết quả: `Tap` (khoanh, có thể đã dời sang phần tử
  bấm được nhỏ nhất bao quanh đích), `Look` (khoanh nhưng nói "xem", không nói "bấm"),
  `Scroll` (không khoanh, nội dung cần tìm ở dưới hoặc trên màn hình), `Nudge` (đích có trên
  màn hình nhưng bị cắt hơn khoảng 30% hoặc bị một nút nổi che hơn khoảng 30%, không khoanh).
- `GuideResult.Show` thêm `look: Boolean`. Có kiểu mới `GuideResult.Move(reply, towards,
  nudge, anchor, verified)` cho hai trường hợp không khoanh.
- `GuideEngine.recheck(snapshot)` chạy lại resolver cho đích cũ mà không gọi model, để follow
  mode khoanh lại sau khi người dùng kéo màn hình.
- `ScreenSnapshot` thêm bounds chưa cắt (`fullRects`) và thứ tự vẽ. JSON gửi cho model,
  `hash` và thứ tự duyệt node không đổi.
- Một bước playbook là bước kéo khi `do` bắt đầu bằng "Kéo" **và** không chứa động từ bấm
  hay gõ ("bấm", "nhấn", "chạm", "ấn", "gõ", "chọn"). Lý do: bước 1 của
  `samsung-phong-to-chu` là "Kéo màn hình lên một chút rồi bấm vào mục «Display»", đó là
  bước bấm. Bước 3 của dichvucong ("Kéo màn hình xuống tới phần «Thành phần hồ sơ»...") là
  bước kéo.

### Nếu phiên điều phối dừng giữa chừng, đây là phần gói 2 còn nợ

- `guide_service.xml`: thêm `typeViewClicked` vào `accessibilityEventTypes`. Chỉ thêm, không
  bỏ gì, vì follow mode của hai demo web dựa vào hai loại sự kiện hiện có.
- `GuideService`: xử lý `GuideResult.Move` (mũi tên, câu "kéo màn hình", không khoanh), xử lý
  `Show.look` (chữ "xem"), gọi `engine.recheck` khi màn hình đứng yên sau một `Move`.
- Sau khi đã khoanh: nhận `TYPE_VIEW_CLICKED` có bounds nguồn nằm ngoài vòng thì nói một lần
  "Bác bấm chưa trúng, ô cần bấm đang được khoanh." Bấm trong vòng mà sau khoảng 4 giây
  (8 giây với trang web) màn hình không đổi thì nói một lần "Trang chưa đổi, bác chờ một chút
  hoặc bấm lại." Mỗi câu tối đa một lần mỗi bước. Chrome có thể không gửi sự kiện này cho
  mọi phần tử web: không có sự kiện thì im lặng, không đoán.
- `BubbleWindow`: nếu bong bóng của mình nằm đè lên đích thì dời bong bóng đi.
- Chuỗi tiếng Việt mới nằm trong `strings.xml`, thêm ở cuối mục.
- Không bao giờ gọi `performAction` hay `dispatchGesture`.

## 4. Việc của bạn

Xếp theo mức ảnh hưởng tới điểm. Việc nào cần điện thoại thì ghi rõ.

### 4.1 Gọi model thật (không cần điện thoại cho bước đầu)

Key của ban tổ chức chỉ có vào ngày demo. Nếu có key cá nhân nào, dùng nó ngay bây giờ.

1. Trong app: Cài đặt, chọn nhà cung cấp, nhập base URL, model, key, bấm "Kiểm tra kết nối".
   Key chỉ nằm trong DataStore của máy, không bao giờ vào repo, kể cả ảnh chụp màn hình.
2. Một lần chạm bong bóng trên `vnpost.vn` với việc "Tìm bưu cục gần nhà", rồi trên
   `dichvucong.gov.vn`. Xem `adb logcat -s ChiDay`:
   - `decision ... in N ms` là độ trễ. Mục tiêu dưới 3 giây mỗi bước.
   - `ring on node` là phần tử model chọn.
   - `guide reply did not parse` nghĩa là JSON của model lệch hợp đồng.
3. Nếu chậm: chỉnh trong app trước, chưa đụng prompt. Hai núm đầu tiên là
   `ScreenReader.MAX_NODES` (150) và playbook JSON gửi kèm mỗi lần gọi.
4. Thử DocCheck với một ảnh nét và một ảnh mờ (`python tools/make_test_docs.py <dir>`). Nếu
   model không có vision, app hiện lỗi HTTP của nhà cung cấp. Lúc demo nói thẳng điều đó,
   không dựng kết quả giả.
5. Ghi kết quả vào `PROGRESS.md`: model nào, độ trễ từng bước, bước nào model chọn sai.

`AnthropicClient` gửi `anthropic-version: 2026-10-21` và không gửi tham số sampling.
`OpenAiCompatClient` chỉ gửi `model` và `messages`. Nếu cổng của ban tổ chức đòi thêm trường
nào, đây là hai file cần sửa.

### 4.2 Việc chỉ làm được khi cắm điện thoại

Chạy trên Wi-Fi. Mạng di động làm vnpost mất khoảng 30 giây mỗi trang và dichvucong 40 tới 60
giây.

- `PHONE_CHECKLIST.md` mục 6: pin "Không hạn chế" và thêm Chỉ Đây vào "Never sleeping apps".
- Mục 10: điền bảng demo target. Bảng hiện chỉ có dichvucong, thêm vnpost và CapCut. Kiểm tra
  trên mạng của hội trường.
- Mục 7: nghe bằng tai xem giọng tiếng Việt có phát thật không. Log đã báo có.
- Mục 13: chụp bằng camera thật, dùng thẻ mẫu hoặc che số.
- VNPost: chạy bước 3 và 4 của `vnpost-tim-buu-cuc`, rồi cả `vnpost-theo-doi-buu-gui`. Lưu ý
  bước 2 của `theo-doi-buu-gui` bắt gõ mã xác nhận trong hình (captcha) và bước 1 cần một mã
  bưu gửi thật. Nếu không có mã thật để trình diễn thì chọn `tim-buu-cuc` cho sân khấu.
- Sau khi ba gói ở mục 3 đã merge, chạy checklist của đợt sửa này:
  1. Cả ba demo từ đầu tới cuối trên Wi-Fi.
  2. Bấm lệch ra ngoài vòng một lần, chờ nghe câu "chưa trúng".
  3. Xem log để biết Chrome có gửi `TYPE_VIEW_CLICKED` cho phần tử web không.
  4. Samsung Settings mới mở: dòng «Display» nằm dưới nút «Search» nổi thì phải ra câu "kéo
     lên", không khoanh. Kéo xong thì vòng hiện trên dòng «Display».
  5. dichvucong bước 3: mũi tên kéo xuống, không có chữ "bấm".
  6. Hai demo web vẫn chạy như trước (follow mode tự sang bước kế).
- `samsung-phong-to-chu` mới kiểm với giao diện tiếng Anh. Nhãn tiếng Việt trong `signals` là
  đoán. Nếu máy demo để tiếng Việt thì phải kiểm lại.

Đừng chạy `adb shell uiautomator dump` khi đang có phiên hướng dẫn: nó treo dịch vụ
accessibility trong lúc chạy. Dùng ảnh chụp màn hình và `adb logcat -s ChiDay`.

### 4.3 CapCut

Không viết playbook bằng cách đoán. Cần đủ ba thứ trước:

1. **Việc cụ thể** người dùng muốn làm trong CapCut. Chọn việc ngắn, 3 tới 4 màn hình, không
   cần đăng nhập. Chủ repo quyết.
2. **Tên package** kiểm trên máy: `adb shell pm list packages | grep -i capcut`
   (Windows: `findstr /i capcut`). Dự đoán là `com.lemon.lvoverseas`, chưa kiểm.
3. **Node dump từng màn hình**: mở CapCut, chạm bong bóng ở mỗi màn hình của việc đã chọn,
   lưu các dòng `ChiDay: node {...}` từ logcat (chỉ có trong bản debug).

Rồi rẽ nhánh theo cái dump cho thấy:

- Dump có nhãn dùng được (text hoặc desc trên các nút cần bấm): viết
  `app/src/main/assets/playbooks/capcut-<viec>.json` theo mẫu các playbook đang có, với
  `match.packages` là tên package đã kiểm. Chạy lại trên máy rồi mới điền `verified`.
- Dump gần rỗng hoặc toàn nút không nhãn: playbook không cứu được. Khi đó có hai lựa chọn,
  báo chủ repo chọn. Một là dựng đường lùi bằng ảnh chụp (`AccessibilityService.takeScreenshot`,
  vẽ ô đánh số, gửi cho model vision, manifest đã khai `canTakeScreenshot`). Đây là việc lớn,
  rủi ro cao sát ngày demo, và phụ thuộc model của ban tổ chức có vision. Hai là đổi demo thứ
  ba sang một app native đọc được, ví dụ Samsung Settings, vốn đã có playbook chạy được trên
  máy.

Sửa hay thêm file playbook JSON cần chủ repo đồng ý trước.

### 4.4 Hai nhánh chưa merge, cần quyết định

- **`feat/privacy-filter`** (commit `734f380`): ô nhập chỉ gửi "đã điền hay chưa", ẩn hẳn ô
  mật khẩu, PIN, OTP, che số điện thoại, số định danh, email; xoá ảnh DocCheck khỏi cache sau
  khi dùng. Đã qua `tools/privacy_filter_test.sh` trên máy ảo, chưa thử trên điện thoại. Nó
  sửa đúng những chỗ gói 1 đang sửa (`GuideEngine.kt` phần `SYSTEM_PROMPT`, `ScreenReader.kt`
  phần `UiNode`, `ScreenSnapshot`, `Walker.visit`). Chờ gói 1 merge rồi mới rebase nhánh này,
  giải quyết conflict, CI xanh, rồi merge. Một điểm cần kiểm lại sau rebase: nhánh này bỏ chữ
  người dùng đã gõ khỏi node, trong khi follow mode nhận ra "người dùng đã gõ xong" nhờ node
  đổi. Nhánh đã thêm `filled` vào `hash` cho việc đó, hãy chắc là phần so khớp trong
  `GuideService.onScreenSettled` vẫn chuyển bước khi ô vừa được điền.
- **`claude/inspiring-feynman-1l3vfp`**: chỉ có `design/ui-options.html`, ba phương án giao
  diện cho Home, overlay và DocCheck. Chưa có code. Chủ repo chọn phương án rồi mới làm. Việc
  này đụng `HomeScreen.kt`, `HighlightWindow.kt`, `strings.xml`, nên phải xếp sau gói 2.

### 4.5 Việc nhỏ, làm khi rảnh

- Vị trí mặc định của bong bóng (mép phải, giữa màn hình) che nút "Hiện" của ô key trong Cài
  đặt và đầu phải của nút "Bắt đầu" ở Home. File là `BubbleWindow.kt`, đang bị gói 2 khoá, nên
  chờ gói 2 merge.
- `PHONE_CHECKLIST.md` dòng "Phone: ____" ở đầu file còn để trống, trong khi máy đã được ghi
  trong `PROGRESS.md`. Điền hãng, model, phiên bản Android. Không ghi tên chủ máy.
- Chrome hiện banner «Install» cho dichvucong. Kiểm xem nó có che đích ở bước nào không.

### 4.6 Chưa dựng, chỉ làm nếu ba demo đã ổn

- Record mode (nửa sau milestone 10): chuyên gia làm một lần, app ghi lại thành playbook nháp.
- Đường lùi bằng ảnh chụp cho app có cây accessibility rỗng (xem 4.3).
- Bản đồ tự xây (`MECHANISM.md` mục 6). Chỉ dùng cho phần thuyết trình.

### 4.7 Phần không phải code

Repo chưa có gì cho 60 điểm còn lại. Cần một người lo:

- Bằng chứng vấn đề người dùng (35 điểm): số liệu có nguồn về người cao tuổi dùng dịch vụ
  công trực tuyến, hồ sơ bị trả lại, và nếu kịp thì một hai buổi thử với người thật.
- Đường tới người dùng đầu tiên và quy mô (25 điểm).
- Kịch bản demo đúng theo `AGENTS.md` mục "Demo script": tắt app khỏi recents, mở lại, ba dấu
  xanh ở Home, mở dịch vụ, chạm bong bóng, đi 3 tới 4 màn hình, rồi DocCheck.

## 5. Luật làm việc (tóm tắt, bản đầy đủ ở `AGENTS.md`)

- `git pull --rebase origin main` trước khi làm và trước mỗi lần push. Không force-push `main`.
- Việc dở hoặc rủi ro thì dùng nhánh ngắn (`feat/<chu-de>`, `fix/<chu-de>`), CI xanh rồi merge.
- Máy này không có JDK. Build bằng GitHub Actions: đẩy nhánh rồi chạy
  `gh workflow run android.yml -R quantrungvu0701-ops/HACKATHON-OPENAI --ref <nhánh>`, hoặc mở
  PR. APK nằm trong artifact `chiday-debug-apk-<commit>` của lần chạy xanh.
- Code và comment tiếng Anh. Chuỗi giao diện tiếng Việt trong `strings.xml`.
- `PROGRESS.md` chỉ thêm dòng, không sửa dòng cũ. Việc chưa kiểm trên máy thì ghi "pending",
  không ghi là đã chạy.
- Không commit API key. Không ghi tên chủ máy hay dữ liệu cá nhân ở bất cứ đâu.
- Không đổi `keystore/debug.keystore`.
- Không thêm dependency. Ngoại lệ duy nhất đã có là JUnit cho test.
- Build hỏng hai lần cùng một kiểu thì dừng, ghi lỗi vào `PROGRESS.md`, không thử biến thể.
- Không bao giờ gọi `performAction` hay `dispatchGesture`. Chỉ hướng dẫn, không bấm hộ.

## 6. Tra nhanh

| Cần | Ở đâu |
|---|---|
| Lấy APK mới nhất | GitHub, Actions, lần chạy **Android** xanh mới nhất, artifact `chiday-debug-apk-<commit>` |
| Cài | `adb install -r app-debug.apk` |
| Biết bản nào đang trên máy | Home, dòng "Bản dựng <commit>" ở cuối |
| Stub model | `python tools/stub/stub_server.py`, base URL `http://127.0.0.1:8787/v1` sau `adb reverse tcp:8787 tcp:8787` |
| Xem app nghĩ gì | `adb logcat -s ChiDay` |
| Thử tắt và mở lại | `bash tools/kill_reopen_test.sh 5` |
| Thử follow mode | `bash tools/follow_mode_test.sh` |
