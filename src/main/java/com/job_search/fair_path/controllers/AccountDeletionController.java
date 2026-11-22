package com.job_search.fair_path.controllers;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.services.AccountDeletionService;

@RestController
public class AccountDeletionController {
    public record Confirmation(String password) {}
    public record Receipt(String token) {}
    public record Status(String status) {}

    private final AccountDeletionService service;

    public AccountDeletionController(AccountDeletionService service) {
        this.service = service;
    }

    @PostMapping("/account/deletion")
    public ResponseEntity<Receipt> request(@AuthenticationPrincipal User user, @RequestBody Confirmation input) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(new Receipt(service.request(user.getId(), input.password())));
    }

    @PostMapping("/auth/account-deletion/status")
    public ResponseEntity<Status> status(@RequestBody Receipt input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Status(service.status(input.token())));
    }
}
