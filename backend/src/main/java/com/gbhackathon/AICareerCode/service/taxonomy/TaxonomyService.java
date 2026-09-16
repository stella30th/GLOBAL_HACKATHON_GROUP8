package com.gbhackathon.AICareerCode.service.taxonomy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbhackathon.AICareerCode.model.TaxonomySkill;
import com.gbhackathon.AICareerCode.repository.TaxonomySkillRepository;
import com.gbhackathon.AICareerCode.service.retrieval.Bm25Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Retrieval over the reference skill taxonomy, and the honest account of what is in it.
 *
 * <p>Two sources live side by side and are never presented as one. SFIA 9 is the licensed
 * framework an operator loads; it describes professional skills at levels of responsibility.
 * The extension rows are concrete technologies and tools - React, Verilog, PostgreSQL - which SFIA
 * deliberately does not enumerate, each carrying the SFIA code it is exercised under so the
 * relationship is stated rather than implied.
 *
 * <p>Retrieval happens before the model is prompted, not after. The model receives a shortlist of
 * real taxonomy rows and maps the student's wording onto them; it is never asked to recall SFIA
 * from memory, which is how invented skill codes get into an analysis.
 */
@Service
public class TaxonomyService {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyService.class);

    private final TaxonomySkillRepository repository;
    private final TaxonomyStore store;
    private final SfiaTaxonomyLoader sfiaLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Rebuilt whenever the underlying rows change. Null means "not built yet". */
    private final AtomicReference<Bm25Index<TaxonomySkill>> index = new AtomicReference<>();

    public TaxonomyService(TaxonomySkillRepository repository,
                           TaxonomyStore store,
                           SfiaTaxonomyLoader sfiaLoader) {
        this.repository = repository;
        this.store = store;
        this.sfiaLoader = sfiaLoader;
    }

    /**
     * The single start-up sequence for the whole taxonomy: SFIA, then the technology extensions,
     * then the retrieval index - in that explicit order, in this one method.
     *
     * <p>Earlier, {@link SfiaTaxonomyLoader} had its own {@code @EventListener} and this method
     * relied on it having already run, by way of a comment about bean construction order rather
     * than anything the framework actually guarantees. Two independent listeners on the same
     * {@link ApplicationReadyEvent} have no ordering contract between them, so that was one
     * dependency-injection change away from silently loading extensions before SFIA existed. There
     * is now exactly one listener for this whole subsystem, and the order below is code, not a
     * side effect of how beans happened to get constructed.
     *
     * <p>Start-up always passes {@code forceRefetch = false} to the loader: a workbook already on
     * disk is used as-is, and the configured source - if any - is only contacted when nothing
     * local is usable. A manual reload is what forces a fresh fetch; see
     * {@link SfiaTaxonomyLoader#load(boolean)}.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initialise() {
        try {
            sfiaLoader.load(false);
        } catch (Exception e) {
            log.warn("SFIA taxonomy not loaded at start-up: {}", e.getMessage());
        }
        try {
            loadExtensions();
        } catch (Exception e) {
            log.warn("Could not load the technology extension rows: {}", e.getMessage());
        }
        rebuildIndex();
    }

    /**
     * Loads the bundled technology rows. Unlike SFIA these are this project's own work, so they
     * ship with the repository; they are replaced wholesale on every startup so an edit to the
     * file takes effect without a manual cleanup step.
     *
     * <p>The write goes through {@link TaxonomyStore} rather than being annotated here, because
     * this method is called from {@link #initialise()} on the same bean and a self-invocation
     * never reaches the transactional proxy.
     */
    public int loadExtensions() throws Exception {
        ClassPathResource resource = new ClassPathResource("taxonomy/technology-extensions.json");
        if (!resource.exists()) {
            log.info("No technology-extensions.json on the classpath; the taxonomy will be SFIA only.");
            return 0;
        }

        List<ExtensionRow> rows;
        try (InputStream in = resource.getInputStream()) {
            ExtensionFile file = objectMapper.readValue(in, ExtensionFile.class);
            rows = file.skills == null ? List.of() : file.skills;
        }

        Set<String> sfiaCodes = new LinkedHashSet<>();
        repository.findBySource(TaxonomySkill.SOURCE_SFIA9).forEach(s -> sfiaCodes.add(s.getCode()));

        List<TaxonomySkill> saved = new ArrayList<>();
        for (ExtensionRow row : rows) {
            if (row.code == null || row.code.isBlank() || row.name == null || row.name.isBlank()) {
                continue;
            }
            TaxonomySkill skill = new TaxonomySkill();
            skill.setSource(TaxonomySkill.SOURCE_EXTENSION);
            skill.setCode(row.code.trim().toUpperCase(Locale.ROOT));
            skill.setName(row.name.trim());
            skill.setCategory(row.category);
            skill.setDescription(row.description);
            // Asserted only when the loaded framework actually contains the code. The suggested
            // mapping in the JSON is this project's editorial guess at which SFIA skill a
            // technology is exercised under; until a real SFIA file confirms the code exists, it
            // stays unset rather than putting an unverified code in front of a student as official.
            String suggested = row.sfiaCode == null ? "" : row.sfiaCode.trim().toUpperCase(Locale.ROOT);
            if (!suggested.isBlank() && sfiaCodes.contains(suggested)) {
                skill.setMappedSfiaCode(suggested);
            }
            skill.setDatasetVersion("technology-extensions.json");
            skill.setSearchText((row.name + " " + orEmpty(row.category) + " " + orEmpty(row.description)
                    + " " + String.join(" ", row.aliases == null ? List.<String>of() : row.aliases))
                    .toLowerCase(Locale.ROOT));
            saved.add(skill);
        }

        int count = store.replaceSource(TaxonomySkill.SOURCE_EXTENSION, saved);
        log.info("Loaded {} technology extension rows into the taxonomy", count);
        return count;
    }

    /** Shape of {@code taxonomy/technology-extensions.json}. */
    // Same as the resource catalogue: the file documents itself in a "_comment" field.
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ExtensionFile {
        public String version;
        public List<ExtensionRow> skills;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ExtensionRow {
        public String code;
        public String name;
        public String category;
        public String description;
        public String sfiaCode;
        public List<String> aliases;
    }

    /**
     * Reloads SFIA, the technology extensions and the retrieval index together, as one operation
     * an operator triggers on purpose - a manual reload, not the start-up path above.
     *
     * <p>This always asks the loader to fetch fresh from {@code SFIA_SOURCE_URL} first when one is
     * configured, even if a workbook is already sitting on disk: an operator who reloads means
     * "get the current version from the source I configured", not "confirm the file that happens
     * to be here already". Without that, a stale local copy - including one this same reload just
     * wrote a minute ago from an admin upload - would keep winning forever and the button would
     * look like it worked while doing nothing.
     *
     * @return skills now loaded, {@code 0} if none could be, or {@code -1} if a refresh was
     *         already running and this call did nothing
     */
    public int reloadSfia() {
        int loaded = sfiaLoader.load(true);
        if (loaded < 0) {
            return loaded;
        }
        try {
            loadExtensions();
        } catch (Exception e) {
            log.warn("Could not reload the technology extension rows: {}", e.getMessage());
        }
        rebuildIndex();
        return loaded;
    }

    public void rebuildIndex() {
        List<TaxonomySkill> all = repository.findAll();
        index.set(new Bm25Index<>(all, TaxonomySkill::getSearchText));
        log.info("Taxonomy retrieval index rebuilt over {} rows", all.size());
    }

    /**
     * The taxonomy rows most relevant to {@code query}.
     *
     * <p>Returns an empty list rather than an arbitrary selection when nothing matches, because
     * the caller's next move depends on the difference: an empty shortlist means the pipeline must
     * report that it has no reference data for this area, not quietly prompt the model without it.
     */
    public List<TaxonomySkill> retrieve(String query, int limit) {
        Bm25Index<TaxonomySkill> current = index.get();
        if (current == null) {
            rebuildIndex();
            current = index.get();
        }
        List<TaxonomySkill> results = new ArrayList<>();
        current.search(query, limit).forEach(scored -> results.add(scored.item()));
        return results;
    }

    /**
     * Retrieval for a set of terms: each term gets its own shortlist and the results are merged,
     * so a profile listing eight unrelated skills does not have seven of them crowded out by
     * whichever one happens to match the taxonomy most strongly.
     */
    public List<TaxonomySkill> retrieveForTerms(List<String> terms, int perTerm, int overallLimit) {
        Map<String, TaxonomySkill> merged = new LinkedHashMap<>();
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            for (TaxonomySkill skill : retrieve(term, perTerm)) {
                merged.putIfAbsent(skill.getSource() + ":" + skill.getCode(), skill);
                if (merged.size() >= overallLimit) {
                    return new ArrayList<>(merged.values());
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** Every code currently in the taxonomy, used to reject a mapping the model invented. */
    public Set<String> knownCodes() {
        Set<String> codes = new LinkedHashSet<>();
        repository.findAll().forEach(s -> codes.add(s.getCode()));
        return codes;
    }

    /** What the taxonomy actually contains right now. Rendered verbatim in the data panel. */
    public Map<String, Object> status() {
        long sfia = repository.countBySource(TaxonomySkill.SOURCE_SFIA9);
        long extensions = repository.countBySource(TaxonomySkill.SOURCE_EXTENSION);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("sfiaLoaded", sfia > 0);
        status.put("sfiaSkillCount", sfia);
        status.put("sfiaDatasetVersion", sfiaLoader.getDatasetVersion());
        status.put("sfiaUnavailableReason", sfia > 0 ? null : sfiaLoader.getUnavailableReason());
        // How the currently loaded workbook got here - never the filesystem path it lives at.
        status.put("sfiaSourceType", sfiaLoader.getActiveSourceType());
        status.put("sfiaAutoRefreshConfigured", sfiaLoader.isSourceConfigured());
        status.put("sfiaLastRefreshAt",
                sfiaLoader.getLastSuccessAt() == null ? null : sfiaLoader.getLastSuccessAt().toString());
        status.put("sfiaLastRefreshFailed", sfiaLoader.isLastAttemptFailed());
        status.put("sfiaLastRefreshError",
                sfiaLoader.isLastAttemptFailed() ? sfiaLoader.getLastAttemptError() : null);
        // True when the most recent refresh attempt failed but an earlier successful load is
        // still what is actually being served - so the panel can say so instead of leaving the
        // failure and "SFIA loaded: yes" looking like two unrelated facts.
        status.put("sfiaUsingPreviousGoodData", sfia > 0 && sfiaLoader.isLastAttemptFailed());
        status.put("extensionSkillCount", extensions);
        // How many technology rows carry a SFIA code the loaded framework actually confirms. The
        // suggested mapping in technology-extensions.json is this project's editorial guess; a low
        // number here means most of those guesses are not real SFIA codes and the extension layer
        // is standing on its own rather than on the framework. Worth seeing rather than assuming.
        status.put("extensionsMappedToSfia", repository.findBySource(TaxonomySkill.SOURCE_EXTENSION)
                .stream().filter(s -> s.getMappedSfiaCode() != null).count());
        return status;
    }

    public boolean isSfiaLoaded() {
        return repository.countBySource(TaxonomySkill.SOURCE_SFIA9) > 0;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
