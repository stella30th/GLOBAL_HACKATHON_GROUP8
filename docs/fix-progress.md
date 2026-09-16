# Tiến độ sửa lỗi P0/P1

> File làm việc. Cập nhật khi mỗi mục xong. Xoá khi tất cả đã merge.
>
> **Trạng thái: cả 5 mục đã code xong.** 93 test backend pass, frontend lint + build sạch.
> Những phần chưa verify được ghi rõ trong từng mục bên dưới.

| # | Mục | Trạng thái |
|---|---|---|
| 1 | P0 — Chung profile / lộ CV giữa người dùng | ✅ xong, đã verify chạy thật |
| 2 | P1 — SFIA chưa vào được production | ✅ code xong, chưa test với URL thật |
| 3 | P1 — Bằng chứng nguyên văn chưa được verify | ✅ xong, có test |
| 4 | P1 — Learning Path không bị graph ràng buộc | ✅ xong, có test |
| 5 | P1 — Regenerate tốn quota / xoá plan khi AI lỗi | ✅ xong, chưa test unit |

## 1. P0 — session isolation ✅

- [x] Cột `session_id` (unique index) trên `user_profiles` — mapped **nullable** vì `ddl-auto: update`
      không thêm được cột NOT NULL vào bảng đã có dữ liệu
- [x] `SessionIdFilter` sinh + set cookie `sid` (`HttpOnly`, Secure/SameSite theo config)
- [x] `ProfileService.getCurrentOrCreateProfile(String sessionId)`; **đã xoá hẳn logic xoá row khác**
- [x] 3 controller truyền sessionId; `saveOrUpdateProfile` / `replaceProfileFrom*` cũng nhận sessionId
- [x] Bỏ `@CrossOrigin(origins = "*")`; CORS dùng `APP_CORS_ALLOWED_ORIGINS`, bỏ wildcard
- [x] `api.js` gửi `credentials: 'include'` (một chỗ duy nhất: `fetchWithTimeout`)
- [x] `LegacyProfilePurge` xoá row không có `session_id` lúc khởi động

**Đã verify chạy thật:** 2 cookie jar → 2 profile khác nhau; A save không đụng B; request không
cookie được profile rỗng mới; cookie có cờ HttpOnly.

**Chưa verify:** `LegacyProfilePurge` chưa chạy với row cũ thật (DB local đã bị xoá sạch trước khi
test). Logic đơn giản nhưng nên xem log `Removing N profile row(s)` ở lần deploy đầu.

**Còn lại của mục này:** đặt `APP_CORS_ALLOWED_ORIGINS` trong `render.yaml` → xem mục 2.

## 2. P1 — SFIA vào production ✅ (code xong)

- [x] `SFIA_SOURCE_URL` + `SFIA_SOURCE_TOKEN`: `SfiaTaxonomyLoader.fetchFromSource()` tải về khi
      thư mục trống. Ghi ra file `.part` rồi mới `move` — kết nối đứt giữa chừng không để lại file
      cụt. URL và token **không bao giờ vào log**.
- [x] `AdminController`: `POST /api/admin/sfia/upload` (multipart) và `POST /api/admin/sfia/reload`,
      chặn bằng header `X-Admin-Token`, so sánh constant-time. `ADMIN_TOKEN` chưa đặt ⇒ **từ chối
      hết**, không fall open.
- [x] `Dockerfile`: ghi rõ vì sao KHÔNG copy file SFIA, tạo sẵn `/tmp/sfia`, đặt `SFIA_DATA_DIR`
- [x] `render.yaml`: `SFIA_SOURCE_URL`, `SFIA_SOURCE_TOKEN`, `ADMIN_TOKEN`,
      `APP_CORS_ALLOWED_ORIGINS` đều `sync: false` (secret, đặt trên dashboard). Bỏ hẳn ý định
      dùng `disk:` — Render free không có persistent disk.

**Chưa làm / chưa verify:**
- Chưa test với URL thật (chưa có bucket). Cần: upload file `.xlsx` lên R2/S3 → tạo pre-signed URL
  hoặc token → đặt env → xem log `Fetched the SFIA workbook (N bytes)`.
- **Không** làm phần "admin upload đẩy ngược lên bucket" như plan gợi ý — việc đó cần S3 SDK và
  credentials ký request. Endpoint upload hiện chỉ ghi vào filesystem container (mất khi cold
  start); đường bền vững là `SFIA_SOURCE_URL`. Response của endpoint nói rõ điều này.
- Parser vẫn chưa chạy với file SFIA thật (xem mục "Chưa verify" trong README).

## 3. P1 — verify quote nguyên văn ✅

- [x] `TextEvidence.normalize()`: NFC → lowercase → gộp whitespace → trim, **cộng thêm** fold dấu
      nháy/gạch ngang typographic và non-breaking space (PDF extraction hay đổi mấy ký tự này)
- [x] `TaxonomyMappingStep.profileCorpus(profile)` gom skills + bio + education + languages +
      title + industry + name + targetRoles + rawCvText. **Không** dùng `describeCandidate` vì
      chuỗi đó có lẫn text hướng dẫn — quote "khớp" với hướng dẫn sẽ qua được check một cách vô nghĩa
