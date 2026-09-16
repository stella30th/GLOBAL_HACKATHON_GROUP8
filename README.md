# AI Career Coach & Global Opportunity Navigator 🚀

> Nền tảng định hướng nghề nghiệp và tìm việc toàn cầu, dùng **Google Gemini**, **Spring Boot** và **React (Vite)**.
> Hoạt động cho **mọi ngành nghề** — không chỉ IT.

---

## 🌟 Tính năng

1. **Phân tích CV bằng AI (đa ngành)** — Gemini đọc trực tiếp nội dung CV để nhận diện đúng lĩnh vực
   (thiết kế vi mạch, cơ khí, tài chính, y tế, marketing, xây dựng…), trích xuất kỹ năng và công cụ
   thật sự có trong CV. Nếu AI không khả dụng, hệ thống dùng bộ từ điển đa ngành làm dự phòng và
   **để trống** những gì không tìm thấy thay vì suy đoán.
2. **Ghép việc làm theo đúng ngành** — Lấy tin tuyển dụng trực tiếp từ **6 nguồn**: Remotive (30 nhóm
   ngành), Jobicy, Remote OK, Himalayas, The Muse (y tế, tài chính, pháp lý, kỹ thuật, bảo trì…) và
   Arbeitnow (châu Âu, có bảo lãnh visa). Điểm phù hợp được tính từ mức độ trùng ngành, kỹ năng, kinh nghiệm và
   khả năng làm việc hợp pháp.
3. **Phân tích chuyên sâu từng vị trí** — AI đối chiếu CV với từng tin tuyển dụng cụ thể: điểm mạnh,
   khoảng trống, khả năng visa và câu hỏi phỏng vấn dự kiến.
4. **Đánh giá CV & lộ trình 12 tháng** — Chấm điểm ATS, viết lại gạch đầu dòng theo công thức STAR và
   dựng lộ trình mốc 3/6/12 tháng bám theo đúng dự án và công cụ trong CV.
5. **Chat với AI Career Coach** — Trả lời trực tiếp câu hỏi dựa trên hồ sơ thật của ứng viên.
   Giao diện hoàn toàn bằng tiếng Anh; AI trả lời tiếng Anh, và tự chuyển sang ngôn ngữ khác nếu
   người dùng hỏi bằng ngôn ngữ đó.
6. **Giao diện responsive** cho điện thoại, máy tính bảng và desktop.

Mọi kết quả AI đều hiển thị rõ **nguồn tạo ra nó**: nhãn tên model khi Gemini trả lời, hoặc nhãn
"AI tạm không khả dụng" khi hệ thống chạy ở chế độ dự phòng — không bao giờ ngụy trang nội dung
dự phòng thành câu trả lời của AI.

---

## ⚙️ Cấu hình Gemini (quan trọng)

| Biến môi trường | Mặc định | Ghi chú |
|---|---|---|
| `GEMINI_API_KEY` | *(bắt buộc)* | Lấy tại [Google AI Studio](https://aistudio.google.com/apikey). Chỉ đặt trong biến môi trường, **không commit vào repo**. |
| `GEMINI_MODEL` | `gemini-flash-lite-latest` | Model chính. |
| `THE_MUSE_API_KEY` | *(không bắt buộc)* | The Muse trả **403** cho IP datacenter của Render. Lấy key miễn phí tại [themuse.com/developers](https://www.themuse.com/developers/api/v2) để dùng lại nguồn này; không có key thì 5 nguồn còn lại vẫn chạy bình thường. |
| `GEMINI_FALLBACK_MODELS` | `gemini-3.1-flash-lite,gemini-flash-latest,gemini-3.5-flash,gemini-2.5-flash` | Danh sách dự phòng, thử lần lượt khi model chính bị 429 (hết quota) hoặc 404. |

> ⚠️ **Lưu ý về quota:** `gemini-3.5-flash` ở gói miễn phí chỉ cho **20 request/ngày/project**. Khi hết,
> mọi tính năng AI sẽ rơi về nội dung dự phòng. Vì vậy model mặc định là bản `flash-lite` có hạn mức
> lớn hơn nhiều, kèm cơ chế tự chuyển model khi gặp lỗi quota.

### Kiểm tra AI có thật sự hoạt động không

```bash
curl "https://<backend-url>/api/coach/ai-status?probe=true"
```

Kết quả trả về `reachable`, `lastWorkingModel` và `lastError` — đủ để biết ngay AI đang trả lời thật
hay đang chạy dự phòng, và vì sao.

---

## 💻 Chạy ở máy local

### Cách 1 — không cần Docker (nhanh nhất)

Dùng profile `local` với cơ sở dữ liệu H2 ghi ra file:

```bash
cd backend && GEMINI_API_KEY=your_key ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

### Cách 2 — dùng MySQL qua Docker

```bash
docker-compose up -d
```

```bash
cd backend && GEMINI_API_KEY=your_key ./mvnw spring-boot:run
```

### Frontend

```bash
cd frontend && npm install && npm run dev
```

Nếu backend chạy ở cổng khác `8080`, tạo `frontend/.env.local`:

```bash
echo "VITE_API_BASE=http://localhost:8099/api" > frontend/.env.local
```

---

## ☁️ Ghi chú khi deploy lên Render (gói Free)

- Render **tắt instance sau 15 phút không dùng**; lần truy cập kế tiếp mất khoảng 50–60 giây để khởi
  động lại. Frontend xử lý việc này bằng cách chờ tối đa 90 giây, hiển thị trạng thái "đang khởi
  động" thay vì báo lỗi, đồng thời ping `/api/health` mỗi 10 phút để giữ instance luôn thức khi người
  dùng đang mở web.
- `healthCheckPath: /api/health` giúp Render đánh dấu service sẵn sàng ngay khi tầng web trả lời, không
  phải chờ database.
- Việc đồng bộ tin tuyển dụng lúc khởi động chạy **bất đồng bộ**, nên không làm chậm thời gian khởi động.
- Kết quả đánh giá CV và lộ trình được **cache theo phiên bản hồ sơ**, vừa tiết kiệm quota AI vừa giúp
  chuyển tab không phải chờ gọi lại model.

---

## 🔌 API chính

| Method | Endpoint | Mô tả |
|---|---|---|
| `GET` | `/api/health` | Kiểm tra sống (không chạm database) |
| `GET` | `/api/coach/ai-status?probe=true` | Chẩn đoán kết nối Gemini |
| `POST` | `/api/profiles/upload-cv` | Tải CV lên và phân tích |
| `GET` | `/api/profiles/current` | Hồ sơ hiện tại |
| `GET` | `/api/jobs` | Danh sách việc làm (lọc theo từ khoá, khu vực, hình thức, visa) |
| `GET` | `/api/jobs/source-status` | Kiểm tra từng nguồn việc làm có bị chặn không |
| `POST` | `/api/jobs/sync-external` | Đồng bộ tin tuyển dụng từ 6 nguồn |
| `GET` | `/api/matches` | Điểm phù hợp cho hồ sơ hiện tại |
| `GET` | `/api/matches/{id}/ai-deep-dive` | Phân tích chuyên sâu một vị trí |
| `GET` | `/api/coach/audit` | Đánh giá CV và lộ trình |
| `GET` | `/api/coach/roadmap` | Lộ trình 12 tháng |
| `POST` | `/api/coach/chat` | Chat với AI Career Coach |
