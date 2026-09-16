package com.gbhackathon.AICareerCode.service.taxonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbhackathon.AICareerCode.model.TaxonomySkill;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the SFIA 9 framework from a workbook the operator supplies, from three possible places:
 * a file already on disk (including inside the per-language subfolders the published download
 * unzips into), a private URL fetched at start-up or on a manual reload, or a one-off admin
 * upload. All three end up going through the same parse-then-commit path, so whichever one
 * supplied the workbook, the taxonomy, its search index and the "what is loaded right now"
 * status all agree.
 *
 * <p>SFIA is licensed content. It is free for personal career development and for most internal
 * use by an employer, but it is obtained by registering at sfia-online.org and accepting those
 * terms - so this repository contains no copy of it and never will. What it contains is this
 * reader, and an honest empty state for when no file has been provided.
 *
 * <p>The column layout of the published workbook is not a contract, so the reader finds its
 * columns by header text rather than by position, and reports precisely which headers it could
 * not find. A file that cannot be understood leaves the taxonomy empty and visibly unavailable;
 * it never leaves a half-parsed framework behind that looks like the real thing.
 *
 * <p>Nothing here ever writes back to the configured source. It downloads; it never uploads,
 * signs a request, or needs write credentials for anything.
 */
@Component
public class SfiaTaxonomyLoader {

    private static final Logger log = LoggerFactory.getLogger(SfiaTaxonomyLoader.class);

    private static final Pattern LEVEL_HEADER = Pattern.compile("level\\s*([1-7])");

    /** Largest workbook accepted from a fetch or an upload. The published file is a few MB. */
    private static final long MAX_DOWNLOAD_BYTES = 25L * 1024 * 1024;

    /** Redirect hops followed before giving up, so a misconfigured source cannot loop forever. */
    private static final int MAX_REDIRECTS = 5;

    /**
     * How deep under {@link #directory} a workbook may sit and still be found. 0 is the
     * directory itself; 1 also covers the per-language subfolders the SFIA download unzips into
     * ("SFIA 9 Excel - English/", "SFIA 9 Excel - Deutsch/", ...). Going any deeper is not needed
     * by that layout and would only slow discovery down.
     */
    private static final int MAX_SCAN_DEPTH = 1;

    /** Fixed name a fetched or uploaded workbook is stored under, so a later reload from either
     * source always replaces exactly the same file rather than accumulating copies. */
    private static final String FETCHED_FILE_NAME = "sfia-9-skills.xlsx";

    /**
     * Directory searched for the workbook. Anything ending in .xlsx (at the top level or one
     * level of subfolder) is considered; a deterministic ranking - never a random pick - decides
     * which one wins when more than one is present. Defaults to a gitignored folder so a
     * licensed copy cannot be committed by accident.
     */
    @Value("${taxonomy.sfia.directory:./data/sfia}")
    private String directory;

    /**
     * Where to fetch the workbook when no usable local copy exists, or always on a manual reload.
     *
     * <p>This exists because of two constraints that meet awkwardly. SFIA is licensed, so the
     * file may not be committed or baked into a public image; and the container filesystem is
     * ephemeral, so a file copied in by hand is gone at the next cold start. Fetching it from
     * private storage the operator controls satisfies both: nothing licensed lives in the
     * repository or the image, and a redeployed or recycled container has the framework back a
     * few seconds after boot. Local development typically leaves this unset and uses whatever is
     * already in the directory.
     */
    @Value("${taxonomy.sfia.source-url:}")
    private String sourceUrl;

    /** Sent as {@code Authorization: Bearer ...}, but only to the host this was configured for -
     * never to a host a redirect points at instead. Never logged. */
    @Value("${taxonomy.sfia.source-token:}")
    private String sourceToken;

    @Value("${taxonomy.sfia.download-timeout-seconds:60}")
    private int downloadTimeoutSeconds;

