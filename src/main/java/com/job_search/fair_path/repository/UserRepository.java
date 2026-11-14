package com.job_search.fair_path.repository;

import com.job_search.fair_path.dataTransferObject.UserProfileDTO;
import com.job_search.fair_path.entity.User;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") UUID userId);

    // Optional is used a not guaranteed return of a user
    Optional<User> findByEmail(String email);

    Optional<User> findByVerificationCode(String verificationCode);

    Optional<User> findByResetTokenHash(String resetTokenHash);

    @Query("""
              select new com.job_search.fair_path.dataTransferObject.UserProfileDTO(
                u.firstName, u.lastName, u.email, u.username,
                r.id, r.fileName, r.updatedAt
              )
              from User u
              left join Resume r on r.userId = u.id
                and r.status = com.job_search.fair_path.entity.Resume.Status.ACTIVE
              where u.id = :userId
            """)
    Optional<UserProfileDTO> getProfile(@Param("userId") UUID userId); // running custom query to get user profile by
                                                                       // joining user and resume tables
                                                                       // note maybe better to move to profile repo
}
