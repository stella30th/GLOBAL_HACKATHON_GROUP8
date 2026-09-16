# Refactor plan: AI Skills Readiness Coach cho sinh viên

> Trạng thái: đặc tả triển khai MVP, chưa phải tính năng đã implement.
> Đối chiếu code ngày 2026-09-16. Đường dẫn tính từ repository root.
> Mục đích: AI/nhóm phát triển có thể triển khai mà không tự đoán data contract,
> vòng đời roadmap, cách nối practice hoặc tiêu chí hoàn thành.
> Refactor ứng dụng hiện có, không viết lại hoặc thay stack.

## 1. Mục tiêu và các quyết định MVP

Tên sản phẩm: **AI Skills Readiness Coach for Students**.
Tagline: **Identify your skill gaps. Build your roadmap. Practice for the AI era.**

Đối tượng là sinh viên mọi năm học. Không có gate sinh viên năm cuối hoặc xác minh năm học.
Demo tập trung Software Engineering nhưng giữ khả năng đọc hồ sơ ngành khác và guardrail tư vấn đúng ngành.

Luồng chính:
1. **Identify:** đọc CV/hồ sơ, chỉ ra điểm mạnh, khoảng trống và chỗ thiếu bằng chứng về kỹ năng.
2. **Develop:** roadmap 3/6/12 tháng phù hợp hồ sơ/năm học, có kỹ năng làm việc cùng AI.
3. **Practice:** luyện nhiệm vụ gắn với milestone, nhận góp ý, tự đánh dấu tiến độ.

Job matching/deep-dive là nguồn tham khảo thị trường, không phải điều kiện để luyện tập.
Chưa có CV hoặc job match vẫn được nhập hồ sơ thủ công và luyện từ roadmap.

Tài liệu lấy Identify/Develop/Practice làm mục tiêu nội bộ. Chưa kèm đề bài/rubric chính thức;
không coi các nhận định về trọng số chấm điểm hay yêu cầu giám khảo là dữ kiện đã xác minh.

### Quyết định để AI triển khai không phải tự chọn

- UI tiếp tục tiếng Anh; tài liệu kỹ thuật tiếng Việt.
- Tiến độ là **người dùng tự xác nhận**, không phải chứng nhận thành thạo hay kết quả tự động từ AI.
- Chat dùng endpoint hiện có; không xây hệ thống chấm thi hoặc bảng practice session.
- Audit/roadmap đang dùng được lưu thành snapshot trong DB; ID milestone ổn định qua reload/restart.
- Thêm một endpoint nhỏ riêng để cập nhật tiến độ, không dùng POST profile cho checkbox.
- Chat được giữ khi đổi tab; reload trang bắt đầu chat mới. Không lưu chat vào DB.
- Không có regenerate roadmap trong MVP. Thay đổi hồ sơ thật sự sẽ reset roadmap/tiến độ.
- Giữ kiến trúc một current profile hiện tại; authentication/multi-user ngoài scope.

## 2. Bản đồ code và những bẫy hiện tại

Backend root: `backend/src/main/java/com/gbhackathon/AICareerCode/`.
Frontend root: `frontend/`.

