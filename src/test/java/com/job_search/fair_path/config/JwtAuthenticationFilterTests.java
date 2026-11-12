package com.job_search.fair_path.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

import com.job_search.fair_path.services.JwtService;

class JwtAuthenticationFilterTests {
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
                return null;
            }
        };
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
