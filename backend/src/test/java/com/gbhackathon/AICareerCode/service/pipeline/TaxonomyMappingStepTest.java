package com.gbhackathon.AICareerCode.service.pipeline;

import com.gbhackathon.AICareerCode.dto.plan.CareerGoalDto;
import com.gbhackathon.AICareerCode.dto.plan.SkillEvidenceDto;
import com.gbhackathon.AICareerCode.dto.plan.TargetRequirementDto;
import com.gbhackathon.AICareerCode.model.TaxonomySkill;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These rules are what stop the taxonomy step from being a suggestion. A model asked to "map this
 * to SFIA" from memory produces codes that look exactly like SFIA codes and frequently are not.
 */
class TaxonomyMappingStepTest {

    private final TaxonomyMappingStep step = new TaxonomyMappingStep(null);

    private static TaxonomySkill skill(String code, String name, String source) {
        TaxonomySkill s = new TaxonomySkill();
        s.setCode(code);
        s.setName(name);
        s.setSource(source);
        return s;
    }

    private Map<String, TaxonomySkill> retrieved() {
        Map<String, TaxonomySkill> byCode = new LinkedHashMap<>();
        byCode.put("PROG", skill("PROG", "Programming/software development", TaxonomySkill.SOURCE_SFIA9));
        byCode.put("EXT-REACT", skill("EXT-REACT", "React", TaxonomySkill.SOURCE_EXTENSION));
        return byCode;
    }

    private static CareerGoalDto goal(String jobDescription) {
        CareerGoalDto goal = new CareerGoalDto();
        goal.targetRole = "Backend Developer";
        goal.targetSeniority = "JUNIOR";
        goal.durationMonths = 3;
        goal.hoursPerWeek = 8;
        goal.jobDescription = jobDescription;
        return goal;
    }

    @Test
    void rejectsASkillCodeThatWasNotRetrieved() {
        TaxonomyMappingStep.Result result = validResult();
        result.profileEvidence.get(0).taxonomyCode = "DTAN";

        List<String> problems = step.validate(result, retrieved(), goal(null));

        assertTrue(problems.stream().anyMatch(p -> p.contains("DTAN")), problems.toString());
    }

    /**
     * The distinction the whole product turns on. A skill claimed as evidenced with nothing
     * quotable behind it is the failure this pipeline exists to prevent, so it is a rejection
     * rather than a warning.
     */
    @Test
    void rejectsEvidencedSkillsWithNothingQuoted() {
        TaxonomyMappingStep.Result result = validResult();
        result.profileEvidence.get(0).evidenceStatus = SkillEvidenceDto.HAS_EVIDENCE;
        result.profileEvidence.get(0).evidenceQuotes = List.of();

        List<String> problems = step.validate(result, retrieved(), goal(null));

        assertTrue(problems.stream().anyMatch(p -> p.contains("quotes")), problems.toString());
    }

    /**
     * Naming a technology is not evidence of a level of responsibility, and a plan built on an
     * inflated level skips the work the student actually needs.
     */
    @Test
    void rejectsALevelWithNoStatedBasis() {
        TaxonomyMappingStep.Result result = validResult();
        result.profileEvidence.get(0).assessedLevel = 4;
        result.profileEvidence.get(0).levelBasis = null;

        List<String> problems = step.validate(result, retrieved(), goal(null));

        assertTrue(problems.stream().anyMatch(p -> p.contains("levelBasis")), problems.toString());
    }

    @Test
    void rejectsALevelOutsideTheFrameworkRange() {
        TaxonomyMappingStep.Result result = validResult();
        result.profileEvidence.get(0).assessedLevel = 9;
        result.profileEvidence.get(0).levelBasis = "led a team";

        List<String> problems = step.validate(result, retrieved(), goal(null));

        assertTrue(problems.stream().anyMatch(p -> p.contains("between 1 and 7")), problems.toString());
    }

    /**
     * A requirement labelled as coming from the advert, when there is no advert, is the model
     * borrowing authority it does not have.
     */
    @Test
    void rejectsAJobDescriptionSourceWhenNoJobDescriptionWasGiven() {
        TaxonomyMappingStep.Result result = validResult();
        result.targetRequirements.get(0).sourceType = TargetRequirementDto.SOURCE_JOB_DESCRIPTION;

        List<String> problems = step.validate(result, retrieved(), goal(null));

        assertTrue(problems.stream().anyMatch(p -> p.contains("no job description was supplied")),
                problems.toString());
    }

    @Test
    void allowsAJobDescriptionSourceWhenOneWasGiven() {
        TaxonomyMappingStep.Result result = validResult();
        result.targetRequirements.get(0).sourceType = TargetRequirementDto.SOURCE_JOB_DESCRIPTION;

        List<String> problems = step.validate(result, retrieved(), goal("We need someone who ..."));

        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void rejectsAnUnknownEvidenceStatus() {
        TaxonomyMappingStep.Result result = validResult();
        result.profileEvidence.get(0).evidenceStatus = "PROBABLY";

        List<String> problems = step.validate(result, retrieved(), goal(null));

        assertTrue(problems.stream().anyMatch(p -> p.contains("evidenceStatus")), problems.toString());
    }

    @Test
    void acceptsAWellFormedResult() {
        assertTrue(step.validate(validResult(), retrieved(), goal(null)).isEmpty());
    }

    /** The server, not the model, supplies the name and source, so the UI never has to join. */
    @Test
    void decorateFillsInTheTaxonomyNameAndSource() {
        TaxonomyMappingStep.Result result = validResult();
        result.profileEvidence.get(0).taxonomyCode = "ext-react";
        result.targetRequirements.get(0).taxonomyCode = "PROG";

        step.decorate(result, retrieved());

        assertEquals("EXT-REACT", result.profileEvidence.get(0).taxonomyCode);
        assertEquals("React", result.profileEvidence.get(0).taxonomyName);
        assertEquals(TaxonomySkill.SOURCE_EXTENSION, result.profileEvidence.get(0).taxonomySource);
        assertEquals(TaxonomySkill.SOURCE_SFIA9, result.targetRequirements.get(0).taxonomySource);
    }

    @Test
    void decorateLeavesAnUnmappedSkillAlone() {
        TaxonomyMappingStep.Result result = validResult();
        result.profileEvidence.get(0).taxonomyCode = null;

        step.decorate(result, retrieved());

        assertNull(result.profileEvidence.get(0).taxonomyName);
    }

    private TaxonomyMappingStep.Result validResult() {
        TaxonomyMappingStep.Result result = new TaxonomyMappingStep.Result();

        SkillEvidenceDto evidence = new SkillEvidenceDto();
        evidence.skillLabel = "ReactJS";
        evidence.taxonomyCode = "EXT-REACT";
        evidence.evidenceStatus = SkillEvidenceDto.HAS_EVIDENCE;
        evidence.evidenceQuotes = new ArrayList<>(List.of("built a booking UI in React"));
        evidence.confidence = "HIGH";
        evidence.rationale = "the CV names React directly";
        result.profileEvidence = new ArrayList<>(List.of(evidence));

        result.targetRequirements = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            TargetRequirementDto requirement = new TargetRequirementDto();
            requirement.skillLabel = "Requirement " + i;
            requirement.taxonomyCode = "PROG";
            requirement.requiredLevel = 3;
            requirement.importance = "ESSENTIAL";
            requirement.sourceType = TargetRequirementDto.SOURCE_TAXONOMY;
            requirement.sourceNote = "PROG describes this";
            result.targetRequirements.add(requirement);
        }
        return result;
    }
}
