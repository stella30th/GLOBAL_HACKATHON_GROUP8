package com.gbhackathon.AICareerCode.service.pipeline;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Checks that a quotation the model attributed to a document actually appears in it.
 *
 * <p>Requiring a quote and never reading it is close to worthless: a model that is told every
 * claim needs supporting text will produce supporting text, and a paraphrase that sounds like the
 * CV reads exactly like a quotation from it. The whole evidence model of this product - the line
 * between "your profile shows this" and "your profile is silent" - rests on the quote being real,
 * so it is compared against the source rather than trusted.
 *
 * <p>The comparison is deliberately forgiving about form and strict about content. A PDF extractor
 * puts line breaks mid-sentence, uses non-breaking spaces and a mix of dash and quote characters,
 * and none of that is the model inventing anything; case and spacing differences are not evidence
 * of fabrication. Different words are.
 */
final class TextEvidence {

    private TextEvidence() {}

    /**
     * Quotes shorter than this are not checked.
     *
     * <p>A four-character quote like "Java" or "SQL" matches almost any document by accident, so
     * verifying it proves nothing, while rejecting it would fail entries that are perfectly
     * honest. The check earns its keep on the sentence-length quotes that carry real claims.
     */
    private static final int MIN_CHECKED_LENGTH = 12;

    /**
     * Lower-cased, whitespace-collapsed, Unicode-normalised form.
     *
     * <p>NFC first, because a CV exported from one tool spells "é" as one code point and another
     * spells it as "e" plus a combining accent; without normalising, two identical-looking strings
     * do not match. Typographic quotes and dashes are folded to their ASCII forms for the same
     * reason - PDF extraction routinely swaps them.
     */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT)
                .replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-')
                .replace(' ', ' ');
        return normalized.replaceAll("\\s+", " ").trim();
    }

    /**
     * Whether {@code quote} appears in {@code source}.
     *
     * <p>Returns true for anything too short to be worth checking, and for an empty quote: this
     * answers "did the model fabricate this", and neither case is evidence that it did. Whether an
     * entry is allowed to have no quote at all is a separate rule, enforced by the caller.
     */
    static boolean appearsIn(String source, String quote) {
        if (quote == null || quote.isBlank()) {
            return true;
        }
        String normalizedQuote = normalize(quote);
        if (normalizedQuote.length() < MIN_CHECKED_LENGTH) {
            return true;
        }
        return normalize(source).contains(normalizedQuote);
    }

    /** A short, safe fragment of a quote for an error message. Never the whole CV. */
    static String excerpt(String quote) {
        String trimmed = quote == null ? "" : quote.trim();
        return trimmed.length() <= 60 ? trimmed : trimmed.substring(0, 57) + "...";
    }
}