| File/khu vực | Hiện trạng cần biết |
|---|---|
| `frontend/src/App.jsx` | Chuyển màn hình bằng activeTab, chưa có router. Render có điều kiện nên đổi tab unmount component. Profile nằm tại App. |
| `frontend/src/components/ResumeAuditView.jsx` | Hiển thị cả audit và roadmap từ audit.careerRoadmap; tải lại khi updatedAt thay đổi. |
| `frontend/src/components/JobMatchingView.jsx` | Có deep-dive/interviewQuestions, chưa có callback mở practice. |
| `frontend/src/components/AiCoachChatView.jsx` | History nằm ở local state, mất khi unmount. Gửi tối đa 6 entries gần nhất, bỏ welcome message. |
| `frontend/src/api.js` | API helpers, DEFAULT_PROFILE, browser profile cache. |
| `service/ProfileService.java` | Mapping entity/DTO thủ công; save merge các field khác null; upload CV replace; save luôn đổi updatedAt. |
| `controller/ProfileController.java` | GET current, POST profile, upload CV, reset sample. Sample hiện là người đi làm và dùng merge. |
| `service/AiCoachService.java` | Cache RAM theo id@updatedAt; audit đã sinh roadmap; có Gemini và heuristic fallback. |
| `dto/CareerRoadmapDto.java` | Milestone chưa có ID; category là String. |
| `service/CvParserService.java` | CvExtraction riêng cho Gemini, parser heuristic riêng; không cho model đặt ID/timestamp. |
| `controller/CoachController.java` | Chat nhận message/history; trả reply/generatedBy/model. |
| `application.yaml`, `application-local.yaml` | Hibernate ddl-auto: update. |

**Bẫy cần sửa:** tick bằng POST profile sẽ đổi updatedAt → cache AI hết hiệu lực → audit tải lại
và có thể sinh roadmap mới, làm mất liên kết tiến độ.

Trước triển khai đọc AGENTS.md nếu có, README, cấu hình build và các file liên quan. Nếu code đã
đổi so với bảng trên, điều chỉnh cách nối nhưng giữ các invariant trong tài liệu.

## 3. Scope

Trong scope: branding/navigation, yearOfStudy, sample sinh viên, audit/roadmap AI-era skills,
snapshot/ID/tiến độ, practice từ roadmap và deep-dive, xử lý lỗi và kiểm thử luồng này.

Ngoài scope:
- Viết lại ExternalJobService, thuật toán JobMatchingService hoặc thêm job board.
- Xóa/thay logic visa/relocation hoặc xóa ngành ngoài Software Engineering.
- Login, multi-user isolation, LMS, chứng chỉ, gamification, lịch học, analytics.
- Chạy code người dùng, chấm code tự động, agent orchestration hoặc RAG mới.
- Điểm AI readiness tổng hợp mới; không đổi nhãn điểm ATS thành điểm năng lực AI.
- Deployment hoặc thay đổi dữ liệu production.

## 4. Data contract và vòng đời dữ liệu

### 4.1 yearOfStudy

Thêm String nullable vào UserProfile, ProfileDto và CvExtraction.

- Canonical values: `Year 1`, `Year 2`, `Year 3`, `Year 4`, `Year 5+`.
- Dropdown có `Not specified`; gửi chuỗi rỗng khi người dùng chủ động xóa.
- POST profile: thiếu/null = giữ nguyên; chuỗi rỗng sau trim = xóa về null; ngoài danh sách = 400.
- Response trả null nếu không rõ; frontend xử lý cache cũ thiếu field.
- CV chỉ trích khi ghi rõ năm đang học; không suy từ tuổi/ngày tốt nghiệp/kinh nghiệm.
- Output model không hợp lệ chuẩn hóa về null, không làm toàn bộ upload thất bại.
- Parser heuristic có thể trả null, không bắt buộc suy luận năm học.
- Lựa chọn dropdown người dùng ưu tiên hơn năm học cũ trong raw CV khi tạo prompt.
  Điều chỉnh hướng dẫn raw CV authoritative hiện tại để không ghi đè lựa chọn đã sửa.
- Map đầy đủ qua saveOrUpdateProfile, replaceProfileFromCv, toDto và sample.

### 4.2 Snapshot và progress storage

Không tạo bảng/entity mới. Thêm storage nội bộ vào UserProfile:

| Field | Kiểu và ý nghĩa |
|---|---|
| learningSnapshotJson | TEXT/LOB JSON của ResumeAuditDto đã chuẩn hóa, gồm careerRoadmap và metadata nguồn hiện có; không expose trực tiếp qua ProfileDto. |
| learningSnapshotVersion | Integer phiên bản serialization/prompt contract, ban đầu 1. |
| learningSnapshotProfileKey | String revision sinh snapshot, theo id@updatedAt hiện có. |
| completedMilestones | TEXT JSON array milestone IDs; null đọc như []; không dùng comma-separated title. |

