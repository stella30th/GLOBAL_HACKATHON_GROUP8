package com.gbhackathon.AICareerCode.controller;

import com.gbhackathon.AICareerCode.dto.ProfileDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.service.CvParserService;
import com.gbhackathon.AICareerCode.service.ProfileService;
import com.gbhackathon.AICareerCode.service.ProfileValidationException;
import com.gbhackathon.AICareerCode.service.ai.AiInvalidResponseException;
import com.gbhackathon.AICareerCode.service.ai.AiUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.gbhackathon.AICareerCode.config.SessionIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The profile and the career goal: the two inputs a plan is built from.
 *
 * <p>A CV upload replaces the profile and keeps the goal, because a student who has said they are
 * aiming at a junior backend role in three months has not changed their mind by uploading a better
 * CV. Extraction is always the model's work; when it is unavailable the upload fails and says so,
 * and manual entry stays open.
 */
@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);

    private final ProfileService profileService;
    private final CvParserService cvParserService;

    public ProfileController(ProfileService profileService, CvParserService cvParserService) {
        this.profileService = profileService;
        this.cvParserService = cvParserService;
    }

    @GetMapping("/current")
    public ResponseEntity<ProfileDto> getCurrentProfile(HttpServletRequest request) {
        UserProfile profile = profileService.getCurrentOrCreateProfile(SessionIdFilter.require(request));
        return ResponseEntity.ok(profileService.toDto(profile));
    }

    @PostMapping
    public ResponseEntity<?> saveProfile(HttpServletRequest request, @RequestBody ProfileDto dto) {
        try {
            UserProfile saved = profileService.saveOrUpdateProfile(SessionIdFilter.require(request), dto);
            return ResponseEntity.ok(profileService.toDto(saved));
        } catch (ProfileValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/upload-cv")
    public ResponseEntity<?> uploadAndParseCv(HttpServletRequest request,
                                             @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please choose a CV file to upload."));
        }
        try {
            String text = cvParserService.extractTextFromFile(file);
            ProfileDto parsed = cvParserService.parseCvTextToProfile(text);
            // A CV describes a whole candidate, so the profile is replaced rather than merged field
            // by field - otherwise the previous candidate's skills survive the upload.
            UserProfile saved = profileService.replaceProfileFromCv(SessionIdFilter.require(request), parsed);
            return ResponseEntity.ok(profileService.toDto(saved));
        } catch (CvParserService.UnreadableCvException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (AiUnavailableException e) {
            // Nothing is stored. A keyword guess at the CV's contents would look like extraction
            // and would quietly become the basis of the whole plan.
            log.warn("CV extraction failed - no model answered: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "The AI service could not be reached, so the CV was not read and nothing "
                            + "was saved. Try again, or fill in the form manually.",
                    "detail", String.valueOf(e.getDetail()),
                    "retryable", true));
        } catch (AiInvalidResponseException e) {
            log.warn("CV extraction returned an unusable result: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "The CV could not be read into a usable profile. Try again, or fill in "
                            + "the form manually.",
                    "detail", e.getMessage(),
                    "retryable", true));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Could not read the file: " + e.getMessage()));
        }
    }

    /**
     * Demo profiles, for showing the product without asking anyone to upload their real CV.
     *
     * <p>Both are fictional, use example.com addresses and record zero professional experience;
     * university projects are coursework, not employment. They carry a goal so the demo reaches a
     * generated plan in one click - and the plan itself is still generated, never stored in advance.
     */
    @PostMapping("/reset-sample/{type}")
    public ResponseEntity<?> setSampleProfile(HttpServletRequest request,
                                              @PathVariable("type") String type) {
        ProfileDto p = new ProfileDto();
        if ("student-early".equalsIgnoreCase(type)) {
            p.setFullName("Mai Tran");
            p.setEmail("mai.tran.student@example.com");
            p.setPhone("+84 900 000 002");
            p.setCurrentTitle("Software Engineering Student");
            p.setIndustry("Software Engineering");
            p.setYearsOfExperience(0.0);
            p.setEducation("B.Sc. Software Engineering (in progress, expected 2028)");
            p.setLanguages("Vietnamese (Native), English (Intermediate)");
            p.setSkills(List.of("Java", "Python", "Git", "SQL basics", "Data Structures", "HTML/CSS"));
            p.setTargetRoles(List.of("Software Engineering Intern"));
            p.setBio("Second-year software engineering student. Coursework in data structures and "
                    + "object-oriented programming, two class projects (a library management CLI and a "
                    + "small web app built with a team of four). No professional experience yet; looking "
                    + "to build strong fundamentals and a first portfolio.");
            p.setTargetRole("Software Engineering Intern");
            p.setTargetSeniority("INTERN");
            p.setPlanDurationMonths(3);
            p.setPlanHoursPerWeek(8);
        } else if ("student-final".equalsIgnoreCase(type)) {
            p.setFullName("Khoa Pham");
            p.setEmail("khoa.pham.student@example.com");
            p.setPhone("+84 900 000 004");
            p.setCurrentTitle("Final-year Software Engineering Student");
            p.setIndustry("Software Engineering");
            p.setYearsOfExperience(0.0);
            p.setEducation("B.Sc. Software Engineering (final year, expected 2026)");
            p.setLanguages("Vietnamese (Native), English (Upper-intermediate)");
            p.setSkills(List.of("Java", "Spring Boot", "React", "PostgreSQL", "REST API", "Git",
                    "Unit Testing", "Docker basics", "Agile teamwork"));
            p.setTargetRoles(List.of("Junior Backend Developer"));
            p.setBio("Final-year software engineering student. Capstone project: a full-stack booking "
                    + "system built with Spring Boot and React in a team of five, with unit tests and a "
                    + "CI pipeline. Comfortable working from a backlog and reviewing teammates' code. No "
                    + "professional employment yet; targeting a junior role after graduation.");
            p.setTargetRole("Junior Backend Developer");
            p.setTargetSeniority("JUNIOR");
            p.setPlanDurationMonths(6);
            p.setPlanHoursPerWeek(12);
        } else {
            // An unrecognised type used to fall through to a default sample, so a typo in the UI
            // silently replaced the user's profile with someone else's.
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unknown sample profile '" + type + "'. Available samples: student-early, student-final."));
        }

        UserProfile saved = profileService.replaceProfileFromSample(SessionIdFilter.require(request), p);
        return ResponseEntity.ok(profileService.toDto(saved));
    }
}
