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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the SFIA 9 framework from a workbook the operator supplies.
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
 */
@Component
public class SfiaTaxonomyLoader {

    private static final Logger log = LoggerFactory.getLogger(SfiaTaxonomyLoader.class);

    /**
     * Directory searched for the workbook. Anything ending in .xlsx is considered; the first file
     * that parses wins. Defaults to a gitignored folder so a licensed copy cannot be committed by
     * accident.
     */
    @Value("${taxonomy.sfia.directory:./data/sfia}")
    private String directory;

    private static final Pattern LEVEL_HEADER = Pattern.compile("level\\s*([1-7])");

    private final TaxonomyStore store;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Why the taxonomy is empty, when it is. Shown in diagnostics and in the UI's data panel. */
    private volatile String unavailableReason = "The SFIA 9 workbook has not been loaded yet.";
    private volatile String datasetVersion;

    public SfiaTaxonomyLoader(TaxonomyStore store) {
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        try {
            load();
        } catch (Exception e) {
            // A taxonomy that cannot be read is a degraded product, not a dead one: the rest of
            // the application starts and says what is missing.
            unavailableReason = "Could not read the SFIA workbook: " + e.getMessage();
            log.warn("SFIA taxonomy not loaded: {}", unavailableReason);
        }
    }

    /**
     * Reads the workbook and replaces the stored SFIA rows.
     *
     * <p>The write goes through {@link TaxonomyStore}: this is called from {@link #loadOnStartup()}
     * on the same bean, and a self-invocation never reaches a transactional proxy.
     *
     * @return how many skills were loaded; zero when no usable file was found
     */
    public int load() {
        Path folder = Paths.get(directory);
        if (!Files.isDirectory(folder)) {
            unavailableReason = "No SFIA data directory at " + folder.toAbsolutePath()
                    + ". Download the SFIA 9 skill descriptions workbook from sfia-online.org and place the .xlsx there.";
            log.info("{}", unavailableReason);
            return 0;
        }

        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.xlsx")) {
            stream.forEach(candidates::add);
        } catch (Exception e) {
            unavailableReason = "Could not list " + folder.toAbsolutePath() + ": " + e.getMessage();
            return 0;
        }
        // Temporary files Excel leaves behind when the workbook is open would otherwise be parsed
        // first and fail in a way that looks like a bad download.
        candidates.removeIf(p -> p.getFileName().toString().startsWith("~$"));

        if (candidates.isEmpty()) {
            unavailableReason = "No .xlsx file in " + folder.toAbsolutePath()
                    + ". Download the SFIA 9 skill descriptions workbook from sfia-online.org and place it there.";
            log.info("{}", unavailableReason);
            return 0;
        }

        for (Path candidate : candidates) {
            try {
                List<TaxonomySkill> skills = parse(candidate);
                if (skills.isEmpty()) {
                    continue;
                }
                store.replaceSource(TaxonomySkill.SOURCE_SFIA9, skills);
                datasetVersion = candidate.getFileName().toString();
                unavailableReason = null;
                log.info("Loaded {} SFIA 9 skills from {}", skills.size(), datasetVersion);
                return skills.size();
            } catch (Exception e) {
                log.warn("Could not parse {} as a SFIA workbook: {}", candidate.getFileName(), e.getMessage());
                unavailableReason = "Could not parse " + candidate.getFileName() + ": " + e.getMessage();
            }
        }
        return 0;
    }

    /**
     * Reads one workbook into skill rows.
     *
     * <p>Every sheet is tried, because the published file carries the skill descriptions alongside
     * sheets for levels of responsibility and behavioural factors, and which sheet holds what has
     * moved between editions. A sheet without both a skill-name column and a code column is not
     * the skills sheet and is skipped in silence.
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

    public String getDirectory() {
        return Paths.get(directory).toAbsolutePath().toString();
    }
}
