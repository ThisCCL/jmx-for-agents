package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionReferenceTokenGeneratorTest {
    private static final String ZERO_TOKEN = "AAAAAAAAAAAAAAAA";
    private static final String ONE_TOKEN = "AQEBAQEBAQEBAQEB";
    private static final String TWO_TOKEN = "AgICAgICAgICAgIC";

    @Test
    void rendersExactlyTwelveRandomBytesAsAnUnquotedBase64UrlToken() {
        SequenceRandomBytes random = new SequenceRandomBytes(bytes(0xff));
        ReferenceTokenGenerator generator = new ReferenceTokenGenerator(random);
        Set<String> prepared = new HashSet<>();

        String token = generator.allocate(
                new HashSet<>(Arrays.asList("HOME_PATH_TYPE_1", "GENERATION_00001")), prepared);

        assertThat(token).isEqualTo("________________");
        assertThat(token).matches("^[A-Za-z0-9_-]{16}$");
        assertThat(token).doesNotContain("\"");
        assertThat(prepared).containsExactly(token);
        assertThat(random.requestedLengths()).containsExactly(12);
    }

    @Test
    void retriesRepeatedLiveAndPreparedCollisionsAcrossTwoAllocations() {
        SequenceRandomBytes random = new SequenceRandomBytes(
                bytes(0), bytes(0), bytes(1), bytes(1), bytes(1), bytes(2));
        ReferenceTokenGenerator generator = new ReferenceTokenGenerator(random);
        Set<String> live = Collections.singleton(ZERO_TOKEN);
        Set<String> prepared = new HashSet<>();

        String first = generator.allocate(live, prepared);
        String second = generator.allocate(live, prepared);

        assertThat(first).isEqualTo(ONE_TOKEN);
        assertThat(second).isEqualTo(TWO_TOKEN);
        assertThat(prepared).containsExactlyInAnyOrder(ONE_TOKEN, TWO_TOKEN);
        assertThat(random.requestedLengths()).containsExactly(12, 12, 12, 12, 12, 12);
    }

    @Test
    void productionGeneratorCreatesTenThousandOpaqueUniqueTokens() {
        ReferenceTokenGenerator generator = new ReferenceTokenGenerator();
        Set<String> prepared = new HashSet<>();
        Set<String> semanticValues = new HashSet<>(Arrays.asList(
                "/sanitized/jmeter/home",
                "/sanitized/documents/plan.jmx",
                "org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy",
                "worker-generation-42"));

        for (int index = 0; index < 10_000; index++) {
            String token = generator.allocate(Collections.<String>emptySet(), prepared);
            assertThat(token).matches("^[A-Za-z0-9_-]{16}$");
            for (String semanticValue : semanticValues) {
                assertThat(token).doesNotContain(semanticValue);
            }
        }

        assertThat(prepared).hasSize(10_000);
    }

    @Test
    void nullCollisionSetsAreRejected() {
        ReferenceTokenGenerator generator = new ReferenceTokenGenerator();

        assertThatThrownBy(() -> generator.allocate(null, new HashSet<String>()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> generator.allocate(Collections.<String>emptySet(), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static byte[] bytes(int value) {
        byte[] bytes = new byte[12];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final class SequenceRandomBytes implements ReferenceTokenGenerator.RandomBytes {
        private final Deque<byte[]> values = new ArrayDeque<>();
        private final java.util.List<Integer> requestedLengths = new java.util.ArrayList<>();

        private SequenceRandomBytes(byte[]... values) {
            this.values.addAll(Arrays.asList(values));
        }

        @Override
        public void nextBytes(byte[] target) {
            requestedLengths.add(target.length);
            byte[] value = values.removeFirst();
            assertThat(value).hasSameSizeAs(target);
            System.arraycopy(value, 0, target, 0, target.length);
        }

        private java.util.List<Integer> requestedLengths() {
            return requestedLengths;
        }
    }
}
