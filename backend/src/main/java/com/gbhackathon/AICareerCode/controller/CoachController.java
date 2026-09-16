package com.gbhackathon.AICareerCode.controller;

import com.gbhackathon.AICareerCode.dto.CareerRoadmapDto;
import com.gbhackathon.AICareerCode.dto.ChatMessageDto;
import com.gbhackathon.AICareerCode.dto.ResumeAuditDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.service.AiCoachService;
import com.gbhackathon.AICareerCode.service.LearningSnapshotService;
import com.gbhackathon.AICareerCode.service.ProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/coach")
@CrossOrigin(origins = "*")
public class CoachController {

    private final ProfileService profileService;
    private final AiCoachService aiCoachService;
    private final LearningSnapshotService learningSnapshotService;

    public CoachController(ProfileService profileService,
                           AiCoachService aiCoachService,
                           LearningSnapshotService learningSnapshotService) {
        this.profileService = profileService;
        this.aiCoachService = aiCoachService;
        this.learningSnapshotService = learningSnapshotService;
    }

    /**
     * The stored analysis for the current profile, generating and storing it on first request.
     *
     * <p>Both this and {@code /roadmap} read the same snapshot, so the milestone ids the Skills tab
     * renders are the ids the progress endpoint will accept. Repeat calls do not regenerate: to get
     * a different analysis a student has to actually change their profile.
     */
    @GetMapping("/audit")
    public ResponseEntity<?> getProfileAudit() {
        UserProfile profile = profileService.getCurrentOrCreateProfile();
        try {
            return ResponseEntity.ok(learningSnapshotService.getOrCreateAudit(profile));
        } catch (LearningSnapshotService.StaleProfileRevisionException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/roadmap")
    public ResponseEntity<?> getCareerRoadmap() {
        UserProfile profile = profileService.getCurrentOrCreateProfile();
        try {
            CareerRoadmapDto roadmap = learningSnapshotService.getOrCreateRoadmap(profile);
            return ResponseEntity.ok(roadmap);
        } catch (LearningSnapshotService.StaleProfileRevisionException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    public static class ChatRequest {
        private String message;
        private List<ChatMessageDto> history;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public List<ChatMessageDto> getHistory() {
            return history;
        }

        public void setHistory(List<ChatMessageDto> history) {
            this.history = history;
        }
    }

    /**
     * Live diagnosis of the AI backend. Because every AI feature degrades to offline text rather
     * than failing loudly, this endpoint is the way to tell whether Gemini is actually answering.
     * Pass {@code ?probe=true} to perform one real round trip to the model.
     */
    @GetMapping("/ai-status")
    public ResponseEntity<Map<String, Object>> getAiStatus(
            @RequestParam(name = "probe", defaultValue = "false") boolean probe) {
        return ResponseEntity.ok(probe ? aiCoachService.probeAi() : aiCoachService.aiStatus());
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chatWithCoach(@RequestBody ChatRequest request) {
        UserProfile profile = profileService.getCurrentOrCreateProfile();
        String userMsg = request.getMessage() != null ? request.getMessage().trim() : "";
        if (userMsg.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("reply", "Please enter a question."));
        }
        String reply = aiCoachService.chat(profile, request.getHistory(), userMsg);
        Object lastWorkingModel = aiCoachService.aiStatus().get("lastWorkingModel");
        Object lastError = aiCoachService.aiStatus().get("lastError");
        boolean fromAi = lastError == null && lastWorkingModel != null;

        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("reply", reply);
        // Let the UI show honestly whether the answer came from Gemini or the offline responder.
        body.put("generatedBy", fromAi ? "gemini" : "offline");
        body.put("model", fromAi && lastWorkingModel != null ? lastWorkingModel.toString() : null);
        return ResponseEntity.ok(body);
    }
}
