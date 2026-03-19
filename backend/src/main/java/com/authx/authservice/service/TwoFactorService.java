package com.authx.authservice.service;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.TimeProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;

import static dev.samstevens.totp.util.Utils.getDataUriForImage;

@Service
public class TwoFactorService {

    private static final int OTP_DIGITS = 6;
    private static final int OTP_PERIOD_SECONDS = 30;
    private static final int ALLOWED_TIME_PERIOD_DISCREPANCY = 4;

    private final Clock clock;

    public TwoFactorService() {
        this(Clock.systemUTC());
    }

    TwoFactorService(Clock clock) {
        this.clock = clock;
    }

    public String generateNewSecret() {
        SecretGenerator generator = new DefaultSecretGenerator();
        return generator.generate();
    }

    public String generateQrCodeImageUri(String secret, String email) {
        QrData data = new QrData.Builder()
                .label(email)
                .secret(secret)
                .issuer("AuthX")
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();

        QrGenerator generator = new ZxingPngQrGenerator();
        try {
            byte[] imageData = generator.generate(data);
            String mimeType = generator.getImageMimeType();
            return getDataUriForImage(imageData, mimeType);
        } catch (QrGenerationException e) {
            throw new RuntimeException("Error while generating QR code.", e);
        }
    }

    public boolean isOtpValid(String secret, String code) {
        if (secret == null || secret.isBlank()) {
            return false;
        }

        String normalizedCode = normalizeOtpCode(code);
        if (normalizedCode.length() != OTP_DIGITS) {
            return false;
        }

        TimeProvider timeProvider = () -> clock.instant().getEpochSecond();
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(buildCodeGenerator(), timeProvider);
        verifier.setTimePeriod(OTP_PERIOD_SECONDS);
        verifier.setAllowedTimePeriodDiscrepancy(ALLOWED_TIME_PERIOD_DISCREPANCY);
        return verifier.isValidCode(secret, normalizedCode);
    }

    CodeGenerator buildCodeGenerator() {
        return new DefaultCodeGenerator(HashingAlgorithm.SHA1, OTP_DIGITS);
    }

    String normalizeOtpCode(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }

        return code.replaceAll("\\D", "");
    }
}
