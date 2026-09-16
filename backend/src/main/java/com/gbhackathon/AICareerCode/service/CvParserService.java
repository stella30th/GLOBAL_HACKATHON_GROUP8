package com.gbhackathon.AICareerCode.service;

import com.gbhackathon.AICareerCode.dto.ProfileDto;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an uploaded CV into a structured profile.
 *
 * <p>Gemini does the extraction whenever it is available, because a keyword dictionary cannot
 * cover every industry. The heuristic path is only a safety net for when the AI is unreachable,
 * and it is deliberately conservative: if it cannot find something in the document it leaves the
 * field empty rather than inventing a plausible-looking value. The previous implementation did the
 * opposite and would emit "Java, Spring Boot, MySQL" for a semiconductor CV.
 */
@Service
public class CvParserService {

    private static final Logger log = LoggerFactory.getLogger(CvParserService.class);

    private final GeminiClient gemini;

    public CvParserService(GeminiClient gemini) {
        this.gemini = gemini;
    }

    /**
     * Skill vocabulary spanning the industries this product serves, not just software.
     * Keys are the canonical label; values are the aliases to look for in the CV text.
     */
    private static final Map<String, List<String>> SKILL_VOCABULARY = buildSkillVocabulary();

    /**
     * Domain signals, checked in order. Each entry maps an industry label to the terms that
     * identify it. Matching is whole-word, which the old {@code lowerText.contains("ai")} check
     * was not: it fired on "constrained", "email" and "maintenance", forcing nearly every CV to
     * be classified as "Data & AI Engineer".
     */
    private static final List<DomainRule> DOMAIN_RULES = buildDomainRules();

