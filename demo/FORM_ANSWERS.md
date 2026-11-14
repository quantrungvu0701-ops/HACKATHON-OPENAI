# Câu trả lời form nộp bài — Chỉ Đây

Copy từng mục vào form. Chỗ có `<...>` là thông tin chỉ đội biết, tự điền.

**Email:** `<email người nộp>`

**Tên đội / Team name:** `<tên đội>`

**Tên sản phẩm / Product name:** Chỉ Đây

**Thành viên thực tế / Actual team members:**
```
<Họ tên> — <email Luma>
<Họ tên> — <email Luma>
<Họ tên> — <email Luma>
```

---

**Người dùng, vấn đề và bằng chứng / Users, problem, and evidence**

Chỉ Đây dành cho bất kỳ ai bị lạc trong một app/web khó hiểu: trước hết là người lớn tuổi và người chưa quen điện thoại phải tự làm thủ tục công, nhưng cũng là người trẻ lần đầu làm một việc lạ (cắt video trong CapCut, tra bưu gửi, nộp hồ sơ trực tuyến) mà không biết bấm vào đâu.
- Việt Nam có khoảng 14,2 triệu người từ 60 tuổi trở lên (Tổng cục Thống kê, 2024), dự kiến 18 triệu vào 2030. Đồng thời thủ tục hành chính đang chuyển mạnh sang trực tuyến (dichvucong.gov.vn, VNeID).
- Khó khăn thật: giao diện chữ nhỏ, nhiều nút giống nhau, thuật ngữ hành chính; người dùng phải nhờ con cháu hoặc ra quầy, mất thời gian, dễ bấm nhầm nút nộp/thanh toán.
- Bằng chứng nhóm tự kiểm tra hôm nay: để xem "cần giấy tờ gì để xin bản sao Giấy khai sinh" trên dichvucong.gov.vn phải đi qua ô tìm kiếm không có nhãn, chọn đúng 1 trong 15 kết quả gần giống nhau ("Cấp bản sao…" vs "Đăng ký khai sinh…") rồi kéo xuống tận mục "Thành phần hồ sơ". Trong CapCut, nút "Tách" (Split) bị ẩn sau nút "Edit"; trang vnpost.vn có banner chạy liên tục làm khó nhìn.
- `<THÊM nếu có: 1–2 câu trích từ người thân/người dùng đã phỏng vấn, ví dụ "Mẹ tôi 62 tuổi phải nhờ tôi nộp hồ sơ…">`

AI giúp trực tiếp: người dùng chỉ cần bấm một nút tròn, AI đọc màn hình, khoanh đúng nút cần bấm và đọc hướng dẫn bằng tiếng Việt, xưng "bác", từng bước một.

---

**Tính năng AI và demo trực tiếp / AI feature and live demo**

Nền tảng: **Android** (Samsung Galaxy A56, Android 16), app native Kotlin.

AI làm gì: dịch vụ trợ năng đọc cây giao diện của app đang mở (không chụp ảnh, đã che mật khẩu, số điện thoại, số CCCD, chữ người dùng gõ), gửi danh sách phần tử cho mô hình (OpenAI gpt-4.1). Mô hình chọn đúng MỘT nút cần bấm tiếp và viết câu hướng dẫn tiếng Việt; app kiểm tra kết quả, vẽ vòng khoanh lên đúng nút, đọc to câu hướng dẫn, và tự sang bước sau khi màn hình đổi (không cần bấm lại). Bước nộp/xóa/thanh toán có cảnh báo đỏ. App chỉ hướng dẫn, không bao giờ tự bấm hộ. Thêm: chụp ảnh giấy tờ, AI kiểm tra ảnh có mờ/lóa/thiếu góc không.

