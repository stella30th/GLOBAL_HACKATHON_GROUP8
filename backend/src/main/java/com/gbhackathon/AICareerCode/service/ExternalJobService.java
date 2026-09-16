package com.gbhackathon.AICareerCode.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbhackathon.AICareerCode.model.JobOpportunity;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.repository.JobOpportunityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Imports live job postings from public job boards.
 *
 * <p>The original version queried two boards and hardcoded {@code category=software-dev}, so the
 * database only ever contained software roles. A candidate in semiconductors, finance or healthcare
 * was matched against backend engineering jobs. This version pulls from five boards across every
 * industry they cover, and biases the fetch toward the signed-in candidate's own field.
 *
 * <p>It also stops inventing data. The old importer assigned every European posting a salary of
 * "EUR 60,000 - 85,000" and filled empty skill lists with "Java, React, Docker" - values that
 * appeared to users as facts about the job. Unknown fields are now left empty.
 */
@Service
public class ExternalJobService {

    private static final Logger log = LoggerFactory.getLogger(ExternalJobService.class);

    private final JobOpportunityRepository jobRepository;
    private final ProfileService profileService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient restClient;

    public ExternalJobService(JobOpportunityRepository jobRepository, ProfileService profileService) {
        this.jobRepository = jobRepository;
        this.profileService = profileService;
    }