    public String extractTextFromFile(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            try (PDDocument document = Loader.loadPDF(file.getBytes())) {
                PDFTextStripper stripper = new PDFTextStripper();
                return stripper.getText(document);
            } catch (Exception e) {
                log.warn("Error parsing PDF via PDFBox, falling back to string conversion: {}", e.getMessage());
                return new String(file.getBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }

    public ProfileDto parseCvTextToProfile(String text) {
        if (text == null) {
            text = "";
        }

        if (gemini.isConfigured()) {
            try {
                ProfileDto aiProfile = parseWithGemini(text);
                if (aiProfile != null && aiProfile.getSkills() != null && !aiProfile.getSkills().isEmpty()) {
                    log.info("Parsed CV with Gemini: title='{}', industry='{}', {} skills",
                            aiProfile.getCurrentTitle(), aiProfile.getIndustry(), aiProfile.getSkills().size());
                    return aiProfile;
                }
                log.warn("Gemini CV parse returned nothing usable, falling back to keyword extraction");
            } catch (Exception e) {
                log.warn("Gemini CV parse failed, falling back to keyword extraction: {}", e.getMessage());
            }
        }

        return parseHeuristically(text);
    }

    // ---------------------------------------------------------------------
    // AI extraction
    // ---------------------------------------------------------------------

    /** Shape Gemini fills in. Kept separate from ProfileDto so the model cannot set ids or timestamps. */
    public static class CvExtraction {
        public String fullName;
        public String email;
        public String phone;
        public String currentTitle;
        public String industry;
        public Double yearsOfExperience;
        public List<String> skills;
        public String education;
        public String languages;
        public List<String> targetRoles;
        public List<String> targetLocations;
        public String summary;
    }

    private ProfileDto parseWithGemini(String text) {
        // A long CV adds cost without adding signal; the first pages carry the structured content.
        String cvText = text.length() > 12000 ? text.substring(0, 12000) : text;

        String prompt = """
                You are an expert resume parser. Extract a structured profile from the CV below.

                CRITICAL RULES:
                1. The candidate may work in ANY industry: semiconductor / integrated circuit design,
                   mechanical or civil engineering, finance, healthcare, law, education, marketing,
                   logistics, hospitality, design, or software. Identify the candidate's ACTUAL field.
                   Do NOT assume software engineering.
                2. Extract ONLY facts that appear in the CV. Never invent skills, employers,
                   certifications or years of experience. If a field is absent, use null (or an empty
                   list). An empty field is correct; a guessed field is a serious error.
                3. "skills" must be the candidate's real technical and domain tools exactly as named in
                   the CV (for example: Verilog HDL, SystemVerilog, RTL Design, FPGA, Xilinx Vivado,
                   ModelSim, Cadence Virtuoso, SolidWorks, AutoCAD, IFRS, SAP, Figma, Adobe Illustrator).
                4. "yearsOfExperience" counts professional working experience. A current student with
                   only coursework, projects or internships is 0 to 1, never more.
                5. "currentTitle" is the candidate's actual role or, for a student, their study
                   specialisation (for example "Integrated Circuit Design Student").
                6. "targetRoles" comes from the objective/career goal section when present; otherwise
                   derive it strictly from their demonstrated field.
                7. Write "industry" as a short label, for example "Semiconductor / IC Design",
                   "Mechanical Engineering", "Finance & Accounting", "Healthcare", "Software Engineering".

                Return STRICT JSON with exactly this structure:
                {
                  "fullName": "string or null",
                  "email": "string or null",
                  "phone": "string or null",
                  "currentTitle": "string or null",
                  "industry": "string or null",
                  "yearsOfExperience": 0,
                  "skills": ["..."],
                  "education": "string or null",
                  "languages": "string or null",
                  "targetRoles": ["..."],
                  "targetLocations": ["..."],
                  "summary": "2-3 sentence factual summary grounded only in the CV"
                }

                CV TEXT:
                %s
                """.formatted(cvText);

        CvExtraction extracted = gemini.generateJson(prompt, CvExtraction.class);
        if (extracted == null) {
            return null;
        }

        ProfileDto profile = new ProfileDto();
        profile.setRawCvText(text);
        profile.setFullName(blankToNull(extracted.fullName));
        profile.setEmail(blankToNull(extracted.email));
        profile.setPhone(blankToNull(extracted.phone));
        profile.setCurrentTitle(blankToNull(extracted.currentTitle));
        profile.setIndustry(blankToNull(extracted.industry));
        profile.setYearsOfExperience(extracted.yearsOfExperience != null ? extracted.yearsOfExperience : 0.0);
        profile.setSkills(cleanList(extracted.skills));
        profile.setEducation(blankToNull(extracted.education));
        profile.setLanguages(blankToNull(extracted.languages));
        profile.setTargetRoles(cleanList(extracted.targetRoles));
        profile.setTargetLocations(cleanList(extracted.targetLocations));
        profile.setBio(blankToNull(extracted.summary));

        // Regex beats the model at contact details, so prefer a literal match from the document.
        String regexEmail = firstMatch(text, EMAIL_PATTERN);
        if (regexEmail != null) {
            profile.setEmail(regexEmail);
        }

        if (profile.getTargetRoles().isEmpty() && profile.getCurrentTitle() != null) {
            profile.setTargetRoles(List.of(profile.getCurrentTitle()));
        }
        // Relocation preference is not something a CV states reliably; leave the user in control.
        profile.setWillingToRelocate(true);
        profile.setTargetWorkType("ANY");
        return profile;
    }

    // ---------------------------------------------------------------------
    // Heuristic fallback
    // ---------------------------------------------------------------------

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,10}");
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(\\+?\\d{1,4}[-.\\s]?)?\\(?\\d{3,4}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{3,4}");

    ProfileDto parseHeuristically(String text) {
        ProfileDto profile = new ProfileDto();
        profile.setRawCvText(text);

        String lower = text.toLowerCase(Locale.ROOT);

        profile.setEmail(firstMatch(text, EMAIL_PATTERN));
        profile.setPhone(firstMatch(text, PHONE_PATTERN));
        profile.setFullName(guessName(text));

        // Skills: match the multi-industry vocabulary on word boundaries. If nothing matches we
        // leave the list empty - an empty profile the user can correct beats a fabricated one.
        Set<String> skills = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : SKILL_VOCABULARY.entrySet()) {
            for (String alias : entry.getValue()) {
                if (containsWord(lower, alias)) {
                    skills.add(entry.getKey());
                    break;
                }
            }
        }
        profile.setSkills(new ArrayList<>(skills));

        DomainRule domain = detectDomain(lower);
        profile.setIndustry(domain.label);

        double years = estimateYearsOfExperience(text, lower);
        profile.setYearsOfExperience(years);

        profile.setCurrentTitle(guessTitle(text, lower, domain, years));
        profile.setLanguages(detectLanguages(lower));
        profile.setEducation(detectEducation(text, lower));

        List<String> targetRoles = new ArrayList<>();
        for (String role : extractObjectiveRoles(lower, domain)) {
            if (!targetRoles.contains(role)) {
                targetRoles.add(role);
            }
        }
        if (profile.getCurrentTitle() != null && !targetRoles.contains(profile.getCurrentTitle())) {
            targetRoles.add(profile.getCurrentTitle());
        }
        profile.setTargetRoles(targetRoles);
        profile.setTargetLocations(new ArrayList<>());
        profile.setWillingToRelocate(true);
        profile.setTargetWorkType("ANY");

        if (!skills.isEmpty()) {
            List<String> top = new ArrayList<>(skills).subList(0, Math.min(4, skills.size()));
            profile.setBio(String.format("%s professional with hands-on experience in %s.",
                    domain.label, String.join(", ", top)));
        } else {
            profile.setBio(null);
        }

        log.info("Parsed CV heuristically: industry='{}', title='{}', {} skills",
                domain.label, profile.getCurrentTitle(), skills.size());
        return profile;
    }

    private DomainRule detectDomain(String lower) {
        DomainRule best = null;
        int bestScore = 0;
        for (DomainRule rule : DOMAIN_RULES) {
            int score = 0;
            for (String term : rule.terms) {
                if (containsWord(lower, term)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = rule;
            }
        }
        return best != null ? best : new DomainRule("General", List.of(), "Professional");
    }

    private double estimateYearsOfExperience(String text, String lower) {
        Matcher explicit = Pattern
                .compile("(\\d{1,2}(?:\\.\\d)?)\\+?\\s*(?:years?|yrs?|nam|năm)\\s*(?:of\\s*)?(?:experience|exp|kinh nghiem|kinh nghiệm)",
                        Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (explicit.find()) {
            try {
                return Math.min(Double.parseDouble(explicit.group(1)), 45.0);
            } catch (NumberFormatException ignored) {
                // fall through to the date-range estimate
            }
        }

        // A CV that reads as a student CV should not be credited with professional years, which is
        // what the old "count date ranges x 1.5" rule did: it turned coursework into 3 years.
        if (containsWord(lower, "student") || containsWord(lower, "sinh vien") || containsWord(lower, "sinh viên")
                || containsWord(lower, "internship") || containsWord(lower, "fresher")
                || containsWord(lower, "undergraduate")) {
            return 0.0;
        }

        // Otherwise total the employment date ranges actually present in the document.
        Matcher ranges = Pattern
                .compile("(19|20)(\\d{2})\\s*[-–—to]{1,3}\\s*((19|20)\\d{2}|present|current|now|hiện tại|hien tai)",
                        Pattern.CASE_INSENSITIVE)
                .matcher(text);
        int currentYear = java.time.Year.now().getValue();
        double total = 0;
        while (ranges.find()) {
            try {
                int from = Integer.parseInt(ranges.group(1) + ranges.group(2));
                String toRaw = ranges.group(3).toLowerCase(Locale.ROOT);
                int to = toRaw.matches("\\d{4}") ? Integer.parseInt(toRaw) : currentYear;
                if (to >= from && to - from <= 45) {
                    total += (to - from);
                }
            } catch (NumberFormatException ignored) {
                // skip malformed range
            }
        }
        return Math.min(total, 45.0);
    }

    private String guessName(String text) {
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.length() < 3 || trimmed.length() > 45) continue;
            if (trimmed.contains("@") || trimmed.matches(".*\\d{4,}.*")) continue;
            String normalized = trimmed.toLowerCase(Locale.ROOT);
            if (normalized.equals("resume") || normalized.equals("curriculum vitae") || normalized.equals("cv")) continue;
            // A name line is words only - no bullets, URLs or section punctuation.
            if (!trimmed.matches("[\\p{L}\\s.'-]+")) continue;
            return trimmed;
        }
        return null;
    }

    private String guessTitle(String text, String lower, DomainRule domain, double years) {
        // An explicit title line beside the name is the most reliable source.
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < Math.min(lines.length, 8); i++) {
            String line = lines[i].trim();
            if (line.length() < 4 || line.length() > 70) continue;
            String normalized = line.toLowerCase(Locale.ROOT);
            if (normalized.contains("engineer") || normalized.contains("developer") || normalized.contains("designer")
                    || normalized.contains("analyst") || normalized.contains("manager") || normalized.contains("student")
                    || normalized.contains("specialist") || normalized.contains("consultant")
                    || normalized.contains("technician") || normalized.contains("accountant")
                    || normalized.contains("nurse") || normalized.contains("teacher")) {
                return line.replaceAll("\\s*\\|\\s*", " | ").trim();
            }
        }
        if (years < 1 && (containsWord(lower, "student") || containsWord(lower, "undergraduate"))) {
            return domain.roleLabel + " Student";
        }
        return domain.roleLabel;
    }

