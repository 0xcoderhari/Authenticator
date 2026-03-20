import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.secret.DefaultSecretGenerator;

import java.time.Clock;
import java.time.Duration;

public class TotpTest {
    public static void main(String[] args) throws Exception {
        DefaultSecretGenerator generator = new DefaultSecretGenerator();
        String secret = generator.generate();
        System.out.println("Secret: " + secret);

        DefaultCodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        
        // Simulating now
        long nowSeconds = Clock.systemUTC().instant().getEpochSecond();
        String currentCode = codeGenerator.generate(secret, nowSeconds / 30);
        System.out.println("Code Now: " + currentCode);

        DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, () -> nowSeconds);
        verifier.setTimePeriod(30);
        verifier.setAllowedTimePeriodDiscrepancy(4);
        System.out.println("IsValid Now: " + verifier.isValidCode(secret, currentCode));

        // Simulating 1 hour later
        long oneHourLaterSeconds = nowSeconds + 3600;
        String hourLaterCode = codeGenerator.generate(secret, oneHourLaterSeconds / 30);
        System.out.println("Code 1Hr Later: " + hourLaterCode);

        DefaultCodeVerifier verifierLater = new DefaultCodeVerifier(codeGenerator, () -> oneHourLaterSeconds);
        verifierLater.setTimePeriod(30);
        verifierLater.setAllowedTimePeriodDiscrepancy(4);
        System.out.println("IsValid 1Hr Later: " + verifierLater.isValidCode(secret, hourLaterCode));

        // Let's test if the verifier caches anything or if there's any state bug.
        DefaultCodeVerifier singleVerifier = new DefaultCodeVerifier(codeGenerator, () -> System.currentTimeMillis() / 1000);
        singleVerifier.setTimePeriod(30);
        singleVerifier.setAllowedTimePeriodDiscrepancy(4);
        
        System.out.println("Is singleVerifier valid now: " + singleVerifier.isValidCode(secret, codeGenerator.generate(secret, (System.currentTimeMillis() / 1000) / 30)));
    }
}
