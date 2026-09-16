package com.gbhackathon.AICareerCode.dto.plan;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The distance between what the profile evidences and what the target role requires, for one skill.
 *
 * <p>A gap is a statement about the profile, not about the person, and {@link #evidenceStatus}
 * keeps that readable: a gap recorded because the CV is silent is presented differently from one
 * recorded because the CV shows beginner-level work against a role that needs more.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillGapDto {

    /** Server-issued. The learning path references gaps by this id, so it is never the label. */
    public String id;

    @JsonAlias({"skill", "label"})
    public String skillLabel;

    @JsonAlias({"code", "sfiaCode"})
    public String taxonomyCode;

    public String taxonomySource;
    public String taxonomyName;

    /** What the profile currently shows: HAS_EVIDENCE, LIMITED_EVIDENCE or NO_DATA. */
    public String evidenceStatus;

    /** Current level where one could be assessed; null when the profile does not support one. */
    public Integer currentLevel;

    /** Level the target role needs. */
    public Integer targetLevel;

    /** CRITICAL, SIGNIFICANT or MINOR. */
    public String severity;

    /** 1 is learned first. The graph decides the order; this records the model's priority. */
    public Integer priority;

    /** Why this is a gap, in terms of the evidence and the requirement. */
    @JsonAlias({"reason", "justification"})
    public String rationale;

    /** HIGH, MEDIUM or LOW - how sure the model is that this gap is real. */
    public String confidence;

    /** What in the profile would change this assessment, when the answer is "nothing shown yet". */
    @JsonAlias({"evidenceNeeded", "whatWouldChangeThis"})
    public String evidenceThatWouldSettleIt;

    /** Quotations from the requirement source that justify needing this at all. */
    public List<String> requirementEvidence;
}
