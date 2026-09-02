package io.github.thisccl.j4a.validation;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;

final class ReferenceTokenGenerator {
    private static final int TOKEN_BYTES = 12;

    private final RandomBytes randomBytes;

    ReferenceTokenGenerator() {
        this(new SecureRandomSource());
    }

    ReferenceTokenGenerator(RandomBytes randomBytes) {
        this.randomBytes = Objects.requireNonNull(randomBytes, "randomBytes");
    }

    String allocate(Set<String> liveTokens, Set<String> preparedTokens) {
        Objects.requireNonNull(liveTokens, "liveTokens");
        Objects.requireNonNull(preparedTokens, "preparedTokens");
        while (true) {
            byte[] bytes = new byte[TOKEN_BYTES];
            randomBytes.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (!liveTokens.contains(token)
                    && !preparedTokens.contains(token)
                    && preparedTokens.add(token)) {
                return token;
            }
        }
    }

    interface RandomBytes {
        void nextBytes(byte[] target);
    }

    private static final class SecureRandomSource implements RandomBytes {
        private final SecureRandom secureRandom = new SecureRandom();

        @Override
        public void nextBytes(byte[] target) {
            secureRandom.nextBytes(target);
        }
    }
}
