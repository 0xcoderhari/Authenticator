package com.authx.authservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private String to;
    private String subject;
    private String textBody;
    private String htmlBody;
    private LocalDateTime createdAt;
    private int retryCount;

    public EmailMessage(String to, String subject, String textBody, String htmlBody) {
        this.to = to;
        this.subject = subject;
        this.textBody = textBody;
        this.htmlBody = htmlBody;
        this.createdAt = LocalDateTime.now();
        this.retryCount = 0;
    }
}