ProfileDto thêm `List<String> completedMilestones`, `String roadmapId` nullable. Hai field này
chỉ đọc đối với POST profile: generic save không áp dụng giá trị client gửi; dùng endpoint mục 5.

CareerRoadmapDto thêm `String roadmapId`; RoadmapMilestone thêm `String id`.

- Backend cấp UUID sau khi Gemini/heuristic sinh nội dung, trước khi lưu snapshot.
- Không giao model tạo ID; không dùng array index/title làm ID.
- Snapshot giữ nguyên thì ID giữ nguyên; snapshot mới có ID mới và progress reset.
- Snapshot JSON hỏng/version không tương thích: log không chứa raw CV, invalidate snapshot và
  tiến độ, tạo lại qua luồng bình thường; không trả 500 chỉ vì dữ liệu legacy.
- Record cũ có cột null đọc như chưa có snapshot/progress; không xóa DB để nâng cấp.

Schema **có thay đổi**. ddl-auto: update hiện tại có thể thêm cột; ghi điều này trong README.
Môi trường không dùng chế độ đó cần migration tương ứng. Không tuyên bố không cần đổi schema.

### 4.3 Reset/invalidation

Giữ updatedAt làm revision nội dung hồ sơ trong MVP.

| Thao tác | Snapshot/progress | Chat |
|---|---|---|
| Sửa field editable có giá trị thực sự thay đổi | Đổi updatedAt, xóa snapshot/progress | Reset theo revision mới |
| Save cùng dữ liệu sau normalize | Giữ nguyên tất cả | Giữ |
| Upload CV thành công | Replace, luôn reset; năm học thiếu về null | Reset |
| Đổi sample thành công | Replace đầy đủ, luôn reset, bỏ raw CV cũ | Reset |
| Tick/untick | Chỉ đổi completedMilestones, không đổi updatedAt | Giữ |
| Đổi tab/reload trang | Giữ | Đổi tab giữ; reload reset |
| Restart backend | Đọc lại từ DB | Không lưu chat server |

MVP invalidate khi bất kỳ field hồ sơ editable thực sự thay đổi, kể cả contact fields;
không cần content hash/revision system mới. UI có note gần Save rằng sửa hồ sơ sẽ reset lộ trình
và tiến độ, không cần modal xác nhận mỗi lần.

Kiểm tra entity lifecycle hooks nếu có để save snapshot/progress không vô tình đổi updatedAt.
Không save entity cũ đã giữ qua một request AI dài rồi ghi đè profile mới.

## 5. API và tính nhất quán

### 5.1 Audit/roadmap dùng chung snapshot

Giữ GET `/api/coach/audit` và GET `/api/coach/roadmap`.

1. Đọc snapshot hợp lệ cùng profile revision và version thì trả lại.
2. Nếu chưa có, sinh audit/roadmap bằng Gemini hoặc fallback, validate nội dung rồi cấp ID.
3. Trước persist, kiểm tra revision vẫn như lúc bắt đầu; đã đổi thì không ghi snapshot cũ.
   Trả 409 để frontend reload revision mới; không tự retry vô hạn.
4. Endpoint roadmap trả chính careerRoadmap của snapshot đó, không sinh bộ ID riêng.

DB snapshot là nguồn chuẩn; cache RAM chỉ tối ưu. Deep-dive giữ cache theo revision hiện tại.
Hai request audit/roadmap đồng thời phải hội tụ cùng snapshot đã commit: tại bước persist dùng
kiểm tra/khóa ngắn hoặc cơ chế tương đương; request đến sau trả snapshot đã lưu. Không giữ DB
transaction/lock trong lúc chờ Gemini.

