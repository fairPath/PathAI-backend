package com.job_search.fair_path.services;

import com.job_search.fair_path.dataTransferObject.UserProfileDTO;
import com.job_search.fair_path.repository.UserRepository;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserProfileDTO getUserProfile(UUID userId) {
        return userRepository.getProfile(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));    }

}