    private final TaxonomyStore store;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Serialises every refresh - start-up, a manual reload, or an admin upload - so two of them
     * can never interleave their temp-file and commit steps. A refresh that cannot get the lock
     * gives up immediately rather than queuing: {@link #load(boolean)} returns {@code -1} and an
     * upload throws {@link IllegalStateException}, and in both cases whatever was already loaded
     * is left exactly as it was.
     */
    private final ReentrantLock refreshLock = new ReentrantLock();

    /** Why the taxonomy is empty, when it is. Shown in diagnostics and in the UI's data panel. */
    private volatile String unavailableReason = "The SFIA 9 workbook has not been loaded yet.";
    private volatile String datasetVersion;

    /** LOCAL, REMOTE, ADMIN_UPLOAD, or REMOTE_OR_UPLOAD_CACHED (a fetched/uploaded file found on
     * disk without this attempt having fetched it itself). Null before anything ever loads. */
    private volatile String activeSourceType;

    private volatile Instant lastAttemptAt;
    private volatile Instant lastSuccessAt;
    private volatile boolean lastAttemptFailed;

    /** Sanitised - never the source URL, never the token - safe to show in an API response. */
    private volatile String lastAttemptError;

    public SfiaTaxonomyLoader(TaxonomyStore store) {
        this.store = store;
    }

    /**
     * Refreshes the taxonomy from whichever source applies.
     *
     * <p>Start-up and a manual reload ask different questions and must pass different values
     * here - that difference is the fix for "reload silently did nothing because a stale local
     * file was already there":
     * <ul>
     *   <li>{@code forceRefetch = false} (start-up): use a local workbook if one parses; fetch
     *       from the configured source only when nothing local is usable.</li>
     *   <li>{@code forceRefetch = true} (a person asked for a reload): always fetch from the
     *       configured source first when one is set, even if a local workbook already exists,
     *       because "reload" from an operator means "get the current version", not "confirm what
     *       is already here". When no source is configured this has no effect and the local
     *       workbook is used exactly as at start-up.</li>
     * </ul>
     *
     * <p>A failed fetch never throws this away: it falls back to whatever local workbook exists,
     * so a source that is temporarily unreachable does not turn a working deployment into a
     * broken one. Nothing is ever written to the taxonomy, its index, or the active workbook file
     * until a candidate has actually parsed with at least one skill in it - a bad download or a
     * bad upload leaves the previous good state exactly as it was.
     *
     * @return the number of skills now loaded (0 when nothing usable was found), or {@code -1}
     *         when another refresh was already running and this call did nothing at all
     */
    public int load(boolean forceRefetch) {
        if (!refreshLock.tryLock()) {
            log.info("Skipped a SFIA refresh: another one is already in progress.");
            return -1;
        }
        lastAttemptAt = Instant.now();
        try {
            Path folder = Paths.get(directory);
            if (!Files.isDirectory(folder) && !createDirectory(folder)) {
                recordFailure();
                return 0;
            }

            boolean sourceConfigured = sourceUrl != null && !sourceUrl.isBlank();
            List<Path> candidates = discoverCandidates(folder);

            if (sourceConfigured && (forceRefetch || candidates.isEmpty())) {
                int fetched = fetchAndApply(folder);
                if (fetched >= 0) {
                    recordSuccess();
                    return fetched;
                }
                // The fetch failed (unavailableReason already explains why): fall back to
                // whatever is on disk rather than reporting "missing" out from under a workbook
                // that was working a moment ago.
                candidates = discoverCandidates(folder);
            }

            if (candidates.isEmpty()) {
                unavailableReason = "No .xlsx file in " + folder.toAbsolutePath()
                        + (sourceConfigured
                        ? ", and the configured source did not provide one."
                        : ". Download the SFIA 9 skill descriptions workbook from sfia-online.org "
                        + "and place it there, or set SFIA_SOURCE_URL so it can be fetched.");
                log.info("{}", unavailableReason);
                recordFailure();
                return 0;
            }

            for (Path candidate : rankCandidates(candidates)) {
                try {
                    List<TaxonomySkill> skills = parse(candidate);
                    if (skills.isEmpty()) {
                        continue;
                    }
                    String sourceType = candidate.getFileName().toString().equals(FETCHED_FILE_NAME)
                            ? "REMOTE_OR_UPLOAD_CACHED" : "LOCAL";
                    commit(skills, candidate.getFileName().toString(), sourceType);
                    recordSuccess();
                    return skills.size();
                } catch (Exception e) {
                    log.warn("Could not parse {} as a SFIA workbook: {}", candidate.getFileName(), e.getMessage());
                    unavailableReason = "Could not parse " + candidate.getFileName() + ": " + e.getMessage();
                }
            }
            recordFailure();
            return 0;
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * Stores a workbook an operator uploaded through the admin endpoint.
     *
     * <p>Written to a temporary file first and validated exactly like a remote fetch before it
     * replaces anything on disk or in the taxonomy: a bad upload must not destroy a workbook that
     * was already working, and it must not touch the store or the index until parsing has
     * actually produced at least one skill. On success it lands under the same fixed name a
     * remote fetch uses, so the next reload from {@code SFIA_SOURCE_URL} - if one is configured -
     * can replace it in the ordinary way; this method itself never contacts that source.
     *
     * @throws IllegalStateException  if another refresh is already running
     * @throws IllegalArgumentException if the content is not a workbook this loader can read
     */
    public int storeUploadedWorkbook(byte[] content) throws IOException {
        if (!refreshLock.tryLock()) {
            throw new IllegalStateException("A SFIA refresh is already in progress; try again shortly.");
        }
        lastAttemptAt = Instant.now();
        Path folder = Paths.get(directory);
        Path temp = null;
        try {
            Files.createDirectories(folder);
            temp = Files.createTempFile(folder, "sfia-upload-", ".part");
            Files.write(temp, content);

            List<TaxonomySkill> skills;
            try {
                skills = parse(temp);
            } catch (Exception e) {
                unavailableReason = "The uploaded file is not a workbook this loader can read ("
                        + e.getClass().getSimpleName() + ").";
                recordFailure();
                throw new IllegalArgumentException(unavailableReason, e);
            }
            if (skills.isEmpty()) {
                unavailableReason = "The uploaded file did not contain any recognisable SFIA rows.";
                recordFailure();
                throw new IllegalArgumentException(unavailableReason);
            }

            Path destination = folder.resolve(FETCHED_FILE_NAME);
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            commit(skills, FETCHED_FILE_NAME, "ADMIN_UPLOAD");
            recordSuccess();
            log.info("Stored an uploaded SFIA workbook ({} skills)", skills.size());
            return skills.size();
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // Best-effort cleanup of a temp file; leaving it behind is harmless.
                }
            }
            refreshLock.unlock();
        }
    }

    private boolean createDirectory(Path folder) {
        try {
            Files.createDirectories(folder);
            return true;
        } catch (Exception e) {
            unavailableReason = "Could not create the SFIA data directory at "
                    + folder.toAbsolutePath() + ": " + e.getMessage();
            log.warn("{}", unavailableReason);
            return false;
        }
    }

    /**
     * Every {@code .xlsx} under {@code folder}, at the folder itself or one level of subfolder -
     * which is what the published SFIA download unzips into ("SFIA 9 Excel - English/...",
     * "SFIA 9 Excel - Deutsch/...", one per language). Earlier versions of this reader scanned
     * only the top level and silently found nothing when the workbook was one directory down.
     * Excel's own lock files ({@code ~$...}) are skipped so an open workbook does not get parsed
     * first and fail in a way that looks like a bad download.
     */
    private List<Path> discoverCandidates(Path folder) {
        List<Path> found = new ArrayList<>();
        try (var stream = Files.walk(folder, MAX_SCAN_DEPTH)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
                    .filter(p -> !p.getFileName().toString().startsWith("~$"))
                    .forEach(found::add);
        } catch (Exception e) {
            unavailableReason = "Could not list " + folder.toAbsolutePath() + ": " + e.getMessage();
            return List.of();
        }
        return found;
    }

    /**
     * Orders candidates deterministically so the same directory always yields the same choice -
     * never a pick that depends on filesystem iteration order, which is what "random between
     * language editions" actually meant in practice. A workbook sitting directly in a folder
     * named for the English edition wins outright; among ties, the full path decides.
     */
    private List<Path> rankCandidates(List<Path> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingInt(this::englishPriority)
                        .thenComparing(p -> p.toAbsolutePath().toString()))
                .toList();
    }

