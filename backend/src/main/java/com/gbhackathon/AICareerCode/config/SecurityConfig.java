package com.gbhackathon.AICareerCode.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Origins allowed to call the API with the session cookie attached.
     *
     * <p>Not a wildcard any more. The session cookie is the only thing separating one person's CV
     * from another's, and reflecting whatever origin asks means any page on the internet can make
     * a credentialed call on a visitor's behalf. The default covers local development only; a
     * deployment sets {@code APP_CORS_ALLOWED_ORIGINS} to the real frontend origin.
     */
    @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            // No CSRF token, because there is no login and no privileged action behind one. The
            // cookie is SameSite-scoped and CORS below names the origins that may use it; a
            // token would add a round trip without changing what an attacker could reach.
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()
            );
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "X-Admin-Token"));
        // Required for the session cookie to travel at all. It is why the origin list above must
        // stay explicit: allowCredentials with a wildcard origin is the combination browsers
        // refuse, and working around that refusal is exactly the hole to avoid.
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
