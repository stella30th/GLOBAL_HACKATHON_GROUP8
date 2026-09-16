# AI Skills Readiness Coach for Students 🎓

> **Identify your skill gaps. Build your roadmap. Practice for the AI era.**
>
> Nền tảng giúp sinh viên nhìn ra khoảng trống kỹ năng, dựng lộ trình học 12 tháng và luyện tập
> theo từng mốc — dùng **Google Gemini**, **Spring Boot** và **React (Vite)**.
> Demo tập trung vào Software Engineering nhưng đọc được hồ sơ **mọi ngành**.

---

## 🌟 Ba bước chính

### 1. Identify — nhìn ra mình đang ở đâu
Nhập hồ sơ thủ công hoặc tải CV lên. Gemini đọc CV để nhận diện đúng lĩnh vực (vi mạch, cơ khí,
tài chính, y tế, marketing…) và trích xuất kỹ năng **thật sự có trong CV**; khi AI không khả dụng,
bộ từ điển đa ngành làm dự phòng và **để trống** thay vì suy đoán.

Phần đánh giá chỉ ra điểm mạnh, khoảng trống và **chỗ thiếu bằng chứng**. Khi hồ sơ im lặng về một
kỹ năng, hệ thống ghi `Not enough evidence in your profile` chứ không kết luận người dùng không biết
kỹ năng đó.

### 2. Develop — lộ trình 3 / 6 / 12 tháng
Lộ trình bám theo **năm học** (`Year 1`…`Year 5+`, hoặc để trống) và hồ sơ thực tế: năm 1–2 ưu tiên
nền tảng và dự án học phần, năm 3 hướng portfolio/thực tập, năm 4–5+ hoàn thiện portfolio và chuẩn
bị internship/junior. Mỗi lộ trình luôn có ít nhất một mốc **AI_FLUENCY** với hành động cụ thể và
sản phẩm đầu ra kiểm tra được — không phải "học AI" chung chung. Nếu model quên, backend tự bổ sung
mốc dự phòng trước khi lưu.

### 3. Practice — luyện tập và tự đánh dấu tiến độ
Mỗi mốc có nút **Practice this skill**: coach đặt **một** bài tập, chờ trả lời, rồi chấm 0–2 cho mỗi
tiêu chí (đúng/phù hợp · lập luận · bằng chứng và cách kiểm chứng) và góp ý. Đây là **phản hồi luyện
tập, không phải chứng chỉ**. Câu hỏi phỏng vấn trong phần deep-dive cũng có nút
**Practice this question** làm nguồn bài tập bổ sung.

### Nguồn tham khảo thị trường
Tin tuyển dụng lấy từ **6 nguồn** (Remotive, Jobicy, Remote OK, Himalayas, The Muse, Arbeitnow) để
cho thấy nhà tuyển dụng đang hỏi gì. Đây là tài liệu tham khảo — **không cần** có job match mới luyện
tập được.

Mọi kết quả AI đều hiển thị rõ **nguồn tạo ra nó**: nhãn tên model khi Gemini trả lời, hoặc nhãn
"AI tạm không khả dụng" khi hệ thống chạy ở chế độ dự phòng — không bao giờ ngụy trang nội dung
dự phòng thành câu trả lời của AI.

---

## 📌 Giới hạn cần nói rõ

- **Điểm ATS là điểm chất lượng CV/hồ sơ**, không phải điểm năng lực và không phải "AI readiness
  score". Giao diện ghi rõ điều này ngay cạnh con số.
- **Tiến độ do người dùng tự xác nhận.** Tick/untick là lựa chọn của sinh viên; AI không tự đánh dấu
  hoàn thành, và điểm 0–6 trong phần luyện tập không bao giờ được dùng để tự tick.
- Luyện tập từ câu hỏi tuyển dụng **không** hoàn thành mốc nào trong lộ trình.
- Không có xác minh năm học, không có gate sinh viên năm cuối: mọi năm học hoặc bỏ trống đều dùng được.

---

## 🔄 Lưu trữ, reset và vòng đời dữ liệu

Bài đánh giá cùng lộ trình được lưu thành **snapshot** trong hàng hồ sơ, kèm `roadmapId` và id từng
mốc do **server** cấp (UUID). Nhờ vậy id không đổi qua reload, qua xoá cache và qua restart backend —
tiến độ đã tick vẫn trỏ đúng chỗ.