    private int englishPriority(Path path) {
        Path parent = path.getParent();
        String parentName = parent == null ? "" : parent.getFileName().toString().toLowerCase(Locale.ROOT);
        if (parentName.equals("sfia 9 excel - english")) {
            return 0;
        }
        if (parentName.contains("english")) {
            return 1;
        }
        return 2;
    }

    /**
     * Downloads the workbook from the configured source, validates it, and only then makes it the
     * active file.
     *
     * @return the number of skills committed on success, or {@code -1} on any failure - with
     *         {@link #unavailableReason} explaining why - leaving whatever was already on disk
     *         and already loaded completely untouched
     */
    private int fetchAndApply(Path folder) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return -1;
        }
        Path temp;
        try {
            temp = Files.createTempFile(folder, "sfia-fetch-", ".part");
        } catch (Exception e) {
            unavailableReason = "Could not create a temporary file to download into: " + e.getMessage();
            return -1;
        }
        try {
            URI start = URI.create(sourceUrl.trim());
            if (!downloadWithRedirects(start, temp)) {
                return -1;
            }
            List<TaxonomySkill> skills;
            try {
                skills = parse(temp);
            } catch (Exception e) {
                unavailableReason = "The downloaded file is not a workbook this loader can read ("
                        + e.getClass().getSimpleName() + ").";
                return -1;
            }
            if (skills.isEmpty()) {
                unavailableReason = "The downloaded file did not contain any recognisable SFIA rows.";
                return -1;
            }
            Path destination = folder.resolve(FETCHED_FILE_NAME);
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            commit(skills, FETCHED_FILE_NAME, "REMOTE");
            log.info("Fetched and applied the SFIA workbook from the configured source ({} skills)",
                    skills.size());
            return skills.size();
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // Best-effort cleanup; the next attempt overwrites it regardless.
                }
            }
        }
    }

    /**
     * Fetches {@code start} into {@code temp}, following redirects itself instead of letting
     * {@link HttpClient} do it automatically.
     *
     * <p>That manual loop is the only way to guarantee the {@code Authorization} header is never
     * resent to a host other than the one it was configured for. The built-in {@code NORMAL}
     * redirect policy has no such restriction - it resends the same headers regardless of where
     * the redirect points - and a pre-signed download link that happens to redirect to a CDN
     * would otherwise hand that CDN a bearer token it has no business seeing. The comparison is
     * on host and port together, not host alone, so two different ports on the same host are
     * treated as different destinations too.
     *
     * <p>The response is streamed into {@code temp} with a hard byte cap enforced while reading,
     * not only from a {@code Content-Length} header a source could omit or misstate, so an
     * oversized or endless response is stopped mid-transfer rather than filling the disk.
     */
    private boolean downloadWithRedirects(URI start, Path temp) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        URI current = start;
        String originalHost = start.getHost();
        int originalPort = resolvedPort(start);

        for (int attempt = 0; attempt <= MAX_REDIRECTS; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .GET()
                    .timeout(Duration.ofSeconds(downloadTimeoutSeconds))
                    .header("Accept", "application/octet-stream, "
                            + "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, */*");

            boolean sameHost = originalHost != null
                    && originalHost.equalsIgnoreCase(current.getHost())
                    && originalPort == resolvedPort(current);
            if (sameHost && sourceToken != null && !sourceToken.isBlank()) {
                builder.header("Authorization", "Bearer " + sourceToken.trim());
            }

            HttpResponse<InputStream> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (Exception e) {
                // The URL and any query-string credentials are deliberately not logged.
                unavailableReason = "Could not reach the SFIA source (" + e.getClass().getSimpleName() + ").";
                return false;
            }

            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                Optional<String> location = response.headers().firstValue("Location");
                closeQuietly(response);
                if (location.isEmpty()) {
                    unavailableReason = "The SFIA source redirected (HTTP " + status
                            + ") without a Location header.";
                    return false;
                }
                current = current.resolve(location.get());
                continue;
            }
            if (status != 200) {
                unavailableReason = "The SFIA source returned HTTP " + status + ".";
                closeQuietly(response);
                return false;
            }

            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declaredLength > MAX_DOWNLOAD_BYTES) {
                unavailableReason = "The SFIA source reported a file larger than the "
                        + (MAX_DOWNLOAD_BYTES / (1024 * 1024)) + " MB limit.";
                closeQuietly(response);
                return false;
            }

            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_DOWNLOAD_BYTES) {
                        unavailableReason = "The SFIA source sent more than the "
                                + (MAX_DOWNLOAD_BYTES / (1024 * 1024)) + " MB limit; the download was stopped.";
                        return false;
                    }
                    out.write(buffer, 0, read);
                }
                if (total == 0) {
                    unavailableReason = "The SFIA source returned an empty file.";
                    return false;
                }
                return true;
            } catch (Exception e) {
                unavailableReason = "The download did not complete (" + e.getClass().getSimpleName() + ").";
                return false;
            }
        }
        unavailableReason = "The SFIA source redirected more than " + MAX_REDIRECTS + " times.";
        return false;
    }

    private static int resolvedPort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static void closeQuietly(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (Exception ignored) {
            // The response is being discarded either way.
        }
    }

    /** Applies a successfully parsed candidate: the one place that touches the store. */
    private void commit(List<TaxonomySkill> skills, String versionLabel, String sourceType) {
        store.replaceSource(TaxonomySkill.SOURCE_SFIA9, skills);
        datasetVersion = versionLabel;
        activeSourceType = sourceType;
        unavailableReason = null;
        log.info("Loaded {} SFIA 9 skills from {} ({})", skills.size(), versionLabel, sourceType);
    }

    private void recordSuccess() {
        lastSuccessAt = Instant.now();
        lastAttemptFailed = false;
        lastAttemptError = null;
    }

    private void recordFailure() {
        lastAttemptFailed = true;
        lastAttemptError = unavailableReason;
    }

    /**
     * Reads one workbook into skill rows.
     *
     * <p>Every sheet is tried, because the published file carries the skill descriptions alongside
     * sheets for levels of responsibility and behavioural factors, and which sheet holds what has
     * moved between editions. A sheet without both a skill-name column and a code column is not
     * the skills sheet and is skipped in silence.
     *
     * <p>This is also the validator for a freshly downloaded or uploaded file: content that is not
     * a real OOXML workbook (an HTML login page, a JSON error body) fails to open at all and
     * throws here, and content that opens but has no sheet shaped like the SFIA table returns an
     * empty list. Neither case is treated as "the workbook, just odd" - both mean the candidate is
     * rejected before it ever reaches the store.
     */
    private List<TaxonomySkill> parse(Path file) throws Exception {
        List<TaxonomySkill> skills = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();

        try (InputStream in = Files.newInputStream(file);
             Workbook workbook = new XSSFWorkbook(in)) {

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                Header header = findHeader(sheet, formatter);
                if (header == null) {
                    continue;
                }

                for (int r = header.rowIndex() + 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    String code = trimmed(formatter, row, header.codeColumn());
                    String name = trimmed(formatter, row, header.nameColumn());
                    if (code.isBlank() || name.isBlank()) {
                        continue;
                    }

                    TaxonomySkill skill = new TaxonomySkill();
                    skill.setSource(TaxonomySkill.SOURCE_SFIA9);
                    skill.setCode(code.toUpperCase(Locale.ROOT));
                    skill.setName(name);
                    skill.setCategory(trimmed(formatter, row, header.categoryColumn()));
                    skill.setSubcategory(trimmed(formatter, row, header.subcategoryColumn()));
                    skill.setDescription(trimmed(formatter, row, header.descriptionColumn()));

                    Map<String, String> levels = new LinkedHashMap<>();
                    Integer min = null;
                    Integer max = null;
                    for (Map.Entry<Integer, Integer> entry : header.levelColumns().entrySet()) {
                        String text = trimmed(formatter, row, entry.getValue());
                        if (text.isBlank()) {
                            continue;
                        }
                        levels.put(String.valueOf(entry.getKey()), text);
                        min = min == null ? entry.getKey() : Math.min(min, entry.getKey());
                        max = max == null ? entry.getKey() : Math.max(max, entry.getKey());
                    }
                    skill.setLevelDescriptionsJson(levels.isEmpty() ? null : objectMapper.writeValueAsString(levels));
                    skill.setMinLevel(min);
                    skill.setMaxLevel(max);
                    skill.setDatasetVersion(file.getFileName().toString());
                    skill.setSearchText(buildSearchText(skill, levels));
                    skills.add(skill);
                }

                if (!skills.isEmpty()) {
                    return skills;
                }
            }
        }
        return skills;
    }

    private record Header(int rowIndex, int codeColumn, int nameColumn, int categoryColumn,
                          int subcategoryColumn, int descriptionColumn, Map<Integer, Integer> levelColumns) {}

    /**
     * Locates the header row by scanning the first rows for a cell that names a skill code column
     * and one that names the skill itself. The published workbook has a title block above the
     * table, so the header is not row zero.
     */
    private Header findHeader(Sheet sheet, DataFormatter formatter) {
        int lastRowToScan = Math.min(sheet.getLastRowNum(), 20);
        for (int r = 0; r <= lastRowToScan; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int code = -1;
            int name = -1;
            int category = -1;
            int subcategory = -1;
            int description = -1;
            Map<Integer, Integer> levelColumns = new LinkedHashMap<>();

            for (int c = row.getFirstCellNum(); c >= 0 && c < row.getLastCellNum(); c++) {
                String value = trimmed(formatter, row, c).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                if (value.isBlank()) {
                    continue;
                }
                if (code < 0 && (value.equals("code") || value.contains("skill code"))) {
                    code = c;
                } else if (name < 0 && (value.equals("skill") || value.contains("skill name"))) {
                    name = c;
                } else if (category < 0 && value.equals("category")) {
                    category = c;
                } else if (subcategory < 0 && value.replace("-", "").equals("subcategory")) {
                    subcategory = c;
                } else if (description < 0
                        && (value.contains("overall description") || value.contains("skill description")
                        || value.equals("description"))) {
                    description = c;
                } else {
                    Matcher matcher = LEVEL_HEADER.matcher(value);
                    if (matcher.find()) {
                        levelColumns.putIfAbsent(Integer.parseInt(matcher.group(1)), c);
                    }
                }
            }

            if (code >= 0 && name >= 0) {
                return new Header(r, code, name, category, subcategory, description, levelColumns);
            }
        }
        return null;
    }

    private String trimmed(DataFormatter formatter, Row row, int column) {
        if (column < 0) {
            return "";
        }
        Cell cell = row.getCell(column);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    /**
     * The text retrieval matches against. Level descriptions are included because that is where
     * SFIA names the concrete activities a skill involves, which is what a CV phrase resembles;
     * the one-line skill name on its own retrieves almost nothing.
     */
    private String buildSearchText(TaxonomySkill skill, Map<String, String> levels) {
        StringBuilder sb = new StringBuilder();
        sb.append(skill.getName()).append(' ').append(skill.getCode()).append(' ');
        if (skill.getCategory() != null) sb.append(skill.getCategory()).append(' ');
        if (skill.getSubcategory() != null) sb.append(skill.getSubcategory()).append(' ');
        if (skill.getDescription() != null) sb.append(skill.getDescription()).append(' ');
        levels.values().forEach(text -> sb.append(text).append(' '));
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Null when the framework is loaded; otherwise the reason it is not, in plain words. */
    public String getUnavailableReason() {
        return unavailableReason;
    }

    public String getDatasetVersion() {
        return datasetVersion;
    }

    /** Absolute path on this instance. Deliberately not exposed through any HTTP response. */
    public String getDirectory() {
        return Paths.get(directory).toAbsolutePath().toString();
    }

    /** LOCAL, REMOTE, ADMIN_UPLOAD, REMOTE_OR_UPLOAD_CACHED, or null if nothing has loaded yet. */
    public String getActiveSourceType() {
        return activeSourceType;
    }

    public boolean isSourceConfigured() {
        return sourceUrl != null && !sourceUrl.isBlank();
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public boolean isLastAttemptFailed() {
        return lastAttemptFailed;
    }

    /** Sanitised - safe to render in an API response or a log line. */
    public String getLastAttemptError() {
        return lastAttemptError;
    }
}