Snapshot fallback cũng giữ ổn định. Hiển thị nguồn offline; không tự thay bằng roadmap Gemini
khi đổi tab. Đổi nhãn nút Re-Scan hiện có thành `Reload analysis`; nút chỉ tải snapshot.
Retry lỗi mạng không đồng nghĩa regenerate. Muốn có phân tích mới, sửa hồ sơ thật sự.

### 5.2 Progress endpoint mới

Thêm PATCH `/api/profiles/current/milestones/{milestoneId}`:

```json
{ "roadmapId": "server-issued-roadmap-uuid", "completed": true }
```

- roadmapId và boolean completed bắt buộc; thiếu/sai kiểu = 400.
- Chưa có snapshot hoặc roadmap không còn là bản hiện tại = 409.
- Milestone không thuộc roadmap hiện tại = 404; không chấp nhận arbitrary ID.
- Set/unset một ID, idempotent; không nhận full list từ client.
- Read-modify-write tiến độ trong transaction có khóa ngắn/cơ chế tránh lost update tương đương.
- Trả ProfileDto hiện tại; updatedAt không đổi. Không gọi Gemini/invalidate cache.

Frontend thêm helper trong api.js, cache response và update profile tại App. Dùng pessimistic
update: disable checkbox khi đang save, tick sau response thành công; lỗi giữ trạng thái cũ và
hiện retry. Khi 409, reload profile và audit; khi 404, thông báo bài không còn hợp lệ rồi reload.

## 6. Identify và Develop

### 6.1 Ý nghĩa audit

Giữ healthScore và DTO audit hiện có để giảm scope. Nhãn điểm nói rõ CV/ATS quality;
không quảng bá là điểm AI readiness.

Dùng strengths/weaknesses/summary và roadmap để nhận xét ba nhóm:
1. Dùng công cụ AI và đặt yêu cầu có ngữ cảnh/mục tiêu/giới hạn.
2. Kiểm chứng output: nguồn, giả định, kiểm thử và phát hiện sai sót.
3. Áp dụng AI giải quyết vấn đề chuyên ngành, giải thích quyết định, dùng dữ liệu phù hợp.

Nhận xét dựa trên bằng chứng hồ sơ. Thiếu bằng chứng dùng `Not enough evidence in your profile`,
không kết luận người dùng không biết kỹ năng. Không bịa dự án/chứng chỉ/điểm số.
Không thêm DTO đánh giá năng lực hoặc hứa cung cấp điểm định lượng cho ba nhóm trong MVP.

### 6.2 Roadmap và prompt

- Giữ 3/6/12 tháng, title/description/category/estimatedHours.
- Thêm AI_FLUENCY, giữ category hiện có (bao gồm APPLICATION đang dùng trong prompt).
- Ít nhất một AI_FLUENCY có hành động và sản phẩm đầu ra kiểm tra được, không chỉ “Learn AI”.
  Ví dụ: dùng AI đề xuất unit tests, tìm test thiếu và giải thích cách kiểm chứng.
- Year 1–2 ưu tiên nền tảng/dự án học phần; Year 3 portfolio/thực tập; Year 4–5+ hoàn thiện
  portfolio và chuẩn bị internship/junior role. Giữ các mốc thời gian, đổi nội dung/độ khó.
- Không có năm học: trung tính, dựa dữ kiện thực tế. Không ép hồ sơ người đi làm thành sinh viên.
- Cập nhật describeCandidate, prompt audit có embedded roadmap, prompt roadmap nếu còn dùng,
  chat context và heuristic fallback. Không chỉ sửa prompt roadmap mà bỏ audit UI đang gọi.
- Validate trước lưu: nếu thiếu AI_FLUENCY, bổ sung milestone fallback phù hợp ngành, không bịa
  dữ kiện. UI có fallback icon/màu cho category chưa biết.
- Giữ generatedBy/model/offlineReason chính xác; không gọi kết quả offline là Gemini.

## 7. Practice: state, entry points và rubric

### 7.1 Từ roadmap — luồng chính

Mỗi milestone có `Practice this skill`. Tạo practice context:

