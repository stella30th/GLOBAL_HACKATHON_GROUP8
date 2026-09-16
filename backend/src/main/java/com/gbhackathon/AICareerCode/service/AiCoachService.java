package com.gbhackathon.AICareerCode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbhackathon.AICareerCode.dto.CareerRoadmapDto;
import com.gbhackathon.AICareerCode.dto.ChatMessageDto;
import com.gbhackathon.AICareerCode.dto.ResumeAuditDto;
import com.gbhackathon.AICareerCode.model.JobOpportunity;
import com.gbhackathon.AICareerCode.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Career-coaching features: resume audit, progression roadmap, job deep-dive and chat.
 *
 * <p>Every prompt here is industry-neutral on purpose. The earlier versions hardcoded a software
 * frame ("top engineering roles", "Spring Boot Bean Lifecycle", "100 LeetCode problems"), so a
 * semiconductor or finance candidate received advice for a job they were not applying for.
 *
 * <p>Audit and roadmap results are cached per profile revision. Without it, switching tabs in the
 * UI fired a fresh Gemini call every time and burned the daily free-tier quota within minutes,
 * after which every response silently reverted to the offline text below.
 */
@Service
public class AiCoachService {

    private static final Logger log = LoggerFactory.getLogger(AiCoachService.class);

    private final GeminiClient gemini;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, ResumeAuditDto> auditCache = new ConcurrentHashMap<>();
    private final Map<String, CareerRoadmapDto> roadmapCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> deepDiveCache = new ConcurrentHashMap<>();

    public AiCoachService(GeminiClient gemini) {
        this.gemini = gemini;
    }

    /** Cache key that changes whenever the profile is edited or a new CV is uploaded. */
    private String profileKey(UserProfile profile) {
        return profile.getId() + "@" + (profile.getUpdatedAt() != null ? profile.getUpdatedAt().toString() : "new");
    }

    /** Compact, factual description of the candidate reused across all prompts. */
    private String describeCandidate(UserProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("- Name: ").append(orUnknown(profile.getFullName())).append('\n');
        sb.append("- Industry / field: ").append(orUnknown(profile.getIndustry())).append('\n');
        sb.append("- Current title: ").append(orUnknown(profile.getCurrentTitle())).append('\n');
        sb.append("- Years of professional experience: ")
                .append(profile.getYearsOfExperience() != null ? profile.getYearsOfExperience() : 0).append('\n');
        sb.append("- Skills and tools: ").append(joinOrUnknown(profile.getSkillList())).append('\n');
        sb.append("- Education: ").append(orUnknown(profile.getEducation())).append('\n');
        sb.append("- Languages: ").append(orUnknown(profile.getLanguages())).append('\n');
        sb.append("- Target roles: ").append(joinOrUnknown(profile.getTargetRoleList())).append('\n');
        sb.append("- Target locations: ").append(joinOrUnknown(profile.getTargetLocationList())).append('\n');
        sb.append("- Willing to relocate: ").append(Boolean.TRUE.equals(profile.getWillingToRelocate()) ? "Yes" : "No").append('\n');
        sb.append("- Summary: ").append(orUnknown(profile.getBio())).append('\n');

        // Grounding the model in the source document is what stops it drifting into generic
        // software advice for a candidate whose CV is about something else entirely.
        String cv = profile.getRawCvText();
        if (cv != null && !cv.isBlank()) {
            sb.append("\nRAW CV TEXT (authoritative - prefer these facts over anything above):\n");
            sb.append(cv.length() > 6000 ? cv.substring(0, 6000) : cv).append('\n');
        }
        return sb.toString();
    }

    /** Instruction block shared by audit, roadmap and deep-dive prompts. */
    private static final String INDUSTRY_GUARDRAIL = """
            IMPORTANT CONTEXT RULES:
            - The candidate may work in ANY field: semiconductor / IC design, mechanical or civil
              engineering, finance, healthcare, law, education, marketing, logistics, design, or
              software. Tailor every sentence to THEIR actual field.
            - Never recommend software-engineering staples (Docker, Kubernetes, LeetCode, microservices,
              AWS certification) unless the candidate's own field genuinely requires them. For a
              digital design candidate the equivalents are UVM verification, static timing analysis,
              a tapeout-grade project, or a Cadence/Synopsys toolchain certification.
            - Reference the candidate's real skills, tools, projects and education by name.
            - A student or recent graduate must receive entry-level advice (internships, portfolio
              projects, fundamentals), not senior-level advice.
            """;

