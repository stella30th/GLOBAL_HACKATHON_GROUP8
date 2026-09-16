package com.gbhackathon.AICareerCode.service.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retrieval has to survive the vocabulary mismatch that is the whole problem: a CV says one thing,
 * the catalogue says another. These check the properties the pipeline depends on, not the exact
 * scores, which are an implementation detail.
 */
class Bm25IndexTest {

    private record Doc(String id, String text) {}

    private static Bm25Index<Doc> indexOf(Doc... docs) {
        return new Bm25Index<>(List.of(docs), d -> d.text().toLowerCase(Locale.ROOT));
    }

    @Test
    void ranksTheDocumentThatActuallyCoversTheTermFirst() {
        Bm25Index<Doc> index = indexOf(
                new Doc("react", "React components hooks state user interface library"),
                new Doc("sql", "SQL joins indexes relational database query"),
                new Doc("generic", "design software development engineering design design"));

        List<Bm25Index.Scored<Doc>> results = index.search("react hooks", 3);

        assertFalse(results.isEmpty());
        assertEquals("react", results.get(0).item().id());
    }

    /**
     * An unanswerable query must come back empty rather than padded with the least-bad match.
     * The pipeline branches on this: an empty shortlist means "we have no source for this skill",
     * which the plan reports, instead of attaching an unrelated document to fill the space.
     */
    @Test
    void returnsNothingWhenNoTermMatches() {
        Bm25Index<Doc> index = indexOf(
                new Doc("react", "React components hooks"),
                new Doc("sql", "SQL joins indexes"));

        assertTrue(index.search("underwater basket weaving", 5).isEmpty());
    }

    /**
     * A term present in every document carries no information. It must not outrank a rare term,
     * or every query for "data analysis" would return whatever mentions "data" most often.
     */
    @Test
    void aTermCommonToEveryDocumentDoesNotDecideTheRanking() {
        Bm25Index<Doc> index = indexOf(
                new Doc("a", "data data data data engineering"),
                new Doc("b", "data verilog timing analysis"),
                new Doc("c", "data marketing funnel"));

        List<Bm25Index.Scored<Doc>> results = index.search("data verilog", 3);

        assertEquals("b", results.get(0).item().id(),
                "the document containing the rare term should win despite fewer 'data' hits");
    }

    @Test
    void respectsTheResultLimit() {
        Bm25Index<Doc> index = indexOf(
                new Doc("a", "java spring"),
                new Doc("b", "java hibernate"),
                new Doc("c", "java maven"));

        assertEquals(2, index.search("java", 2).size());
    }

    /**
     * Blank documents used to be indexed as empty ones, which dragged the average length down and
     * inflated every other score.
     */
    @Test
    void skipsDocumentsWithNoIndexableText() {
        Bm25Index<Doc> index = indexOf(
                new Doc("a", "kubernetes orchestration"),
                new Doc("blank", "   "),
                new Doc("empty", ""));

        assertEquals(1, index.size());
    }

    @Test
    void tokenizerKeepsTechnologyNamesThatContainPunctuation() {
        List<String> tokens = Bm25Index.tokenize("C++ and C# with Node.js");

        assertTrue(tokens.contains("c++"), tokens.toString());
        assertTrue(tokens.contains("c#"), tokens.toString());
        assertTrue(tokens.contains("node.js"), tokens.toString());
        assertFalse(tokens.contains("and"), "stop words should be dropped: " + tokens);
    }
}
