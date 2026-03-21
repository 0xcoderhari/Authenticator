package com.authx.authservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailTask {
    private String to;
    private String subject;
    private String textBody;
    private String htmlBody;
}
