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
     * Runs after {@link SfiaTaxonomyLoader}, which is ordered ahead of this by depending on the
     * same event and being constructed first. Extensions are loaded regardless of whether SFIA is
     * present, so a project without a SFIA licence still retrieves technology rows - and the UI
     * still says plainly that the SFIA layer is missing.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initialise() {
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
     * Re-reads the SFIA workbook from disk. Exposed so an operator can drop the licensed file in
     * and have it picked up without a restart, which on a free hosting tier costs a cold boot.
     */
    public int reloadSfia() {
        int loaded = sfiaLoader.load();
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
        status.put("sfiaExpectedDirectory", sfiaLoader.getDirectory());
        status.put("extensionSkillCount", extensions);
        return status;
    }

    public boolean isSfiaLoaded() {
        return repository.countBySource(TaxonomySkill.SOURCE_SFIA9) > 0;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
