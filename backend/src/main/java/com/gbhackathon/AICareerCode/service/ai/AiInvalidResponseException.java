package com.gbhackathon.AICareerCode.service.ai;

/**
 * Thrown when the model answered but the answer does not satisfy the contract: malformed JSON,
 * a missing required section, a citation to a document that was never retrieved.
 *
 * <p>Separate from {@link AiUnavailableException} because the two deserve different handling. An
 * unavailable model cannot be helped by asking again in the same second; an invalid response can,
 * so the pipeline feeds the validation errors back to the model a bounded number of times before
 * giving up.
 */
public class AiInvalidResponseException extends RuntimeException {

    public AiInvalidResponseException(String message) {
        super(message);
    }
}
