package com.authx.authservice.repository;

import com.authx.authservice.entity.RefreshToken;
import com.authx.authservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findBySessionId(String sessionId);

    Optional<RefreshToken> findBySessionIdAndUser(String sessionId, User user);

    List<RefreshToken> findByUserAndRevokedAtIsNull(User user);

    List<RefreshToken> findByUserAndRevokedAtIsNullAndExpiresAtAfterOrderByLastUsedAtDesc(
            User user,
            LocalDateTime now
    );

    List<RefreshToken> findByRevokedAtIsNullAndExpiresAtAfterOrderByLastUsedAtDesc(LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM RefreshToken r WHERE r.user = :user")
    void deleteByUser(User user);
}