Các bước demo sau khi đóng hẳn và mở lại app:
1. Vuốt tắt Chỉ Đây khỏi đa nhiệm, mở lại: màn hình chính "Sẵn sàng" (quyền trợ năng, kết nối AI, giọng đọc tiếng Việt).
2. Mở dichvucong.gov.vn, bấm nút tròn: AI khoanh ô tìm kiếm, đọc "Bác chạm vào ô trắng… gõ khai sinh".
3. Gõ "khai sinh": AI tự sang Bước 2/3, khoanh đúng dòng "Cấp bản sao Trích lục hộ tịch, bản sao Giấy khai sinh".
4. Bấm vào: Bước 3/3, AI bảo kéo xuống xem "Thành phần hồ sơ".
5. (Nếu còn giờ) CapCut cắt bỏ đoạn giữa video: 6 bước, AI chờ đủ 2 lần bấm "Tách" rồi khoanh "Xóa" với cảnh báo đỏ.
Mỗi bước AI trả lời trong 1,5–3 giây.

---

**Người dùng đầu tiên và hướng mở rộng / First users and growth**

Người dùng đầu tiên: (1) cha mẹ, ông bà của chính các thành viên và người quen; (2) câu lạc bộ người cao tuổi phường/xã và các lớp "bình dân học vụ số" ở Hà Nội — nơi có sẵn tình nguyện viên cài app hộ; (3) điểm bưu điện văn hóa xã và bộ phận một cửa, nơi nhân viên đang phải hướng dẫn từng người. Cài một lần, sau đó người dùng tự làm.

Mở rộng: mỗi dịch vụ chỉ cần một "playbook" ngắn (JSON); app đã có chức năng biến văn bản hướng dẫn thành playbook nháp, nên thêm dịch vụ mới (ngân hàng, bệnh viện, VNeID, điện nước) rất nhanh. Không phụ thuộc app đích có API. Ra nước ngoài: chỉ đổi ngôn ngữ prompt và playbook; nhóm người cao tuổi dùng dịch vụ số đang tăng ở khắp Đông Nam Á, Nhật, Hàn, châu Âu. Kênh B2B/B2G: ngân hàng, nhà mạng, cổng dịch vụ công trả phí để giảm tải tổng đài và quầy giao dịch; bản cá nhân miễn phí.

---

**Phần làm hôm nay và phần có sẵn / Work built today and prior work**

Làm trong hackathon (13–14/11/2026), không có mã nguồn có sẵn từ trước: toàn bộ app Android (dịch vụ trợ năng, nút nổi, vòng khoanh, đọc màn hình, bộ lọc riêng tư, kiểm tra vị trí bấm, chế độ theo dõi tự sang bước, đọc giọng tiếng Việt, kiểm tra ảnh giấy tờ, 5 playbook đã thử trên điện thoại thật).
Bên thứ ba: OpenAI API (gpt-4.1); thư viện Android/Jetpack Compose, OkHttp, kotlinx.serialization; bộ đọc giọng nói (TTS) có sẵn của điện thoại. Các trang dịch vụ công khai: dichvucong.gov.vn, vnpost.vn, app CapCut.
Dữ liệu mô phỏng: server giả lập (stub) chỉ dùng khi phát triển, KHÔNG có trong demo; ảnh CCCD mẫu do nhóm tự tạo để thử. Demo dùng mô hình thật.

---

**Công nghệ, công cụ và bài học / Stack, tools, and one lesson**

Kotlin, Jetpack Compose, Android AccessibilityService + cửa sổ overlay, OkHttp, DataStore, OpenAI gpt-4.1; GitHub Actions build APK; adb để thử trên máy thật. Code viết cùng trợ lý AI lập trình (Claude Code `<+ Codex nếu đội có dùng: mô tả bước>`).
Bài học: AI chọn đúng nút là chưa đủ — phải kiểm tra bằng hình học xem ngón tay có bấm tới được không (nút bị che, bị cắt ở mép, nút ẩn sau menu như "Split" trong CapCut). Phần lớn thời gian là thử trên điện thoại thật chứ không phải viết prompt.

---

**Link sản phẩm hoặc mã nguồn:** https://github.com/quantrungvu0701-ops/HACKATHON-OPENAI
> ⚠️ Repo đang để private: phải chuyển public (hoặc thêm BGK làm collaborator) trước khi nộp, nếu không BGK không mở được.

**Link video dự phòng:** `<link Google Drive của demo/chiday_demo.mp4, chế độ "Bất kỳ ai có link">`
