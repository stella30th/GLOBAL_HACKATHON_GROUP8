# Pipeline và data contract

> Đối chiếu code ngày 2026-09-16. Đường dẫn tính từ repository root.
> Tài liệu này mô tả **code đang chạy**, không phải đặc tả dự kiến.
>
> Thay thế `track-refactor-plan.md`, vốn mô tả sản phẩm cũ (Job Matching, Practice mode, roadmap
> 3/6/12 tháng theo năm học) và đã không còn đúng sau bản cập nhật này.

---

## 1. Toàn cảnh

```
                 ┌─────────────────── backend ────────────────────┐
  CV PDF ──▶ CvParserService ──▶ UserProfile (hồ sơ + mục tiêu)
                                      │
                                      ▼
                            LearningPlanPipeline
                                      │
      TaxonomyService ◀── retrieval #1│
   (SFIA 9 + extensions)              │
                                      ▼
                            TaxonomyMappingStep      ← gọi Gemini (1)
                                      │  profileEvidence, targetRequirements
                                      ▼
                             GapAndGraphStep         ← gọi Gemini (2)
                                      │  skillGaps, knowledgeGraph
                                      ▼
                        orderGraph()  — topological sort, code thuần
                                      │  learningOrder, brokenCycles
   ResourceRetrievalService ◀─ retr #2│
        (94 tài liệu, BM25)           │
                                      ▼
                             LearningPathStep        ← gọi Gemini (3)
                                      │  phases, resources
                                      ▼
                            ProfileAuditStep         ← gọi Gemini (4)
                                      │
                                      ▼
                              LearningPlanDto ──▶ LearningSnapshotStore ──▶ DB
```

Bốn lần gọi model cho một lộ trình. Mỗi bước có validator riêng; hỏng thì
[`AiStepRunner`](../backend/src/main/java/com/gbhackathon/AICareerCode/service/pipeline/AiStepRunner.java)
gửi lại đúng quy tắc bị vi phạm, tối đa `AI_MAX_REPAIR_ATTEMPTS` lần.

---

## 2. Vì sao chia bước như vậy

Gộp taxonomy mapping và target requirements vào **một** lần gọi vì cả hai cần cùng một shortlist
taxonomy trước mặt, và cách ánh xạ phải nhất quán giữa chúng: nếu "ReactJS" trong CV thành
`EXT-REACT` thì yêu cầu của vị trí cũng phải dùng `EXT-REACT`, nếu không gap analysis so sánh hai
thứ không bao giờ gặp nhau.

Gộp gap analysis và knowledge graph vì mục đích duy nhất của graph là **sắp thứ tự các gap**. Sinh
graph ở lần gọi riêng thì nó thường xuyên nhắc tới kỹ năng không có trong danh sách gap; sinh cùng
nhau thì mỗi node `GAP` có thể bị bắt buộc trỏ về một gap có thật.

Tách CV review ra vì nó trả lời câu hỏi khác: gap nói *học gì*, review nói *những gì bạn đã làm có
đọc ra được không*. Một người có thể không có gap đáng kể mà CV vẫn bị loại từ vòng lọc.

---

## 3. Data contract

### Đầu vào

| Trường | Bắt buộc | Ràng buộc |
|---|---|---|
| Hồ sơ | ✔ | `skills` không rỗng **hoặc** `rawCvText` không rỗng |
| `targetRole` | ✔ | chuỗi tự do |
| `targetSeniority` | ✔ | `INTERN` / `JUNIOR` / `MID` / `SENIOR` |
| `planDurationMonths` | ✔ | `1` / `3` / `6` |
| `planHoursPerWeek` | ✔ | 1–40 |
| `targetJobDescription` | ✖ | text tự do; là **dữ liệu**, không phải chỉ dẫn |

Thiếu cái nào thì `GET /api/plan` trả về trong `missingInputs` và nút tạo lộ trình nói rõ thiếu gì
— thay vì để request chạy một phút rồi mới hỏng.

### Đầu ra — `LearningPlanDto`

```
planId                 UUID do server cấp
goal                   CareerGoalDto
profileEvidence[]      SkillEvidenceDto   — hồ sơ chứng minh được gì
targetRequirements[]   TargetRequirementDto — vị trí đòi hỏi gì, và căn cứ từ đâu
skillGaps[]            SkillGapDto        — khoảng cách, có id do server cấp
knowledgeGraph         SkillGraphDto      — nodes, edges, learningOrder, brokenCycles
learningPath           LearningPathDto    — phases[], mỗi phase có id
audit                  ProfileAuditDto    — CV như một tài liệu
provenance             Provenance         — model nào, lúc nào, truy xuất gì, giới hạn gì
```

### Ba giá trị `evidenceStatus`

| Giá trị | Nghĩa |
|---|---|
| `HAS_EVIDENCE` | Hồ sơ **trích dẫn được**. Validator bắt buộc có `evidenceQuotes` không rỗng. |
| `LIMITED_EVIDENCE` | Hồ sơ có nhắc nhưng không cho thấy đã làm gì với nó. |
| `NO_DATA` | Hồ sơ im lặng. **Không phải** kết luận người dùng không có kỹ năng đó. |

`assessedLevel` (1–7) chỉ được đặt khi có `levelBasis` nói rõ điều gì trong hồ sơ chống lưng cho
mức đó. Không có `levelBasis` thì validator từ chối.

### Ba giá trị `basis` trên cạnh đồ thị