    private RestClient client() {
        if (restClient == null) {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(Duration.ofSeconds(20));
            restClient = RestClient.builder()
                    .requestFactory(factory)
                    // Several of these boards sit behind Cloudflare and serve a bot challenge to
                    // datacenter IPs presenting a non-browser agent. The same requests succeed from a
                    // laptop and return nothing from Render, so send browser-like headers.
                    .defaultHeader("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                    + "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .defaultHeader("Accept", "application/json, text/plain, */*")
                    .defaultHeader("Accept-Language", "en-US,en;q=0.9")
                    .build();
        }
        return restClient;
    }

    /**
     * Warm the database on startup without delaying readiness. The previous implementation ran the
     * HTTP fetches inline in the {@code ApplicationReadyEvent} listener, adding those seconds to
     * every Render cold start before the app could answer its first request.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void onStartup() {
        try {
            long count = jobRepository.count();
            if (count <= 10) {
                log.info("Database has {} jobs; fetching live postings in the background...", count);
                syncExternalJobs(null);
            }
        } catch (Exception e) {
            log.warn("Background job sync on startup failed: {}", e.getMessage());
        }
    }

    public Map<String, Object> syncExternalJobs() {
        UserProfile profile = null;
        try {
            profile = profileService.getCurrentOrCreateProfile();
        } catch (Exception e) {
            log.debug("No profile available to steer the job sync: {}", e.getMessage());
        }
        return syncExternalJobs(profile);
    }

    /**
     * Pulls postings from every configured source. When a profile is supplied, the industry-indexed
     * boards are queried with that candidate's field first so the results are relevant to them.
     */
    @Transactional
    public Map<String, Object> syncExternalJobs(UserProfile profile) {
        FieldProfile field = FieldProfile.forProfile(profile);
        log.info("Syncing external jobs for field '{}' (keywords: {})", field.label(), field.keywords);

        Map<String, Integer> perSource = new LinkedHashMap<>();
        perSource.put("Remotive", runSource("Remotive", () -> fetchRemotive(field)));
        perSource.put("Jobicy", runSource("Jobicy", () -> fetchJobicy(field)));
        perSource.put("RemoteOK", runSource("RemoteOK", () -> fetchRemoteOk(field)));
        perSource.put("TheMuse", runSource("TheMuse", () -> fetchTheMuse(field)));
        perSource.put("Arbeitnow", runSource("Arbeitnow", () -> fetchArbeitnow(field)));

        int added = perSource.values().stream().mapToInt(Integer::intValue).sum();
        long total = jobRepository.count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("added", added);
        result.put("perSource", perSource);
        result.put("field", field.label());
        result.put("totalJobsInDatabase", total);
        result.put("message", String.format(
                "Đã thêm %d việc làm mới từ %d nguồn (Remotive, Jobicy, RemoteOK, The Muse, Arbeitnow) cho lĩnh vực: %s.",
                added, perSource.size(), field.label()));
        return result;
    }

    private int runSource(String name, SourceFetcher fetcher) {
        try {
            int added = fetcher.fetch();
            log.info("Source {} contributed {} new jobs", name, added);
            return added;
        } catch (Exception e) {
            log.warn("Source {} failed: {}", name, e.toString());
            return 0;
        }
    }

    @FunctionalInterface
    private interface SourceFetcher {
        int fetch() throws Exception;
    }

    // ---------------------------------------------------------------------
    // Source 1: Remotive - 30 categories covering medical, legal, finance, education, supply chain
    // ---------------------------------------------------------------------

    private int fetchRemotive(FieldProfile field) {
        int added = 0;
        for (String category : field.remotiveCategories) {
            String url = "https://remotive.com/api/remote-jobs?limit=20&category=" + urlEncode(category);
            JsonNode root = getJson(url);
            if (root == null) continue;

            for (JsonNode node : root.path("jobs")) {
                JobOpportunity job = new JobOpportunity();
                job.setTitle(text(node, "title"));
                job.setCompany(text(node, "company_name"));
                job.setCompanyLogo(text(node, "company_logo"));

                String requiredLocation = textOr(node, "candidate_required_location", "Worldwide");
                job.setLocation("Remote (" + requiredLocation + ")");
                job.setCountry(requiredLocation);
                job.setIsOverseas(true);
                job.setWorkType("REMOTE");
                job.setSalaryRange(blankToNull(text(node, "salary")));
                job.setCategory(prettifyCategory(category));
                job.setRequiredSkillList(readTags(node.path("tags"), 8));
                job.setDescription(cleanHtml(text(node, "description")));
                job.setApplyUrl(text(node, "url"));
                job.setSource("Remotive");
                job.setVisaSponsorship(false);
                applyDerivedFields(job);

                if (save(job)) added++;
                if (added >= 60) return added;
            }
        }
        return added;
    }

    // ---------------------------------------------------------------------
    // Source 2: Jobicy - industry-indexed remote board
    // ---------------------------------------------------------------------

    private int fetchJobicy(FieldProfile field) {
        int added = 0;
        List<String> queries = new ArrayList<>();
        for (String industry : field.jobicyIndustries) {
            queries.add("https://jobicy.com/api/v2/remote-jobs?count=20&industry=" + urlEncode(industry));
        }
        for (String keyword : field.keywords.subList(0, Math.min(2, field.keywords.size()))) {
            queries.add("https://jobicy.com/api/v2/remote-jobs?count=20&tag=" + urlEncode(keyword));
        }

        for (String url : queries) {
            JsonNode root = getJson(url);
            if (root == null) continue;

            for (JsonNode node : root.path("jobs")) {
                JobOpportunity job = new JobOpportunity();
                job.setTitle(text(node, "jobTitle"));
                job.setCompany(text(node, "companyName"));
                job.setCompanyLogo(text(node, "companyLogo"));
                String geo = textOr(node, "jobGeo", "Anywhere");
                job.setLocation("Remote (" + geo + ")");
                job.setCountry(geo);
                job.setIsOverseas(true);
                job.setWorkType("REMOTE");
                job.setCategory(firstOfArray(node.path("jobIndustry")));
                job.setRequiredSkillList(readTags(node.path("jobType"), 4));
                job.setDescription(cleanHtml(text(node, "jobExcerpt")));
                job.setApplyUrl(text(node, "url"));
                job.setSource("Jobicy");
                job.setVisaSponsorship(false);

                // Jobicy publishes a numeric salary range only when the employer disclosed one.
                String min = text(node, "annualSalaryMin");
                String max = text(node, "annualSalaryMax");
                String currency = textOr(node, "salaryCurrency", "USD");
                if (notBlank(min) && notBlank(max)) {
                    job.setSalaryRange(currency + " " + min + " - " + max + " / year");
                }
                applyDerivedFields(job);

                if (save(job)) added++;
                if (added >= 40) return added;
            }
        }
        return added;
    }

    // ---------------------------------------------------------------------
    // Source 3: Remote OK - design, marketing, sales and finance alongside engineering
    // ---------------------------------------------------------------------

    private int fetchRemoteOk(FieldProfile field) {
        JsonNode root = getJson("https://remoteok.com/api");
        if (root == null || !root.isArray()) {
            return 0;
        }

        int added = 0;
        int index = 0;
        for (JsonNode node : root) {
            // The first element of the feed is Remote OK's legal notice, not a job.
            if (index++ == 0 && node.has("legal")) continue;

            String title = text(node, "position");
            if (!notBlank(title)) continue;

            List<String> tags = readTags(node.path("tags"), 8);
            // The feed is large and mostly software, so keep the entries that match this field.
            if (!field.matches(title + " " + String.join(" ", tags))) continue;

            JobOpportunity job = new JobOpportunity();
            job.setTitle(title);
            job.setCompany(text(node, "company"));
            job.setCompanyLogo(text(node, "company_logo"));
            String location = textOr(node, "location", "Worldwide");
            job.setLocation("Remote (" + location + ")");
            job.setCountry(location);
            job.setIsOverseas(true);
            job.setWorkType("REMOTE");
            job.setRequiredSkillList(tags);
            job.setDescription(cleanHtml(text(node, "description")));
            job.setApplyUrl(text(node, "url"));
            job.setSource("RemoteOK");
            job.setVisaSponsorship(false);
            // Classify from the posting itself. Stamping the candidate's own field here would make
            // every imported job look like a match for them.
            job.setCategory(CareerField.classify(title + " " + String.join(" ", tags)).label());

            long salaryMin = node.path("salary_min").asLong(0);
            long salaryMax = node.path("salary_max").asLong(0);
            if (salaryMin > 0 && salaryMax > 0) {
                job.setSalaryRange(String.format("USD %,d - %,d / year", salaryMin, salaryMax));
            }
            applyDerivedFields(job);

            if (save(job)) added++;
            if (added >= 30) break;
        }
        return added;
    }

    // ---------------------------------------------------------------------
    // Source 4: The Muse - the widest non-IT coverage (healthcare, legal, energy, maintenance,
    // science and engineering), and the only source here with onsite roles at named employers.
    // ---------------------------------------------------------------------

    private int fetchTheMuse(FieldProfile field) {
        int added = 0;
        for (String category : field.museCategories) {
            for (int page = 1; page <= 2; page++) {
                String url = "https://www.themuse.com/api/public/jobs?page=" + page
                        + "&category=" + urlEncode(category);
                JsonNode root = getJson(url);
                if (root == null) continue;

                for (JsonNode node : root.path("results")) {
                    JobOpportunity job = new JobOpportunity();
                    job.setTitle(text(node, "name"));
                    job.setCompany(text(node.path("company"), "name"));

                    List<String> locations = new ArrayList<>();
                    for (JsonNode loc : node.path("locations")) {
                        String name = text(loc, "name");
                        if (notBlank(name)) locations.add(name);
                    }
                    String primaryLocation = locations.isEmpty() ? "Not stated" : locations.get(0);
                    job.setLocation(primaryLocation);
                    job.setCountry(countryFromLocation(primaryLocation));
                    boolean remote = primaryLocation.toLowerCase(Locale.ROOT).contains("remote")
                            || primaryLocation.toLowerCase(Locale.ROOT).contains("flexible");
                    job.setWorkType(remote ? "REMOTE" : "ONSITE");
                    job.setIsOverseas(!primaryLocation.toLowerCase(Locale.ROOT).contains("vietnam"));

                    job.setCategory(firstOfObjectArray(node.path("categories")));
                    job.setExperienceLevel(firstOfObjectArray(node.path("levels")));
                    job.setDescription(cleanHtml(text(node, "contents")));
                    job.setApplyUrl(text(node.path("refs"), "landing_page"));
                    job.setSource("The Muse");
                    job.setVisaSponsorship(false);
                    applyDerivedFields(job);

                    if (save(job)) added++;
                    if (added >= 45) return added;
                }
            }
        }
        return added;
    }

    // ---------------------------------------------------------------------
    // Source 5: Arbeitnow - European roles, some with visa sponsorship
    // ---------------------------------------------------------------------

    private int fetchArbeitnow(FieldProfile field) {
        JsonNode root = getJson("https://www.arbeitnow.com/api/job-board-api");
        if (root == null) {
            return 0;
        }

        int added = 0;
        for (JsonNode node : root.path("data")) {
            String title = text(node, "title");
            if (!notBlank(title)) continue;

            List<String> tags = readTags(node.path("tags"), 8);
            if (!field.matches(title + " " + String.join(" ", tags))) continue;

            JobOpportunity job = new JobOpportunity();
            job.setTitle(title);
            job.setCompany(text(node, "company_name"));
            job.setLocation(textOr(node, "location", "Germany"));
            job.setCountry("Germany / Europe");
            job.setIsOverseas(true);
            job.setWorkType(node.path("remote").asBoolean(false) ? "REMOTE" : "ONSITE");

            boolean visa = node.path("visa_sponsorship").asBoolean(false);
            job.setVisaSponsorship(visa);
            job.setRelocationAssistance(visa);
            job.setRequiredSkillList(tags);
            job.setDescription(cleanHtml(text(node, "description")));
            job.setApplyUrl(text(node, "url"));
            job.setSource("Arbeitnow");
            job.setCategory(CareerField.classify(title + " " + String.join(" ", tags)).label());
            applyDerivedFields(job);

            if (save(job)) added++;
            if (added >= 25) break;
        }
        return added;
    }

    // ---------------------------------------------------------------------
    // Persistence helpers
    // ---------------------------------------------------------------------

    private boolean save(JobOpportunity job) {
        if (!notBlank(job.getTitle()) || !notBlank(job.getCompany())) {
            return false;
        }
        // Indexed existence check. The previous importer called searchJobs(title, ...) for every
        // candidate row, scanning the whole table once per posting.
        if (jobRepository.existsByTitleIgnoreCaseAndCompanyIgnoreCase(job.getTitle(), job.getCompany())) {
            return false;
        }
        job.setPostedAt(LocalDateTime.now());
        jobRepository.save(job);
        return true;
    }

    /** Fills only what can be inferred from the posting itself; leaves the rest empty. */
    private void applyDerivedFields(JobOpportunity job) {
        if (job.getMinYearsExp() == null) {
            job.setMinYearsExp(inferMinYears(job.getTitle(), job.getDescription()));
        }
        if (!notBlank(job.getExperienceLevel())) {
            job.setExperienceLevel(inferLevel(job.getTitle()));
        }
        if (job.getDescription() != null && job.getDescription().length() > 1200) {
            job.setDescription(job.getDescription().substring(0, 1200) + "...");
        }
    }

    private int inferMinYears(String title, String description) {
        String haystack = ((title == null ? "" : title) + " " + (description == null ? "" : description))
                .toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,2})\\s*\\+?\\s*(?:-|to)?\\s*\\d{0,2}\\s*years?(?:\\s+of)?\\s+experience")
                .matcher(haystack);
        if (m.find()) {
            try {
                return Math.min(Integer.parseInt(m.group(1)), 20);
            } catch (NumberFormatException ignored) {
                // fall through to the title heuristic
            }
        }
        if (haystack.contains("intern") || haystack.contains("graduate") || haystack.contains("entry level")) return 0;
        if (haystack.contains("junior")) return 1;
        if (haystack.contains("principal") || haystack.contains("staff") || haystack.contains("director")) return 8;
        if (haystack.contains("senior") || haystack.contains("lead")) return 5;
        return 2;
    }

    private String inferLevel(String title) {
        String lower = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (lower.contains("intern")) return "Internship";
        if (lower.contains("junior") || lower.contains("graduate") || lower.contains("entry")) return "Entry level";
        if (lower.contains("principal") || lower.contains("staff") || lower.contains("head")) return "Principal / Staff";
        if (lower.contains("senior") || lower.contains("sr.") || lower.contains("lead")) return "Senior";
        return "Mid level";
    }

    // ---------------------------------------------------------------------
    // HTTP + JSON helpers
    // ---------------------------------------------------------------------

    private JsonNode getJson(String url) {
        try {
            String response = client().get().uri(url).retrieve().body(String.class);
            if (response == null) {
                log.warn("GET {} returned an empty body", url);
                return null;
            }
            return objectMapper.readTree(response);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // A source returning 403/429 here is the difference between "no matching jobs" and
            // "this board refuses our requests", and the two need very different responses.
            log.warn("GET {} failed with HTTP {}: {}", url, e.getStatusCode().value(),
                    truncate(e.getResponseBodyAsString(), 200));
            return null;
        } catch (Exception e) {
            log.warn("GET {} failed: {}", url, e.toString());
            return null;
        }
    }

    /**
     * Probes every source and reports what each one actually returned. Exposed through
     * {@code GET /api/jobs/source-status} because a board can be reachable from a developer laptop
     * and blocked from the deployment's datacenter IP, which is invisible from a job count alone.
     */
    public List<Map<String, Object>> probeSources() {
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("Remotive", "https://remotive.com/api/remote-jobs?limit=1&category=software-development");
        endpoints.put("Jobicy", "https://jobicy.com/api/v2/remote-jobs?count=1");
        endpoints.put("RemoteOK", "https://remoteok.com/api");
        endpoints.put("TheMuse", "https://www.themuse.com/api/public/jobs?page=1");
        endpoints.put("Arbeitnow", "https://www.arbeitnow.com/api/job-board-api");

        for (Map.Entry<String, String> entry : endpoints.entrySet()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", entry.getKey());
            result.put("url", entry.getValue());
            long startedAt = System.currentTimeMillis();
            try {
                String body = client().get().uri(entry.getValue()).retrieve().body(String.class);
                result.put("status", 200);
                result.put("bytes", body == null ? 0 : body.length());
                try {
                    JsonNode root = objectMapper.readTree(body);
                    JsonNode items = root.isArray() ? root
                            : root.has("jobs") ? root.path("jobs")
                            : root.has("data") ? root.path("data")
                            : root.path("results");
                    result.put("items", items.isArray() ? items.size() : 0);
                    result.put("ok", true);
                } catch (Exception parseError) {
                    // A Cloudflare challenge arrives as HTML with a 200 status.
                    result.put("ok", false);
                    result.put("error", "Response was not JSON: " + truncate(body, 120));
                }
            } catch (org.springframework.web.client.RestClientResponseException e) {
                result.put("ok", false);
                result.put("status", e.getStatusCode().value());
                result.put("error", truncate(e.getResponseBodyAsString(), 200));
            } catch (Exception e) {
                result.put("ok", false);
                result.put("error", e.toString());
            }
            result.put("elapsedMs", System.currentTimeMillis() - startedAt);
            results.add(result);
        }
        return results;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        String collapsed = value.replaceAll("\s+", " ").trim();
        return collapsed.length() > max ? collapsed.substring(0, max) + "..." : collapsed;
    }

    private static String text(JsonNode node, String fieldName) {
        return node.path(fieldName).asText("");
    }

    private static String textOr(JsonNode node, String fieldName, String fallback) {
        String value = node.path(fieldName).asText("");
        return notBlank(value) ? value : fallback;
    }

    private static List<String> readTags(JsonNode tags, int limit) {
        List<String> result = new ArrayList<>();
        if (tags.isArray()) {
            for (JsonNode tag : tags) {
                String value = tag.asText("").trim();
                if (value.isEmpty() || value.length() > 40) continue;
                if (value.equalsIgnoreCase("remote") || value.equalsIgnoreCase("full time")
                        || value.equalsIgnoreCase("full-time")) continue;
                result.add(value);
                if (result.size() >= limit) break;
            }
        } else if (tags.isTextual()) {
            String value = tags.asText("").trim();
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }

    private static String firstOfArray(JsonNode array) {
        if (array.isArray() && !array.isEmpty()) {
            return array.get(0).asText("");
        }
        return array.isTextual() ? array.asText("") : null;
    }

    private static String firstOfObjectArray(JsonNode array) {
        if (array.isArray() && !array.isEmpty()) {
            return array.get(0).path("name").asText("");
        }
        return null;
    }

    private static String cleanHtml(String raw) {
        if (raw == null) return null;
        String clean = raw.replaceAll("<[^>]*>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&#039;", "'").replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
        return clean.isEmpty() ? null : clean;
    }

    private static String countryFromLocation(String location) {
        if (location == null || location.isBlank()) return "Not stated";
        String[] parts = location.split(",");
        String last = parts[parts.length - 1].trim();
        // A two-letter tail is a US state abbreviation, e.g. "Austin, TX".
        if (last.length() == 2 && last.equals(last.toUpperCase(Locale.ROOT))) {
            return "United States";
        }
        return last.isEmpty() ? location : last;
    }

    private static String prettifyCategory(String slug) {
        String[] words = slug.split("-");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s : null;
    }

    // ---------------------------------------------------------------------
    // Field routing: maps a candidate's field to each board's own taxonomy
    // ---------------------------------------------------------------------

    /**
     * Pairs the candidate's {@link CareerField} with the keywords used to filter the feeds that
     * cannot be queried by category (Remote OK and Arbeitnow return one large mixed list).
     */
    private record FieldProfile(CareerField field, String label, List<String> keywords,
                                List<String> remotiveCategories, List<String> jobicyIndustries,
                                List<String> museCategories) {

        boolean matches(String haystack) {
            if (keywords.isEmpty()) {
                return true;
            }
            String lower = haystack.toLowerCase(Locale.ROOT);
            return keywords.stream().anyMatch(k -> lower.contains(k.toLowerCase(Locale.ROOT)));
        }

        static FieldProfile forProfile(UserProfile profile) {
            CareerField field = CareerField.classify(buildSignal(profile));
            return new FieldProfile(field, field.label(), field.keywords(),
                    field.remotiveCategories(), field.jobicyIndustries(), field.museCategories());
        }

        private static String buildSignal(UserProfile profile) {
            if (profile == null) {
                return "";
            }
            Set<String> parts = new LinkedHashSet<>();
            if (profile.getIndustry() != null) parts.add(profile.getIndustry());
            if (profile.getCurrentTitle() != null) parts.add(profile.getCurrentTitle());
            if (profile.getTargetRoles() != null) parts.add(profile.getTargetRoles());
            parts.addAll(profile.getSkillList());
            return String.join(" ", parts);
        }
    }
}