```text
requestId: client UUID nhận diện lần bấm
source: "roadmap"
profileRevision: id + updatedAt
roadmapId, milestoneId, milestoneTitle, milestoneDescription, category
question: null
```

App giữ practice request và chat state (hoặc hook do App sở hữu), chuyển activeTab sang chat.
Không thêm router chỉ để truyền prompt. History/loading/context phải tồn tại khi đổi tab.

### 7.2 Từ job deep-dive — luồng phụ

Mỗi câu hỏi có `Practice this question`; context dùng source `job`, jobId/jobTitle/question;
roadmapId/milestoneId null. Không tự đoán câu hỏi thuộc milestone nào.

Practice từ job không tự đổi tiến độ. Người dùng có thể quay roadmap và tự đánh dấu nếu thấy
đã hoàn thành. Không gọi đây là liên kết tự động giữa câu hỏi thị trường và roadmap.

### 7.3 Hành vi hội thoại

Giữ POST `/api/coach/chat` và response hiện có. Prompt yêu cầu:

- Coach đúng ngành, giai đoạn học và context milestone/job.
- Có question thì hỏi đúng câu đó; từ milestone thì tạo bài tập nhỏ liên quan.
- Chỉ hỏi **một câu/nhiệm vụ**, chờ trả lời; không đưa đáp án hoặc chấm trước.
- Sau trả lời, đánh giá 0–2 mỗi tiêu chí: tính đúng/phù hợp; giải thích/lập luận;
  bằng chứng hoặc cách kiểm chứng. Tổng 0–6 là phản hồi luyện tập, không phải chứng chỉ.
- Nêu một điểm tốt, một điểm cần cải thiện, một gợi ý cụ thể và mời thử lại.
- AI_FLUENCY phải có kiểm chứng output/giả định, không chỉ hỏi thuộc định nghĩa.

Rubric là yêu cầu output văn bản, không phải structured grading API. Không parse điểm/prose
để auto-complete. Format sai vẫn hiển thị an toàn, không giả định có điểm hợp lệ.

Mỗi request trong phiên luyện phải giữ practice context dù history cắt còn 6 entries. Có thể
prepend context block vào message gửi API; UI chỉ hiển thị text người dùng nhập, history không
lặp context. Chưa cần mở rộng chat API chỉ để truyền metadata.

### 7.4 Session, lỗi và completion

- Bắt đầu bài practice mới tạo session mới; UI ghi rõ hành vi đó.
- Consume requestId một lần; StrictMode/rerender/quay lại tab không gửi lại prompt.
- Khi gửi disable Send và các entry point practice mới để tránh request ghi đè messages.
- Response thuộc revision/session cũ bị bỏ qua nếu đã đổi hồ sơ/session.
- Đổi tab giữ chat; reload reset chat. Đổi profile revision reset chat.
- `Back to roadmap` về tab audit. Source roadmap có thể hiện `Mark milestone complete
  (self-reported)` gọi API progress; không bắt buộc phải có điểm AI.
- Roadmap ghi `Self-reported progress`, cho tick và untick; completion không tự động.
- Network error/offline: thông báo AI không khả dụng, cho retry, không tạo điểm/feedback giả.
- Retry do người dùng chủ động, không nhân đôi user message.
- Chat hiện dùng dangerouslySetInnerHTML: khi sửa phải escape HTML trước formatting hoặc dùng
  renderer an toàn; không render raw HTML từ CV/model/câu hỏi/input thành executable markup.

## 8. UX, sample và dữ liệu mặc định

Branding sửa Navbar, index.html, ProfileView hero, audit header, chat header/welcome/quick prompts
và README. Tên tab: `My profile`, `Skills & roadmap`, `Practice & coach`, `Market opportunities`.
Tab khởi đầu từ matching đổi sang profile; profile có nút đi Skills & roadmap, vẫn truy cập matching.

Hai sample thay ba mẫu người đi làm:

