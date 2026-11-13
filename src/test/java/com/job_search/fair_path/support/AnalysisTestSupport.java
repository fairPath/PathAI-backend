package com.job_search.fair_path.support;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.job_search.fair_path.entity.Resume;
import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.repository.ResumeRepository;
import com.job_search.fair_path.repository.UserRepository;
import com.job_search.fair_path.services.JwtService;

/**
 * Shared fixtures for Analysis tests: a signed-in user with an ACTIVE
 * synthetic Resume. Adds no beans or properties, so the context is still shared.
 */
public abstract class AnalysisTestSupport extends IntegrationTestBase {

    /** One document-level Issue, as the AI service returns it. */
    protected static final String MISSING_CONTACT_ISSUE_JSON = """
            {
              "issue_id": "fmt-001", "issue_type": "MISSING_CONTACT", "category": "formatting",
              "source": "check", "severity": "high", "snippet": null, "line_id": null,
              "line_text": null, "section": null, "line_kind": null,
              "description": "No email address or phone number was found in your resume.",
              "suggestion": "Add an email address or phone number near the top so recruiters can reach you."
            }
            """;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected ResumeRepository resumeRepository;

    @Autowired
    protected JwtService jwtService;

    protected User user;
    protected Resume resume;
    protected String bearerToken;

    @BeforeEach
    void createUserWithActiveResume() {
        String unique = UUID.randomUUID().toString();
        User newUser = new User("Test", "User", "user-" + unique, unique + "@example.invalid", "not-a-real-hash");
        newUser.setEnabled(true);
        user = userRepository.save(newUser);
        bearerToken = "Bearer " + jwtService.generateToken(user);

        Resume active = new Resume();
        active.setUserId(user.getId());
        active.setS3Bucket("test-bucket");
        active.setS3Key("resumes/" + user.getId() + "/active.pdf");
        active.setFileName("synthetic-resume.pdf");
        active.setStatus(Resume.Status.ACTIVE);
        resume = resumeRepository.save(active);
    }
}
