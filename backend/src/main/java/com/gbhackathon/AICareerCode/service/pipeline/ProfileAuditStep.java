package com.gbhackathon.AICareerCode.service.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbhackathon.AICareerCode.dto.ProfileAuditDto;
import com.gbhackathon.AICareerCode.dto.plan.CareerGoalDto;
import com.gbhackathon.AICareerCode.dto.plan.SkillEvidenceDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 5: how the profile reads as a screening document.
 *
 * <p>Kept as its own step and its own section of the page because it answers a different question
 * from the gap analysis. The gaps say what to learn; this says whether the things the student has
 * already done are legible to someone reading for thirty seconds. A student can have no gaps worth
 * mentioning and a document that gets them filtered out, and the fix for that is rewriting, not
 * studying.
 *
 * <p>It runs after the evidence mapping so it can work from what was actually found rather than
 * re-reading the CV from scratch, which is how the two sections used to end up disagreeing about
 * what the profile contained.
 */
@Component
public class ProfileAuditStep {

    private static final Logger log = LoggerFactory.getLogger(ProfileAuditStep.class);

    private final AiStepRunner runner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProfileAuditStep(AiStepRunner runner) {
        this.runner = runner;
    }

    public AiStepRunner.StepResult<ProfileAuditDto> run(UserProfile profile, CareerGoalDto goal,
                                                        List<SkillEvidenceDto> evidence) {
        String prompt = """
                You are reviewing this profile as a DOCUMENT: how it reads to a person screening
                applications for the target role, and to the software that filters them first.

                This is not an assessment of the person's ability. It rates the writing: what a
                reader can and cannot tell from it, and what is missing that would let them tell.

                %s

                CANDIDATE PROFILE:
                %s
                TARGET ROLE:
                %s

                WHAT THE PREVIOUS STEP FOUND IN THIS PROFILE:
                %s

                Return STRICT JSON in exactly this shape:
                {
                  "healthScore": 0,
                  "verdict": "one line on how this document reads",
                  "summary": "2-3 sentences: strongest asset, and the single highest-value fix",
                  "strengths": ["something the document does well, quoting or naming what it is"],
                  "weaknesses": ["something a reader cannot tell from it, and what would fix that"],
                  "atsKeywordsPresent": ["term already in the document that matters for this role"],
                  "atsKeywordsMissing": ["term this role's adverts use that the document lacks"],
                  "bulletImprovements": [
                    {
                      "original": "a weak line taken from this profile, quoted or closely adapted",
                      "improved": "the same content rewritten with situation, action and a result",
                      "rationale": "why the rewrite reads better to someone screening for this role"
                    }
                  ]
                }

                RULES FOR THIS STEP
                - healthScore is 0-100 for the DOCUMENT. It is not an ability score and not a
                  readiness score. Judge completeness, specificity, evidence and how easy it is to
                  scan.
                - 3 strengths, 3 weaknesses, 3 bulletImprovements.
                - Every weakness must be about the document - "the projects do not say what you
                  personally did" - never about the person.
                - originals in bulletImprovements must come from this profile. If the profile has no
                  bullet-style content at all, say so in a weakness and return an empty list rather
                  than inventing a line to rewrite.
                - Rewrites may use placeholders such as [number of users] for facts you do not have.
                  They may not contain invented facts.
                - atsKeywordsMissing must be terms that matter for %s specifically, in this
                  candidate's field. Do not list software-engineering terms for a candidate in
                  another field.
                """.formatted(
                PromptSupport.GROUND_RULES,
                PromptSupport.describeCandidate(profile),
                PromptSupport.describeGoal(goal),
                toJson(evidence),
                goal.targetRole == null ? "the target role" : goal.targetRole);

        return runner.run("profile-audit", prompt, ProfileAuditDto.class, this::validate);
    }

    // Package-private so the rules can be exercised directly in tests, without a model call.
    List<String> validate(ProfileAuditDto audit) {
        List<String> problems = new ArrayList<>();
        if (audit.healthScore < 0 || audit.healthScore > 100) {
            problems.add("healthScore must be an integer between 0 and 100.");
        }
        if (isBlank(audit.verdict)) {
            problems.add("verdict is required.");
        }
        if (isBlank(audit.summary)) {
            problems.add("summary is required.");
        }
        if (audit.strengths == null || audit.strengths.isEmpty()) {
            problems.add("strengths must contain at least one entry grounded in the profile.");
        }
        if (audit.weaknesses == null || audit.weaknesses.isEmpty()) {
            problems.add("weaknesses must contain at least one entry about the document.");
        }
        if (audit.bulletImprovements != null) {
            for (ProfileAuditDto.BulletImprovement bullet : audit.bulletImprovements) {
                if (bullet == null) {
                    continue;
                }
                if (isBlank(bullet.original) || isBlank(bullet.improved)) {
                    problems.add("Every bulletImprovement needs both an original and an improved "
                            + "version. Drop the entry rather than leaving one side blank.");
                }
                if (isBlank(bullet.rationale)) {
                    problems.add("Every bulletImprovement needs a rationale.");
                }
            }
        }
        return problems;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialise the evidence list for the audit prompt: {}", e.getMessage());
            return "[]";
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
