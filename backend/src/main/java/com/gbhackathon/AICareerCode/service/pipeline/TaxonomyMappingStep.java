package com.gbhackathon.AICareerCode.service.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gbhackathon.AICareerCode.dto.plan.CareerGoalDto;
import com.gbhackathon.AICareerCode.dto.plan.SkillEvidenceDto;
import com.gbhackathon.AICareerCode.dto.plan.TargetRequirementDto;
import com.gbhackathon.AICareerCode.model.TaxonomySkill;
import com.gbhackathon.AICareerCode.model.UserProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Step 2 of the pipeline: normalise the student's own wording onto the reference taxonomy, and
 * establish what the target role requires.
 *
 * <p>Both halves are one call because they need the same retrieved taxonomy rows in front of them
 * and because the mapping has to be consistent across them: if "ReactJS" on the CV becomes
 * EXT-REACT, the requirement for the role has to use EXT-REACT too, or the gap analysis compares
 * two things that never meet.
 *
 * <p>Retrieval runs first and the model may only use codes it was handed. That is the difference
 * between a taxonomy and a suggestion: a model asked to "map this to SFIA" from memory produces
 * codes that look exactly like SFIA codes and frequently are not.
 */
@Component
public class TaxonomyMappingStep {

    private final AiStepRunner runner;

    public TaxonomyMappingStep(AiStepRunner runner) {
        this.runner = runner;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        public List<SkillEvidenceDto> profileEvidence;
        public List<TargetRequirementDto> targetRequirements;
        /** Anything the model could not ground in the retrieved rows, in its own words. */
        public String referenceDataLimitations;
    }

    public AiStepRunner.StepResult<Result> run(UserProfile profile, CareerGoalDto goal,
                                               List<TaxonomySkill> retrievedTaxonomy) {
        Map<String, TaxonomySkill> byCode = new LinkedHashMap<>();
        retrievedTaxonomy.forEach(s -> byCode.put(s.getCode().toUpperCase(Locale.ROOT), s));

        String prompt = """
                You are a skills analyst. Two jobs, in one JSON answer.

                JOB 1 - Map what this profile evidences onto the reference taxonomy below.
                Work from the profile's own wording. For every skill the profile mentions, decide
                which taxonomy row it corresponds to, quote the profile text that supports it, and
                state how strong that evidence is.

                JOB 2 - Establish what the target role requires.
                Work from the job description when one is supplied, otherwise from the taxonomy and
                your knowledge of the role. Mark where each requirement came from. Include
                requirements the student already meets - this is the role's shape, not a to-do list.

                %s

                CANDIDATE PROFILE:
                %s
                CAREER GOAL:
                %s
                %s

                Return STRICT JSON in exactly this shape:
                {
                  "profileEvidence": [
                    {
                      "skillLabel": "the profile's own wording",
                      "taxonomyCode": "a code from the list above, or null",
                      "evidenceStatus": "HAS_EVIDENCE | LIMITED_EVIDENCE | NO_DATA",
                      "evidenceQuotes": ["a short quotation that appears verbatim in the profile"],
                      "confidence": "HIGH | MEDIUM | LOW",
                      "rationale": "why this wording maps to this taxonomy row",
                      "assessedLevel": null,
                      "levelBasis": null
                    }
                  ],
                  "targetRequirements": [
                    {
                      "skillLabel": "skill the role needs",
                      "taxonomyCode": "a code from the list above, or null",
                      "requiredLevel": 3,
                      "importance": "ESSENTIAL | IMPORTANT | NICE_TO_HAVE",
                      "sourceType": "JOB_DESCRIPTION | TAXONOMY | AI_JUDGEMENT",
                      "sourceNote": "the quotation or taxonomy row this rests on"
                    }
                  ],
                  "referenceDataLimitations": "what you could not ground in the retrieved rows, or null"
                }

                RULES FOR THIS STEP
                - profileEvidence: one entry per distinct skill the profile mentions. Do not add
                  skills the profile does not mention. Every string in evidenceQuotes must appear
                  in the profile text above; if you cannot quote it, use an empty list and set
                  evidenceStatus accordingly.
                - assessedLevel: leave it null unless the profile shows what the person did with
                  the skill, not merely that they named it. When you do set it, levelBasis must say
                  what in the profile supports that level. A level with no basis will be rejected.
                - targetRequirements: 8 to 15 entries for a real role at this seniority. Aim
                  requiredLevel at this seniority, not at a senior version of the role - an intern
                  and a mid-level engineer do not need the same level of the same skill.
                - sourceType JOB_DESCRIPTION is only allowed when a job description was supplied AND
                  sourceNote quotes it. Otherwise use TAXONOMY when a retrieved row states it, or
                  AI_JUDGEMENT when it is your own assessment of the role. Do not label your own
                  judgement as a source.
                """.formatted(
                PromptSupport.GROUND_RULES,
                PromptSupport.describeCandidate(profile),
                PromptSupport.describeGoal(goal),
                PromptSupport.describeTaxonomy(retrievedTaxonomy));

        return runner.run("taxonomy-mapping", prompt, Result.class, result -> validate(result, byCode, goal));
    }

