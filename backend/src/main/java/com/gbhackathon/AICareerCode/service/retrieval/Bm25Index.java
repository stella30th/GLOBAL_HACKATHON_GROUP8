package com.gbhackathon.AICareerCode.service.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * An in-memory BM25 ranker over a small corpus.
 *
 * <p>BM25 rather than substring matching because retrieval has to survive the vocabulary mismatch
 * that is the whole problem here: a CV says "ReactJS", the catalogue says "React", the taxonomy
 * says "Programming/software development". Term-frequency weighting with an inverse document
 * frequency lets a rare, discriminating term such as "verilog" outrank a term such as "design"
 * that appears in half the corpus, which a LIKE query cannot do.
 *
 * <p>An external search engine would do this better. It is not worth a second service here: the
 * taxonomy is a few hundred rows and the resource catalogue a few hundred more, so the whole index
 * is a handful of megabytes and rebuilds in milliseconds whenever the underlying table changes.
 */
public class Bm25Index<T> {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private static final Pattern TOKEN = Pattern.compile("[^a-z0-9+#.]+");

    /**
     * Words so common in this domain that they match everything and rank nothing. Kept short on
     * purpose: an over-eager stop list throws away the signal in a query such as "data analysis".
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "for", "on", "with", "at", "by",
            "from", "is", "are", "be", "as", "that", "this", "it", "you", "your");

    private final List<Entry<T>> entries = new ArrayList<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private double averageLength;

    private record Entry<T>(T item, Map<String, Integer> termCounts, int length) {}

    /**
     * Builds an index over {@code items}, taking each item's indexable text from {@code textOf}.
     * Items whose text is blank are skipped rather than indexed as empty documents, which would
     * otherwise drag the average length down and inflate every other score.
     */
    public Bm25Index(List<T> items, Function<T, String> textOf) {
        long totalLength = 0;
        for (T item : items) {
            String text = textOf.apply(item);
            if (text == null || text.isBlank()) {
                continue;
            }
            List<String> tokens = tokenize(text);
            if (tokens.isEmpty()) {
                continue;
            }
            Map<String, Integer> counts = new HashMap<>();
            for (String token : tokens) {
                counts.merge(token, 1, Integer::sum);
            }
            for (String term : counts.keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
            entries.add(new Entry<>(item, counts, tokens.size()));
            totalLength += tokens.size();
        }
        averageLength = entries.isEmpty() ? 1.0 : (double) totalLength / entries.size();
    }

    public int size() {
        return entries.size();
    }

    /**
     * The {@code limit} best-scoring items for {@code query}, most relevant first. Items that score
     * zero - no query term appears in them at all - are left out rather than padded in, so an
     * unanswerable query returns an empty list and the caller can say so honestly.
     */
    public List<Scored<T>> search(String query, int limit) {
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty() || entries.isEmpty()) {
            return List.of();
        }
        int corpusSize = entries.size();
        List<Scored<T>> scored = new ArrayList<>();
        for (Entry<T> entry : entries) {
            double score = 0;
            for (String term : queryTerms) {
                Integer frequency = entry.termCounts().get(term);
                if (frequency == null) {
                    continue;
                }
                int df = documentFrequency.getOrDefault(term, 0);
                // The +0.5 smoothing keeps the idf of a term present in every document slightly
                // positive instead of negative, so a common term adds nothing rather than
                // penalising a document that happens to contain it.
                double idf = Math.log(1 + (corpusSize - df + 0.5) / (df + 0.5));
                double norm = frequency * (K1 + 1)
                        / (frequency + K1 * (1 - B + B * entry.length() / averageLength));
                score += idf * norm;
            }
            if (score > 0) {
                scored.add(new Scored<>(entry.item(), score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored<T>::score).reversed());
        return scored.size() > limit ? new ArrayList<>(scored.subList(0, limit)) : scored;
    }

    public record Scored<T>(T item, double score) {}

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String raw : TOKEN.split(text.toLowerCase(Locale.ROOT))) {
            String token = raw.replaceAll("^\\.+|\\.+$", "");
            if (token.length() < 2 || STOP_WORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }
}
