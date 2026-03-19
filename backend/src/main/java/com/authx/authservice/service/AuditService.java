package com.authx.authservice.service;

import com.authx.authservice.entity.AuditAction;
import com.authx.authservice.entity.AuditLog;
import com.authx.authservice.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Async
    public void log(Long userId, String email, AuditAction action,
                    String ipAddress, String userAgent, String details) {
        auditLogRepository.save(AuditLog.builder()
                .userId(userId)
                .email(email)
                .action(action)
                .ipAddress(sanitize(ipAddress, 128))
                .userAgent(sanitize(userAgent, 512))
                .details(sanitize(details, 1024))
                .createdAt(LocalDateTime.now())
                .build());
    }

    public void log(Long userId, String email, AuditAction action,
                    String ipAddress, String userAgent) {
        log(userId, email, action, ipAddress, userAgent, null);
    }

    public Page<AuditLog> getAllLogs(int page, int size) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(page, Math.min(size, 100)));
    }

    public Page<AuditLog> getLogsByUser(Long userId, int page, int size) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(page, Math.min(size, 100)));
    }

    private String sanitize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