    // ---------------------------------------------------------------------
    // Resume audit
    // ---------------------------------------------------------------------

    public ResumeAuditDto auditProfile(UserProfile profile) {
        String key = profileKey(profile);
        ResumeAuditDto cached = auditCache.get(key);
        if (cached != null) {
            return cached;
        }

        ResumeAuditDto audit = null;
        if (gemini.isConfigured()) {
            audit = callGeminiForAudit(profile);
        }
        if (audit == null || audit.getHealthScore() <= 0) {
            log.info("Using offline audit for profile {}", profile.getId());
            audit = generateHeuristicAudit(profile);
            audit.setGeneratedBy("offline");
            Object lastError = gemini.status().get("lastError");
            audit.setOfflineReason(lastError != null ? lastError.toString()
                    : (gemini.isConfigured() ? "AI did not return a usable result" : "GEMINI_API_KEY is not configured"));
        } else {
            log.info("Generated AI resume audit for profile {} ({})", profile.getId(), profile.getIndustry());
            audit.setGeneratedBy("gemini");
            Object model = gemini.status().get("lastWorkingModel");
            audit.setModel(model != null ? model.toString() : null);
        }

        // Keep only the newest revision so the cache cannot grow with every edit.
        auditCache.clear();
        auditCache.put(key, audit);
        return audit;
    }

    private ResumeAuditDto callGeminiForAudit(UserProfile profile) {
        String prompt = """
                You are a senior recruiter and career strategist. Evaluate the candidate below for
                roles in their own field, both domestically and internationally.

                %s
                CANDIDATE:
                %s

                Produce a resume audit and a 12-month progression roadmap. Ground every strength,
                weakness, keyword and milestone in the candidate's actual field and actual CV.
                The atsKeywordsMissing list must contain keywords that matter FOR THEIR FIELD.

                Return STRICT JSON with exactly this structure:
                {
                  "healthScore": 0,
                  "verdict": "one-line verdict on market readiness",
                  "summary": "2-3 sentences on readiness, strongest asset and highest-value improvement",
                  "strengths": ["specific strength grounded in the CV", "...", "..."],
                  "weaknesses": ["specific actionable gap", "...", "..."],
                  "atsKeywordsPresent": ["keyword already in the CV"],
                  "atsKeywordsMissing": ["field-relevant keyword the CV lacks"],
                  "bulletImprovements": [
                    {
                      "originalBullet": "a weak bullet taken or closely adapted from this CV",
                      "improvedBullet": "rewritten with the STAR formula and concrete metrics relevant to their field",
                      "explanation": "why the rewrite is stronger to a hiring manager in this field"
                    }
                  ],
                  "careerRoadmap": {
                    "targetGoal": "realistic 12-month goal for THIS candidate",
                    "months3": [{"title": "...", "description": "...", "type": "SKILL", "estimatedHours": "40 hrs"}],
                    "months6": [{"title": "...", "description": "...", "type": "PROJECT", "estimatedHours": "50 hrs"}],
                    "months12": [{"title": "...", "description": "...", "type": "APPLICATION", "estimatedHours": "40 hrs"}]
                  }
                }

                Provide 3 strengths, 3 weaknesses, 3 bulletImprovements and 2 milestones per roadmap stage.
                healthScore is an integer from 0 to 100 reflecting genuine market readiness.
                """.formatted(INDUSTRY_GUARDRAIL, describeCandidate(profile));

        return gemini.generateJson(prompt, ResumeAuditDto.class);
    }

    // ---------------------------------------------------------------------
    // Career roadmap
    // ---------------------------------------------------------------------