| Giá trị | Nghĩa |
|---|---|
| `TAXONOMY` | Một dòng taxonomy đã truy xuất phát biểu quan hệ này. Với cạnh `PREREQUISITE_OF`, bắt buộc có `basisNote` nêu tên dòng đó. |
| `RETRIEVED_DOCUMENT` | Có tài liệu đã truy xuất chống lưng. |
| `AI_SUGGESTED` | Suy luận của model. Vẽ nét đứt trên đồ thị, ghi rõ trong bảng quan hệ. |

SFIA mô tả kỹ năng nghề nghiệp và mức trách nhiệm; nó **không** phát biểu "học React trước
Next.js". Prompt nói điều đó, và validator từ chối cạnh tiên quyết nhận `TAXONOMY` mà không nêu
được dòng nào.

---

## 4. Những kiểm tra do code làm, không phải AI

[`LearningPathStep.validate`](../backend/src/main/java/com/gbhackathon/AICareerCode/service/pipeline/LearningPathStep.java):

- Mọi `resourceKey` phải nằm trong shortlist đã truy xuất. Model **không được ghi URL**; server gắn
  URL từ catalogue.
- Mọi `addressesGapIds` phải là id gap có thật.
- Tổng giờ không vượt quá quỹ thời gian (dung sai 15%). **Dưới quỹ thì được** — "bạn có 120 giờ,
  đây là 90 giờ công việc và đây là lý do phần còn lại không vừa" là câu trả lời trung thực.
- Các giai đoạn chạy liên tiếp theo tuần, không chồng lấn, không hở.
- Giai đoạn không được cần một kỹ năng mà giai đoạn sau mới dạy.

`GapAndGraphStep.orderGraph`: topological sort (Kahn). Cạnh trùng lặp không bị nhầm là chu trình.
Chu trình thật được phá và **ghi lại** trong `brokenCycles` → hiện ở panel provenance, vì chu trình
nghĩa là model tự mâu thuẫn về thứ tự và đó là thông tin đáng biết.

`LearningSnapshotStore.isServable`: kiểm tra lại lúc **đọc ra**, không chỉ lúc ghi vào. Sửa lúc
sinh chỉ chữa được thứ bản build này ghi; nó không làm gì cho snapshot một bản build cũ đã lưu, mà
snapshot thì sống tới khi người dùng đổi hồ sơ hoặc mục tiêu.

---

## 5. Khoá snapshot

```
profileKey = id@updatedAt
goalKey    = role | seniority | months | hoursPerWeek | hash(jobDescription)
```

Snapshot chỉ được phục vụ khi **cả hai** khớp, và `learningSnapshotVersion` đúng
`SNAPSHOT_VERSION` hiện tại.

`updatedAt` chỉ nhích khi **nội dung hồ sơ** thật sự đổi. Đổi mục tiêu **không** nhích nó — nhờ
vậy đổi mục tiêu rồi đổi ngược lại thì lộ trình cũ khớp lại và không mất. Tick một ô cũng không
nhích nó, nếu không cái checkbox sẽ xoá chính lộ trình nó đang đánh dấu.

---

## 6. Retrieval

BM25 trong bộ nhớ ([`Bm25Index`](../backend/src/main/java/com/gbhackathon/AICareerCode/service/retrieval/Bm25Index.java)),
không phải LIKE query, vì vấn đề cốt lõi là lệch từ vựng: CV viết "ReactJS", catalogue viết
"React", taxonomy viết "Programming/software development". IDF cho phép một từ hiếm và phân biệt
tốt ("verilog") thắng một từ xuất hiện ở nửa corpus ("design").

Truy vấn theo **từng kỹ năng một** rồi trộn kết quả, thay vì nối tất cả thành một truy vấn — một
truy vấn gộp sẽ bị chi phối bởi kỹ năng có từ vựng đặc trưng nhất, và năm gap còn lại không có
nguồn nào.

Không khớp gì thì trả **rỗng**, không độn kết quả gần đúng nhất. Pipeline rẽ nhánh dựa trên khác
biệt đó: shortlist rỗng nghĩa là "chúng tôi không có nguồn cho kỹ năng này", và điều đó được ghi
vào `provenance.limitations` thay vì gắn bừa một tài liệu không liên quan cho trang trông đầy đủ.

---

## 7. Kiểm thử

83 test ở backend. Những thứ được cover:

| Khu vực | File |
|---|---|
| Xếp hạng retrieval, chu trình từ vựng, rỗng-là-rỗng | `Bm25IndexTest` |
| Topological sort, phá chu trình, cạnh trùng, resolve gap id | `GapAndGraphStepTest` |
| Citation ngoài shortlist, URL do model bịa, vượt quỹ giờ, sai thứ tự tiên quyết | `LearningPathStepTest` |
| Mã taxonomy bịa, `HAS_EVIDENCE` không trích dẫn, level không căn cứ, nguồn JD giả | `TaxonomyMappingStepTest` |
| Vòng lặp sửa lỗi, không retry khi model chết, ngân sách sửa | `AiStepRunnerTest` |
| Snapshot khác mục tiêu / khác revision / khác version, JSON hỏng | `LearningSnapshotStoreTest` |
| CV chứa chỉ dẫn độc hại, JD chứa chỉ dẫn, shortlist rỗng | `PromptSupportTest` |
| Chỉ hai model, thất bại to tiếng, status không lộ key | `GeminiClientTest` |
| Validate mục tiêu, `missingInputs`, tiến độ | `ProfileServiceTest` |

Mock chỉ nằm trong test (`AiStepRunnerTest.StubGemini`), tách hẳn khỏi runtime: production không
có nhánh nào thay model bằng dữ liệu viết sẵn.
