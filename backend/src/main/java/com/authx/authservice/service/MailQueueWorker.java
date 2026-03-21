package com.authx.authservice.service;

import com.authx.authservice.dto.EmailTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MailQueueWorker {

    private final RedisTemplate<String, Object> redisTemplate;
    private final MailService mailService;
    private final ObjectMapper objectMapper;
    private static final String EMAIL_QUEUE = "email:queue";

    // Runs constantly (every 50ms) as long as the previous run completed
    @Scheduled(fixedDelay = 50)
    public void processEmailQueue() {
        try {
            Object obj = redisTemplate.opsForList().rightPop(EMAIL_QUEUE);
            if (obj != null) {
                EmailTask task;
                if (obj instanceof EmailTask) {
                    task = (EmailTask) obj;
                } else {
                    task = objectMapper.convertValue(obj, EmailTask.class);
                }
                
                log.info("Sending queued email to {}", task.getTo());
                mailService.executeSendEmail(task.getTo(), task.getSubject(), task.getTextBody(), task.getHtmlBody());
            }
        } catch (Exception e) {
            log.error("Failed to process email from queue", e);
        }
    }
}
