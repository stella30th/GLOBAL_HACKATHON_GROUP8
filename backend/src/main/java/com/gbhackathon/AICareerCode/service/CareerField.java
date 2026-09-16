package com.gbhackathon.AICareerCode.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The professions this product understands, with the vocabulary that identifies each one and the
 * category names each job board uses for it.
 *
 * <p>Two features depend on this. The importer uses it to decide which board categories to fetch for
 * a given candidate, so the catalogue is not all software. Matching uses it to classify the
 * candidate and each posting independently and compare the two, which is far more reliable than
 * counting shared words: a semiconductor CV and a marketing posting both contain "design", and that
 * coincidence alone used to be enough to score the marketing role around 50%.
 */
public enum CareerField {

    SEMICONDUCTOR("Semiconductor / Hardware",
            List.of("semiconductor", "ic design", "integrated circuit", "vlsi", "rtl", "verilog",
                    "systemverilog", "vhdl", "fpga", "asic", "soc", "uvm", "tapeout", "silicon",
                    "physical design", "timing analysis", "vivado", "quartus", "modelsim", "cadence",
                    "synopsys", "risc-v", "riscv", "testbench", "hdl", "chip", "wafer", "foundry",
                    "analog", "vi mach"),
            List.of("software-development", "engineering", "all-others"),
            List.of("engineering", "software-engineering"),
            List.of("Science and Engineering", "Software Engineering", "Installation, Maintenance, and Repairs")),

    EMBEDDED("Embedded Systems",
            List.of("embedded", "firmware", "microcontroller", "rtos", "freertos", "stm32", "arduino",
                    "bare metal", "device driver", "bootloader", "can bus", "i2c", "spi", "uart"),
            List.of("software-development", "engineering"),
            List.of("engineering", "software-engineering"),
            List.of("Science and Engineering", "Software Engineering")),

    ELECTRICAL("Electrical Engineering",
            List.of("electrical engineer", "power systems", "plc", "scada", "substation", "altium",
                    "pcb", "ltspice", "kicad", "motor control", "electrical design"),
            List.of("engineering", "all-others"),
            List.of("engineering"),
            List.of("Science and Engineering", "Energy Generation and Mining", "Installation, Maintenance, and Repairs")),

    MECHANICAL("Mechanical / Manufacturing",
            List.of("mechanical engineer", "solidworks", "autocad", "catia", "ansys", "cnc", "gd&t",
                    "thermodynamics", "finite element", "manufacturing", "tooling", "co khi"),
            List.of("engineering", "all-others"),
            List.of("engineering"),
            List.of("Science and Engineering", "Installation, Maintenance, and Repairs", "Energy Generation and Mining")),

    CIVIL("Civil / Construction",
            List.of("civil engineer", "structural", "construction", "revit", "geotechnical",
                    "quantity surveying", "site engineer", "xay dung"),
            List.of("engineering", "all-others"),
            List.of("engineering"),
            List.of("Science and Engineering", "Installation, Maintenance, and Repairs")),

    DATA_AI("Data & AI",
            List.of("data engineer", "data analyst", "data scientist", "machine learning", "deep learning",
                    "pytorch", "tensorflow", "etl", "airflow", "dbt", "power bi", "tableau",
                    "data warehouse", "analytics", "nlp", "computer vision", "llm"),
            List.of("data", "artificial-intelligence", "software-development"),
            List.of("data-science", "software-engineering"),
            List.of("Data and Analytics", "Science and Engineering")),

    DEVOPS("DevOps & Cloud",
            List.of("devops", "kubernetes", "terraform", "ansible", "site reliability", "sre",
                    "cloud engineer", "platform engineer", "infrastructure engineer", "ci/cd"),
            List.of("devops", "software-development"),
            List.of("devops-infrastructure", "software-engineering"),
            List.of("Software Engineering", "IT")),

    CYBERSECURITY("Cybersecurity",
            List.of("cybersecurity", "penetration testing", "soc analyst", "siem", "incident response",
                    "vulnerability", "cissp", "threat", "security engineer"),
            List.of("devops", "software-development", "all-others"),
            List.of("cybersecurity"),
            List.of("Software Engineering", "IT")),

