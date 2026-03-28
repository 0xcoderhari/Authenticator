package com.authx.authservice.service;

import com.authx.authservice.dto.EmailMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EmailQueueProducer {

    private static final String EMAIL_QUEUE_KEY = "email:queue";
    private static final String EMAIL_DLQ_KEY = "email:dlq";

    private final RedisTemplate<String, EmailMessage> emailRedisTemplate;

    public EmailQueueProducer(RedisTemplate<String, EmailMessage> emailRedisTemplate) {
        this.emailRedisTemplate = emailRedisTemplate;
    }

    public void queueEmail(EmailMessage emailMessage) {
        try {
            emailRedisTemplate.opsForList().rightPush(EMAIL_QUEUE_KEY, emailMessage);
            log.info("Email queued for: {}", emailMessage.getTo());
        } catch (Exception e) {
            log.error("Failed to queue email to {}: {}", emailMessage.getTo(), e.getMessage());
            throw new RuntimeException("Failed to queue email", e);
        }
    }

    public void queueToDlq(EmailMessage emailMessage, String reason) {
        try {
            emailRedisTemplate.opsForList().rightPush(EMAIL_DLQ_KEY, emailMessage);
            log.warn("Email moved to DLQ for: {}, reason: {}, retryCount: {}", 
                    emailMessage.getTo(), reason, emailMessage.getRetryCount());
        } catch (Exception e) {
            log.error("Failed to move email to DLQ: {}", e.getMessage());
        }
    }

    public EmailMessage popEmail() {
        try {
            return emailRedisTemplate.opsForList().leftPop(EMAIL_QUEUE_KEY);
        } catch (Exception e) {
            log.error("Failed to pop email from queue: {}", e.getMessage());
            return null;
        }
    }

    public long getQueueSize() {
        try {
            Long size = emailRedisTemplate.opsForList().size(EMAIL_QUEUE_KEY);
            return size != null ? size : 0;
        } catch (Exception e) {
            log.error("Failed to get queue size: {}", e.getMessage());
            return 0;
        }
    }
}