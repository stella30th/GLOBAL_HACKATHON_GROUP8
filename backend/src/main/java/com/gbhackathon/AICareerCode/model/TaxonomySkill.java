package com.gbhackathon.AICareerCode.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * One standardised skill in the reference taxonomy.
 *
 * <p>This is reference knowledge, not analysis: it describes what the industry calls a skill and
 * at which levels of responsibility it is exercised. Nothing here is a statement about any
 * particular student. The analysis that maps a student's CV onto these rows is produced by the
 * model at request time and is never stored in this table.
 *
 * <p>{@link #source} matters and is shown in the UI. SFIA 9 rows are the official framework, which
 * this project ships no copy of - an operator loads it themselves under their own licence.
 * EXTENSION rows are concrete technologies and tools that SFIA deliberately does not enumerate
 * (SFIA describes professional skills such as "Programming/software development", not "React"),
 * and they carry a {@link #mappedSfiaCode} so the relationship stays explicit rather than implied.
 */
@Entity
@Table(name = "taxonomy_skills", indexes = {
        @Index(name = "idx_taxonomy_source", columnList = "source"),
        @Index(name = "idx_taxonomy_code", columnList = "code")
})
public class TaxonomySkill {

    /** Official SFIA 9 content, loaded from a file the operator supplies. */
    public static final String SOURCE_SFIA9 = "SFIA9";
    /** Technologies and tools that are outside the SFIA framework's scope, mapped onto it. */
    public static final String SOURCE_EXTENSION = "EXTENSION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@link #SOURCE_SFIA9} or {@link #SOURCE_EXTENSION}. Never blended in the UI. */
    @Column(nullable = false, length = 32)
    private String source;

    /** SFIA skill code (for example PROG, DTAN, TEST), or a generated code for an extension row. */
    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String category;

    @Column(length = 255)
    private String subcategory;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * SFIA level descriptions as a JSON object keyed by level number, for example
     * {@code {"3":"...","4":"..."}}. Kept as text because the levels a skill defines vary by
     * skill and a fixed set of seven columns would be mostly empty.
     */
    @Column(columnDefinition = "TEXT")
    private String levelDescriptionsJson;

    private Integer minLevel;
    private Integer maxLevel;

    /** For {@link #SOURCE_EXTENSION} rows: the SFIA skill this technology is exercised under. */
    @Column(length = 64)
    private String mappedSfiaCode;

    /**
     * Lower-cased name, category and description, concatenated once at load time so retrieval does
     * not rebuild it for every query.
     */
    @Column(columnDefinition = "TEXT")
    private String searchText;

    /** Identifies the exact input file this row came from, so a reload can replace it cleanly. */
    @Column(length = 128)
    private String datasetVersion;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSubcategory() {
        return subcategory;
    }

    public void setSubcategory(String subcategory) {
        this.subcategory = subcategory;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLevelDescriptionsJson() {
        return levelDescriptionsJson;
    }

    public void setLevelDescriptionsJson(String levelDescriptionsJson) {
        this.levelDescriptionsJson = levelDescriptionsJson;
    }

    public Integer getMinLevel() {
        return minLevel;
    }

    public void setMinLevel(Integer minLevel) {
        this.minLevel = minLevel;
    }

    public Integer getMaxLevel() {
        return maxLevel;
    }

    public void setMaxLevel(Integer maxLevel) {
        this.maxLevel = maxLevel;
    }

    public String getMappedSfiaCode() {
        return mappedSfiaCode;
    }

    public void setMappedSfiaCode(String mappedSfiaCode) {
        this.mappedSfiaCode = mappedSfiaCode;
    }

    public String getSearchText() {
        return searchText;
    }

    public void setSearchText(String searchText) {
        this.searchText = searchText;
    }

    public String getDatasetVersion() {
        return datasetVersion;
    }

    public void setDatasetVersion(String datasetVersion) {
        this.datasetVersion = datasetVersion;
    }
}
