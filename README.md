# Skill Path 🧭

> **Phân tích hồ sơ và mục tiêu nghề nghiệp để xây dựng một learning path cá nhân hoá, có căn cứ và nguồn học rõ ràng.**
>
> Một trang, một việc: bạn đưa CV và vị trí đang nhắm tới, hệ thống trả về lộ trình học theo giai
> đoạn — học kỹ năng gì, theo thứ tự nào, tốn bao nhiêu giờ, và học ở đâu.
> Spring Boot + React (Vite) + Google Gemini.

---

## 🌟 Sản phẩm làm gì

Vào trang là thấy ngay khu vực **learning path** chiếm phần lớn màn hình, bên cạnh là cột nhập
liệu. Không có tab điều hướng: sản phẩm chỉ làm một việc, và một hàng tab ngang hàng sẽ nói điều
ngược lại với người dùng lần đầu.

**Đầu vào** (cột trái):
1. Hồ sơ — tải CV PDF hoặc nhập thủ công. Sau khi tải lên, form hiện đúng những gì AI đọc được,
   có thể sửa. Toàn bộ phân tích phía sau dựa trên các trường này.
2. Mục tiêu — vị trí nhắm tới, cấp độ (Intern/Junior/Mid/Senior), độ dài lộ trình **1 / 3 / 6
   tháng**, số giờ học mỗi tuần, và tuỳ chọn dán mô tả công việc.

**Đầu ra** (cột phải), bốn phần:

| Phần | Nội dung |
|---|---|
| **Learning path** | Các giai đoạn: mục tiêu, kỹ năng, kỹ năng tiên quyết, hoạt động cụ thể, dự án đầu ra, tiêu chí tự đánh giá, số giờ, và nguồn học cho từng kỹ năng. Tick được từng mục. |
| **Skill gaps** | Khoảng cách giữa bằng chứng trong hồ sơ và yêu cầu vị trí, kèm mức độ, thứ tự ưu tiên, độ chắc chắn, và **bằng chứng nào sẽ thay đổi đánh giá đó**. |
| **Skill graph** | Đồ thị quan hệ kỹ năng. Không phải hình vẽ trang trí — thứ tự các giai đoạn được tính từ chính các cạnh tiên quyết này. |
| **CV review** | Điểm 0–100 cho **CV như một tài liệu**, kèm điểm mạnh, chỗ người đọc chưa thấy được, từ khoá ATS và các dòng nên viết lại. |

Chat là một **panel nhỏ** ("Hỏi về lộ trình") mở đè lên trang, giải thích phân tích và các giai
đoạn. Nó không sửa được lộ trình và không tick hộ bạn.

---

## 📐 Năm phương pháp, và chúng được triển khai thật ở đâu

Không có prompt lớn nào chỉ nhắc tên năm phương pháp. Mỗi bước có dữ liệu trung gian riêng, có
kiểm tra riêng, và bước sau thao tác trên kết quả bước trước.

| Phương pháp | Triển khai ở đâu | Bằng chứng nó thực sự chạy |
|---|---|---|
| **LLM CV Extraction** | [`CvParserService`](backend/src/main/java/com/gbhackathon/AICareerCode/service/CvParserService.java) — PDFBox đọc text, Gemini trích xuất có cấu trúc | Form hiện đúng dữ liệu đã trích, người dùng sửa được trước khi tạo lộ trình |
| **Skill Taxonomy (SFIA 9)** | [`TaxonomyService`](backend/src/main/java/com/gbhackathon/AICareerCode/service/taxonomy/TaxonomyService.java) + [`SfiaTaxonomyLoader`](backend/src/main/java/com/gbhackathon/AICareerCode/service/taxonomy/SfiaTaxonomyLoader.java) → [`TaxonomyMappingStep`](backend/src/main/java/com/gbhackathon/AICareerCode/service/pipeline/TaxonomyMappingStep.java) | Model **chỉ được dùng các mã đã truy xuất**; mã ngoài danh sách bị từ chối và bắt sinh lại |
| **Skill Gap Analysis** | [`GapAndGraphStep`](backend/src/main/java/com/gbhackathon/AICareerCode/service/pipeline/GapAndGraphStep.java) | Mỗi gap phải có `rationale`, `evidenceStatus` và thứ tự ưu tiên không trùng |
| **Knowledge Graph** | `GapAndGraphStep.orderGraph()` — topological sort trên các cạnh `PREREQUISITE_OF` | Thứ tự tính ra được truyền vào bước lập lộ trình làm ràng buộc; giai đoạn dạy kỹ năng trước tiên quyết của nó bị từ chối |
| **RAG** | [`ResourceRetrievalService`](backend/src/main/java/com/gbhackathon/AICareerCode/service/retrieval/ResourceRetrievalService.java) + [`Bm25Index`](backend/src/main/java/com/gbhackathon/AICareerCode/service/retrieval/Bm25Index.java) | Model nhận một shortlist tài liệu **có thật trong DB** và chỉ được trích dẫn bằng `resourceKey`; URL do server gắn vào từ catalogue |

