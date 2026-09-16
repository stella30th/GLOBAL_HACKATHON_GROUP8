package com.gbhackathon.AICareerCode.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness probe for the frontend and for keep-alive pings.
 *
 * <p>Deliberately touches neither the database nor any external service: on Render's free tier the
 * instance sleeps after 15 minutes of inactivity and a cold start takes roughly a minute, most of it
 * before the JVM can serve anything. The frontend used {@code /profiles/current} to decide whether
 * the backend was reachable, which additionally waits on the first JDBC connection, so the check
 * failed even once the server was up. This endpoint answers as soon as the web layer is ready.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class HealthController {

    private static final long STARTED_AT = System.currentTimeMillis();

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "uptimeSeconds", (System.currentTimeMillis() - STARTED_AT) / 1000
        ));
    }
}
