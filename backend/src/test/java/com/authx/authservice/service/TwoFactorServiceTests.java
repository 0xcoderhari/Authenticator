package com.authx.authservice.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwoFactorServiceTests {

    private static final String SECRET = "JBSWY3DPEHPK3PXP";

    @Test
    void acceptsGroupedOtpCodes() throws Exception {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-03-19T12:00:00Z"), ZoneOffset.UTC);
        TwoFactorService twoFactorService = new TwoFactorService(fixedClock);
        long currentBucket = fixedClock.instant().getEpochSecond() / 30;
        String code = twoFactorService.buildCodeGenerator().generate(SECRET, currentBucket);

        assertTrue(twoFactorService.isOtpValid(SECRET, code));
        assertTrue(twoFactorService.isOtpValid(SECRET, code.substring(0, 3) + " " + code.substring(3)));
        assertTrue(twoFactorService.isOtpValid(SECRET, code.substring(0, 3) + "-" + code.substring(3)));
    }

    @Test
    void acceptsSmallClockDriftButRejectsLargeDrift() throws Exception {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-03-19T12:00:00Z"), ZoneOffset.UTC);
        TwoFactorService twoFactorService = new TwoFactorService(fixedClock);
        long currentBucket = fixedClock.instant().getEpochSecond() / 30;

        String slightlyOldCode = twoFactorService.buildCodeGenerator().generate(SECRET, currentBucket - 2);
        String tooOldCode = twoFactorService.buildCodeGenerator().generate(SECRET, currentBucket - 3);

        assertTrue(twoFactorService.isOtpValid(SECRET, slightlyOldCode));
        assertFalse(twoFactorService.isOtpValid(SECRET, tooOldCode));
    }
}
