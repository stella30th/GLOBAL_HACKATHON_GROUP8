package com.gbhackathon.AICareerCode.controller;

import com.gbhackathon.AICareerCode.dto.ProfileDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.service.CvParserService;
import com.gbhackathon.AICareerCode.service.LearningSnapshotService;
import com.gbhackathon.AICareerCode.service.ProfileService;
import com.gbhackathon.AICareerCode.service.ProfileValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/profiles")
@CrossOrigin(origins = "*")
public class ProfileController {

    private final ProfileService profileService;
    private final CvParserService cvParserService;
    private final LearningSnapshotService learningSnapshotService;

    public ProfileController(ProfileService profileService,
                             CvParserService cvParserService,
                             LearningSnapshotService learningSnapshotService) {
        this.profileService = profileService;
        this.cvParserService = cvParserService;
        this.learningSnapshotService = learningSnapshotService;
    }

    @GetMapping("/current")
    public ResponseEntity<ProfileDto> getCurrentProfile() {
        UserProfile profile = profileService.getCurrentOrCreateProfile();
        return ResponseEntity.ok(profileService.toDto(profile));
    }

    @PostMapping
    public ResponseEntity<?> saveProfile(@RequestBody ProfileDto dto) {
        try {
            UserProfile saved = profileService.saveOrUpdateProfile(dto);
            return ResponseEntity.ok(profileService.toDto(saved));
        } catch (ProfileValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/upload-cv")
    public ResponseEntity<?> uploadAndParseCv(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please choose a CV file to upload."));
        }
        try {
            String text = cvParserService.extractTextFromFile(file);
            if (text == null || text.replaceAll("\\s+", "").length() < 40) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "No readable text was found in this CV. If it is a scan or an image, upload a PDF that contains selectable text."));
            }
            ProfileDto parsedProfile = cvParserService.parseCvTextToProfile(text);
            // A CV describes a whole candidate, so replace the stored profile rather than merging
            // field by field - otherwise the previous candidate's skills survive the upload.
            UserProfile saved = profileService.replaceProfileFromCv(parsedProfile);
            return ResponseEntity.ok(profileService.toDto(saved));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not read the file: " + e.getMessage()));
        }
    }

    /**
     * Records that the student has, by their own judgement, finished a milestone.
     *
     * <p>Deliberately not part of {@code POST /api/profiles}: that endpoint moves the profile
     * revision, which throws away the snapshot the milestone belongs to - a checkbox would have
     * deleted the roadmap it was ticking. This one validates the milestone against the current
     * roadmap, writes only the progress list, and leaves {@code updatedAt} alone. No AI is called.
     */
    @PatchMapping("/current/milestones/{milestoneId}")
    public ResponseEntity<?> updateMilestoneProgress(@PathVariable("milestoneId") String milestoneId,
                                                     @RequestBody MilestoneProgressRequest request) {
        if (request == null || request.getRoadmapId() == null || request.getRoadmapId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "roadmapId is required."));
        }
        if (request.getCompleted() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "completed must be true or false."));
        }
        if (milestoneId == null || milestoneId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "milestoneId is required."));
        }

        UserProfile current = profileService.getCurrentOrCreateProfile();
        try {
            UserProfile updated = learningSnapshotService.updateMilestoneProgress(
                    current.getId(), request.getRoadmapId(), milestoneId, request.getCompleted());
            return ResponseEntity.ok(profileService.toDto(updated));
        } catch (LearningSnapshotService.StaleRoadmapException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (LearningSnapshotService.MilestoneNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    public static class MilestoneProgressRequest {
        private String roadmapId;
        /** Boxed so a missing field is distinguishable from an explicit false. */
        private Boolean completed;

        public String getRoadmapId() {
            return roadmapId;
        }

        public void setRoadmapId(String roadmapId) {
            this.roadmapId = roadmapId;
        }

        public Boolean getCompleted() {
            return completed;
        }

        public void setCompleted(Boolean completed) {
            this.completed = completed;
        }
    }

    /**
     * Demo profiles.
     *
     * <p>Two students rather than the three working professionals that used to live here: a
     * mid-career backend engineer produced a roadmap about senior roles and relocation, which is
     * not the product. Both samples are fictional, use example.com addresses, and record zero
     * professional experience - university projects are coursework, not employment.
     */
    @PostMapping("/reset-sample/{type}")
    public ResponseEntity<?> setSampleProfile(@PathVariable("type") String type) {
        ProfileDto p = new ProfileDto();
        if ("student-year-2".equalsIgnoreCase(type)) {
            p.setFullName("Mai Tran");
            p.setEmail("mai.tran.student@example.com");
            p.setPhone("+84 900 000 002");
            p.setCurrentTitle("Software Engineering Student");
            p.setIndustry("Software Engineering");
            p.setYearOfStudy("Year 2");
            p.setYearsOfExperience(0.0);
            p.setEducation("B.Sc. Software Engineering (in progress, expected 2028)");
            p.setLanguages("Vietnamese (Native), English (Intermediate)");
            p.setSkills(List.of("Java", "Python", "Git", "SQL basics", "Data Structures", "HTML/CSS"));
            p.setTargetRoles(List.of("Software Engineering Intern"));
            p.setTargetLocations(List.of("Vietnam"));
            p.setWillingToRelocate(false);
            p.setTargetWorkType("ANY");
            p.setBio("Second-year software engineering student. Coursework in data structures and "
                    + "object-oriented programming, two class projects (a library management CLI and a "
                    + "small web app built with a team of four). No professional experience yet; looking "
                    + "to build strong fundamentals and a first portfolio.");
        } else if ("student-year-4".equalsIgnoreCase(type)) {
            p.setFullName("Khoa Pham");
            p.setEmail("khoa.pham.student@example.com");
            p.setPhone("+84 900 000 004");
            p.setCurrentTitle("Final-year Software Engineering Student");
            p.setIndustry("Software Engineering");
            p.setYearOfStudy("Year 4");
            p.setYearsOfExperience(0.0);
            p.setEducation("B.Sc. Software Engineering (final year, expected 2026)");
            p.setLanguages("Vietnamese (Native), English (Upper-intermediate)");
            p.setSkills(List.of("Java", "Spring Boot", "React", "PostgreSQL", "REST API", "Git",
                    "Unit Testing", "Docker basics", "Agile teamwork"));
            p.setTargetRoles(List.of("Software Engineering Intern", "Junior Backend Developer"));
            p.setTargetLocations(List.of("Vietnam", "Remote"));
            p.setWillingToRelocate(false);
            p.setTargetWorkType("ANY");
            p.setBio("Final-year software engineering student. Capstone project: a full-stack booking "
                    + "system built with Spring Boot and React in a team of five, with unit tests and a "
                    + "CI pipeline. Comfortable working from a backlog and reviewing teammates' code. No "
                    + "professional employment yet; targeting an internship or a junior role after graduation.");
        } else {
            // An unrecognised type used to fall through to a default sample, so a typo in the UI
            // silently replaced the user's profile with someone else's.
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unknown sample profile '" + type + "'. Available samples: student-year-2, student-year-4."));
        }

        UserProfile saved = profileService.replaceProfileFromSample(p);
        return ResponseEntity.ok(profileService.toDto(saved));
    }
}
