package com.authx.authservice.repository;

import com.authx.authservice.entity.ActionToken;
import com.authx.authservice.entity.ActionTokenPurpose;
import com.authx.authservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ActionTokenRepository extends JpaRepository<ActionToken, Long> {
    Optional<ActionToken> findByTokenId(String tokenId);

    void deleteByUserAndPurpose(User user, ActionTokenPurpose purpose);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ActionToken a WHERE a.user = :user")
    void deleteByUser(User user);
}