- [x] Mỗi `evidenceQuotes` phải xuất hiện trong corpus, không thì vào `problems` → repair loop
- [x] `sourceNote` phải xuất hiện trong `goal.jobDescription` khi `sourceType == JOB_DESCRIPTION`
- [x] Quote < 12 ký tự không check (ví dụ "Java" khớp ngẫu nhiên với mọi tài liệu)
- [x] Prompt nói rõ quote sẽ bị kiểm tra tự động → giảm số vòng repair
- [x] 15 test pass, gồm: quote bịa bị chặn, khác whitespace/hoa thường vẫn qua, quote quá ngắn bỏ qua,
      `sourceNote` không có trong JD bị chặn

## 4. P1 — graph ràng buộc learning path ✅ (làm bản robust)

Đã làm **bản robust** ngay, không làm bản string-matching tạm:

- [x] Thêm `skillNodeIds` vào `LearningPathDto.Phase` — model reference **id node**, không tự đặt
      lại tên. Tránh hẳn rủi ro "REST API design" vs "Designing REST APIs" giữa 2 lần gọi model
- [x] `describeOrder()` in kèm id (`[n3] React (GAP)`) để model copy được
- [x] Prompt bắt buộc `skillNodeIds`, nói rõ đây là thứ dùng để chạy check
- [x] `LearningPathStep.validate(path, gapIds, resourceKeys, budget, graph)` — build map
      `nodeId → node` và `to → [from]` cho cạnh `PREREQUISITE_OF`, duyệt phase theo thứ tự,
      reject khi prerequisite chưa được dạy ở phase trước
- [x] Bỏ qua node `kind == CURRENT` (hồ sơ đã có, plan không cần dạy)
- [x] Giữ luôn check theo label cũ (`prerequisiteSkills`) vì phase có thể nêu tiên quyết mà graph
      không có node
- [x] 19 test pass, gồm: dạy sai thứ tự bị chặn, prerequisite CURRENT không bị đòi, id không tồn
      tại bị chặn, thiếu `skillNodeIds` bị chặn, không có graph thì bỏ qua check

## 5. P1 — force regenerate ✅

- [x] **Bỏ hẳn** `store.invalidate()` chạy trước `pipeline.generate()`. Không cần thiết thật:
      `store.read()` vốn đã từ chối phục vụ snapshot lệch revision/goal, nên không xoá vật lý vẫn
      không hiển thị sai — mà lại giữ được plan cũ khi pipeline lỗi, và giữ khả năng quay về goal cũ
- [x] `force` xuyên suốt: `PlanController.generatePlan(request, force)` →
      `snapshotService.generate(profile, force)` → `store.store(..., force)`
- [x] `force=false` giữ nguyên hành vi dedupe cho 2 tab generate song song (cùng hội tụ 1 bộ phase id)
- [x] Frontend: nút đổi chữ thành **"Rebuild my learning path"** khi đã có plan, gửi `force=true`,
      kèm cảnh báo **trước khi bấm**: sẽ thay plan hiện tại + mất tick + tốn quota
- [x] Nút "Try again" trong banner lỗi (khi vẫn còn plan cũ) gửi `force=true`; nút retry ở màn
      hình lỗi trắng gửi `force=false`

**Chưa làm:** chưa có unit test cho nhánh `force` — `LearningSnapshotStore.store()` cần repository
thật, test hiện tại dựng store với repository null nên chỉ test được `read()`. Muốn test cần stub
`UserProfileRepository` hoặc dùng `@DataJpaTest`.

## Phát hiện ngoài kế hoạch

**SFIA 9 thật đã chạy được.** File `sfia-9_current-standard_en_260521.xlsx` có sẵn trong
`backend/data/sfia/`, parser đọc ra **147 skills** — đúng số skill của SFIA 9. Trước đây README ghi
"parser chưa chạy với file thật"; điều đó **không còn đúng**, đã cập nhật.

- Taxonomy index giờ chạy trên **213 dòng** (147 SFIA + 66 extension).
- **65/66** dòng extension khớp mã SFIA thật ⇒ các mã đoán trong
  `technology-extensions.json` gần như đúng hết.
- **1 dòng không khớp**, chưa xác định là dòng nào. Nó không bịa mã — chỉ ở lại làm extension không
  có link SFIA, đúng thiết kế. Muốn tìm: so danh sách `sfiaCode` trong
  `backend/src/main/resources/taxonomy/technology-extensions.json` với cột Code trong workbook.
- Đã thêm `extensionsMappedToSfia` vào `GET /api/plan/data-status` để con số này nhìn thấy được.

⚠️ File SFIA nằm trong `backend/data/` — đã có trong `.gitignore`, **không được commit**.

## Ghi chú môi trường

- Máy dev chỉ có JDK 17, pom để `java.version=21`. Build/test bằng `-Djava.version=17`.
- IDE tự build nền bằng JDK 21 → `clean` trước khi chạy jar.
- Chạy: `./mvnw -q -Djava.version=17 clean package -DskipTests` rồi
  `"$JAVA_HOME/bin/java" -jar target/AICareerCode-0.0.1-SNAPSHOT.jar --spring.profiles.active=local`
