package com.itemmanagement;

import com.itemmanagement.security.JwtService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class JwtServiceTest {
    @Test
    void rejectsMissingBlankAndShortSigningSecrets() {
        for (String value : new String[]{null, "", " ".repeat(32), "x".repeat(31)}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new JwtService(value, 60_000))
                    .withMessage("JWT_SECRET must contain at least 32 bytes");
        }
    }

    @Test
    void acceptsA32ByteSigningSecret() {
        assertThatCode(() -> new JwtService("x".repeat(32), 60_000)).doesNotThrowAnyException();
    }
}
