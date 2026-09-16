package com.gbhackathon.AICareerCode.service.retrieval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbhackathon.AICareerCode.model.LearningResourceDoc;
import com.gbhackathon.AICareerCode.repository.LearningResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The retrieval half of RAG: it holds a real corpus, indexes it, and hands the pipeline a
 * shortlist before the model is prompted.
 *
 * <p>Asking a model to "cite your sources" is not retrieval. It produces citations that look
 * right and frequently point nowhere, and nothing in the system can tell the difference. Here the
 * model receives a numbered shortlist of rows that exist in {@code learning_resources} and may
 * cite only their {@code resourceKey}; the validator rejects any other key, so a fabricated link
 * cannot survive into a plan.
 *
 * <p>What this buys is traceability, not correctness. A resource that was retrieved and cited can
 * be followed back to the catalogue entry, and a reachable URL means the link is not dead - it
 * does not prove the content suits the student, and the UI says so rather than implying otherwise.
 */
@Service
public class ResourceRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(ResourceRetrievalService.class);

    private final LearningResourceRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${retrieval.verify-urls-on-startup:false}")
    private boolean verifyUrlsOnStartup;

    @Value("${retrieval.url-check-timeout-seconds:6}")
    private int urlCheckTimeoutSeconds;

    private final AtomicReference<Bm25Index<LearningResourceDoc>> index = new AtomicReference<>();
    private volatile String corpusVersion;

    public ResourceRetrievalService(LearningResourceRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialise() {
        try {
            loadCatalogue();
        } catch (Exception e) {
            log.warn("Could not load the learning resource catalogue: {}", e.getMessage());
        }
        rebuildIndex();
        if (verifyUrlsOnStartup) {
            // Off by default: a few hundred HEAD requests would add a minute to every cold start,
            // and on a free hosting tier that is the whole start-up budget. The endpoint on
            // PlanController runs it on demand instead.
            verifyAllUrls();
        }
    }

    /**
     * Replaces the corpus from the bundled catalogue.
     *
     * <p>Rows are matched on {@code resourceKey} and updated in place rather than deleted and
     * re-inserted, so a reachability check recorded earlier survives a catalogue edit. Keys that
     * disappear from the file are removed, because a plan may not cite a document the corpus no
     * longer contains.
     *
     * <p>The annotation applies when {@code PlanController} calls this through the proxy. On the
     * start-up path it is called from {@link #initialise()} on this same bean, so the proxy is
     * bypassed and there is no surrounding transaction - which is safe here only because
     * {@code saveAll} and {@code deleteAll} are each transactional in their own right. Anything
     * added to this method that needs the two to be atomic, or that uses a derived delete query,
     * has to move into its own bean the way {@code TaxonomyStore} did.
     */
    @Transactional
    public int loadCatalogue() throws Exception {
        ClassPathResource resource = new ClassPathResource("retrieval/learning-resources.json");
        if (!resource.exists()) {
            log.warn("No retrieval/learning-resources.json on the classpath; retrieval will return nothing "
                    + "and the pipeline will report that it has no reference sources.");
            return 0;
        }

        CatalogueFile file;
        try (InputStream in = resource.getInputStream()) {
            file = objectMapper.readValue(in, CatalogueFile.class);
        }
        List<CatalogueEntry> entries = file.resources == null ? List.of() : file.resources;
        corpusVersion = file.version;

        Map<String, LearningResourceDoc> existing = new LinkedHashMap<>();
        repository.findAll().forEach(doc -> existing.put(doc.getResourceKey(), doc));

        List<LearningResourceDoc> toSave = new ArrayList<>();
        for (CatalogueEntry entry : entries) {
            if (entry.resourceKey == null || entry.resourceKey.isBlank()
                    || entry.url == null || entry.url.isBlank()) {
                continue;
            }
            LearningResourceDoc doc = existing.remove(entry.resourceKey);
            if (doc == null) {
                doc = new LearningResourceDoc();
                doc.setResourceKey(entry.resourceKey.trim());
            } else if (!entry.url.equals(doc.getUrl())) {
                // The URL moved, so the previous reachability result describes a different page.
                doc.setUrlReachable(null);
                doc.setUrlCheckedAt(null);
            }
            doc.setTitle(entry.title);
            doc.setProvider(entry.provider);
            doc.setUrl(entry.url.trim());
            doc.setType(entry.type);
            doc.setLevel(entry.level);
            doc.setSkillTags(entry.skillTags);
            doc.setSfiaCodes(entry.sfiaCodes);
            doc.setSummary(entry.summary);
            doc.setCost(entry.cost);
            doc.setApproxHours(entry.approxHours);
            doc.setLanguage(entry.language == null ? "en" : entry.language);
            doc.setDatasetVersion(file.version);
            doc.setSearchText((entry.title + " " + entry.provider + " " + orEmpty(entry.skillTags) + " "
                    + orEmpty(entry.summary) + " " + orEmpty(entry.type) + " " + orEmpty(entry.level))
                    .toLowerCase(Locale.ROOT));
            toSave.add(doc);
        }

        repository.saveAll(toSave);
        if (!existing.isEmpty()) {
            log.info("Removing {} learning resources that are no longer in the catalogue", existing.size());
            repository.deleteAll(existing.values());
        }
        log.info("Learning resource catalogue loaded: {} documents (version {})", toSave.size(), file.version);
        return toSave.size();
    }

    // The catalogue carries a "_comment" field explaining what the file is for. Tolerating unknown
    // fields keeps that possible, and keeps a future field from breaking every existing deployment.
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CatalogueFile {
        public String version;
        public List<CatalogueEntry> resources;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CatalogueEntry {
        public String resourceKey;
        public String title;
        public String provider;
        public String url;
        public String type;
        public String level;
        public String skillTags;
        public String sfiaCodes;
        public String summary;
        public String cost;
        public Integer approxHours;
        public String language;
    }

    public void rebuildIndex() {
        List<LearningResourceDoc> all = repository.findAll();
        index.set(new Bm25Index<>(all, LearningResourceDoc::getSearchText));
        log.info("Retrieval index rebuilt over {} learning resources", all.size());
    }

    /** The documents most relevant to {@code query}, most relevant first. May be empty. */
    public List<LearningResourceDoc> retrieve(String query, int limit) {
        Bm25Index<LearningResourceDoc> current = index.get();
        if (current == null) {
            rebuildIndex();
            current = index.get();
        }
        List<LearningResourceDoc> results = new ArrayList<>();
        current.search(query, limit).forEach(scored -> results.add(scored.item()));
        return results;
    }

    /**
     * Retrieval for a list of skills, merged.
     *
     * <p>One query per skill rather than one query built from all of them: a single concatenated
     * query is dominated by whichever skill has the most distinctive vocabulary, and the student's
     * other five gaps come back with no sources at all.
     */
    public List<LearningResourceDoc> retrieveForSkills(List<String> skills, int perSkill, int overallLimit) {
        Map<String, LearningResourceDoc> merged = new LinkedHashMap<>();
        for (String skill : skills) {
            if (skill == null || skill.isBlank()) {
                continue;
            }
            for (LearningResourceDoc doc : retrieve(skill, perSkill)) {
                merged.putIfAbsent(doc.getResourceKey(), doc);
            }
            if (merged.size() >= overallLimit) {
                break;
            }
        }
        List<LearningResourceDoc> results = new ArrayList<>(merged.values());
        return results.size() > overallLimit ? results.subList(0, overallLimit) : results;
    }

    public Optional<LearningResourceDoc> findByKey(String resourceKey) {
        return repository.findByResourceKey(resourceKey);
    }

    public List<LearningResourceDoc> findByKeys(List<String> resourceKeys) {
        return resourceKeys.isEmpty() ? List.of() : repository.findByResourceKeyIn(resourceKeys);
    }

    /**
     * Checks that each catalogue URL still answers.
     *
     * <p>Three outcomes, not two. A 2xx means the page exists. A 4xx that is not a bot-block means
     * it does not. A 403 means a bot filter turned us away, which says nothing either way - several
     * perfectly live sites in this catalogue (LeetCode, Exercism, Real Python) answer 403 to any
     * non-browser client. Recording that as "unreachable" would put a warning next to a working
     * link, which is a worse error than saying nothing, so it is recorded as unknown.
     */
    @Transactional
    public Map<String, Object> verifyAllUrls() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(urlCheckTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        List<LearningResourceDoc> all = repository.findAll();
        int reachable = 0;
        int unreachable = 0;
        int inconclusive = 0;
        List<String> failures = new ArrayList<>();
        List<String> blocked = new ArrayList<>();

        for (LearningResourceDoc doc : all) {
            Boolean outcome = checkUrl(client, doc.getUrl());
            doc.setUrlReachable(outcome);
            doc.setUrlCheckedAt(LocalDateTime.now());
            if (Boolean.TRUE.equals(outcome)) {
                reachable++;
            } else if (Boolean.FALSE.equals(outcome)) {
                unreachable++;
                failures.add(doc.getResourceKey());
            } else {
                inconclusive++;
                blocked.add(doc.getResourceKey());
            }
        }
        repository.saveAll(all);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", all.size());
        result.put("reachable", reachable);
        result.put("unreachable", unreachable);
        result.put("unreachableKeys", failures);
        result.put("inconclusive", inconclusive);
        result.put("inconclusiveKeys", blocked);
        result.put("note", "A reachable URL means the link resolves. It does not verify that the "
                + "content is current, suitable for a given level, or still free. 'Inconclusive' "
                + "means the host refused an automated request (403); the page may well be fine.");
        log.info("URL verification finished: {} reachable, {} unreachable, {} inconclusive, of {}",
                reachable, unreachable, inconclusive, all.size());
        return result;
    }

    /** {@code true} reachable, {@code false} gone, {@code null} the host refused an automated request. */
    private Boolean checkUrl(HttpClient client, String url) {
        try {
            HttpRequest head = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(urlCheckTimeoutSeconds))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .build();
            HttpResponse<Void> response = client.send(head, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 400) {
                return true;
            }
            // Any failing HEAD falls through to the GET below. A HEAD is a shortcut, not evidence:
            // several hosts in this catalogue answer 404 to HEAD and 200 to GET for the same page,
            // and trusting the HEAD would put "this link is dead" next to a working link.
        } catch (Exception ignored) {
            // Fall through to the GET attempt: some hosts reject HEAD at the connection level.
        }
        try {
            HttpRequest get = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(urlCheckTimeoutSeconds))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .header("Range", "bytes=0-1024")
                    .build();
            HttpResponse<Void> response = client.send(get, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 400) {
                return true;
            }
            // A bot filter says nothing about whether the page exists, so neither do we.
            return response.statusCode() == 403 ? null : false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * A browser user agent rather than a bot string.
     *
     * <p>Not an attempt to hide: the check is one range-limited request per catalogue entry, run
     * manually. It is that a "LearningPathBot" string is turned away by several hosts in this
     * catalogue that serve the page perfectly well to a person, which turns a link check into
     * noise.
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; SkillPath link check; +catalogue verification)";

    /** What the corpus contains, for the data-provenance panel. */
    public Map<String, Object> status() {
        long total = repository.count();
        long verified = repository.findAll().stream()
                .filter(d -> Boolean.TRUE.equals(d.getUrlReachable())).count();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("documentCount", total);
        status.put("corpusVersion", corpusVersion);
        status.put("urlsVerifiedReachable", verified);
        return status;
    }

    public String getCorpusVersion() {
        return corpusVersion;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