    private String detectLanguages(String lower) {
        List<String> languages = new ArrayList<>();
        if (containsWord(lower, "english") || containsWord(lower, "ielts") || containsWord(lower, "toeic")
                || containsWord(lower, "toefl") || lower.contains("tiếng anh")) {
            languages.add("English");
        }
        if (containsWord(lower, "vietnamese") || lower.contains("tiếng việt") || lower.contains("tieng viet")) {
            languages.add("Vietnamese");
        }
        if (containsWord(lower, "japanese") || lower.contains("tiếng nhật") || containsWord(lower, "jlpt")) {
            languages.add("Japanese");
        }
        if (containsWord(lower, "korean") || containsWord(lower, "topik")) {
            languages.add("Korean");
        }
        if (containsWord(lower, "german") || containsWord(lower, "deutsch")) {
            languages.add("German");
        }
        if (containsWord(lower, "chinese") || containsWord(lower, "mandarin") || containsWord(lower, "hsk")) {
            languages.add("Chinese");
        }
        return languages.isEmpty() ? null : String.join(", ", languages);
    }

    private String detectEducation(String text, String lower) {
        // Prefer the actual line naming the institution over a generic degree label.
        for (String line : text.split("\\r?\\n")) {
            String normalized = line.toLowerCase(Locale.ROOT);
            if (normalized.contains("university") || normalized.contains("college")
                    || normalized.contains("institute") || normalized.contains("đại học")
                    || normalized.contains("dai hoc")) {
                String trimmed = line.trim();
                if (trimmed.length() >= 5 && trimmed.length() <= 120) {
                    return trimmed;
                }
            }
        }
        if (containsWord(lower, "phd") || containsWord(lower, "doctorate") || lower.contains("tiến sĩ")) {
            return "Doctorate";
        }
        if (containsWord(lower, "master") || containsWord(lower, "msc") || lower.contains("thạc sĩ")) {
            return "Master's degree";
        }
        if (containsWord(lower, "bachelor") || containsWord(lower, "bsc") || containsWord(lower, "beng")
                || lower.contains("cử nhân") || lower.contains("kỹ sư")) {
            return "Bachelor's degree";
        }
        return null;
    }

