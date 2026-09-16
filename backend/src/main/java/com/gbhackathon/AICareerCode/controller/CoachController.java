package com.gbhackathon.AICareerCode.controller;

import com.gbhackathon.AICareerCode.dto.ChatMessageDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.service.CoachChatService;
import com.gbhackathon.AICareerCode.service.ProfileService;
import com.gbhackathon.AICareerCode.service.ai.AiUnavailableException;
import com.gbhackathon.AICareerCode.service.ai.GeminiClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "ask about your plan" panel and the AI diagnostics behind it.
 *
 * <p>Chat is a support tool here, not a second product surface: it explains the plan the pipeline
 * produced and cannot modify it.
 */
@RestController
@RequestMapping("/api/coach")
@CrossOrigin(origins = "*")
public class CoachController {

    private final ProfileService profileService;
    private final CoachChatService chatService;
    private final GeminiClient gemini;

    public CoachController(ProfileService profileService, CoachChatService chatService, GeminiClient gemini) {
        this.profileService = profileService;
        this.chatService = chatService;
        this.gemini = gemini;
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

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        UserProfile profile = profileService.getCurrentOrCreateProfile();
        String question = request.getMessage() != null ? request.getMessage().trim() : "";
        if (question.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please enter a question."));
        }

        try {
            CoachChatService.Reply reply = chatService.chat(profile, request.getHistory(), question);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reply", reply.text());
            // The model that actually answered, so the panel never attributes an answer to a model
            // that did not produce it.
            body.put("model", reply.model());
            return ResponseEntity.ok(body);
        } catch (AiUnavailableException e) {
            // No scripted reply stands in for this. A canned answer in a coaching panel reads as
            // advice, and the student has no way to tell it apart from the real thing.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "The AI service could not be reached, so there is no answer to show. "
                            + "Please try again in a moment.",
                    "detail", String.valueOf(e.getDetail()),
                    "retryable", true));
        }
    }

    /**
     * Live diagnosis of the AI backend. Pass {@code ?probe=true} for one real round trip, which
     * is the only way to tell a working key from one that is merely present.
     */
    @GetMapping("/ai-status")
    public ResponseEntity<Map<String, Object>> getAiStatus(
            @RequestParam(name = "probe", defaultValue = "false") boolean probe) {
        return ResponseEntity.ok(probe ? gemini.probe() : gemini.status());
    }
}