    SOFTWARE("Software Engineering",
            List.of("software engineer", "backend", "frontend", "full stack", "fullstack", "web developer",
                    "spring boot", "django", "laravel", "react", "angular", "node.js", "microservices",
                    "rest api", "mobile developer", "android", "ios", "qa engineer"),
            List.of("software-development", "qa", "devops"),
            List.of("software-engineering", "devops-infrastructure"),
            List.of("Software Engineering", "Data and Analytics")),

    FINANCE("Finance & Accounting",
            List.of("accounting", "accountant", "ifrs", "gaap", "audit", "taxation", "financial analyst",
                    "cfa", "acca", "cpa", "bookkeeping", "treasury", "controller", "ke toan", "tai chinh"),
            List.of("finance", "finance-legal", "business", "all-others"),
            List.of("finance-accounting", "business-development"),
            List.of("Accounting and Finance", "Management", "Account Management")),

    HEALTHCARE("Healthcare",
            List.of("nurse", "nursing", "clinical", "patient care", "pharmacy", "pharmacist", "physician",
                    "medical doctor", "diagnosis", "therapist", "dental", "y te", "dieu duong"),
            List.of("medical", "all-others"),
            List.of("customer-support"),
            List.of("Healthcare", "Science and Engineering", "Social Services")),

    MARKETING("Marketing & Communications",
            List.of("marketing", "seo", "sem", "content marketing", "social media", "brand management",
                    "google ads", "copywriting", "public relations", "growth marketing", "truyen thong"),
            List.of("marketing", "writing", "communications"),
            List.of("marketing-sales", "business-development"),
            List.of("Marketing and PR", "Creative and Design", "Editorial and Writing")),

    SALES("Sales & Business Development",
            List.of("sales", "business development", "account executive", "crm", "salesforce",
                    "lead generation", "key account", "quota", "kinh doanh"),
            List.of("sales", "business-development", "account-management"),
            List.of("sales", "business-development"),
            List.of("Sales", "Account Management", "Management")),

    DESIGN("Design & Creative",
            List.of("graphic design", "ui/ux", "ux design", "ux designer", "ui designer", "figma",
                    "photoshop", "illustrator", "motion graphics", "product designer", "thiet ke"),
            List.of("design", "product"),
            List.of("design-ux", "product-operations"),
            List.of("Creative and Design", "Marketing and PR")),

    HR("Human Resources",
            List.of("human resources", "recruitment", "recruiter", "talent acquisition", "payroll",
                    "onboarding", "hr business partner", "people operations", "nhan su"),
            List.of("human-resources", "all-others"),
            List.of("hr"),
            List.of("Human Resources and Recruitment", "Management")),

    LOGISTICS("Logistics & Supply Chain",
            List.of("supply chain", "logistics", "warehouse", "procurement", "inventory management",
                    "freight", "customs", "demand planning", "xuat nhap khau"),
            List.of("supply-chain", "operations", "all-others"),
            List.of("product-operations"),
            List.of("Transportation and Logistics", "Business Operations", "Management")),

    EDUCATION("Education & Training",
            List.of("teacher", "lecturer", "curriculum", "tutoring", "pedagogy", "classroom",
                    "instructional design", "giao vien", "giang vien"),
            List.of("education", "all-others"),
            List.of("education-e-learning"),
            List.of("Education", "Social Services")),

    LEGAL("Legal & Compliance",
            List.of("legal counsel", "lawyer", "attorney", "contract law", "compliance officer",
                    "litigation", "paralegal", "phap ly", "luat su"),
            List.of("legal", "compliance", "finance-legal"),
            List.of("business-development"),
            List.of("Legal Services", "Management")),

    GENERAL("General",
            List.of(),
            List.of("software-development", "medical", "finance", "marketing", "education",
                    "supply-chain", "legal", "customer-service", "design"),
            List.of("software-engineering", "marketing-sales", "finance-accounting", "design-ux"),
            List.of("Science and Engineering", "Healthcare", "Accounting and Finance",
                    "Marketing and PR", "Sales", "Human Resources and Recruitment"));