    private List<String> extractObjectiveRoles(String lower, DomainRule domain) {
        int idx = lower.indexOf("objective");
        if (idx < 0) idx = lower.indexOf("career goal");
        if (idx < 0) idx = lower.indexOf("mục tiêu");
        if (idx < 0) {
            return List.of(domain.roleLabel);
        }
        String section = lower.substring(idx, Math.min(lower.length(), idx + 400));
        Matcher m = Pattern.compile("(?:internship|position|role|opportunity)\\s+in\\s+([a-z0-9 /&-]{3,60})").matcher(section);
        if (m.find()) {
            String phrase = m.group(1)
                    // Trim trailing filler so "rtl design in which i could help" ends at the role.
                    .replaceAll("\\s+(in which|where|that|which|to|for|at)\\b.*$", "")
                    .trim();
            List<String> roles = new ArrayList<>();
            // An objective often names two acceptable roles; keep them as separate entries so
            // matching can compare each one against a job title.
            for (String part : phrase.split("\\s+(?:or|and)\\s+|\\s*/\\s*|\\s*,\\s*")) {
                String role = titleCase(part.trim());
                if (role.length() >= 3 && !roles.contains(role)) {
                    roles.add(role);
                }
                if (roles.size() == 3) break;
            }
            if (!roles.isEmpty()) {
                return roles;
            }
        }
        return List.of(domain.roleLabel);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Whole-word containment. Terms that already contain non-word characters (C++, .NET, C#) are
     * matched literally with boundaries only where they make sense.
     */
    private static boolean containsWord(String haystack, String needle) {
        String escaped = Pattern.quote(needle.toLowerCase(Locale.ROOT));
        String prefix = Character.isLetterOrDigit(needle.charAt(0)) ? "(?<![\\p{L}\\p{N}])" : "";
        char last = needle.charAt(needle.length() - 1);
        String suffix = Character.isLetterOrDigit(last) ? "(?![\\p{L}\\p{N}])" : "";
        return Pattern.compile(prefix + escaped + suffix).matcher(haystack).find();
    }

    private static String firstMatch(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group().trim() : null;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) ? null : value.trim();
    }

    private static List<String> cleanList(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized != null) {
                cleaned.add(normalized);
            }
        }
        return new ArrayList<>(cleaned);
    }

    /**
     * Acronyms these fields are full of. A plain capitalise-the-first-letter pass turned
     * "rtl design or digital ic design" into "Rtl Design Or Digital Ic Design".
     */
    private static final Set<String> ACRONYMS = Set.of(
            "rtl", "ic", "vlsi", "asic", "fpga", "soc", "hdl", "uvm", "dft", "sta", "pcb", "cad",
            "cpu", "gpu", "iot", "ai", "ml", "ui", "ux", "qa", "hr", "it", "sql", "api", "erp",
            "crm", "seo", "sem", "cnc", "plc", "bim", "fea", "sre");

    private static String titleCase(String input) {
        StringBuilder sb = new StringBuilder();
        for (String part : input.split("\\s+")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            String lower = part.toLowerCase(Locale.ROOT);
            if (ACRONYMS.contains(lower)) {
                sb.append(lower.toUpperCase(Locale.ROOT));
            } else {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }

    private record DomainRule(String label, List<String> terms, String roleLabel) {
    }

    private static List<DomainRule> buildDomainRules() {
        return List.of(
                new DomainRule("Semiconductor / IC Design", List.of(
                        "verilog", "systemverilog", "vhdl", "rtl", "asic", "fpga", "soc", "uvm", "vlsi",
                        "integrated circuit", "semiconductor", "tapeout", "synthesis", "timing closure",
                        "vivado", "quartus", "modelsim", "cadence", "synopsys", "riscv", "risc-v",
                        "testbench", "dft", "physical design", "analog", "vi mach", "vi mạch"),
                        "Digital IC Design Engineer"),
                new DomainRule("Embedded Systems", List.of(
                        "embedded", "firmware", "microcontroller", "rtos", "freertos", "stm32", "arduino",
                        "bare-metal", "device driver", "i2c", "spi", "uart", "can bus"),
                        "Embedded Systems Engineer"),
                new DomainRule("Mechanical Engineering", List.of(
                        "solidworks", "autocad", "catia", "ansys", "cnc", "gd&t", "mechanical design",
                        "thermodynamics", "fea", "cad", "manufacturing process", "co khi", "cơ khí"),
                        "Mechanical Engineer"),
                new DomainRule("Electrical Engineering", List.of(
                        "plc", "scada", "power systems", "circuit design", "electrical engineering",
                        "motor control", "substation", "eplan", "ltspice", "altium", "kicad", "pcb"),
                        "Electrical Engineer"),
                new DomainRule("Civil & Construction", List.of(
                        "civil engineering", "structural", "revit", "bim", "construction", "surveying",
                        "geotechnical", "quantity surveying", "xay dung", "xây dựng"),
                        "Civil Engineer"),
                new DomainRule("Data & Analytics", List.of(
                        "data engineer", "data analyst", "data scientist", "etl", "airflow", "dbt",
                        "power bi", "tableau", "data warehouse", "spark", "snowflake", "bigquery"),
                        "Data Engineer"),
                new DomainRule("Machine Learning & AI", List.of(
                        "machine learning", "deep learning", "pytorch", "tensorflow", "nlp",
                        "computer vision", "llm", "neural network", "hoc may", "học máy"),
                        "Machine Learning Engineer"),
                new DomainRule("Software Engineering", List.of(
                        "software engineer", "backend", "frontend", "full stack", "fullstack", "spring boot",
                        "django", "laravel", "react", "angular", "microservices", "rest api", "web developer"),
                        "Software Engineer"),
                new DomainRule("DevOps & Cloud", List.of(
                        "devops", "kubernetes", "terraform", "ansible", "ci/cd", "site reliability",
                        "cloud engineer", "aws", "azure", "gcp"),
                        "DevOps Engineer"),
                new DomainRule("Cybersecurity", List.of(
                        "penetration testing", "soc analyst", "siem", "incident response", "vulnerability",
                        "cissp", "ceh", "an toan thong tin", "an toàn thông tin"),
                        "Security Engineer"),
                new DomainRule("Finance & Accounting", List.of(
                        "accounting", "accountant", "ifrs", "gaap", "audit", "taxation", "financial analyst",
                        "cfa", "acca", "cpa", "bookkeeping", "ke toan", "kế toán", "tai chinh", "tài chính"),
                        "Financial Analyst"),
                new DomainRule("Healthcare & Medicine", List.of(
                        "nurse", "nursing", "clinical", "patient care", "pharmacy", "pharmacist",
                        "medical doctor", "physician", "diagnosis", "y te", "y tế", "dieu duong", "điều dưỡng"),
                        "Healthcare Professional"),
                new DomainRule("Marketing & Communications", List.of(
                        "marketing", "seo", "sem", "content marketing", "social media", "brand management",
                        "google ads", "copywriting", "public relations", "truyen thong", "truyền thông"),
                        "Marketing Specialist"),
                new DomainRule("Sales & Business Development", List.of(
                        "sales", "business development", "account executive", "crm", "salesforce",
                        "lead generation", "key account", "kinh doanh"),
                        "Sales Executive"),
                new DomainRule("Design & Creative", List.of(
                        "graphic design", "ui/ux", "ux design", "figma", "adobe photoshop", "illustrator",
                        "after effects", "motion graphics", "thiet ke", "thiết kế"),
                        "Designer"),
                new DomainRule("Human Resources", List.of(
                        "human resources", "recruitment", "talent acquisition", "payroll", "onboarding",
                        "hr business partner", "nhan su", "nhân sự"),
                        "HR Specialist"),
                new DomainRule("Logistics & Supply Chain", List.of(
                        "supply chain", "logistics", "warehouse", "procurement", "inventory management",
                        "freight", "customs", "xuat nhap khau", "xuất nhập khẩu"),
                        "Supply Chain Specialist"),
                new DomainRule("Education & Training", List.of(
                        "teacher", "lecturer", "curriculum", "tutoring", "pedagogy", "classroom",
                        "giao vien", "giáo viên", "giang vien", "giảng viên"),
                        "Educator"),
                new DomainRule("Legal", List.of(
                        "legal counsel", "lawyer", "attorney", "contract law", "compliance officer",
                        "litigation", "phap ly", "pháp lý", "luat su", "luật sư"),
                        "Legal Counsel"),
                new DomainRule("Hospitality & Tourism", List.of(
                        "hospitality", "hotel management", "front office", "food and beverage", "tourism",
                        "concierge", "du lich", "du lịch", "nha hang", "nhà hàng"),
                        "Hospitality Professional")
        );
    }

    private static Map<String, List<String>> buildSkillVocabulary() {
        Map<String, List<String>> vocabulary = new LinkedHashMap<>();

        // Semiconductor / IC design - the gap that made a VLSI CV look like a backend CV.
        put(vocabulary, "Verilog HDL", "verilog");
        put(vocabulary, "SystemVerilog", "systemverilog", "system verilog");
        put(vocabulary, "VHDL", "vhdl");
        put(vocabulary, "RTL Design", "rtl design", "rtl");
        put(vocabulary, "FPGA", "fpga");
        put(vocabulary, "ASIC Design", "asic");
        put(vocabulary, "SoC Design", "soc design", "system on chip");
        put(vocabulary, "UVM", "uvm", "universal verification methodology");
        put(vocabulary, "Design Verification", "design verification", "testbench", "functional verification");
        put(vocabulary, "Computer Architecture", "computer architecture");
        put(vocabulary, "RISC-V", "risc-v", "riscv", "rv32");
        put(vocabulary, "Xilinx Vivado", "vivado");
        put(vocabulary, "Intel Quartus", "quartus");
        put(vocabulary, "ModelSim", "modelsim", "questasim");
        put(vocabulary, "Cadence Virtuoso", "cadence", "virtuoso");
        put(vocabulary, "Synopsys Design Compiler", "synopsys", "design compiler");
        put(vocabulary, "LTspice", "ltspice");
        put(vocabulary, "KiCad", "kicad");
        put(vocabulary, "Altium Designer", "altium");
        put(vocabulary, "PCB Design", "pcb");
        put(vocabulary, "Static Timing Analysis", "static timing analysis", "timing closure");
        put(vocabulary, "DFT", "design for test", "scan chain");
        put(vocabulary, "Analog IC Design", "analog integrated circuit", "analog ic");
        put(vocabulary, "Digital IC Design", "digital ic design");

        // Embedded
        put(vocabulary, "Embedded C", "embedded c");
        put(vocabulary, "Firmware Development", "firmware");
        put(vocabulary, "RTOS", "rtos", "freertos");
        put(vocabulary, "STM32", "stm32");
        put(vocabulary, "Arduino", "arduino");
        put(vocabulary, "I2C / SPI / UART", "i2c", "spi protocol", "uart");

        // Programming languages
        put(vocabulary, "C/C++", "c/c++", "c++", "cpp");
        put(vocabulary, "C", " c ", "c language");
        put(vocabulary, "Python", "python");
        put(vocabulary, "Java", "java");
        put(vocabulary, "JavaScript", "javascript");
        put(vocabulary, "TypeScript", "typescript");
        put(vocabulary, "Go", "golang");
        put(vocabulary, "Rust", "rust");
        put(vocabulary, "C#", "c#", ".net");
        put(vocabulary, "PHP", "php");
        put(vocabulary, "Swift", "swift");
        put(vocabulary, "Kotlin", "kotlin");
        put(vocabulary, "MATLAB", "matlab");
        put(vocabulary, "R", "rstudio");
        put(vocabulary, "Tcl", "tcl");
        put(vocabulary, "Assembly", "assembly language");

        // Software / web
        put(vocabulary, "Spring Boot", "spring boot", "springboot");
        put(vocabulary, "React", "react");
        put(vocabulary, "Next.js", "next.js", "nextjs");
        put(vocabulary, "Vue.js", "vue.js", "vuejs");
        put(vocabulary, "Angular", "angular");
        put(vocabulary, "Node.js", "node.js", "nodejs");
        put(vocabulary, "Django", "django");
        put(vocabulary, "Flask", "flask");
        put(vocabulary, "Laravel", "laravel");
        put(vocabulary, "REST API", "rest api", "restful");
        put(vocabulary, "GraphQL", "graphql");
        put(vocabulary, "Microservices", "microservices");

        // Data / infra
        put(vocabulary, "SQL", "sql");
        put(vocabulary, "MySQL", "mysql");
        put(vocabulary, "PostgreSQL", "postgresql", "postgres");
        put(vocabulary, "MongoDB", "mongodb");
        put(vocabulary, "Redis", "redis");
        put(vocabulary, "Kafka", "kafka");
        put(vocabulary, "Docker", "docker");
        put(vocabulary, "Kubernetes", "kubernetes", "k8s");
        put(vocabulary, "AWS", "aws", "amazon web services");
        put(vocabulary, "Azure", "azure");
        put(vocabulary, "Google Cloud", "google cloud", "gcp");
        put(vocabulary, "Terraform", "terraform");
        put(vocabulary, "CI/CD", "ci/cd", "continuous integration");
        put(vocabulary, "Linux", "linux");
        put(vocabulary, "Git", "git", "github", "gitlab");
        put(vocabulary, "Power BI", "power bi");
        put(vocabulary, "Tableau", "tableau");
        put(vocabulary, "Apache Spark", "spark");
        put(vocabulary, "Pandas", "pandas");
        put(vocabulary, "PyTorch", "pytorch");
        put(vocabulary, "TensorFlow", "tensorflow");

        // Mechanical / civil / industrial
        put(vocabulary, "SolidWorks", "solidworks");
        put(vocabulary, "AutoCAD", "autocad");
        put(vocabulary, "CATIA", "catia");
        put(vocabulary, "ANSYS", "ansys");
        put(vocabulary, "Revit", "revit");
        put(vocabulary, "CNC Machining", "cnc");
        put(vocabulary, "GD&T", "gd&t");
        put(vocabulary, "Finite Element Analysis", "finite element", "fea");
        put(vocabulary, "PLC Programming", "plc");
        put(vocabulary, "SCADA", "scada");
        put(vocabulary, "Lean Manufacturing", "lean manufacturing", "six sigma");

        // Business / finance / other industries
        put(vocabulary, "Financial Analysis", "financial analysis", "financial modeling");
        put(vocabulary, "IFRS", "ifrs");
        put(vocabulary, "GAAP", "gaap");
        put(vocabulary, "Auditing", "auditing", "internal audit");
        put(vocabulary, "SAP", "sap");
        put(vocabulary, "Excel", "excel");
        put(vocabulary, "Salesforce", "salesforce");
        put(vocabulary, "SEO", "seo");
        put(vocabulary, "Google Ads", "google ads", "google adwords");
        put(vocabulary, "Content Marketing", "content marketing");
        put(vocabulary, "Figma", "figma");
        put(vocabulary, "Adobe Photoshop", "photoshop");
        put(vocabulary, "Adobe Illustrator", "illustrator");
        put(vocabulary, "Project Management", "project management");
        put(vocabulary, "Agile / Scrum", "scrum", "agile");
        put(vocabulary, "Recruitment", "recruitment", "talent acquisition");
        put(vocabulary, "Supply Chain Management", "supply chain");
        put(vocabulary, "Patient Care", "patient care");
        put(vocabulary, "Teaching", "curriculum development", "lesson planning");

        return vocabulary;
    }

    private static void put(Map<String, List<String>> map, String canonical, String... aliases) {
        map.put(canonical, Arrays.asList(aliases.length == 0 ? new String[]{canonical.toLowerCase(Locale.ROOT)} : aliases));
    }
}
