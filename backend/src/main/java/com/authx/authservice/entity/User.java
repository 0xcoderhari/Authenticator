package com.authx.authservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

import com.authx.authservice.entity.Role;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    // nullable for Google OAuth users
    @Column(nullable = true)
    private String password;

    // for Google login
    @Column(nullable = true, unique = true)
    private String googleId;

    @Enumerated(EnumType.STRING)
    private Role role;

    private boolean isVerified;

    private LocalDateTime createdAt;
}