    /**
     * Roadmap for the candidate. The previous implementation ignored its {@code profile} argument
     * entirely and returned the same four hardcoded milestones (Docker/Kubernetes, AWS certification,
     * LeetCode) to every user regardless of what their CV said.
     */
    public CareerRoadmapDto generateCareerRoadmap(UserProfile profile) {
        String key = profileKey(profile);
        CareerRoadmapDto cached = roadmapCache.get(key);
        if (cached != null) {
            return cached;
        }

        // The audit already produces a roadmap, so reuse it rather than spending a second AI call.
        ResumeAuditDto audit = auditCache.get(key);
        if (audit != null && audit.getCareerRoadmap() != null) {
            roadmapCache.clear();
            roadmapCache.put(key, audit.getCareerRoadmap());
            return audit.getCareerRoadmap();
        }

        CareerRoadmapDto roadmap = null;
        if (gemini.isConfigured()) {
            roadmap = callGeminiForRoadmap(profile);
        }
        if (roadmap == null || roadmap.getMonths3() == null || roadmap.getMonths3().isEmpty()) {
            roadmap = generateHeuristicRoadmap(profile);
        }

        roadmapCache.clear();
        roadmapCache.put(key, roadmap);
        return roadmap;
    }

    private CareerRoadmapDto callGeminiForRoadmap(UserProfile profile) {
        String prompt = """
                You are a career development strategist. Build a concrete 12-month progression plan
                for the candidate below, specific to their own field and current level.

                %s
                CANDIDATE:
                %s

                Return STRICT JSON with exactly this structure:
                {
                  "targetGoal": "realistic, specific 12-month goal for this candidate",
                  "months3": [{"title": "...", "description": "...", "category": "SKILL", "estimatedHours": "40 hrs"}],
                  "months6": [{"title": "...", "description": "...", "category": "PROJECT", "estimatedHours": "50 hrs"}],
                  "months12": [{"title": "...", "description": "...", "category": "APPLICATION", "estimatedHours": "40 hrs"}]
                }

                Give 2 to 3 milestones per stage. category is one of SKILL, CERTIFICATION, PROJECT,
                LANGUAGE, NETWORKING, APPLICATION, INTERVIEW. Each description must name concrete
                tools, standards or resources used in the candidate's field.
                """.formatted(INDUSTRY_GUARDRAIL, describeCandidate(profile));

        return gemini.generateJson(prompt, CareerRoadmapDto.class);
    }

    // ---------------------------------------------------------------------
    // Job deep-dive
    // ---------------------------------------------------------------------