    /**
     * The rules the model must satisfy. Each returned string is fed back verbatim, so each one is
     * written as an instruction the model can act on rather than as a description of the fault.
     *
     * <p>Package-private so the rules can be exercised directly in tests, without a model call.
     */
    List<String> validate(Result result, Map<String, TaxonomySkill> byCode, CareerGoalDto goal) {
        List<String> problems = new ArrayList<>();

        if (result.profileEvidence == null || result.profileEvidence.isEmpty()) {
            problems.add("profileEvidence was empty. Return one entry for every skill the profile "
                    + "mentions. If the profile genuinely lists no skills, return a single entry with "
                    + "evidenceStatus NO_DATA explaining that.");
        }
        if (result.targetRequirements == null || result.targetRequirements.size() < 5) {
            problems.add("targetRequirements must contain at least 5 entries describing what a "
                    + goal.targetSeniority + " " + goal.targetRole + " needs. Return 8 to 15.");
        }

        if (result.profileEvidence != null) {
            for (SkillEvidenceDto evidence : result.profileEvidence) {
                if (evidence == null || isBlank(evidence.skillLabel)) {
                    problems.add("Every profileEvidence entry needs a non-empty skillLabel.");
                    continue;
                }
                if (!isBlank(evidence.taxonomyCode)
                        && !byCode.containsKey(evidence.taxonomyCode.toUpperCase(Locale.ROOT))) {
                    problems.add("taxonomyCode '" + evidence.taxonomyCode + "' on skill '"
                            + evidence.skillLabel + "' is not in the retrieved taxonomy list. Use a "
                            + "code from that list or set it to null.");
                }
                if (!isValidStatus(evidence.evidenceStatus)) {
                    problems.add("evidenceStatus on '" + evidence.skillLabel
                            + "' must be exactly HAS_EVIDENCE, LIMITED_EVIDENCE or NO_DATA.");
                }
                if (evidence.assessedLevel != null && isBlank(evidence.levelBasis)) {
                    problems.add("'" + evidence.skillLabel + "' has assessedLevel "
                            + evidence.assessedLevel + " but no levelBasis. Either say what in the "
                            + "profile supports that level, or set assessedLevel to null.");
                }
                if (evidence.assessedLevel != null
                        && (evidence.assessedLevel < 1 || evidence.assessedLevel > 7)) {
                    problems.add("assessedLevel on '" + evidence.skillLabel
                            + "' must be between 1 and 7, or null.");
                }
                // A skill claimed as evidenced with nothing quotable behind it is the exact failure
                // this pipeline exists to prevent, so it is a hard rejection rather than a warning.
                if (SkillEvidenceDto.HAS_EVIDENCE.equals(evidence.evidenceStatus)
                        && (evidence.evidenceQuotes == null || evidence.evidenceQuotes.isEmpty())) {
                    problems.add("'" + evidence.skillLabel + "' is marked HAS_EVIDENCE but quotes "
                            + "nothing from the profile. Quote the supporting text, or lower the "
                            + "status to LIMITED_EVIDENCE or NO_DATA.");
                }
            }
        }

        if (result.targetRequirements != null) {
            boolean jdSupplied = goal.jobDescription != null && !goal.jobDescription.isBlank();
            for (TargetRequirementDto requirement : result.targetRequirements) {
                if (requirement == null || isBlank(requirement.skillLabel)) {
                    problems.add("Every targetRequirements entry needs a non-empty skillLabel.");
                    continue;
                }
                if (!isBlank(requirement.taxonomyCode)
                        && !byCode.containsKey(requirement.taxonomyCode.toUpperCase(Locale.ROOT))) {
                    problems.add("taxonomyCode '" + requirement.taxonomyCode + "' on requirement '"
                            + requirement.skillLabel + "' is not in the retrieved taxonomy list. Use a "
                            + "code from that list or set it to null.");
                }
                if (!jdSupplied && TargetRequirementDto.SOURCE_JOB_DESCRIPTION.equals(requirement.sourceType)) {
                    problems.add("'" + requirement.skillLabel + "' claims sourceType JOB_DESCRIPTION "
                            + "but no job description was supplied. Use TAXONOMY or AI_JUDGEMENT.");
                }
                if (isBlank(requirement.sourceNote)) {
                    problems.add("'" + requirement.skillLabel + "' needs a sourceNote saying what "
                            + "the requirement rests on.");
                }
                if (requirement.requiredLevel != null
                        && (requirement.requiredLevel < 1 || requirement.requiredLevel > 7)) {
                    problems.add("requiredLevel on '" + requirement.skillLabel
                            + "' must be between 1 and 7, or null.");
                }
            }
        }
        return problems;
    }

    /** Fills in the taxonomy name and source from the retrieved rows, so the UI need not join. */
    public void decorate(Result result, Map<String, TaxonomySkill> byCode) {
        if (result.profileEvidence != null) {
            for (SkillEvidenceDto evidence : result.profileEvidence) {
                TaxonomySkill row = lookup(byCode, evidence.taxonomyCode);
                if (row != null) {
                    evidence.taxonomyCode = row.getCode();
                    evidence.taxonomyName = row.getName();
                    evidence.taxonomySource = row.getSource();
                }
            }
        }
        if (result.targetRequirements != null) {
            for (TargetRequirementDto requirement : result.targetRequirements) {
                TaxonomySkill row = lookup(byCode, requirement.taxonomyCode);
                if (row != null) {
                    requirement.taxonomyCode = row.getCode();
                    requirement.taxonomyName = row.getName();
                    requirement.taxonomySource = row.getSource();
                }
            }
        }
    }

    private TaxonomySkill lookup(Map<String, TaxonomySkill> byCode, String code) {
        return isBlank(code) ? null : byCode.get(code.toUpperCase(Locale.ROOT));
    }

    private static boolean isValidStatus(String status) {
        return SkillEvidenceDto.HAS_EVIDENCE.equals(status)
                || SkillEvidenceDto.LIMITED_EVIDENCE.equals(status)
                || SkillEvidenceDto.NO_DATA.equals(status);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
