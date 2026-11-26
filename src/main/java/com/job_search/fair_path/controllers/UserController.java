package com.job_search.fair_path.controllers;

import com.job_search.fair_path.dataTransferObject.UserProfileDTO;
import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.services.UserService;
import com.job_search.fair_path.services.DailyJobMatchService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RequestMapping("/users")
@RestController
public class UserController {
    private final UserService userService;

    private final DailyJobMatchService dailyJobMatch;

    public UserController(UserService userService, DailyJobMatchService dailyJobMatch) {
        this.userService = userService;
        this.dailyJobMatch = dailyJobMatch;
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileDTO> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        }
        try {
            User user = ((User) authentication.getPrincipal());
            UserProfileDTO userProfile = userService.getUserProfile(user.getId());
            return ResponseEntity.ok(userProfile);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        }

    }

    @GetMapping("/me/daily-job-match")
    public DailyJobMatchPreference dailyJobMatch(@AuthenticationPrincipal User user) {
        return new DailyJobMatchPreference(dailyJobMatch.isEnabled(user.getId()));
    }

    @PutMapping("/me/daily-job-match")
    public DailyJobMatchPreference updateDailyJobMatch(@AuthenticationPrincipal User user,
            @RequestBody DailyJobMatchPreference preference) {
        if (preference.enabled() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return new DailyJobMatchPreference(dailyJobMatch.setEnabled(user.getId(), preference.enabled()));
    }

    public record DailyJobMatchPreference(Boolean enabled) {}
}
