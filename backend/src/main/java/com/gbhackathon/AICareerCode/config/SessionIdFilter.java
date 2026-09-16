package com.gbhackathon.AICareerCode.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Issues and carries the anonymous session that every profile is scoped by.
 *
 * <p>There is no login here, so this cookie is the only thing that separates one person's CV from
 * another's. That places two requirements on it. It is issued by the server and never read from a
 * request body or query string, so a caller cannot simply name someone else's session; and it is
 * {@code HttpOnly}, so a script injected into the page cannot read it back out.
 *
 * <p>Guessing is not a realistic attack on a random UUID v4, but note what this is and is not: it
 * is isolation between ordinary users sharing a deployment, not authentication. Anyone who obtains
 * the cookie value has that profile. For a product holding real CVs beyond a demo, this is the
 * layer that should be replaced by accounts, not extended.
 */
@Component
public class SessionIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionIdFilter.class);

    /** Request attribute the controllers read. */
    public static final String ATTRIBUTE = "aicareer.sessionId";

    public static final String COOKIE_NAME = "sid";

    /** A value we issued: a UUID and nothing else. Anything else is discarded, not trusted. */
    private static final Pattern VALID = Pattern.compile("[0-9a-fA-F-]{36}");

    /**
     * {@code Secure} and {@code SameSite=None} are required in the deployed setup, where the static
     * frontend and the API are different origins and the browser will not attach a cross-site
     * cookie without both. They are wrong for plain-HTTP local development, where the Vite proxy
     * makes the call same-origin anyway, so the local profile turns them off.
     */
    @Value("${app.session.cookie-secure:true}")
    private boolean cookieSecure;

    @Value("${app.session.cookie-same-site:None}")
    private String cookieSameSite;

    @Value("${app.session.cookie-max-age-days:180}")
    private int cookieMaxAgeDays;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String sessionId = readCookie(request);

        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString();
            response.addHeader("Set-Cookie", buildCookie(sessionId).toString());
            log.debug("Issued a new anonymous session");
        }

        request.setAttribute(ATTRIBUTE, sessionId);
        chain.doFilter(request, response);
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())
                    && cookie.getValue() != null
                    && VALID.matcher(cookie.getValue()).matches()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * Built with {@link ResponseCookie} rather than {@code Cookie} because the servlet cookie API
     * has no SameSite attribute, and without SameSite the deployed frontend gets no cookie at all.
     */
    private ResponseCookie buildCookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/")
                .maxAge(Duration.ofDays(cookieMaxAgeDays))
                .build();
    }

    /**
     * The session for the request being handled.
     *
     * @throws IllegalStateException when the filter did not run, which would otherwise mean a
     *         request silently falling back to some other person's profile
     */
    public static String require(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof String sessionId && !sessionId.isBlank()) {
            return sessionId;
        }
        throw new IllegalStateException(
                "No session on this request. SessionIdFilter must run before every controller.");
    }
}
