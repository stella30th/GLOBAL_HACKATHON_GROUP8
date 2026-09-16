package com.gbhackathon.AICareerCode.dto.plan;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One skill the profile mentions, mapped onto the reference taxonomy, with what the profile
 * actually shows about it.
 *
 * <p>{@link #evidenceStatus} carries the distinction the whole product turns on. A CV that never
 * mentions testing does not tell us the student cannot test; it tells us the CV is silent. Those
 * are different findings and they lead to different advice, so they are different values here and
 * they are rendered differently in the UI.
 *
 * <p>{@link #assessedLevel} is deliberately awkward to fill in. Naming a technology is not
 * evidence of a level of responsibility, and a plan built on an inflated level skips the work the
 * student actually needs; the prompt requires a stated basis for any level at all, and a level
 * without one is stripped during validation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillEvidenceDto {

    /** The profile's own wording, for example "ReactJS" or "lập trình hướng đối tượng". */
    @JsonAlias({"skill", "label", "cvSkill"})
    public String skillLabel;

    /** Code of the taxonomy row this was mapped onto, or null when nothing matched. */
    @JsonAlias({"code", "sfiaCode"})
    public String taxonomyCode;

    /** SFIA9 or EXTENSION. Set by the server from the taxonomy row, never by the model. */
    public String taxonomySource;

    /** Name of the taxonomy row, filled in by the server so the UI need not join. */
    public String taxonomyName;

    /** HAS_EVIDENCE, LIMITED_EVIDENCE or NO_DATA. */
    public String evidenceStatus;

    /** Short quotations from the profile or CV that support the mapping. */
    @JsonAlias({"evidence", "quotes"})
    public List<String> evidenceQuotes;

    /** HIGH, MEDIUM or LOW - how sure the model is about the mapping itself. */
    public String confidence;

    /** Why this profile wording maps to this taxonomy row. */
    @JsonAlias({"reason", "justification"})
    public String rationale;

    /** SFIA responsibility level 1-7 where the evidence supports one; null otherwise. */
    public Integer assessedLevel;

    /** What in the profile supports {@link #assessedLevel}. Required whenever a level is given. */
    public String levelBasis;

    public static final String HAS_EVIDENCE = "HAS_EVIDENCE";
    public static final String LIMITED_EVIDENCE = "LIMITED_EVIDENCE";
    public static final String NO_DATA = "NO_DATA";
}
