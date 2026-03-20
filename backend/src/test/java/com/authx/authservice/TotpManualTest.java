package com.authx.authservice;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.secret.DefaultSecretGenerator;

public class TotpManualTest {
    public static void main(String[] args) throws Exception {
        DefaultSecretGenerator generator = new DefaultSecretGenerator();
        String secret = "JBSWY3DPEHPK3PXP"; // Static secret for predictability

        DefaultCodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        
        long baseTimeSeconds = 1711000000L; // some epoch
        
        System.out.println("Current Base Time Code: " + codeGenerator.generate(secret, baseTimeSeconds / 30));

        // Test with discrepancy
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, () -> baseTimeSeconds);
        verifier.setTimePeriod(30);
        verifier.setAllowedTimePeriodDiscrepancy(4);
        
        String oneHourLaterCode = codeGenerator.generate(secret, (baseTimeSeconds + 3600) / 30);
        System.out.println("Validating Code from 1 Hour Later at Base Time: " + verifier.isValidCode(secret, oneHourLaterCode));
        
        String fiveMinsAgoCode = codeGenerator.generate(secret, (baseTimeSeconds - 300) / 30);
        System.out.println("Validating Code from 5 mins ago at Base Time: " + verifier.isValidCode(secret, fiveMinsAgoCode));
    }
}
