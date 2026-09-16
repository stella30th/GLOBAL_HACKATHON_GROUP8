package com.gbhackathon.AICareerCode.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One document in the retrieval corpus: a course, an official documentation set, a university
 * module, a book, a practice platform.
 *
 * <p>This table is what makes retrieval real rather than a polite request to the model. The model
 * is never asked to remember a URL; it is handed a shortlist retrieved from these rows and may
 * cite only what it was handed. A citation to anything else fails validation and the plan is
 * regenerated.
 *
 * <p>Each row is a fact about a public resource, curated in advance. That is input knowledge, not
 * a conclusion about a student: which of these appears in a plan, in which phase, and why, is
 * decided by the model from the student's own gaps.
 */
@Entity
@Table(name = "learning_resources", indexes = {
        @Index(name = "idx_resource_key", columnList = "resourceKey", unique = true)
})
public class LearningResourceDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Stable identifier used in citations, for example {@code mdn-javascript}. The model cites
     * this, not a URL, so a hallucinated link cannot reach the page: an unknown key is rejected.
     */
    @Column(nullable = false, length = 128, unique = true)
    private String resourceKey;

    @Column(nullable = false, length = 512)
    private String title;

    /** The organisation behind it: MDN, MIT OpenCourseWare, PostgreSQL Global Development Group. */
    @Column(nullable = false, length = 255)
    private String provider;

    @Column(nullable = false, length = 1024)
    private String url;

    /** DOCUMENTATION, COURSE, BOOK, TUTORIAL, PRACTICE, SPECIFICATION, VIDEO. */
    @Column(length = 64)
    private String type;

    /** BEGINNER, INTERMEDIATE, ADVANCED, or ALL when the resource spans levels. */
    @Column(length = 32)
    private String level;

    /** Comma-separated skill labels this resource teaches, used as retrieval signal. */
    @Column(columnDefinition = "TEXT")
    private String skillTags;

    /** SFIA codes this resource is relevant to, when the curator could establish them. */
    @Column(length = 512)
    private String sfiaCodes;

    @Column(columnDefinition = "TEXT")
    private String summary;

    /**
     * FREE, PAID or MIXED - and only when the catalogue entry actually records it. A null means
     * unknown, and the UI says nothing rather than guessing, because "free" is the claim students
     * act on and it changes without notice.
     */
    @Column(length = 32)
    private String cost;

    /** Stated study hours, when the provider publishes one. Null means unknown, not zero. */
    private Integer approxHours;

    @Column(length = 32)
    private String language;

    /** Lower-cased title, provider, tags and summary, built once at load time for retrieval. */
    @Column(columnDefinition = "TEXT")
    private String searchText;

    /**
     * Result of the last reachability check. A reachable URL does not prove the content is
     * suitable - only that the link is not dead - and the UI says exactly that.
     */
    private Boolean urlReachable;
    private LocalDateTime urlCheckedAt;

    @Column(length = 128)
    private String datasetVersion;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getResourceKey() {
        return resourceKey;
    }

    public void setResourceKey(String resourceKey) {
        this.resourceKey = resourceKey;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getSkillTags() {
        return skillTags;
    }

    public void setSkillTags(String skillTags) {
        this.skillTags = skillTags;
    }

    public String getSfiaCodes() {
        return sfiaCodes;
    }

    public void setSfiaCodes(String sfiaCodes) {
        this.sfiaCodes = sfiaCodes;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getCost() {
        return cost;
    }

    public void setCost(String cost) {
        this.cost = cost;
    }

    public Integer getApproxHours() {
        return approxHours;
    }

    public void setApproxHours(Integer approxHours) {
        this.approxHours = approxHours;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getSearchText() {
        return searchText;
    }

    public void setSearchText(String searchText) {
        this.searchText = searchText;
    }

    public Boolean getUrlReachable() {
        return urlReachable;
    }

    public void setUrlReachable(Boolean urlReachable) {
        this.urlReachable = urlReachable;
    }

    public LocalDateTime getUrlCheckedAt() {
        return urlCheckedAt;
    }

    public void setUrlCheckedAt(LocalDateTime urlCheckedAt) {
        this.urlCheckedAt = urlCheckedAt;
    }

    public String getDatasetVersion() {
        return datasetVersion;
    }

    public void setDatasetVersion(String datasetVersion) {
        this.datasetVersion = datasetVersion;
    }
}
