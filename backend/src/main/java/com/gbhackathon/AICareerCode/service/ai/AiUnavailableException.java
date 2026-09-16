package com.gbhackathon.AICareerCode.service.ai;

/**
 * Thrown when no configured Gemini model could answer.
 *
 * <p>The previous design returned {@code null} from every AI call and let each caller substitute
 * written-in-advance text. That is exactly what this product may not do: a roadmap assembled from
 * a template is not "AI analysis", and labelling it as such misleads the student about where the
 * advice came from. Failing loudly is the only honest option, so callers propagate this and the
 * UI explains what happened and offers a retry.
 */
public class AiUnavailableException extends RuntimeException {

    private final String detail;

    public AiUnavailableException(String detail) {
        super(detail == null || detail.isBlank() ? "The AI service is unavailable." : detail);
        this.detail = detail;
    }

    /** The underlying reason - a quota message, a transport error - for the diagnostics panel. */
    public String getDetail() {
        return detail;
    }
}