    private final String label;
    private final List<String> keywords;
    private final List<String> remotiveCategories;
    private final List<String> jobicyIndustries;
    private final List<String> museCategories;

    CareerField(String label, List<String> keywords, List<String> remotiveCategories,
                List<String> jobicyIndustries, List<String> museCategories) {
        this.label = label;
        this.keywords = keywords;
        this.remotiveCategories = remotiveCategories;
        this.jobicyIndustries = jobicyIndustries;
        this.museCategories = museCategories;
    }

    public String label() {
        return label;
    }

    public List<String> keywords() {
        return keywords;
    }

    public List<String> remotiveCategories() {
        return remotiveCategories;
    }

    public List<String> jobicyIndustries() {
        return jobicyIndustries;
    }

    public List<String> museCategories() {
        return museCategories;
    }

    /** Fields close enough that experience in one is credible evidence for the other. */
    private static final Set<Set<CareerField>> ADJACENT = Set.of(
            Set.of(SEMICONDUCTOR, EMBEDDED),
            Set.of(SEMICONDUCTOR, ELECTRICAL),
            Set.of(EMBEDDED, ELECTRICAL),
            Set.of(EMBEDDED, SOFTWARE),
            Set.of(ELECTRICAL, MECHANICAL),
            Set.of(MECHANICAL, CIVIL),
            Set.of(SOFTWARE, DEVOPS),
            Set.of(SOFTWARE, DATA_AI),
            Set.of(SOFTWARE, CYBERSECURITY),
            Set.of(DEVOPS, CYBERSECURITY),
            Set.of(DATA_AI, FINANCE),
            Set.of(MARKETING, SALES),
            Set.of(MARKETING, DESIGN),
            Set.of(SALES, HR),
            Set.of(FINANCE, LEGAL),
            Set.of(LOGISTICS, SALES));

    public boolean isAdjacentTo(CareerField other) {
        return this != other && ADJACENT.contains(Set.of(this, other));
    }

    /**
     * Keyword patterns, compiled once. Matching is anchored on word boundaries: plain
     * {@code contains} reported "graphic designer" as a semiconductor role, because that string
     * contains "ic design".
     */
    private static final Map<CareerField, List<Pattern>> PATTERNS = compilePatterns();

    private static Map<CareerField, List<Pattern>> compilePatterns() {
        Map<CareerField, List<Pattern>> compiled = new EnumMap<>(CareerField.class);
        for (CareerField field : values()) {
            List<Pattern> patterns = new ArrayList<>();
            for (String keyword : field.keywords) {
                patterns.add(Pattern.compile(
                        "(?<![\\p{L}\\p{N}])" + Pattern.quote(keyword) + "(?![\\p{L}\\p{N}])"));
            }
            compiled.put(field, patterns);
        }
        return compiled;
    }

    /** Evidence weight of one keyword: longer and multi-word terms are far more decisive. */
    private static int weightOf(String keyword) {
        if (keyword.contains(" ") || keyword.contains("-") || keyword.contains("/")) return 5;
        if (keyword.length() >= 8) return 4;
        if (keyword.length() >= 5) return 3;
        return 2;
    }

    /**
     * Picks the field whose vocabulary best fits the supplied text, or {@link #GENERAL} when the
     * evidence is too thin to call.
     */
    public static CareerField classify(String text) {
        if (text == null || text.isBlank()) {
            return GENERAL;
        }
        String haystack = text.toLowerCase(Locale.ROOT);

        CareerField best = GENERAL;
        int bestScore = 0;
        for (CareerField field : values()) {
            if (field == GENERAL) continue;
            int score = 0;
            List<Pattern> patterns = PATTERNS.get(field);
            for (int i = 0; i < patterns.size(); i++) {
                if (patterns.get(i).matcher(haystack).find()) {
                    score += weightOf(field.keywords.get(i));
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = field;
            }
        }
        // One short, generic hit is noise; a single strong term or two weak ones is a classification.
        return bestScore >= 3 ? best : GENERAL;
    }
}