Pipeline chạy theo thứ tự trong
[`LearningPlanPipeline`](backend/src/main/java/com/gbhackathon/AICareerCode/service/pipeline/LearningPlanPipeline.java):

```
CV extraction (lúc upload)
  → retrieval #1 trên taxonomy
  → taxonomy mapping + yêu cầu vị trí        (1 lần gọi Gemini)
  → gap analysis + knowledge graph           (1 lần gọi Gemini)
  → topological sort (code, không phải AI)
  → retrieval #2 trên kho tài liệu
  → learning path                            (1 lần gọi Gemini)
  → CV review                                (1 lần gọi Gemini)
```

Bỏ bất kỳ bước nào thì bước sau mất thứ nó thao tác lên.

---

## 🚫 Quy tắc "không hardcode nhận xét"

Toàn bộ nhận xét cá nhân, skill gap, thứ tự ưu tiên và nội dung lộ trình do AI tạo ra từ hồ sơ và
dữ liệu tham chiếu đã truy xuất. **Không có template dự phòng nào phía sau.**

Khi cả hai model không trả lời:

- `POST /api/plan/generate` trả **503** kèm lý do thật, không tạo gì cả.
- Giao diện nói rõ: *"Nothing was written in its place."* và cho thử lại.
- Lộ trình đã lưu trước đó **không bị xoá** — một lần sinh lại thất bại không được phép làm mất
  lộ trình bạn đang học.
- Chat cũng vậy: không có câu trả lời soạn sẵn đóng vai lời khuyên.

`GeminiClient` **ném exception** thay vì trả `null`, chính là để không caller nào có thể lặng lẽ
thay bằng text viết sẵn.

**Code được phép làm:** validate schema, cấp UUID, lưu snapshot, sort topo và phát hiện chu trình,
cộng giờ, truy xuất tài liệu, kiểm tra citation, kiểm tra URL, lưu tiến độ người dùng tự tick.

**Taxonomy và catalogue nguồn học là tri thức đầu vào, không phải kết luận soạn sẵn.** Chúng mô tả
ngành nghề và tài liệu công khai; việc cái nào xuất hiện trong lộ trình của ai, ở giai đoạn nào, vì
lý do gì là do AI quyết từ hồ sơ cụ thể.

Nếu model trả kết quả sai hợp đồng, [`AiStepRunner`](backend/src/main/java/com/gbhackathon/AICareerCode/service/pipeline/AiStepRunner.java)
gửi lại **đúng quy tắc đã vi phạm** và cho sửa tối đa 2 lần; vẫn hỏng thì request thất bại.

---

## ⚙️ Cấu hình

### Gemini (bắt buộc)

