package com.gbhackathon.AICareerCode.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The review of the profile as a document: how it reads to someone screening it.
 *
 * <p>Deliberately separate from the skill gaps. A gap is about capability; this is about how the
 * capability is written down, which is a different failure with a different fix. Keeping them
 * apart also keeps {@link #healthScore} honest: it rates a document, it is not an ability rating
 * and it is not a readiness score, and the UI is required to say so beside the number.
 *
 * <p>The aliases exist because a model asked for "originalBullet" will periodically answer with
 * "before". Without them one renamed field failed the entire deserialisation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProfileAuditDto {

    /** 0-100, for the profile as a screening document only. */
    public int healthScore;

    /** One line on how the document reads. */
    public String verdict;

    /** Two or three sentences: what it does well, and the highest-value fix. */
    public String summary;

    /** Each grounded in something the profile actually contains. */
    public List<String> strengths;

    /** Each phrased as a gap in the document, with what would close it. */
    public List<String> weaknesses;

    @JsonAlias({"keywordsPresent", "presentKeywords"})
    public List<String> atsKeywordsPresent;

    @JsonAlias({"keywordsMissing", "missingKeywords"})
    public List<String> atsKeywordsMissing;

    public List<BulletImprovement> bulletImprovements;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BulletImprovement {
        @JsonAlias({"originalBullet", "before", "weakBullet"})
        public String original;

        @JsonAlias({"improvedBullet", "after", "rewrittenBullet"})
        public String improved;

        @JsonAlias({"explanation", "why", "reason"})
        public String rationale;
    }
}
