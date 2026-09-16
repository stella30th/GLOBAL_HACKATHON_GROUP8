package com.gbhackathon.AICareerCode.dto.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gbhackathon.AICareerCode.dto.ProfileAuditDto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one run of the pipeline produced, stored as a single snapshot.
 *
 * <p>It is one document rather than five endpoints because the parts only mean anything together:
 * a gap refers to a piece of evidence, a phase refers to a gap, a resource refers to a retrieved
 * document. Storing them separately would let them drift apart, and a student would be reading a
 * phase built to close a gap that the currently displayed analysis no longer contains.
 *
 * <p>{@link #provenance} is not metadata for developers. It is what lets a reader answer "where
 * did this come from" for any sentence on the page: which model wrote it, which taxonomy rows and
 * which documents were retrieved before it was written, and which version of the pipeline ran.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LearningPlanDto {

    /** Server-issued UUID for this plan. Progress ticks name it, so a stale tab is rejected. */
    public String planId;

    public CareerGoalDto goal;

    /** What the profile evidences, mapped onto the taxonomy. */
    public List<SkillEvidenceDto> profileEvidence;

    /** What the target role requires, and where each requirement came from. */
    public List<TargetRequirementDto> targetRequirements;

    /** The difference between the two, prioritised. */
    public List<SkillGapDto> skillGaps;

    /** Skills and their relationships, used to order the phases. */
    public SkillGraphDto knowledgeGraph;

    /** The phased plan itself. */
    public LearningPathDto learningPath;

    /** The profile-as-a-document review: verdict, strengths, gaps in the writing, keywords, bullets. */
    public ProfileAuditDto audit;

    public Provenance provenance;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Provenance {
        /** ISO-8601 instant the plan finished generating. */
        public String generatedAt;

        /** Which model answered each step. Two entries differing means a fallback happened mid-run. */
        public Map<String, String> stepModels = new LinkedHashMap<>();

        /** Bumped when the prompts or the stored shape change enough to invalidate older plans. */
        public Integer pipelineVersion;

        /** Whether SFIA 9 was loaded when this plan was built, and which file it came from. */
        public Boolean sfiaLoaded;
        public String sfiaDatasetVersion;

        /** Version string of the learning-resource catalogue used for retrieval. */
        public String corpusVersion;

        /** Taxonomy rows retrieved and put in front of the model, by code. */
        public List<String> retrievedTaxonomyCodes;

        /** Documents retrieved and put in front of the model, by resourceKey. */
        public List<String> retrievedResourceKeys;

        /**
         * Limits worth stating on the page: no SFIA file, a skill with no matching document in the
         * corpus, a requirement the model had to judge without a source.
         */
        public List<String> limitations;

        /** Number of times the model was asked to correct an invalid answer during this run. */
        public Integer repairAttempts;
    }
}