| Thao tác | Snapshot / tiến độ | Chat |
|---|---|---|
| Sửa hồ sơ (có thay đổi thật) | Reset, sinh lại lộ trình mới | Reset |
| Bấm Save mà không đổi gì | **Giữ nguyên** (`updatedAt` không đổi) | Giữ |
| Tải CV lên / đổi sample | Reset, bỏ CV và dữ liệu người trước | Reset |
| Tick / untick mốc | Chỉ đổi danh sách đã hoàn thành | Giữ |
| Đổi tab | Giữ | Giữ |
| Reload trang | Giữ (đọc lại từ DB) | Bắt đầu chat mới |
| Restart backend | Giữ | Không lưu chat ở server |

Nút **Reload analysis** chỉ tải lại snapshot, **không** sinh lại phân tích. Muốn có phân tích mới,
hãy sửa hồ sơ thật sự.

### ⚠️ Thay đổi schema

Bản này **thêm cột** vào `user_profiles`: `year_of_study`, `learning_snapshot_json`,
`learning_snapshot_version`, `learning_snapshot_profile_key`, `completed_milestones`.
Cấu hình hiện tại dùng `spring.jpa.hibernate.ddl-auto: update` nên Hibernate tự thêm cột khi khởi
động. Môi trường nào không dùng chế độ đó cần migration tương ứng. Hàng dữ liệu cũ có cột null đọc
như "chưa có snapshot / chưa có tiến độ" — **không cần xoá database để nâng cấp**.

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
- Kết quả đánh giá CV và lộ trình được **lưu snapshot theo phiên bản hồ sơ** trong database (cache RAM
  chỉ là lớp tối ưu phía trước), vừa tiết kiệm quota AI vừa giúp chuyển tab và restart không làm mất
  id mốc lộ trình.

---

## 🔌 API chính

| Method | Endpoint | Mô tả |
|---|---|---|
| `GET` | `/api/health` | Kiểm tra sống (không chạm database) |
| `GET` | `/api/coach/ai-status?probe=true` | Chẩn đoán kết nối Gemini |
| `POST` | `/api/profiles/upload-cv` | Tải CV lên và phân tích |
| `GET` | `/api/profiles/current` | Hồ sơ hiện tại (kèm `completedMilestones`, `roadmapId` — chỉ đọc) |
| `POST` | `/api/profiles/reset-sample/{type}` | Nạp hồ sơ mẫu: `student-year-2` hoặc `student-year-4` |
| `PATCH` | `/api/profiles/current/milestones/{milestoneId}` | Tự đánh dấu một mốc (`{ "roadmapId": "...", "completed": true }`). Không đổi `updatedAt`, không gọi AI. 409 nếu lộ trình đã cũ, 404 nếu mốc không thuộc lộ trình hiện tại |
| `GET` | `/api/jobs` | Danh sách việc làm (lọc theo từ khoá, khu vực, hình thức, visa) |
| `GET` | `/api/jobs/source-status` | Kiểm tra từng nguồn việc làm có bị chặn không |
| `POST` | `/api/jobs/sync-external` | Đồng bộ tin tuyển dụng từ 6 nguồn |
| `GET` | `/api/matches` | Điểm phù hợp cho hồ sơ hiện tại |
| `GET` | `/api/matches/{id}/ai-deep-dive` | Phân tích chuyên sâu một vị trí |
| `GET` | `/api/coach/audit` | Đánh giá hồ sơ + lộ trình (đọc snapshot; sinh mới ở lần gọi đầu) |
| `GET` | `/api/coach/roadmap` | Lộ trình 12 tháng — **cùng snapshot, cùng id** với `/audit` |
| `POST` | `/api/coach/chat` | Chat và luyện tập với coach |

---

## 🧪 Kiểm thử

```bash
cd backend && ./mvnw test
```

24 test chạy trên H2 in-memory, không gọi Gemini thật (không cấu hình API key trong test → đi đường
offline, kết quả tất định). Phạm vi: vòng đời `yearOfStudy`, no-op save vs. reset, snapshot tái dùng
sau khi service khởi tạo lại, `/audit` và `/roadmap` trùng id, tick/untick idempotent không đổi
`updatedAt` và không gọi model, chặn ghi đè khi hồ sơ đã đổi, và lộ trình offline luôn có AI_FLUENCY.

```bash
cd frontend && npm run build && npm run lint
```