| Endpoint type | Hồ sơ |
|---|---|
| student-year-2 | Year 2, Software Engineering, 0 năm kinh nghiệm nghề nghiệp, lập trình cơ bản/Git/dự án học phần, mục tiêu nền tảng và thực tập. |
| student-year-4 | Year 4, Software Engineering, 0 năm kinh nghiệm nghề nghiệp, capstone/full-stack/testing/làm việc nhóm, mục tiêu internship/junior. |

Dùng tên giả/email example.com; set industry rõ ràng; dự án học tập không tính thành đi làm.
Sample replace đầy đủ, bỏ rawCvText/target roles/dữ liệu người trước. Nút frontend dùng type mới;
unknown type trả 400, không âm thầm chọn sample mặc định.

DEFAULT_PROFILE frontend và mặc định backend chuyển sang onboarding trống/trung tính; không
điền 3 năm kinh nghiệm hoặc mục tiêu senior. Không bắt chọn sample mới sử dụng được app.
Cache cũ thiếu field mới vẫn render được.

## 9. Checklist file

### Backend (đường dẫn tương đối backend root ở mục 2)

- [ ] model/UserProfile.java: yearOfStudy, snapshot/progress storage, timestamp hooks.
- [ ] dto/ProfileDto.java: yearOfStudy, completedMilestones/roadmapId read-only với generic save.
- [ ] dto/CareerRoadmapDto.java: roadmapId và milestone id.
- [ ] service/ProfileService.java: mapping, normalize/validate, no-op save, replace/reset,
      invalidation, progress update không sửa revision.
- [ ] controller/ProfileController.java: samples, validation/status codes, PATCH progress.
- [ ] service/CvParserService.java: CvExtraction/prompt/mapping, không suy đoán năm học.
- [ ] service/AiCoachService.java: prompt/heuristic, snapshot reuse, ID/version, stale/concurrent handling.
- [ ] controller/CoachController.java: audit/roadmap cùng snapshot, chat API tương thích.
- [ ] repository/UserProfileRepository.java: query/locking nếu cần persist snapshot/progress an toàn.
- [ ] Có thể thêm LearningSnapshotService và request DTO nhỏ để tách trách nhiệm; tránh dependency
      vòng ProfileService/AiCoachService, không thêm framework hoặc entity không cần thiết.

### Frontend/docs

- [ ] src/App.jsx: navigation, state chat/practice, stale response guard, reset theo revision,
      update profile sau progress response.
- [ ] src/api.js: default fields, PATCH helper/cache, xử lý status errors.
- [ ] src/components/ProfileView.jsx: year dropdown, samples, reset note, nút roadmap.
- [ ] src/components/ResumeAuditView.jsx: category, stable IDs, checkbox/count, practice callback,
      không refetch vì chỉ tick progress.
- [ ] src/components/JobMatchingView.jsx: practice callback từng interview question.
- [ ] src/components/AiCoachChatView.jsx: context/rubric/session/retry/completion/safe rendering.
- [ ] src/components/Navbar.jsx, index.html, CSS liên quan: branding và labels.
- [ ] README.md: scope, demo, storage/reset rules, schema changes, ATS/self-report limitations.

## 10. Thứ tự làm và kiểm thử

1. Data contract và profile mapping/reset/normalization.
2. Snapshot, stable IDs, progress API và test invariant.
3. Prompt/heuristic, sample/default data.
4. UI roadmap/progress và App state.
5. Practice, giữ chat và xử lý lỗi.
6. Copy, checks và demo walkthrough.

Automated tests ưu tiên:
- yearOfStudy round-trip/clear/invalid; CV/sample replace không giữ dữ liệu cũ.
- Tick/untick/idempotence/invalid/stale; updatedAt không đổi, Gemini không được gọi.
- Snapshot tái dùng khi service/cache mới khởi tạo với cùng DB; audit/roadmap cùng IDs.
- Edit thật reset, no-op giữ; request cũ không ghi đè revision mới.
- Concurrent generation hội tụ; progress updates không làm mất cập nhật.
- Chat context còn khi truncate history, không gửi trùng vì render/đổi tab.

