package com.job_search.fair_path.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.job_search.fair_path.services.JwtService;

class SecurityErrorDispatchTests {
    @Configuration
    @EnableWebMvc
    @Import(SecurityConfiguration.class)
    static class TestConfig {
        @Bean AuthenticationProvider authenticationProvider() { return mock(AuthenticationProvider.class); }
        @Bean JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(new JwtService(), username -> null,
                    (request, response, handler, exception) -> null);
        }
        @Bean ErrorController errorController() { return new ErrorController(); }
    }

    @RestController
    static class ErrorController {
        @GetMapping("/error")
        ResponseEntity<String> error() { return ResponseEntity.status(502).body("Upstream search unavailable"); }
    }

    @Test
    void errorDispatchRetainsItsStatusWhileOrdinaryRequestsStillRequireLogin() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(TestConfig.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();

            var error = mvc.perform(MockMvcRequestBuilders.get("/error").with(request -> {
                request.setDispatcherType(DispatcherType.ERROR);
                return request;
            }))
                    .andReturn().getResponse();
            assertEquals(502, error.getStatus());

            var direct = mvc.perform(MockMvcRequestBuilders.get("/error")).andReturn().getResponse();
            assertEquals(401, direct.getStatus());
            assertTrue(direct.getContentAsString().contains("AUTHENTICATION_REQUIRED"));

            var search = mvc.perform(MockMvcRequestBuilders.get("/jobs")).andReturn().getResponse();
            assertEquals(401, search.getStatus());
        }
    }
}
