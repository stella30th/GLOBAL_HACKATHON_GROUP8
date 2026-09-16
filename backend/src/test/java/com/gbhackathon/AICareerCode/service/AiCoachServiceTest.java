package com.gbhackathon.AICareerCode.service;

import com.gbhackathon.AICareerCode.dto.CareerRoadmapDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The offline coaching path. It runs whenever Gemini is unreachable or out of quota, which on a
 * free tier is often, so the promises the product makes have to hold here too.
 */
class AiCoachServiceTest {

    private final AiCoachService coach = new AiCoachService(new GeminiClient());

    private UserProfile student(String yearOfStudy) {
        UserProfile p = new UserProfile();
        p.setId(1L);
        p.setFullName("Mai Tran");
        p.setCurrentTitle("Software Engineering Student");
        p.setIndustry("Software Engineering");
        p.setYearOfStudy(yearOfStudy);
        p.setYearsOfExperience(0.0);
        p.setSkills("Java, Git, SQL basics");
        p.setEducation("B.Sc. Software Engineering (in progress)");
        return p;
    }

    private boolean hasAiFluency(CareerRoadmapDto roadmap) {
        return roadmap.allMilestones().stream().anyMatch(m -> "AI_FLUENCY".equalsIgnoreCase(m.getCategory()));
    }

    @Test
    void everyStageOfStudyGetsAnAiFluencyMilestoneWithSomethingToHandIn() {
        for (String year : List.of("Year 1", "Year 2", "Year 3", "Year 4", "Year 5+")) {
            CareerRoadmapDto roadmap = coach.generateHeuristicRoadmap(student(year));
            assertTrue(hasAiFluency(roadmap), "no AI_FLUENCY milestone for " + year);

            CareerRoadmapDto.RoadmapMilestone aiMilestone = roadmap.allMilestones().stream()
                    .filter(m -> "AI_FLUENCY".equalsIgnoreCase(m.getCategory()))
                    .findFirst().orElseThrow();
            // "Learn AI" is not a milestone: there has to be an action and something checkable.
            assertTrue(aiMilestone.getDescription().toLowerCase().contains("verif"),
                    "the AI milestone must ask the student to verify the output: " + aiMilestone.getDescription());
        }
    }

    @Test
    void anUnknownYearGetsNeutralAdviceRatherThanAnAssumedOne() {
        CareerRoadmapDto roadmap = coach.generateHeuristicRoadmap(student(null));
        assertTrue(hasAiFluency(roadmap));
        String goal = roadmap.getTargetGoal().toLowerCase();
        assertFalse(goal.contains("internship"), "no year stated means no assumed internship timeline: " + goal);
    }

    @Test
    void anEarlyStudentAndAFinalYearStudentGetDifferentPlans() {
        CareerRoadmapDto early = coach.generateHeuristicRoadmap(student("Year 2"));
        CareerRoadmapDto finalYear = coach.generateHeuristicRoadmap(student("Year 4"));

        assertNotEquals(early.getTargetGoal(), finalYear.getTargetGoal());
        assertTrue(finalYear.getTargetGoal().toLowerCase().contains("internship")
                        || finalYear.getTargetGoal().toLowerCase().contains("junior"),
                "a final-year plan should aim at converting: " + finalYear.getTargetGoal());

        // The stage names stay put; only the content changes.
        for (CareerRoadmapDto roadmap : List.of(early, finalYear)) {
            assertNotNull(roadmap.getMonths3());
            assertNotNull(roadmap.getMonths6());
            assertNotNull(roadmap.getMonths12());
        }
    }

    @Test
    void aRoadmapThatCameBackWithoutAnAiMilestoneGetsOneAdded() {
        CareerRoadmapDto bare = new CareerRoadmapDto();
        bare.setTargetGoal("Something the model produced");
        bare.setMonths3(List.of(new CareerRoadmapDto.RoadmapMilestone(
                "Learn the fundamentals", "Study the basics.", "SKILL", "20 hrs")));

        coach.ensureAiFluencyMilestone(bare, student("Year 3"));

        assertTrue(hasAiFluency(bare));
        assertEquals(2, bare.getMonths3().size());
    }

    @Test
    void aRoadmapThatAlreadyHasOneIsLeftAlone() {
        CareerRoadmapDto roadmap = coach.generateHeuristicRoadmap(student("Year 2"));
        int before = roadmap.allMilestones().size();
        coach.ensureAiFluencyMilestone(roadmap, student("Year 2"));
        assertEquals(before, roadmap.allMilestones().size());
    }

    @Test
    void theCandidateDescriptionCarriesTheYearAndMarksTheCvAsSupportingEvidence() {
        String described = coach.describeCandidate(student("Year 4"));
        assertTrue(described.contains("Year of study: Year 4"));

        UserProfile withCv = student("Year 2");
        withCv.setRawCvText("Nguyen Van A - 4th year student, Computer Science");
        String withCvDescribed = coach.describeCandidate(withCv);
        // The edited field has to win over what an older CV says, or correcting the dropdown does
        // nothing and the student is coached at the wrong stage.
        assertTrue(withCvDescribed.contains("the field above wins"));
        assertFalse(withCvDescribed.contains("authoritative"));
    }

    @Test
    void theOfflineAuditReportsMissingEvidenceRatherThanMissingAbility() {
        var audit = coach.generateHeuristicAudit(student("Year 2"));
        assertTrue(audit.getWeaknesses().stream().anyMatch(w -> w.contains("Not enough evidence in your profile")),
                "a silent profile is missing evidence, not proof that the student cannot do it");
        assertTrue(audit.getSummary().contains("rule-based"), "offline output must say it is offline");
    }
}
