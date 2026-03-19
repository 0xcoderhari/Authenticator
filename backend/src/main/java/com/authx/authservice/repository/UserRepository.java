package com.authx.authservice.repository;

import com.authx.authservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    Optional<User> findByGoogleId(String googleId);

    List<User> findTop50ByEmailContainingIgnoreCaseOrderByCreatedAtDesc(String emailPart);
}
