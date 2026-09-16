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
 * Generates the coaching content: profile audit, learning roadmap, job deep-dive and chat.
 *
 * <p>Every prompt here is industry-neutral on purpose. The earlier versions hardcoded a software
 * frame ("top engineering roles", "Spring Boot Bean Lifecycle", "100 LeetCode problems"), so a
 * semiconductor or finance candidate received advice for a job they were not applying for. The
 * prompts are also stage-aware: a second-year student and a final-year student need different
 * plans, and someone who never said what year they are in gets neither assumed.
 *
 * <p>This class generates; it does not decide what is current. {@link LearningSnapshotService}
 * owns the stored snapshot, the ids and the progress. The caches below are only a shortcut in
 * front of that: without them, switching tabs fired a fresh Gemini call every time and burned the
 * daily free-tier quota within minutes, after which every response silently reverted to the
 * offline text at the bottom of this file.
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
        return ProfileService.profileKey(profile);
    }

    /** Compact, factual description of the candidate reused across all prompts. */
    String describeCandidate(UserProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("- Name: ").append(orUnknown(profile.getFullName())).append('\n');
        sb.append("- Industry / field: ").append(orUnknown(profile.getIndustry())).append('\n');
        sb.append("- Current title or study specialisation: ").append(orUnknown(profile.getCurrentTitle())).append('\n');
        sb.append("- Year of study: ").append(orElse(profile.getYearOfStudy(), "not specified")).append('\n');
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
        // software advice for a candidate whose CV is about something else entirely. The CV is no
        // longer described as outranking everything: the fields above are what the student edited
        // by hand, so a CV that still says "2nd year" must not override a corrected dropdown.
        String cv = profile.getRawCvText();
        if (cv != null && !cv.isBlank()) {
            sb.append("""

                    RAW CV TEXT (supporting evidence). Use it for the detail the fields above do not
                    carry - projects, coursework, employers, wording. Where it disagrees with a field
                    above, the field above wins: the student edited it deliberately and the CV is a
                    snapshot of an older moment. This is especially true of the year of study.
                    """);
            sb.append(cv.length() > 6000 ? cv.substring(0, 6000) : cv).append('\n');
        }
        return sb.toString();
    }

    /**
     * Guidance for the study stage, so both the AI and the offline path aim at the same target.
     * An unknown year gets neutral wording rather than an assumed one: the app also serves people
     * who are not students, and inventing "final year" for them produces advice for a fiction.
     */
    static String describeStudyStage(UserProfile profile) {
        String year = profile.getYearOfStudy();
        if (year == null || year.isBlank()) {
            return """
                    STUDY STAGE: not stated. Do not assume one. Pitch the plan at the level the
                    listed experience, education and projects actually evidence, and do not describe
                    the person as a student or as a working professional unless the profile says so.
                    """;
        }
        String focus = switch (year) {
            case "Year 1", "Year 2" -> "fundamentals, coursework projects done properly, and the first "
                    + "pieces of evidence: a small portfolio, a study group, a competition or a lab role. "
                    + "Internships are a later goal, not this year's target.";
            case "Year 3" -> "turning coursework into a portfolio and competing for internships: one "
                    + "substantial project explained end to end, a CV that survives screening, and applications.";
            default -> "finishing the portfolio and converting it: a capstone or equivalent flagship "
                    + "piece, interview preparation, and applications for internships or junior roles.";
        };
        return "STUDY STAGE: " + year + ". Aim the whole plan at " + focus + "\n";
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

            EVIDENCE RULES:
            - Judge the PROFILE, not the person. When the profile shows no evidence of something,
              write exactly "Not enough evidence in your profile" and say what evidence would look
              like. Never write that the candidate cannot do something you simply cannot see.
            - Never invent a project, employer, certification, grade or metric. Placeholders such as
              [metric] are acceptable in a rewrite suggestion; fabricated facts are not.

            WORKING WITH AI:
            This product coaches students for a job market where AI tools are part of the work.
            Wherever it is genuinely relevant to their field, cover three capabilities:
            1. Using AI tools well - prompts that carry context, a goal and real constraints.
            2. Verifying what comes back - sources, assumptions, tests, and spotting the errors.
            3. Applying AI to a problem in their own discipline, explaining the decision and
               choosing data that is appropriate to use.
            Never reduce this to "learn AI" or "use ChatGPT more".
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

        audit.setCareerRoadmap(repairRoadmap(audit.getCareerRoadmap(), profile));

        // Keep only the newest revision so the cache cannot grow with every edit.
        auditCache.clear();
        auditCache.put(key, audit);
        return audit;
    }

    /**
     * Brings a generated roadmap up to the shape the product guarantees, stage by stage.
     *
     * <p>This runs before ids are issued and before anything is stored, because a snapshot is kept
     * until the student next edits their profile — an incomplete roadmap is not a transient glitch
     * here, it is what they will look at for the rest of the session. Checking only for a null
     * roadmap was not enough: a model that returns {@code months3} and then stops, or that emits a
     * milestone with an empty title, produced a page with a blank column and a checkbox attached
     * to nothing.
     *
     * <p>Repair is per stage rather than all-or-nothing. A model that got two stages right keeps
     * them; only the stage that came back unusable is replaced with the offline equivalent, which
     * is grounded in the profile and invents no facts.
     */
    CareerRoadmapDto repairRoadmap(CareerRoadmapDto roadmap, UserProfile profile) {
        if (roadmap == null) {
            roadmap = new CareerRoadmapDto();
        }

        CareerRoadmapDto fallback = null;
        List<List<CareerRoadmapDto.RoadmapMilestone>> stages = List.of(
                usable(roadmap.getMonths3()), usable(roadmap.getMonths6()), usable(roadmap.getMonths12()));

        if (stages.stream().anyMatch(List::isEmpty) || isBlank(roadmap.getTargetGoal())) {
            fallback = generateHeuristicRoadmap(profile);
            log.info("Repairing an incomplete roadmap for profile {} from the offline plan", profile.getId());
        }

        roadmap.setMonths3(stages.get(0).isEmpty() ? usable(fallback.getMonths3()) : stages.get(0));
        roadmap.setMonths6(stages.get(1).isEmpty() ? usable(fallback.getMonths6()) : stages.get(1));
        roadmap.setMonths12(stages.get(2).isEmpty() ? usable(fallback.getMonths12()) : stages.get(2));
        if (isBlank(roadmap.getTargetGoal())) {
            roadmap.setTargetGoal(fallback.getTargetGoal());
        }

        // A roadmap with no AI_FLUENCY milestone is the one failure mode that would make the
        // product's central claim false, and the model does drop it. Repairing it here covers the
        // Gemini path and the offline path alike; the added milestone states only what the student
        // will do, never a fact about them.
        ensureAiFluencyMilestone(roadmap, profile);
        return roadmap;
    }

    /**
     * The milestones in a stage that can actually be rendered and ticked: a title and a description
     * are both required, and a milestone with neither is dropped rather than shown as a blank row.
     */
    private List<CareerRoadmapDto.RoadmapMilestone> usable(List<CareerRoadmapDto.RoadmapMilestone> stage) {
        if (stage == null) {
            return new ArrayList<>();
        }
        List<CareerRoadmapDto.RoadmapMilestone> kept = new ArrayList<>();
        for (CareerRoadmapDto.RoadmapMilestone milestone : stage) {
            if (milestone == null || isBlank(milestone.getTitle()) || isBlank(milestone.getDescription())) {
                continue;
            }
            if (isBlank(milestone.getEstimatedHours())) {
                milestone.setEstimatedHours("effort not estimated");
            }
            kept.add(milestone);
        }
        return kept;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** True when the roadmap already coaches working with AI rather than only about it. */
    private boolean hasAiFluency(CareerRoadmapDto roadmap) {
        return roadmap != null && roadmap.allMilestones().stream()
                .anyMatch(m -> m.getCategory() != null
                        && m.getCategory().replace(' ', '_').equalsIgnoreCase("AI_FLUENCY"));
    }

    void ensureAiFluencyMilestone(CareerRoadmapDto roadmap, UserProfile profile) {
        if (roadmap == null || hasAiFluency(roadmap)) {
            return;
        }
        String field = orElse(profile.getIndustry(), "your field");
        String artefact = orElse(firstOrNull(profile.getSkillList()), "a piece of your own coursework");

        CareerRoadmapDto.RoadmapMilestone milestone = new CareerRoadmapDto.RoadmapMilestone(
                "Use an AI assistant on your own work, then verify what it produces",
                "Take one piece of work you already have (" + artefact + ") and ask an AI assistant to "
                        + "review or extend it with a prompt that states the context, the goal and the "
                        + "constraints. Then check the answer yourself: list what it got right, what it got "
                        + "wrong or invented, and how you verified each point using the standards and sources "
                        + "used in " + field + ". Write the verification up in half a page - that write-up is "
                        + "the deliverable, and it is what an employer can actually judge.",
                "AI_FLUENCY", "12 hrs");

        List<CareerRoadmapDto.RoadmapMilestone> months3 = roadmap.getMonths3() != null
                ? new ArrayList<>(roadmap.getMonths3()) : new ArrayList<>();
        months3.add(milestone);
        roadmap.setMonths3(months3);
    }

    private static String firstOrNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private ResumeAuditDto callGeminiForAudit(UserProfile profile) {
        String prompt = """
                You are a skills coach for students. Read the profile below and identify what it
                already evidences, what it does not, and what to build next in this student's own
                field.

                %s
                %s
                CANDIDATE:
                %s

                Produce a profile audit and a 12-month learning roadmap. Ground every strength,
                weakness, keyword and milestone in the candidate's actual field and actual profile.
                The atsKeywordsMissing list must contain keywords that matter FOR THEIR FIELD.
                At least one of the strengths or weaknesses must address working with AI tools:
                using them, verifying their output, or applying them to problems in this field.
                healthScore rates the CV/profile as a document that must survive screening. It is
                not a rating of the person's ability and not an AI-readiness score.

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
                    "targetGoal": "realistic 12-month goal for THIS candidate at THIS study stage",
                    "months3": [{"title": "...", "description": "...", "type": "SKILL", "estimatedHours": "40 hrs"}],
                    "months6": [{"title": "...", "description": "...", "type": "PROJECT", "estimatedHours": "50 hrs"}],
                    "months12": [{"title": "...", "description": "...", "type": "APPLICATION", "estimatedHours": "40 hrs"}]
                  }
                }

                Provide 3 strengths, 3 weaknesses, 3 bulletImprovements and 2 to 3 milestones per
                roadmap stage. healthScore is an integer from 0 to 100 for the profile as a document.

                ROADMAP RULES:
                - "type" is one of SKILL, CERTIFICATION, PROJECT, LANGUAGE, NETWORKING, APPLICATION,
                  INTERVIEW, AI_FLUENCY.
                - At least one milestone must be AI_FLUENCY, and it must name a concrete action and a
                  checkable output in this student's own field. "Learn about AI" is not acceptable.
                  A usable example for a software student: use an AI assistant to propose unit tests
                  for one of your own modules, find the cases it missed, and write down how you
                  verified each one. Build the equivalent for whatever field this student is in.
                - Every description must name real tools, standards or resources used in that field.
                """.formatted(INDUSTRY_GUARDRAIL, describeStudyStage(profile), describeCandidate(profile));

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
        roadmap = repairRoadmap(roadmap, profile);

        roadmapCache.clear();
        roadmapCache.put(key, roadmap);
        return roadmap;
    }

    private CareerRoadmapDto callGeminiForRoadmap(UserProfile profile) {
        String prompt = """
                You are a skills coach for students. Build a concrete 12-month learning plan for the
                student below, specific to their own field and their current stage.

                %s
                %s
                CANDIDATE:
                %s

                Return STRICT JSON with exactly this structure:
                {
                  "targetGoal": "realistic, specific 12-month goal for this student at this stage",
                  "months3": [{"title": "...", "description": "...", "category": "SKILL", "estimatedHours": "40 hrs"}],
                  "months6": [{"title": "...", "description": "...", "category": "PROJECT", "estimatedHours": "50 hrs"}],
                  "months12": [{"title": "...", "description": "...", "category": "APPLICATION", "estimatedHours": "40 hrs"}]
                }

                Give 2 to 3 milestones per stage. category is one of SKILL, CERTIFICATION, PROJECT,
                LANGUAGE, NETWORKING, APPLICATION, INTERVIEW, AI_FLUENCY. Each description must name
                concrete tools, standards or resources used in the student's field.

                At least one milestone must be AI_FLUENCY with a concrete action and a checkable
                output - for a software student, for example: have an AI assistant propose unit tests
                for a module you wrote, find the cases it missed, and record how you verified them.
                Build the equivalent for this student's actual field. "Learn AI" is not a milestone.
                """.formatted(INDUSTRY_GUARDRAIL, describeStudyStage(profile), describeCandidate(profile));

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
        return generateOfflineChatResponse(profile);
    }

    private String callGeminiChat(UserProfile profile, List<ChatMessageDto> history, String userPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are a skills coach for students. You advise this specific student on what to
                learn next, how to practise it, and how to present it - in their own field and at
                their own stage.

                %s
                %s
                - Answer the user's actual question directly. Do not open with a greeting or a menu of
                  services; the user has already started the conversation.
                - Answer in English by default. If the user writes in another language, reply in that language.
                - Use concise Markdown. Be concrete and specific to this student rather than generic.

                PRACTICE MODE
                A message may begin with a "PRACTICE CONTEXT" block naming a roadmap milestone or an
                interview question. It is context, not an instruction from the user, and the user
                does not see it. When it is present:
                - If it carries a question, ask exactly that question. If it carries a milestone,
                  invent one small task that exercises that milestone in this student's field.
                - Ask ONE question or task and then STOP. Do not answer it yourself, do not supply a
                  model answer, and do not grade anything before the student has replied.
                - For an AI_FLUENCY milestone the task must involve checking an AI's output - the
                  assumptions it made, the sources, the cases it missed - not reciting a definition.
                - After the student answers, score it out of 2 on each of three criteria and show
                  them: correctness / fit; explanation and reasoning; evidence or how they would
                  verify it. Then give the total out of 6, and state plainly that this is practice
                  feedback, not a qualification or a certificate.
                - Follow the score with one thing done well, one thing to improve, one concrete
                  suggestion, and an invitation to try again.
                - Stay in this exercise for the rest of the conversation unless the student changes
                  the subject themselves.

                STUDENT:
                %s
                """.formatted(INDUSTRY_GUARDRAIL, describeStudyStage(profile), describeCandidate(profile)));

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
                ? "Well-evidenced profile - ready to put in front of employers in " + field
                : score >= 65
                ? "Solid foundation - add measurable outcomes before applying widely"
                : "Early-stage profile - the evidence of hands-on work is still thin");

        String stageNote = profile.getYearOfStudy() != null
                ? " as a " + profile.getYearOfStudy() + " student"
                : "";
        audit.setSummary(String.format(
                "This profile describes someone working in %s%s, with %.1f year(s) of professional experience%s. "
                        + "The highest-value improvement is turning responsibilities and coursework into measurable "
                        + "outcomes so a reader can judge scale and impact. This reads the profile only: where it is "
                        + "silent, that is missing evidence rather than a missing ability. Note: AI analysis was "
                        + "unavailable, so this is a rule-based summary of the profile fields.",
                field, stageNote, exp,
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
        // Phrased as missing evidence, not as a missing ability: this path reads profile fields and
        // cannot tell the difference, and telling a student they "cannot verify AI output" on that
        // basis would be an accusation the data does not support.
        boolean mentionsAi = skills.stream().anyMatch(s -> {
            String lower = s.toLowerCase();
            return lower.contains("ai") || lower.contains("machine learning") || lower.contains("llm")
                    || lower.contains("prompt") || lower.contains("copilot") || lower.contains("gpt");
        }) || (notBlank(profile.getBio()) && profile.getBio().toLowerCase().contains("ai"));
        if (!mentionsAi) {
            weaknesses.add("Not enough evidence in your profile about working with AI tools. Employers in "
                    + field + " increasingly ask how you use them and how you check what they produce - add a "
                    + "project line naming the tool, what you asked it for, and how you verified the answer.");
        }
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

    /**
     * Offline roadmap. It branches on the year of study rather than only on years of experience,
     * because the two say different things: a second-year student with a summer job and a
     * final-year student with the same number of hours need opposite plans. The stage names stay
     * fixed at 3 / 6 / 12 months; what changes is the content and the difficulty.
     */
    public CareerRoadmapDto generateHeuristicRoadmap(UserProfile profile) {
        CareerRoadmapDto roadmap = new CareerRoadmapDto();
        String field = orElse(profile.getIndustry(), "your field");
        List<String> skills = profile.getSkillList();
        List<String> gaps = suggestKeywordsForField(field, skills);
        String gapText = gaps.isEmpty() ? "the methods used day to day in " + field : String.join(", ", gaps);
        String toolText = skills.isEmpty()
                ? "your core tools"
                : String.join(", ", skills.subList(0, Math.min(3, skills.size())));

        Stage stage = stageOf(profile);

        roadmap.setTargetGoal(switch (stage) {
            case FOUNDATION -> "Build solid fundamentals in " + field
                    + " and finish two coursework projects worth showing to someone";
            case PORTFOLIO -> "Turn your " + field + " coursework into a portfolio strong enough to win an internship";
            case CONVERSION -> "Finish a flagship " + field + " project and convert it into an internship or a junior role";
            case NEUTRAL -> "Close the highest-value gaps in " + field + " and build evidence for the roles you want";
        });

        List<CareerRoadmapDto.RoadmapMilestone> m3 = new ArrayList<>();
        m3.add(switch (stage) {
            case FOUNDATION -> new CareerRoadmapDto.RoadmapMilestone(
                    "Get the fundamentals of " + field + " genuinely solid",
                    "Pick the two subjects your later work depends on most and study them until you can explain "
                            + "them without notes. Work through " + gapText + " with exercises rather than reading, "
                            + "and keep what you build - it becomes your first portfolio entry.",
                    "SKILL", "40 hrs");
            case PORTFOLIO -> new CareerRoadmapDto.RoadmapMilestone(
                    "Close the gaps internship postings keep asking for",
                    "Work through " + gapText + ", the capabilities " + field + " internship postings request most "
                            + "often and your profile does not yet evidence. Finish each with something you can show.",
                    "SKILL", "40 hrs");
            case CONVERSION -> new CareerRoadmapDto.RoadmapMilestone(
                    "Close the last gaps before you apply",
                    "Work through " + gapText + " so that no requirement on a junior " + field + " posting is one "
                            + "you have never touched. Depth on two of them beats a passing look at all of them.",
                    "SKILL", "35 hrs");
            case NEUTRAL -> new CareerRoadmapDto.RoadmapMilestone(
                    "Close the highest-value skill gaps",
                    "Work through " + gapText + ", the capabilities most often requested in " + field
                            + " that this profile does not yet evidence.",
                    "SKILL", "40 hrs");
        });
        m3.add(new CareerRoadmapDto.RoadmapMilestone(
                "Use an AI assistant on your own work, then verify what it produces",
                "Take something you have already built with " + toolText + " and ask an AI assistant to review or "
                        + "extend it, with a prompt that states the context, the goal and the constraints. Then check "
                        + "the answer: list what it got right, what it got wrong or invented, and how you verified "
                        + "each point against the standards and sources used in " + field + ". The half-page "
                        + "verification write-up is the deliverable - it is the part an employer can judge.",
                "AI_FLUENCY", "12 hrs"));
        m3.add(new CareerRoadmapDto.RoadmapMilestone(
                stage == Stage.FOUNDATION
                        ? "Write your first CV around what you have actually done"
                        : "Rewrite your CV around measurable outcomes",
                "Turn each project and responsibility into a Situation-Task-Action-Result bullet with a number "
                        + "attached, and mirror the vocabulary used in " + field + " job descriptions. Coursework "
                        + "counts, as long as you describe your own contribution and the result.",
                "SKILL", "15 hrs"));
        if (notBlank(profile.getLanguages()) && profile.getLanguages().toLowerCase().contains("english")) {
            m3.add(new CareerRoadmapDto.RoadmapMilestone(
                    "Practise explaining your work in professional English",
                    "Rehearse walking through your projects and the trade-offs you chose, in English. This is the "
                            + "screening bar for most international employers and for many local ones.",
                    "LANGUAGE", "30 hrs"));
        }
        roadmap.setMonths3(m3);

        List<CareerRoadmapDto.RoadmapMilestone> m6 = new ArrayList<>();
        m6.add(switch (stage) {
            case FOUNDATION -> new CareerRoadmapDto.RoadmapMilestone(
                    "Take one coursework project further than the assignment required",
                    "Choose the module project you enjoyed most and extend it past the marking criteria using "
                            + toolText + ". Document the requirements, the decisions you made and how you checked "
                            + "the result. One project done properly outweighs four half-finished ones.",
                    "PROJECT", "45 hrs");
            case PORTFOLIO -> new CareerRoadmapDto.RoadmapMilestone(
                    "Build one portfolio-grade project in " + field,
                    "Complete an end-to-end piece of work exercising " + toolText + ", and document the "
                            + "requirements, the decisions and the verified results. This is the project you will "
                            + "walk an internship interviewer through.",
                    "PROJECT", "60 hrs");
            case CONVERSION, NEUTRAL -> new CareerRoadmapDto.RoadmapMilestone(
                    "Finish your flagship project and write it up properly",
                    "Bring your capstone or strongest " + field + " project to a finished state using " + toolText
                            + ", and write up the requirements, the decisions, the trade-offs and the evidence that "
                            + "it works. Employers hire from the write-up as much as from the artefact.",
                    "PROJECT", "60 hrs");
        });
        m6.add(new CareerRoadmapDto.RoadmapMilestone(
                stage == Stage.FOUNDATION
                        ? "Add a credential that opens the next step"
                        : "Earn a credential recognised in " + field,
                "Pick the certification, course or accreditation that employers in " + field + " actually screen "
                        + "for at your level, and schedule it so the deadline is real.",
                "CERTIFICATION", "50 hrs"));
        roadmap.setMonths6(m6);

        List<CareerRoadmapDto.RoadmapMilestone> m12 = new ArrayList<>();
        m12.add(switch (stage) {
            case FOUNDATION -> new CareerRoadmapDto.RoadmapMilestone(
                    "Find one real setting to apply your work",
                    "A lab assistant role, a student club project, a competition or an open-source issue in "
                            + field + ". The goal this year is one piece of work that existed outside a classroom, "
                            + "which is what makes an internship application credible next year.",
                    "PROJECT", "40 hrs");
            case PORTFOLIO -> new CareerRoadmapDto.RoadmapMilestone(
                    "Run a focused internship application campaign",
                    "Apply to 20-30 well-matched " + field + " internships, tailoring the CV to each posting and "
                            + "tracking responses so you can see which framing lands.",
                    "APPLICATION", "40 hrs");
            case CONVERSION, NEUTRAL -> new CareerRoadmapDto.RoadmapMilestone(
                    "Run a focused application campaign",
                    "Apply to 20-30 well-matched " + field + " internship or junior roles, tailoring the CV to each "
                            + "posting and tracking responses so you can see which framing lands.",
                    "APPLICATION", "40 hrs");
        });
        m12.add(new CareerRoadmapDto.RoadmapMilestone(
                "Prepare for interviews in " + field,
                "Drill the fundamentals interviewers probe in " + field + ", and prepare a STAR story for every "
                        + "project on your CV - including how you checked your own work and what you would redo.",
                "INTERVIEW", "45 hrs"));
        m12.add(new CareerRoadmapDto.RoadmapMilestone(
                "Build a network in the field while you still have a student badge",
                "Connect with practitioners, alumni and recruiters in " + field + ", attend two industry or campus "
                        + "events, and ask for referrals rather than relying on cold applications.",
                "NETWORKING", "20 hrs"));
        roadmap.setMonths12(m12);

        return roadmap;
    }

    /** Study stage used by the offline path. NEUTRAL means "the profile does not say". */
    private enum Stage { FOUNDATION, PORTFOLIO, CONVERSION, NEUTRAL }

    private Stage stageOf(UserProfile profile) {
        String year = profile.getYearOfStudy();
        if (year != null) {
            return switch (year) {
                case "Year 1", "Year 2" -> Stage.FOUNDATION;
                case "Year 3" -> Stage.PORTFOLIO;
                case "Year 4", "Year 5+" -> Stage.CONVERSION;
                default -> Stage.NEUTRAL;
            };
        }
        // No year stated. Do not invent one; fall back to what the experience actually evidences.
        return Stage.NEUTRAL;
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

    private String generateOfflineChatResponse(UserProfile profile) {
        Object lastError = gemini.status().get("lastError");
        String reason = lastError != null
                ? lastError.toString()
                : (gemini.isConfigured() ? "the AI did not return a usable answer" : "GEMINI_API_KEY is not configured");
        String field = orElse(profile.getIndustry(), "your field");

        // Be explicit that this is not an answer. The old fallback returned a cheerful greeting that
        // looked like a working assistant, which is exactly how the outage went unnoticed.
        //
        // The user's message is deliberately not echoed. In a practice session it is prefixed with a
        // context block the app wrote and the user never saw, so quoting it back printed the app's
        // own machinery into the conversation as if the student had typed it.
        return String.format("""
                ⚠️ **The AI coach is temporarily unavailable, so I cannot answer right now.** Nothing
                was scored, and nothing about your roadmap has changed.

                Reason reported by the AI service: `%s`

                **What to do**
                - If the reason mentions quota, the daily free-tier limit for the configured Gemini model has been reached. It resets after 24 hours, or you can switch `GEMINI_MODEL` to a model with a larger free allowance.
                - If it mentions the API key, set a valid `GEMINI_API_KEY` in the backend environment.
                - Check `GET /api/coach/ai-status` for the live diagnosis.

                Everything else in the app still works: your profile (%s, %s), your roadmap and the market opportunities are all available while the AI is offline.
                """,
                reason, orElse(profile.getCurrentTitle(), "profile"), field);
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