Mock Gemini cho test data flow; không phụ thuộc API live trong unit test. Chỉ kiểm tra live có
kiểm soát khi đã có cấu hình hợp lệ; không đưa API key vào source/log/docs.

Trong frontend chạy `npm run build`, `npm run lint`. package.json hiện chưa có test runner;
không thêm framework lớn chỉ để test copy; có thể manual verify UI và test logic thuần khi phù hợp.
Trong backend chạy `mvn test` hoặc wrapper nếu có. Persistence tests dùng DB test, không DB deployment.
Nếu check bị lỗi có sẵn/thiếu cấu hình, ghi rõ; không tuyên bố pass nếu chưa chạy thành công.

## 11. Acceptance criteria

### Profile/Identify
- [ ] Mọi năm học hoặc bỏ trống dùng được; không có gate năm cuối.
- [ ] Năm học lưu/đọc/xóa/reload đúng; CV thiếu năm không giữ giá trị cũ.
- [ ] Sample đúng giai đoạn, không lẫn raw CV hoặc dữ liệu người trước.
- [ ] Điểm ATS giữ đúng nghĩa; phân biệt thiếu bằng chứng với thiếu kỹ năng.

### Roadmap/progress
- [ ] Có 3/6/12 tháng và AI_FLUENCY với hành động/sản phẩm đầu ra cụ thể.
- [ ] Year 2/Year 4 có hướng dẫn phù hợp ở cả Gemini và heuristic.
- [ ] Tick/untick/count giữ sau reload/restart; nội dung/ID roadmap không đổi.
- [ ] Tick không đổi updatedAt, không gọi Gemini hoặc reload audit.
- [ ] Edit thật reset snapshot/progress; no-op save không reset.
- [ ] Audit/roadmap cùng roadmapId/milestone IDs; request cũ không overwrite bản mới.

### Practice/UI
- [ ] Roadmap mở đúng bài, deep-dive đúng câu; prompt đầu chỉ gửi một lần.
- [ ] AI chờ câu trả lời mới góp ý; context/rubric không mất sau nhiều lượt.
- [ ] Đổi tab giữ chat; đổi profile reset và bỏ response cũ.
- [ ] Completion chỉ do người dùng chọn, không theo điểm/prose của AI.
- [ ] Job practice không tự hoàn thành milestone bất kỳ.
- [ ] Error/offline rõ ràng; retry không nhân đôi; không giả lập điểm.
- [ ] HTML trong chat hiển thị an toàn, không thực thi.
- [ ] Build/tests liên quan pass hoặc báo rõ blocker; matching cũ vẫn truy cập được.

## 12. Demo và bàn giao

1. Chọn Year 2 hoặc nhập/upload CV; xem Skills & roadmap, chỉ ra nhận xét dựa bằng chứng và AI_FLUENCY.
2. Chọn Year 4 để minh họa tư vấn theo giai đoạn. Hai sample cũng khác kỹ năng/dự án, không gọi
   đây là bằng chứng chỉ riêng yearOfStudy tạo khác biệt. Muốn kiểm tra riêng năm học, sửa duy
   nhất dropdown trên cùng profile.
3. Practice từ milestone AI_FLUENCY, trả lời, nhận góp ý và thử lại.
4. Tự đánh dấu hoàn thành, quay roadmap rồi reload để chứng minh tiến độ/nội dung được giữ.
5. Nếu còn thời gian, deep-dive job minh họa nguồn câu hỏi bổ sung.

Đầu ra task triển khai: code, tests phù hợp, README và báo cáo ngắn thay đổi/checks/giới hạn.
Không tự deploy hoặc mở rộng non-goals. Nếu cần đổi quyết định sản phẩm trong plan, nêu lý do
cụ thể; tổ chức code tương đương có thể tự quyết miễn giữ contract và acceptance criteria.