| Biến môi trường | Mặc định | Ghi chú |
|---|---|---|
| `GEMINI_API_KEY` | *(bắt buộc)* | Lấy tại [Google AI Studio](https://aistudio.google.com/apikey). Chỉ đặt qua biến môi trường, **không commit**. |
| `GEMINI_PRIMARY_MODEL` | `gemini-3.1-flash-lite` | Model chính. |
| `GEMINI_FALLBACK_MODEL` | `gemini-3.5-flash` | Model dự phòng. |
| `GEMINI_TIMEOUT_SECONDS` | `60` | |
| `AI_MAX_REPAIR_ATTEMPTS` | `2` | Số lần yêu cầu model sửa một câu trả lời sai hợp đồng. |

> ⚠️ **Về thứ tự hai model.** Yêu cầu ban đầu ghi model chính là **Gemini 3.5 Flash**, dự phòng là
> **Gemini 3.1 Flash-Lite**. Mặc định ở đây **đảo lại**, vì `gemini-3.5-flash` ở gói miễn phí chỉ
> cho khoảng **20 request/ngày/project**, mà một lộ trình tốn 4 lần gọi — demo sẽ hết quota sau
> 4–5 lần chạy. Cả hai vẫn nằm trong đúng hai model được yêu cầu, chỉ khác thứ tự ưu tiên.
> Muốn đúng nguyên văn yêu cầu, đổi hai biến môi trường, **không cần sửa code**:
> ```
> GEMINI_PRIMARY_MODEL=gemini-3.5-flash
> GEMINI_FALLBACK_MODEL=gemini-3.1-flash-lite
> ```

Hệ thống chỉ liên hệ **đúng hai model này**. Chuyển sang model dự phòng chỉ xảy ra với 429 (hết
quota), 404 (key không thấy model) và 5xx. Key sai hoặc request sai định dạng thì dừng ngay, vì
model thứ hai sẽ hỏng y hệt.

Kiểm tra AI có thật sự trả lời:

```bash
curl "http://localhost:8080/api/coach/ai-status?probe=true"
```

### SFIA 9

SFIA là nội dung **có bản quyền**: miễn phí cho phát triển cá nhân và phần lớn nhu cầu nội bộ của
doanh nghiệp, nhưng phải **đăng ký tài khoản** mới tải được. Repo này không chứa và sẽ không chứa
bản sao nào, và `backend/data/` nằm trong `.gitignore` để không commit nhầm.

✅ **Đã chạy được với file thật.** Workbook `sfia-9_current-standard_en_260521.xlsx` cho ra
**147 skills**, và **65/66** dòng từ vựng công nghệ khớp được mã SFIA thật. Xem số liệu hiện tại ở
`GET /api/plan/data-status`.

**Local:**

1. Đăng ký tại [sfia-online.org](https://sfia-online.org/en/sfia-9/documentation).
2. Vào **SFIA 9 → Documentation**, tải file Excel *"SFIA 9 skill descriptions"* (`.xlsx`).
3. Đặt vào `backend/data/sfia/`.
4. Khởi động lại backend, hoặc gọi `POST /api/admin/sfia/reload` với header `X-Admin-Token`.

**Trên production (Render và tương tự):** filesystem của container là ephemeral — file copy tay sẽ
mất sau mỗi cold start — và Dockerfile **cố tình không copy** file SFIA vào image vì image build từ
repo công khai. Thay vào đó backend **tự tải lúc khởi động** khi thư mục trống:

| Biến | Ý nghĩa |
|---|---|
| `SFIA_DATA_DIR` | Nơi đặt/tải file. Mặc định `./data/sfia`, trên container là `/tmp/sfia`. |
| `SFIA_SOURCE_URL` | URL tải workbook: pre-signed link từ R2/S3, hoặc URL có token. **Secret.** |
| `SFIA_SOURCE_TOKEN` | Gửi kèm dạng `Authorization: Bearer`. Tuỳ chọn. **Secret.** |

File được ghi ra `.part` rồi mới đổi tên — kết nối đứt giữa chừng không để lại file cụt khiến
parser báo lỗi sai ở mọi lần khởi động sau. URL và token **không bao giờ vào log**.

Endpoint admin (`X-Admin-Token`, xem `ADMIN_TOKEN`):

```bash
curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" -F "file=@sfia-9.xlsx" \
  http://localhost:8080/api/admin/sfia/upload
```

Hữu ích để sửa nhanh không cần redeploy, nhưng **không phải** đường bền vững trên container
ephemeral — nó ghi vào filesystem, mất khi cold start. `SFIA_SOURCE_URL` mới là thứ sống sót.
Chưa đặt `ADMIN_TOKEN` thì các endpoint admin **từ chối tất cả**, không mở toang.

**Khi không có file:** hệ thống vẫn chạy trên bộ từ vựng công nghệ đi kèm (66 mục trong
`backend/src/main/resources/taxonomy/technology-extensions.json`), và **nói rõ** điều đó ở panel
*Sources & data* cùng trong phần provenance của mọi lộ trình sinh ra trong thời gian đó. Các mã
hiển thị khi đó **không phải** mã SFIA chính thức.

Bộ từ vựng công nghệ là phần mở rộng của dự án, **không phải** SFIA: SFIA mô tả kỹ năng nghề
nghiệp ("Programming/software development") chứ không liệt kê công nghệ ("React"). Mỗi dòng mở rộng
có gợi ý mã SFIA tương ứng, nhưng mã đó **chỉ được ghi nhận khi file SFIA thật xác nhận mã tồn tại**.

### Kho tài liệu học (RAG)

94 tài liệu trong `backend/src/main/resources/retrieval/learning-resources.json`: tài liệu chính
chủ (MDN, PostgreSQL, Spring, PyTorch…), khoá đại học (MIT OCW, Harvard CS50), và nền tảng học có
uy tín. Sửa file này rồi gọi `POST /api/plan/data-status/reload` là đủ.

Kiểm tra link còn sống:

```bash
curl -X POST http://localhost:8080/api/plan/data-status/verify-urls
```

Chạy thủ công chứ không chạy lúc khởi động — vài trăm request HTTP sẽ thêm cả phút vào mỗi lần
cold start. **Link truy cập được chỉ chứng minh trang tồn tại**, không chứng minh nội dung phù hợp
trình độ hay còn miễn phí, và giao diện ghi đúng như vậy.

### Các tài nguyên khác

Không cần embedding model, không cần vector database, không cần search API. Retrieval dùng BM25
trong bộ nhớ trên vài trăm dòng — đủ tốt ở quy mô này và không tốn thêm quota.

---

## 🔄 Lưu trữ, tiến độ và vòng đời dữ liệu

Lộ trình được lưu thành **snapshot** trong hàng hồ sơ, kèm `planId` và id từng giai đoạn / hoạt
động / tiêu chí do **server** cấp (UUID). Id không đổi qua reload, qua xoá cache và qua restart
backend — tiến độ đã tick vẫn trỏ đúng chỗ.

Snapshot gắn với **cả hồ sơ lẫn mục tiêu**:

| Thao tác | Lộ trình đã lưu | Tiến độ |
|---|---|---|
| Sửa hồ sơ (có thay đổi thật) | Không còn được phục vụ, phải tạo lại | Xoá khi lưu lộ trình mới |
| Bấm Save mà không đổi gì | Giữ nguyên | Giữ |
| Đổi vị trí / cấp độ / thời lượng / giờ mỗi tuần | Không còn khớp, phải tạo lại — **nhưng đổi ngược lại thì lộ trình cũ khớp lại** | Giữ cho tới khi lưu lộ trình mới |
| Tải CV mới | Hồ sơ bị thay, lộ trình phải tạo lại. **Mục tiêu được giữ** | Xoá khi lưu lộ trình mới |
| Tick / untick | Giữ | Chỉ đổi danh sách đã tick |
| Reload trang / restart backend | Giữ (đọc lại từ DB) | Giữ |
| Sinh lộ trình mới thất bại | **Giữ nguyên lộ trình cũ** | Giữ |

Khi đầu vào đã đổi, API trả `supersededSnapshot: true` và giao diện nói *"bạn đã tạo một lộ trình
trước đó, nhưng hồ sơ hoặc mục tiêu đã thay đổi"* — khác hẳn với "bạn chưa tạo lần nào".

### ⚠️ Thay đổi schema

Bản này **thêm bảng** `taxonomy_skills`, `learning_resources`, và **thêm cột** vào `user_profiles`:
`target_role`, `target_seniority`, `target_job_description`, `plan_duration_months`,
`plan_hours_per_week`, `learning_snapshot_goal_key`.

`spring.jpa.hibernate.ddl-auto: update` tự thêm khi khởi động. Môi trường không dùng chế độ đó cần
migration tương ứng.

**Không cần xoá database.** Các cột của Job Matching (`target_locations`, `willing_to_relocate`,
`target_work_type`) và `year_of_study` không còn được map nhưng **vẫn nguyên trong DB** —
`ddl-auto: update` không bao giờ xoá cột. Bảng `job_opportunities` cũng được giữ nguyên.

Snapshot cũ (`learning_snapshot_version = 1`) được sinh ra khi **chưa có** taxonomy, retrieval hay
graph. Chúng bị nhận diện và **không được phục vụ**, vì trình bày chúng như kết quả của pipeline
mới là nói sai về nguồn gốc của chúng.

---

## 📌 Giới hạn cần nói rõ

- **Điểm CV review là điểm chất lượng tài liệu**, không phải điểm năng lực và không phải điểm sẵn
  sàng nghề nghiệp. Giao diện ghi rõ ngay cạnh con số.
- **Tiến độ do người dùng tự xác nhận.** AI không tự tick.
- **Hồ sơ im lặng ≠ không có kỹ năng.** Hệ thống phân biệt `HAS_EVIDENCE` / `LIMITED_EVIDENCE` /
  `NO_DATA` và không bao giờ kết luận người dùng không làm được điều mà hồ sơ chỉ đơn giản là
  không nhắc tới.
- **RAG tăng khả năng truy vết, không bảo đảm câu trả lời đúng.**
- **Lộ trình không hứa việc làm.** Khi mục tiêu không vừa quỹ thời gian, hệ thống ghi
  `TIGHT` hoặc `NOT_ACHIEVABLE`, nêu rõ phần bị bỏ ra ngoài, và lập lộ trình cho phần vừa được.
- Quan hệ học trước–học sau giữa các công nghệ hầu hết là **đề xuất của AI**, được đánh dấu
  `AI_SUGGESTED` và vẽ bằng nét đứt trên đồ thị. SFIA không phải nguồn cho loại quan hệ này.

---

## 🚀 Chạy dự án

### Yêu cầu

- **JDK 21** (pom và Dockerfile đặt `java.version=21`)
- Node.js 20+
- MySQL/PostgreSQL, hoặc dùng profile `local` với H2 (không cần cài gì thêm)

### Backend

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

> Máy chỉ có JDK 17 vẫn build và chạy được — mã nguồn không dùng tính năng nào của Java 21 —
> bằng cách thêm `-Djava.version=17` vào lệnh Maven. Đây là cách bản cập nhật này được kiểm thử
> trên máy dev; deployment vẫn dùng JDK 21 theo Dockerfile.

### Frontend

```bash
cd frontend && npm install && npm run dev
```

Vite proxy `/api` sang `http://localhost:8080`.

### Kiểm thử

```bash
cd backend && ./mvnw test
```

```bash
cd frontend && npm run lint && npm run build
```

---

## 🔌 API

| Endpoint | Mô tả |
|---|---|
| `GET /api/profiles/current` | Hồ sơ + mục tiêu hiện tại |
| `POST /api/profiles` | Lưu hồ sơ và/hoặc mục tiêu |
| `POST /api/profiles/upload-cv` | Tải CV, trích xuất bằng AI (503 khi AI không khả dụng) |
| `POST /api/profiles/reset-sample/{student-early\|student-final}` | Hồ sơ mẫu để demo |
| `GET /api/plan` | Lộ trình đã lưu, hoặc `plan: null` kèm `missingInputs` |
| `POST /api/plan/generate[?force=true]` | **Chạy pipeline.** 503 khi AI hỏng, 502 khi AI không sửa được kết quả. `force=true` khi người dùng chủ động tạo lại; không có nó thì input không đổi sẽ trả lại plan đã lưu |
| `PATCH /api/plan/progress/{itemId}` | Tick một giai đoạn / hoạt động / tiêu chí |
| `GET /api/plan/data-status` | Taxonomy, kho tài liệu và cấu hình model đang có |
| `POST /api/plan/data-status/reload` | Nạp lại SFIA và các catalogue từ đĩa |
| `POST /api/admin/sfia/upload` | Tải workbook SFIA lên và nạp ngay. Cần `X-Admin-Token` |
| `POST /api/admin/sfia/reload` | Đọc lại workbook, hoặc tải từ `SFIA_SOURCE_URL`. Cần `X-Admin-Token` |
| `POST /api/plan/data-status/verify-urls` | Kiểm tra link toàn bộ catalogue |
| `POST /api/coach/chat` | Panel hỏi đáp về lộ trình |
| `GET /api/coach/ai-status?probe=true` | Một round trip thật tới Gemini |

Đọc và sinh là hai endpoint tách biệt. Sinh lộ trình tốn 4 lần gọi model và gần một phút; để việc
mở trang tự kích hoạt nó nghĩa là tiêu quota theo ngày cho người chỉ muốn xem lại thứ đã có.

---

## 👤 Phiên và cách ly dữ liệu

Không có đăng nhập. Mỗi trình duyệt nhận một **phiên ẩn danh** qua cookie `sid` (UUID do server
cấp, `HttpOnly`), và **mọi** profile đều gắn với phiên đó qua cột `session_id`.

Điều này sửa một lỗi nghiêm trọng của bản trước: hệ thống chỉ có **một hàng profile duy nhất dùng
chung cho tất cả mọi người**. `getCurrentOrCreateProfile()` lấy hàng có `updatedAt` mới nhất rồi
**xoá mọi hàng còn lại**, nên hai người mở web cùng lúc là đọc CV của nhau, và người thứ hai lưu hồ
sơ là xoá mất hàng của người thứ nhất.

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173,...` | **Bắt buộc đặt khi deploy.** Danh sách origin cụ thể, không dùng `*`: request có cookie mà phản chiếu origin bất kỳ nghĩa là trang web nào cũng gọi API thay người dùng được. |
| `APP_SESSION_COOKIE_SECURE` | `true` | Profile `local` để `false` (dev chạy HTTP). |
| `APP_SESSION_COOKIE_SAME_SITE` | `None` | Bắt buộc `None` khi frontend và API khác origin. Profile `local` dùng `Lax`. |
| `ADMIN_TOKEN` | *(trống)* | Guard cho `/api/admin/**`. Trống ⇒ từ chối hết. |

Frontend gửi `credentials: 'include'` ở một chỗ duy nhất (`fetchWithTimeout` trong `api.js`).

**Nói rõ giới hạn:** đây là **cách ly giữa người dùng bình thường**, không phải xác thực. Ai có giá
trị cookie thì có profile đó. Với sản phẩm giữ CV thật ngoài phạm vi demo, lớp này nên được **thay
bằng tài khoản**, không phải mở rộng thêm.

**Migration:** `LegacyProfilePurge` xoá các hàng không có `session_id` lúc khởi động. Đó không phải
dữ liệu của một người cụ thể — nó là dữ liệu trộn lẫn giữa những người đã dùng bản cũ, không quy
được cho ai và không có gì để giữ. Log ghi số hàng bị xoá, không ghi nội dung.

## 🔒 Bảo mật và dữ liệu cá nhân

- API key chỉ đi trong header `x-goog-api-key`, không bao giờ nằm trong URL hay access log.
- Nội dung CV **không bao giờ** được ghi log — kể cả khi parse lỗi, chỉ log tên loại exception.
- CV, JD và tài liệu truy xuất được đưa vào prompt trong khối có rào và được giới thiệu là **dữ
  liệu, không phải chỉ dẫn**. Kèm theo đó, mọi câu trả lời đều bị validate lại theo dữ liệu đã truy
  xuất — đó mới là lớp phòng vệ thật, vì rào prompt một mình không đủ.

---

## ❌ Đã gỡ khỏi bản này

- **Job Matching** và toàn bộ deep-dive, đồng bộ 6 job board, filter, `THE_MUSE_API_KEY`.
- **Practice mode** trong chat (ra đề, chấm 0–6).
- **Year of study** — thay bằng cấp độ mục tiêu, thứ quyết định trực tiếp mức yêu cầu của từng kỹ năng.
- **Mọi nhánh fallback viết sẵn**: audit heuristic, roadmap heuristic, từ điển kỹ năng cho CV, câu
  trả lời chat offline.

Dữ liệu cũ trong database không bị xoá.
