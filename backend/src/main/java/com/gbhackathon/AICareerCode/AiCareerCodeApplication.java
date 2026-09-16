package com.gbhackathon.AICareerCode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Async is enabled so the startup job import runs off the main thread. Fetching five job boards
 * inline during {@code ApplicationReadyEvent} added those seconds to every Render cold start before
 * the service could answer its first request.
 */
@SpringBootApplication
@EnableAsync
public class AiCareerCodeApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiCareerCodeApplication.class, args);
	}

}
