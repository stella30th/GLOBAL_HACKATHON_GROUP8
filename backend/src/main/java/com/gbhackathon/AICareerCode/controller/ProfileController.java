package com.gbhackathon.AICareerCode.controller;

import com.gbhackathon.AICareerCode.dto.ProfileDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.service.CvParserService;
import com.gbhackathon.AICareerCode.service.ProfileService;
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

    public ProfileController(ProfileService profileService, CvParserService cvParserService) {
        this.profileService = profileService;
        this.cvParserService = cvParserService;
    }

    @GetMapping("/current")
    public ResponseEntity<ProfileDto> getCurrentProfile() {
        UserProfile profile = profileService.getCurrentOrCreateProfile();
        return ResponseEntity.ok(profileService.toDto(profile));
    }

    @PostMapping
    public ResponseEntity<ProfileDto> saveProfile(@RequestBody ProfileDto dto) {
        UserProfile saved = profileService.saveOrUpdateProfile(dto);
        return ResponseEntity.ok(profileService.toDto(saved));
    }

    @PostMapping("/upload-cv")
    public ResponseEntity<?> uploadAndParseCv(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Vui lòng chọn một tệp tin CV."));
        }
        try {
            String text = cvParserService.extractTextFromFile(file);
            if (text == null || text.replaceAll("\\s+", "").length() < 40) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Khong doc duoc noi dung van ban tu CV. Neu day la ban scan/anh, hay dung file PDF co text."));
            }
            ProfileDto parsedProfile = cvParserService.parseCvTextToProfile(text);
            // A CV describes a whole candidate, so replace the stored profile rather than merging
            // field by field - otherwise the previous candidate's skills survive the upload.
            UserProfile saved = profileService.replaceProfileFromCv(parsedProfile);
            return ResponseEntity.ok(profileService.toDto(saved));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi đọc tệp tin: " + e.getMessage()));
        }
    }

    @PostMapping("/reset-sample/{type}")
    public ResponseEntity<ProfileDto> setSampleProfile(@PathVariable("type") String type) {
        ProfileDto p = new ProfileDto();
        if ("senior-backend".equalsIgnoreCase(type)) {
            p.setFullName("Alex Tuan Le");
            p.setEmail("alex.tuan.dev@gmail.com");
            p.setPhone("+84 908 112 233");
            p.setCurrentTitle("Senior Backend Engineer (Distributed Systems)");
            p.setYearsOfExperience(5.0);
            p.setEducation("B.S. in Software Engineering - National University");
            p.setLanguages("English (Professional Working - IELTS 7.5), Vietnamese (Native)");
            p.setSkills(List.of("Java", "Spring Boot", "MySQL", "Docker", "Kubernetes", "Redis", "Kafka", "Microservices", "AWS", "System Design", "Git"));
            p.setTargetRoles(List.of("Senior Backend Engineer", "Lead Platform Engineer", "Cloud Architect"));
            p.setTargetLocations(List.of("Singapore", "Germany / EU", "Japan", "Remote Worldwide"));
            p.setWillingToRelocate(true);
            p.setTargetWorkType("ANY");
            p.setBio("5 years of specialized experience in high-throughput backend architecture and event-driven microservices. Seeking relocation opportunities in Singapore or Europe with visa sponsorship.");
        } else if ("frontend-react".equalsIgnoreCase(type)) {
            p.setFullName("Brian Nam Tran");
            p.setEmail("brian.nam.ui@gmail.com");
            p.setPhone("+84 938 776 554");
            p.setCurrentTitle("Frontend React / Next.js Specialist");
            p.setYearsOfExperience(3.0);
            p.setEducation("B.S. in Computer Science");
            p.setLanguages("English (Fluent), Vietnamese (Native)");
            p.setSkills(List.of("React", "TypeScript", "JavaScript", "Next.js", "Tailwind CSS", "REST API", "Git", "Node.js"));
            p.setTargetRoles(List.of("Senior Frontend Engineer", "UI/UX Tech Lead"));
            p.setTargetLocations(List.of("Vietnam", "Remote Worldwide (US/EU)", "Singapore"));
            p.setWillingToRelocate(false);
            p.setTargetWorkType("REMOTE");
            p.setBio("3 years building responsive, high-performance web applications with React & TypeScript. Seeking high-impact 100% remote global software positions with USD compensation.");
        } else {
            p.setFullName("David Nguyen");
            p.setEmail("david.nguyen.tech@example.com");
            p.setPhone("+84 912 345 678");
            p.setCurrentTitle("Full Stack Developer");
            p.setYearsOfExperience(2.5);
            p.setEducation("B.S. in Information Technology");
            p.setLanguages("English (Professional Working), Vietnamese (Native)");
            p.setSkills(List.of("Java", "Spring Boot", "React", "MySQL", "Docker", "REST API", "Git"));
            p.setTargetRoles(List.of("Full Stack Engineer", "Backend Developer"));
            p.setTargetLocations(List.of("Vietnam", "Remote Worldwide", "Singapore"));
            p.setWillingToRelocate(true);
            p.setTargetWorkType("ANY");
            p.setBio("Enthusiastic full-stack engineer experienced in Java Spring Boot backend services and modern React user interfaces.");
        }
        UserProfile saved = profileService.saveOrUpdateProfile(p);
        return ResponseEntity.ok(profileService.toDto(saved));
    }
}
