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
bản sao nào, và `backend/data/` nằm trong `.gitignore` để không commit nhầm. Xem `backend/.env.example`
cho danh sách đầy đủ các biến ở mục này.

✅ **Đã chạy được với file thật.** Workbook `sfia-9_current-standard_en_260521.xlsx` cho ra
**147 skills**, và **65/66** dòng từ vựng công nghệ khớp được mã SFIA thật. Xem số liệu hiện tại ở
`GET /api/plan/data-status`.

Backend chỉ **tải về** từ nguồn cấu hình — không có đường nào để nó tự đẩy file ngược lên GitHub,
S3 hay R2, và không cần S3 SDK hay credentials có quyền ghi cho việc này.

**Local:**

1. Đăng ký tại [sfia-online.org](https://sfia-online.org/en/sfia-9/documentation).
2. Vào **SFIA 9 → Documentation**, tải file Excel *"SFIA 9 skill descriptions"* (`.xlsx`).
3. Đặt vào `backend/data/sfia/` — kể cả khi file nằm trong thư mục con theo ngôn ngữ mà bản tải về
   tự giải nén ra (`SFIA 9 Excel - English/...`, `SFIA 9 Excel - Deutsch/...`, ...): loader quét cả
   thư mục gốc và một cấp thư mục con, ưu tiên xác định bản `English` khi có nhiều lựa chọn — không
   bao giờ chọn ngẫu nhiên giữa các ngôn ngữ.
4. Khởi động lại backend, hoặc gọi `POST /api/plan/data-status/reload` (hoặc
   `POST /api/admin/sfia/reload`) với header `X-Admin-Token`.

**Trên production (Render và tương tự):** filesystem của container là ephemeral — file copy tay sẽ
mất sau mỗi cold start — và Dockerfile **cố tình không copy** file SFIA vào image vì image build từ
repo công khai. Thay vào đó backend **tự tải** từ nguồn cấu hình:

| Biến | Ý nghĩa |
|---|---|
| `SFIA_DATA_DIR` | Nơi đặt/tải file. Mặc định `./data/sfia`, trên container là `/tmp/sfia`. |
| `SFIA_SOURCE_URL` | URL tải workbook `.xlsx`. Nguồn mặc định được hướng dẫn là **GitHub private release asset** — xem mục vận hành ở dưới. **Secret.** |
| `SFIA_SOURCE_TOKEN` | Gửi kèm dạng `Authorization: Bearer`, nhưng **chỉ tới host đã cấu hình** — không bao giờ chuyển tiếp sang host khác nếu nguồn redirect. Tuỳ chọn. **Secret.** |
| `ADMIN_TOKEN` | Bảo vệ mọi hành động ghi lại dữ liệu tham chiếu: `/api/admin/sfia/*` **và** `POST /api/plan/data-status/reload`. Chưa đặt thì các endpoint này từ chối tất cả. |

Khi nào backend mới thực sự tải lại từ nguồn:

- **Khởi động (start-up):** chỉ tải nếu **chưa có workbook cục bộ nào đọc được** — file đang có sẵn
  thì được dùng nguyên, không gọi ra ngoài.
- **Reload thủ công** (`/api/plan/data-status/reload` hoặc `/api/admin/sfia/reload`): nếu
  `SFIA_SOURCE_URL` đã cấu hình thì **luôn tải lại**, dù thư mục cục bộ đang có file hợp lệ — vì
  "reload" từ một operator có nghĩa là "lấy bản mới nhất", không phải "xác nhận lại file đang có".
  Không cấu hình `SFIA_SOURCE_URL` thì reload đọc từ file cục bộ như bình thường.

File tải về được ghi vào file tạm rồi **được validate bằng đúng parser đọc SFIA** (mở được như một
workbook thật và có ít nhất một dòng SFIA nhận ra được) trước khi thay thế file đang dùng — một
trang HTML đăng nhập hay JSON lỗi không mở được như workbook nên bị loại ngay, không bao giờ được
lưu thành "workbook". Tải/parse thất bại thì **giữ nguyên** file, taxonomy và index tốt trước đó;
lỗi được ghi nhận và hiển thị ở panel *Sources & data* (đã lọc bỏ URL/token) chứ không làm mất dữ
liệu đang chạy tốt. Kết nối đứt giữa chừng, redirect sang host khác, hay response vượt quá 25 MB
đều bị chặn bằng cùng cơ chế đó. Một lock nội bộ đảm bảo không có hai lượt tải/reload nào chạy chồng
lên nhau. URL và token **không bao giờ vào log hay vào response API**.

Endpoint admin (`X-Admin-Token`, xem `ADMIN_TOKEN`) — tuỳ chọn, chỉ phục vụ dev hoặc khôi phục tạm
thời cho instance đang chạy:

```bash
curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" -F "file=@sfia-9.xlsx" \
  http://localhost:8080/api/admin/sfia/upload
```

File tải lên chỉ được lưu trên filesystem của **instance hiện tại**, và cũng được validate trước khi
thay file đang dùng — không phải đường bền vững trên container ephemeral: nó có thể mất khi restart,
redeploy, hoặc instance được tái tạo sau khi ngủ. `SFIA_SOURCE_URL` mới là thứ sống sót qua những
việc đó, và lần reload nguồn tiếp theo sẽ thay file upload tạm này bằng bản từ nguồn đó. Chưa đặt
`ADMIN_TOKEN` thì các endpoint admin **từ chối tất cả**, không mở toang.

**Vận hành nguồn GitHub private release (mặc định được hướng dẫn):**

1. Xác nhận repository sẽ lưu asset thực sự **private** — đừng giả định repo hiện tại đã private.
2. Tạo một **Release** trong repo đó, tải workbook SFIA 9 bản English (`.xlsx`) lên làm asset của
   release.
3. Lấy **asset ID** (xem trong phần API của release, hoặc qua `gh api`), dùng đúng dạng URL REST
   API của GitHub cho asset — **không dùng** URL trang release hay link tải trên trình duyệt:
   ```
   https://api.github.com/repos/<owner>/<repo>/releases/assets/<asset_id>
   ```
   Backend gửi kèm `Authorization: Bearer <SFIA_SOURCE_TOKEN>` và
   `Accept: application/octet-stream` — đúng theo tài liệu GitHub cho tải private release asset.
   GitHub có thể trả redirect tới một host tải file khác; backend theo được redirect đó nhưng
   **không** gửi lại `Authorization` sang host khác.
4. Tạo một **fine-grained personal access token** giới hạn đúng repository đó, quyền đọc tối
   thiểu cần thiết (Contents: Read-only là đủ). Nếu tổ chức có yêu cầu phê duyệt token, xin phê
   duyệt trước khi dùng.
5. Đặt `SFIA_SOURCE_URL` (URL ở bước 3) và `SFIA_SOURCE_TOKEN` (token ở bước 4) trong Render
   Environment cho service backend.
6. Khởi động lại service, hoặc gọi endpoint reload có `X-Admin-Token`.
7. Kiểm tra số skill, dataset version và trạng thái nguồn ở panel *Sources & data*
   (`GET /api/plan/data-status`).

PAT không hết hạn theo từng request như một pre-signed URL, nhưng vẫn có thể hết hạn hoặc bị thu
hồi theo chính sách của bạn hoặc của tổ chức — khi đó tạo token mới và cập nhật lại
`SFIA_SOURCE_TOKEN`. Khi thay workbook bằng một release asset mới, **asset ID có thể đổi** — cập
nhật lại `SFIA_SOURCE_URL` rồi reload; đừng giả định việc tải file mới cùng tên sẽ giữ nguyên URL
API cũ. Phương án lưu trữ khác (Cloudflare R2 + Worker kiểm tra token tĩnh, v.v.) chưa được triển
khai trong repo này.

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