    public Map<String, Object> generateJobDeepDive(UserProfile profile, JobOpportunity job) {
        String key = profileKey(profile) + "#" + job.getId();
        Map<String, Object> cached = deepDiveCache.get(key);
        if (cached != null) {
            return cached;
        }

        Map<String, Object> analysis = null;
        if (gemini.isConfigured()) {
            analysis = callGeminiForJobDeepDive(profile, job);
        }
        boolean fromAi = analysis != null && !analysis.isEmpty();
        if (!fromAi) {
            analysis = generateHeuristicJobDeepDive(profile, job);
        } else {
            log.info("Generated AI deep-dive for job '{}' and profile {}", job.getTitle(), profile.getId());
        }

        // The UI claimed "Powered by Gemini" unconditionally. Say which engine actually answered.
        Map<String, Object> result = new LinkedHashMap<>(analysis);
        result.put("generatedBy", fromAi ? "gemini" : "offline");
        result.put("model", fromAi ? gemini.status().get("lastWorkingModel") : null);
        if (!fromAi) {
            result.put("offlineReason", gemini.status().get("lastError"));
        }

        if (deepDiveCache.size() > 50) {
            deepDiveCache.clear();
        }
        deepDiveCache.put(key, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callGeminiForJobDeepDive(UserProfile profile, JobOpportunity job) {
        String description = job.getDescription() != null && job.getDescription().length() > 1500
                ? job.getDescription().substring(0, 1500)
                : job.getDescription();

        String prompt = """
                You are an experienced recruiter and relocation advisor. Analyse the fit between this
                specific candidate and this specific job posting.

                %s
                CANDIDATE:
                %s
                JOB POSTING:
                - Title: %s
                - Company: %s
                - Location: %s (%s)
                - Work type: %s
                - Salary: %s
                - Visa sponsorship: %s
                - Required skills: %s
                - Description: %s

                Be honest. If the candidate's field does not match this role, say so plainly and
                explain what would need to change; do not inflate the fit.

                Return STRICT JSON with exactly this structure:
                {
                  "aiSummary": "3-4 sentences naming the specific overlaps and the specific gaps between this candidate and this posting",
                  "visaSuitability": "realistic work-authorisation analysis for this candidate and this location",
                  "actionItems": ["concrete step", "concrete step", "concrete step"],
                  "interviewQuestions": ["question this employer would realistically ask for THIS role", "...", "..."],
                  "interviewTips": "one specific tip for this role and company"
                }
                """.formatted(
                INDUSTRY_GUARDRAIL,
                describeCandidate(profile),
                orUnknown(job.getTitle()), orUnknown(job.getCompany()),
                orUnknown(job.getLocation()), orUnknown(job.getCountry()),
                orUnknown(job.getWorkType()), orUnknown(job.getSalaryRange()),
                Boolean.TRUE.equals(job.getVisaSponsorship()) ? "Yes" : "Not stated",
                orUnknown(job.getRequiredSkills()), orUnknown(description));

        String json = gemini.generateJson(prompt);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Could not map deep-dive JSON: {}", e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Chat
    // ---------------------------------------------------------------------

    public String chat(UserProfile profile, List<ChatMessageDto> history, String userPrompt) {
        if (gemini.isConfigured()) {
            String response = callGeminiChat(profile, history, userPrompt);
            if (response != null && !response.isBlank()) {
                return response;
            }
        }
        return generateOfflineChatResponse(profile, userPrompt);
    }

    private String callGeminiChat(UserProfile profile, List<ChatMessageDto> history, String userPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an expert career coach. You advise this specific candidate on career growth,
                resumes, interviews, salary negotiation and international relocation.

                %s
                - Answer the user's actual question directly. Do not open with a greeting or a menu of
                  services; the user has already started the conversation.
                - Answer in English by default. If the user writes in another language, reply in that language.
                - Use concise Markdown. Be concrete and specific to this candidate rather than generic.

                CANDIDATE:
                %s
                """.formatted(INDUSTRY_GUARDRAIL, describeCandidate(profile)));

        if (history != null && !history.isEmpty()) {
            sb.append("\nCONVERSATION SO FAR:\n");
            for (ChatMessageDto msg : history) {
                String role = "user".equalsIgnoreCase(msg.getRole()) ? "User" : "Coach";
                sb.append(role).append(": ").append(msg.getContent()).append('\n');
            }
        }
        sb.append("\nUser: ").append(userPrompt).append("\nCoach:");

        return gemini.generateText(sb.toString());
    }

    /** True when the last chat answer came from Gemini rather than the offline responder. */
    public Map<String, Object> aiStatus() {
        return gemini.status();
    }

    public Map<String, Object> probeAi() {
        return gemini.probe();
    }

    // ---------------------------------------------------------------------
    // Offline fallbacks - used only when Gemini is unreachable
    // ---------------------------------------------------------------------

    public ResumeAuditDto generateHeuristicAudit(UserProfile profile) {
        ResumeAuditDto audit = new ResumeAuditDto();
        List<String> skills = profile.getSkillList();
        double exp = profile.getYearsOfExperience() != null ? profile.getYearsOfExperience() : 0.0;
        String field = orElse(profile.getIndustry(), "your field");
        String title = orElse(profile.getCurrentTitle(), "professional");

        int score = 55;
        if (!skills.isEmpty()) score += 8;
        if (skills.size() >= 6) score += 7;
        if (exp >= 2.0) score += 8;
        if (exp >= 5.0) score += 5;
        if (notBlank(profile.getEducation())) score += 6;
        if (notBlank(profile.getLanguages())) score += 5;
        if (notBlank(profile.getBio())) score += 4;
        score = Math.min(92, score);
        audit.setHealthScore(score);

        audit.setVerdict(score >= 80
                ? "Strong profile - ready to apply in " + field
                : score >= 65
                ? "Solid foundation - add measurable outcomes before applying widely"
                : "Early-stage profile - strengthen evidence of hands-on work");

        audit.setSummary(String.format(
                "This profile presents a %s in %s with %.1f year(s) of professional experience%s. "
                        + "The highest-value improvement is turning responsibilities into measurable outcomes "
                        + "so a reader can judge scale and impact. Note: AI analysis was unavailable, so this "
                        + "is a rule-based summary of the profile fields.",
                title, field, exp,
                skills.isEmpty() ? " and no extracted skills yet" : " across " + skills.size() + " listed skills"));

        List<String> strengths = new ArrayList<>();
        if (!skills.isEmpty()) {
            strengths.add("Concrete toolset listed: " + String.join(", ", skills.subList(0, Math.min(5, skills.size()))));
        }
        if (notBlank(profile.getEducation())) {
            strengths.add("Relevant academic background: " + profile.getEducation());
        }
        if (notBlank(profile.getLanguages())) {
            strengths.add("Language coverage for international applications: " + profile.getLanguages());
        }
        if (Boolean.TRUE.equals(profile.getWillingToRelocate())) {
            strengths.add("Open to relocation, which widens the reachable job market");
        }
        if (strengths.isEmpty()) {
            strengths.add("Profile created - add skills and experience to surface strengths");
        }
        audit.setStrengths(strengths);

        List<String> weaknesses = new ArrayList<>();
        weaknesses.add("Experience entries lack quantified outcomes (scale, percentage, time saved, budget).");
        if (skills.size() < 6) {
            weaknesses.add("Few tools or methods listed - name the specific software, standards and equipment you use in " + field + ".");
        }
        if (!notBlank(profile.getLanguages())) {
            weaknesses.add("No language proficiency stated, which blocks screening for international roles.");
        }
        if (exp < 1.0) {
            weaknesses.add("Little professional experience recorded - lead with projects, internships and coursework outcomes.");
        }
        audit.setWeaknesses(weaknesses);

        audit.setAtsKeywordsPresent(new ArrayList<>(skills));
        audit.setAtsKeywordsMissing(suggestKeywordsForField(field, skills));

        // Generic scaffolds, clearly marked as such rather than pretending to quote this CV.
        List<ResumeAuditDto.BulletImprovement> bullets = new ArrayList<>();
        String anchor = skills.isEmpty() ? "your main tool" : skills.get(0);
        bullets.add(new ResumeAuditDto.BulletImprovement(
                "Responsible for daily tasks and supporting the team.",
                "Owned [specific deliverable] using " + anchor + ", completing it [X]% faster than the previous process across [N] cases.",
                "Replaces a duty statement with ownership plus a measurable result, which is what a reviewer scans for."));
        bullets.add(new ResumeAuditDto.BulletImprovement(
                "Participated in a project at university.",
                "Designed and validated [component] in a [N]-person team, achieving [metric] verified with " + anchor + ".",
                "Names your contribution, the scale of the team and the verified outcome instead of mere participation."));
        bullets.add(new ResumeAuditDto.BulletImprovement(
                "Good communication and teamwork skills.",
                "Presented [topic] to [audience] and coordinated with [team] to resolve [problem], cutting [metric] by [X]%.",
                "Turns an unverifiable trait into a demonstrated event with a result attached."));
        audit.setBulletImprovements(bullets);

        audit.setCareerRoadmap(generateHeuristicRoadmap(profile));
        return audit;
    }

    private List<String> suggestKeywordsForField(String field, List<String> existing) {
        String normalized = field.toLowerCase();
        List<String> pool;
        if (normalized.contains("semiconductor") || normalized.contains("ic design")) {
            pool = List.of("SystemVerilog", "UVM", "Static Timing Analysis", "Design Verification",
                    "Synthesis", "Low Power Design", "AXI/AHB Protocols", "Tapeout");
        } else if (normalized.contains("embedded")) {
            pool = List.of("RTOS", "Device Drivers", "CAN Bus", "Power Optimisation", "Unit Testing", "MISRA C");
        } else if (normalized.contains("mechanical")) {
            pool = List.of("GD&T", "Finite Element Analysis", "Tolerance Analysis", "DFM", "ISO 9001");
        } else if (normalized.contains("finance") || normalized.contains("accounting")) {
            pool = List.of("IFRS", "Financial Modelling", "Variance Analysis", "Internal Controls", "SAP");
        } else if (normalized.contains("healthcare")) {
            pool = List.of("Patient Assessment", "Clinical Documentation", "Infection Control", "EMR Systems");
        } else if (normalized.contains("marketing")) {
            pool = List.of("Conversion Rate Optimisation", "Marketing Automation", "A/B Testing", "Attribution Modelling");
        } else if (normalized.contains("data")) {
            pool = List.of("Data Modelling", "ETL Orchestration", "Data Quality", "Dimensional Modelling");
        } else if (normalized.contains("software") || normalized.contains("devops")) {
            pool = List.of("System Design", "CI/CD", "Observability", "Automated Testing", "Code Review");
        } else {
            pool = List.of("Stakeholder Management", "Process Improvement", "Quality Assurance", "Reporting & Analytics");
        }
        List<String> missing = new ArrayList<>();
        for (String keyword : pool) {
            boolean present = existing.stream().anyMatch(s -> s.equalsIgnoreCase(keyword));
            if (!present && missing.size() < 5) {
                missing.add(keyword);
            }
        }
        return missing;
    }

    public CareerRoadmapDto generateHeuristicRoadmap(UserProfile profile) {
        CareerRoadmapDto roadmap = new CareerRoadmapDto();
        String field = orElse(profile.getIndustry(), "your field");
        String title = orElse(profile.getCurrentTitle(), "professional");
        double exp = profile.getYearsOfExperience() != null ? profile.getYearsOfExperience() : 0.0;
        List<String> skills = profile.getSkillList();
        List<String> gaps = suggestKeywordsForField(field, skills);
        String gapText = gaps.isEmpty() ? "the advanced methods used in " + field : String.join(", ", gaps);
        boolean earlyCareer = exp < 1.5;

        roadmap.setTargetGoal(earlyCareer
                ? "Secure a first full-time role or internship as a " + title + " in " + field
                : "Progress to a senior " + title + " role in " + field + ", locally or abroad");

        List<CareerRoadmapDto.RoadmapMilestone> m3 = new ArrayList<>();
        m3.add(new CareerRoadmapDto.RoadmapMilestone(
                "Close the highest-value skill gaps",
                "Work through " + gapText + ", the capabilities most often requested in " + field
                        + " postings that this profile does not yet evidence.",
                "SKILL", "40 hrs"));
        m3.add(new CareerRoadmapDto.RoadmapMilestone(
                "Rewrite the CV around measurable outcomes",
                "Convert each responsibility into a Situation-Task-Action-Result bullet with a number attached, "
                        + "and mirror the vocabulary used in " + field + " job descriptions.",
                "SKILL", "15 hrs"));
        if (notBlank(profile.getLanguages()) && profile.getLanguages().toLowerCase().contains("english")) {
            m3.add(new CareerRoadmapDto.RoadmapMilestone(
                    "Practise professional English for interviews",
                    "Rehearse explaining your projects and technical trade-offs in English, which is the "
                            + "screening bar for most international employers.",
                    "LANGUAGE", "30 hrs"));
        }
        roadmap.setMonths3(m3);

        List<CareerRoadmapDto.RoadmapMilestone> m6 = new ArrayList<>();
        m6.add(new CareerRoadmapDto.RoadmapMilestone(
                "Build one portfolio-grade project in " + field,
                "Complete an end-to-end piece of work that exercises "
                        + (skills.isEmpty() ? "your core tools" : String.join(", ", skills.subList(0, Math.min(3, skills.size()))))
                        + ", and document the requirements, decisions and verified results.",
                "PROJECT", "60 hrs"));
        m6.add(new CareerRoadmapDto.RoadmapMilestone(
                "Earn a credential recognised in " + field,
                "Pick the certification or accreditation that employers in " + field + " actually screen for, "
                        + "and schedule the exam so the deadline is real.",
                "CERTIFICATION", "50 hrs"));
        roadmap.setMonths6(m6);

        List<CareerRoadmapDto.RoadmapMilestone> m12 = new ArrayList<>();
        m12.add(new CareerRoadmapDto.RoadmapMilestone(
                "Run a focused application campaign",
                "Apply to 20-30 well-matched " + field + " roles in your target locations, tailoring the CV to each "
                        + "posting and tracking responses to see which framing lands.",
                "APPLICATION", "40 hrs"));
        m12.add(new CareerRoadmapDto.RoadmapMilestone(
                "Prepare for domain interviews",
                "Drill the fundamentals interviewers probe in " + field + ", and prepare STAR stories for the "
                        + "projects already on your CV.",
                "INTERVIEW", "45 hrs"));
        m12.add(new CareerRoadmapDto.RoadmapMilestone(
                "Build a professional network in the field",
                "Connect with practitioners and recruiters in " + field + ", attend two industry events, "
                        + "and ask for referrals rather than relying on cold applications.",
                "NETWORKING", "20 hrs"));
        roadmap.setMonths12(m12);

        return roadmap;
    }

    private Map<String, Object> generateHeuristicJobDeepDive(UserProfile profile, JobOpportunity job) {
        Map<String, Object> res = new HashMap<>();
        List<String> userSkills = profile.getSkillList();
        List<String> required = job.getRequiredSkillList();
        List<String> overlap = required.stream()
                .filter(r -> userSkills.stream().anyMatch(s -> s.equalsIgnoreCase(r)))
                .toList();

        res.put("aiSummary", String.format(
                "Rule-based comparison (AI analysis unavailable): this profile matches %d of %d listed requirements for %s at %s%s. "
                        + "Review the description yourself for requirements that are not captured as keywords.",
                overlap.size(), required.size(), orUnknown(job.getTitle()), orUnknown(job.getCompany()),
                overlap.isEmpty() ? "" : " (" + String.join(", ", overlap) + ")"));

        if (Boolean.TRUE.equals(job.getIsOverseas())) {
            if ("REMOTE".equalsIgnoreCase(job.getWorkType())) {
                res.put("visaSuitability", "Remote role - no immigration visa required, but check the employer's accepted countries of residence.");
            } else if (Boolean.TRUE.equals(job.getVisaSponsorship())) {
                res.put("visaSuitability", "The posting indicates visa sponsorship for " + orUnknown(job.getCountry()) + ". Confirm eligibility criteria with the employer.");
            } else {
                res.put("visaSuitability", "Onsite role in " + orUnknown(job.getCountry()) + " with no stated sponsorship - verify whether you already hold work authorisation.");
            }
        } else {
            res.put("visaSuitability", "Domestic role - standard local employment terms.");
        }

        List<String> actions = new ArrayList<>();
        List<String> missing = required.stream()
                .filter(r -> userSkills.stream().noneMatch(s -> s.equalsIgnoreCase(r)))
                .limit(3).toList();
        if (!missing.isEmpty()) {
            actions.add("Address the missing requirements in your CV or with a project: " + String.join(", ", missing));
        }
        actions.add("Mirror the exact wording of this posting's requirements in your CV where it is truthful.");
        actions.add("Prepare one worked example for each requirement you do meet.");
        res.put("actionItems", actions);

        res.put("interviewQuestions", List.of(
                "Walk me through a project where you used " + (userSkills.isEmpty() ? "your core tools" : userSkills.get(0)) + ".",
                "Which part of the " + orUnknown(job.getTitle()) + " scope is least familiar to you, and how would you close that gap?",
                "Describe a time your work had to be corrected. What did you change afterwards?"));
        res.put("interviewTips", "Lead with outcomes and the reasoning behind your decisions rather than a list of tools.");
        return res;
    }

    private String generateOfflineChatResponse(UserProfile profile, String userPrompt) {
        String reason = String.valueOf(gemini.status().get("lastError"));
        String field = orElse(profile.getIndustry(), "your field");

        // Be explicit that this is not an answer. The old fallback returned a cheerful greeting that
        // looked like a working assistant, which is exactly how the outage went unnoticed.
        return String.format("""
                ⚠️ **The AI coach is temporarily unavailable, so I cannot answer your question right now.**

                Reason reported by the AI service: `%s`

                Your question was: *"%s"*

                **What to do**
                - If the reason mentions quota, the daily free-tier limit for the configured Gemini model has been reached. It resets after 24 hours, or you can switch `GEMINI_MODEL` to a model with a larger free allowance.
                - If it mentions the API key, set a valid `GEMINI_API_KEY` in the backend environment.
                - Check `GET /api/coach/ai-status` for the live diagnosis.

                Everything else in the app still works: your profile (%s, %s), job matching and the resume audit are all available while the AI is offline.
                """,
                reason, userPrompt, orElse(profile.getCurrentTitle(), "profile"), field);
    }

    // ---------------------------------------------------------------------

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String orElse(String value, String fallback) {
        return notBlank(value) ? value : fallback;
    }

    private static String orUnknown(String value) {
        return notBlank(value) ? value : "not stated";
    }

    private static String joinOrUnknown(List<String> values) {
        return (values == null || values.isEmpty()) ? "not stated" : String.join(", ", values);
    }
}
