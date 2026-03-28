package com.authx.authservice.service;

import com.authx.authservice.dto.EmailMessage;
import com.authx.authservice.exception.EmailDeliveryException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class EmailQueueConsumer {

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 3000;

    private final EmailQueueProducer producer;
    private final MailService mailService;
    private final ExecutorService executor;
    private final AtomicBoolean running;

    @Value("${app.email.consumer.enabled:true}")
    private boolean consumerEnabled;

    public EmailQueueConsumer(EmailQueueProducer producer, MailService mailService) {
        this.producer = producer;
        this.mailService = mailService;
        this.executor = Executors.newSingleThreadExecutor();
        this.running = new AtomicBoolean(false);
    }

    @PostConstruct
    public void start() {
        if (consumerEnabled) {
            executor.submit(this::processQueue);
            log.info("Email queue consumer started");
        } else {
            log.info("Email queue consumer is disabled");
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        executor.shutdown();
        log.info("Email queue consumer stopped");
    }

    private void processQueue() {
        running.set(true);
        while (running.get()) {
            try {
                EmailMessage email = producer.popEmail();
                if (email != null) {
                    processEmail(email);
                } else {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing email queue: {}", e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void processEmail(EmailMessage email) {
        try {
            log.info("Processing email to: {}", email.getTo());
            mailService.executeSendEmail(
                    email.getTo(),
                    email.getSubject(),
                    email.getTextBody(),
                    email.getHtmlBody()
            );
            log.info("Email sent successfully to: {}", email.getTo());
        } catch (EmailDeliveryException e) {
            handleFailure(email, e.getMessage());
        } catch (Exception e) {
            handleFailure(email, e.getMessage());
        }
    }

    private void handleFailure(EmailMessage email, String errorMessage) {
        email.setRetryCount(email.getRetryCount() + 1);
        log.warn("Failed to send email to {} (attempt {}/{}): {}",
                email.getTo(), email.getRetryCount(), MAX_RETRIES, errorMessage);

        if (email.getRetryCount() >= MAX_RETRIES) {
            producer.queueToDlq(email, "Max retries exceeded");
        } else {
            try {
                Thread.sleep(RETRY_DELAY_MS);
                producer.queueEmail(email);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}