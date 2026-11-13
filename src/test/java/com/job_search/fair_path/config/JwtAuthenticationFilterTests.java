package com.job_search.fair_path.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

import com.job_search.fair_path.services.JwtService;

class JwtAuthenticationFilterTests {
    @Test
    void invalidJwtStillRequiresLogin() throws Exception {
        var invalidToken = new JwtService() {
            @Override
            public String extractUserId(String token) {
                throw new io.jsonwebtoken.MalformedJwtException("Malformed test token");
            }
        };
        var filter = new JwtAuthenticationFilter(invalidToken, username -> null,
                (request, response, handler, exception) -> null);
        var request = new MockHttpServletRequest("GET", "/jobs");
        request.addHeader("Authorization", "Bearer invalid-token");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Invalid authentication must stop the request"));
        assertEquals(401, response.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(response.getContentAsString().contains("TOKEN_INVALID"));
    }

    @Test
    void databaseFailureDuringUserLookupReturnsServerError() throws Exception {
        var jwtService = new JwtService() {
            @Override
            public String extractUserId(String token) {
                return "test-user";
            }
        };
        var filter = new JwtAuthenticationFilter(jwtService, username -> {
            throw new org.springframework.dao.DataAccessResourceFailureException("Database unavailable");
        }, (request, response, handler, exception) -> null);
        var request = new MockHttpServletRequest("POST", "/saved-jobs/prepare");
        request.addHeader("Authorization", "Bearer test-token");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> fail("The controller must not run"));

        assertEquals(500, response.getStatus());
    }

    private JwtService tokenService() {
        return new JwtService() {
            @Override
            public String extractUserId(String token) {
                throw new IllegalStateException("Authentication database unavailable");
            }
        };
    }

    @Test
    void applicationFailureMustNotBecomeAnInvalidTokenResponse() throws Exception {
        var validToken = new JwtService() {
            @Override
            public String extractUserId(String token) { return null; }
        };
        var filter = new JwtAuthenticationFilter(validToken, username -> null,
                (request, response, handler, exception) -> { fail("Application exceptions must propagate to MVC"); return null; });
        var request = new MockHttpServletRequest("GET", "/jobs");
        request.addHeader("Authorization", "Bearer test-token");
        var response = new MockHttpServletResponse();
        assertThrows(IllegalArgumentException.class, () -> filter.doFilter(request, response, (req, res) -> {
            throw new IllegalArgumentException("Invalid upstream search response");
        }));
        assertEquals(200, response.getStatus()); // No authentication error was written.
    }

    @Test
    void unresolvedFailureMustNotReturnEmptySuccess() throws Exception {
        var filter = new JwtAuthenticationFilter(tokenService(), username -> null,
                (request, response, handler, exception) -> null);
        var request = new MockHttpServletRequest("POST", "/saved-jobs/prepare");
        request.addHeader("Authorization", "Bearer test-token");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new IllegalStateException("Database unavailable");
        });

        assertEquals(500, response.getStatus());
    }

    @Test
    void resolvedFailurePreservesResolverResponse() throws Exception {
        var filter = new JwtAuthenticationFilter(tokenService(), username -> null,
                (request, response, handler, exception) -> {
                    response.setStatus(503);
                    return new ModelAndView();
                });
        var request = new MockHttpServletRequest("POST", "/saved-jobs/prepare");
        request.addHeader("Authorization", "Bearer test-token");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new IllegalStateException("Database unavailable");
        });

        assertEquals(503, response.getStatus());
    }
}
