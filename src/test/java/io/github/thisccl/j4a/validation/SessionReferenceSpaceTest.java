package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.reference.BoundReferences;
import io.github.thisccl.j4a.reference.ReferenceResolution;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionReferenceSpaceTest {
    private static final String ZERO_TOKEN = "AAAAAAAAAAAAAAAA";
    private static final String ONE_TOKEN = "AQEBAQEBAQEBAQEB";
    private static final String TWO_TOKEN = "AgICAgICAgICAgIC";

    @TempDir
    Path tempDir;

    @Test
    void allocatesOnlyExposedNodesAndReusesTheirExactTokensOnAnUnchangedRead() throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("lazy-home"));
        Fixture fixture = fixture(home, tempDir.resolve("lazy.jmx"), "same-bytes", new LeftTypes.Duplicate(), 3);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 6);

        SessionReferenceRegistry.PreparedState firstPrepared = registry.prepare(fixture.identity);
        SessionReferenceSpace firstSpace = new SessionReferenceSpace(firstPrepared, fixture.identity, fixture.snapshot);
        BoundReferences firstBound = firstSpace.bind(fixture.plan);
        String firstToken = io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(firstBound, fixture.children[0]);
        firstSpace.prepareSuccessfulUse();
        assertThat(registry.publish(firstPrepared)).isTrue();

        assertThat(registry.snapshot().referenceCount()).isEqualTo(1);
        assertThat(registry.snapshot().tokens(fixture.identity)).containsExactly(firstToken);

        SessionReferenceRegistry.PreparedState secondPrepared = registry.prepare(fixture.identity);
        SessionReferenceSpace secondSpace = new SessionReferenceSpace(secondPrepared, fixture.identity, fixture.snapshot);
        BoundReferences secondBound = secondSpace.bind(fixture.plan);
        assertThat(io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(secondBound, fixture.children[0])).isEqualTo(firstToken);
        String deeperToken = io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(secondBound, fixture.children[2]);
        secondSpace.prepareSuccessfulUse();
        assertThat(registry.publish(secondPrepared)).isTrue();

        assertThat(deeperToken).isNotEqualTo(firstToken);
        assertThat(registry.snapshot().referenceCount()).isEqualTo(2);
        assertThat(registry.snapshot().tokens(fixture.identity))
                .containsExactlyInAnyOrder(firstToken, deeperToken);
    }

    @Test
    void wrongHomePathTypeAndUnknownTokensAreUnavailableWithoutFallbackMatching() throws Exception {
        Path firstHome = Files.createDirectory(tempDir.resolve("binding-home-a"));
        Path secondHome = Files.createDirectory(tempDir.resolve("binding-home-b"));
        Path firstPath = tempDir.resolve("binding-a.jmx");
        Path secondPath = tempDir.resolve("binding-b.jmx");
        Fixture original = fixture(firstHome, firstPath, "same-fingerprint", new LeftTypes.Duplicate(), 1);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(4, 8);
        String token = exposeAndPublish(registry, original, original.children[0]);

        Fixture wrongPath = fixture(firstHome, secondPath, "same-fingerprint", new LeftTypes.Duplicate(), 1);
        Fixture wrongHome = fixture(secondHome, firstPath, "same-fingerprint", new LeftTypes.Duplicate(), 1);
        Fixture wrongType = fixture(firstHome, firstPath, "same-fingerprint", new RightTypes.Duplicate(), 1);

        assertUnavailable(bind(registry, wrongPath).resolve(token));
        assertUnavailable(bind(registry, wrongHome).resolve(token));
        assertUnavailable(bind(registry, wrongType).resolve(token));
        assertUnavailable(bind(registry, original).resolve(null));
        assertUnavailable(bind(registry, original).resolve(""));
        assertUnavailable(bind(registry, original).resolve("not-a-live-token"));
        assertUnavailable(bind(new SessionReferenceRegistry(4, 8), original).resolve(token));

        assertThat(registry.snapshot().documentCount()).isEqualTo(1);
        assertThat(registry.snapshot().tokens(original.identity)).containsExactly(token);
        assertThat(token).matches("^[A-Za-z0-9_-]{16}$");
        assertThat(token).doesNotContain(original.identity.jmeterHome().toString());
        assertThat(token).doesNotContain(original.identity.documentPath().toString());
        assertThat(token).doesNotContain(original.children[0].getClass().getName());
    }

    @Test
    void resolveReturnsTheExactRequestNodeAndSpaceRetainsNoMutablePlan() throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("exact-home"));
        Fixture fixture = fixture(home, tempDir.resolve("exact.jmx"), "exact", new LeftTypes.Duplicate(), 2);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(2, 4);
        String token = exposeAndPublish(registry, fixture, fixture.children[1]);

        SessionReferenceRegistry.PreparedState prepared = registry.prepare(fixture.identity);
        SessionReferenceSpace space = new SessionReferenceSpace(prepared, fixture.identity, fixture.snapshot);
        BoundReferences bound = space.bind(fixture.plan);
        ReferenceResolution resolution = bound.resolve(token);

        assertThat(resolution.status()).isEqualTo(ReferenceResolution.Status.RESOLVED);
        assertThat(ReferenceTestSupport.element(resolution.handle().get())).isSameAs(fixture.children[1]);
        assertThat(Arrays.stream(SessionReferenceSpace.class.getDeclaredFields()).map(Field::getType))
                .noneMatch(JmxTestPlan.class::isAssignableFrom)
                .noneMatch(TestElement.class::isAssignableFrom)
                .noneMatch(BoundReferences.class::isAssignableFrom);
        registry.discard(prepared);
    }

    @Test
    void tokenAllocationRejectsLiveAndSamePreparationCollisions() throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("collision-home"));
        Fixture liveFixture = fixture(home, tempDir.resolve("collision-live.jmx"), "live", new LeftTypes.Duplicate(), 1);
        Fixture preparedFixture = fixture(
                home, tempDir.resolve("collision-prepared.jmx"), "prepared", new LeftTypes.Duplicate(), 2);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 6);

        ReferenceTokenGenerator liveGenerator = new ReferenceTokenGenerator(new SequenceRandomBytes(bytes(0)));
        String live = exposeAndPublish(registry, liveFixture, liveFixture.children[0], liveGenerator);
        assertThat(live).isEqualTo(ZERO_TOKEN);

        ReferenceTokenGenerator collisionGenerator = new ReferenceTokenGenerator(
                new SequenceRandomBytes(bytes(0), bytes(1), bytes(1), bytes(2)));
        SessionReferenceRegistry.PreparedState prepared = registry.prepare(preparedFixture.identity);
        SessionReferenceSpace space = new SessionReferenceSpace(
                prepared, preparedFixture.identity, preparedFixture.snapshot, collisionGenerator);
        BoundReferences bound = space.bind(preparedFixture.plan);

        assertThat(io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(bound, preparedFixture.children[0])).isEqualTo(ONE_TOKEN);
        assertThat(io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(bound, preparedFixture.children[1])).isEqualTo(TWO_TOKEN);
        space.prepareSuccessfulUse();
        assertThat(registry.publish(prepared)).isTrue();
        assertThat(registry.snapshot().tokens(preparedFixture.identity))
                .containsExactlyInAnyOrder(ONE_TOKEN, TWO_TOKEN);
    }

    private static BoundReferences bind(SessionReferenceRegistry registry, Fixture fixture) {
        SessionReferenceRegistry.PreparedState prepared = registry.prepare(fixture.identity);
        return new SessionReferenceSpace(prepared, fixture.identity, fixture.snapshot).bind(fixture.plan);
    }

    private static String exposeAndPublish(
            SessionReferenceRegistry registry, Fixture fixture, ProbeElement node) {
        return exposeAndPublish(registry, fixture, node, new ReferenceTokenGenerator());
    }

    private static String exposeAndPublish(
            SessionReferenceRegistry registry,
            Fixture fixture,
            ProbeElement node,
            ReferenceTokenGenerator generator) {
        SessionReferenceRegistry.PreparedState prepared = registry.prepare(fixture.identity);
        SessionReferenceSpace space = new SessionReferenceSpace(
                prepared, fixture.identity, fixture.snapshot, generator);
        BoundReferences bound = space.bind(fixture.plan);
        String token = io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(bound, node);
        space.prepareSuccessfulUse();
        assertThat(registry.publish(prepared)).isTrue();
        return token;
    }

    private static void assertUnavailable(ReferenceResolution resolution) {
        assertThat(resolution.status()).isEqualTo(ReferenceResolution.Status.UNAVAILABLE);
        assertThat(resolution.handle()).isEmpty();
    }

    private Fixture fixture(
            Path home,
            Path document,
            String sourceBytes,
            ProbeElement firstChild,
            int childCount) throws Exception {
        Files.write(document, sourceBytes.getBytes(StandardCharsets.UTF_8));
        ProbeElement root = new ProbeElement("root");
        ProbeElement[] children = new ProbeElement[childCount];
        ListedHashTree tree = new ListedHashTree();
        HashTree childTree = tree.add(root);
        for (int index = 0; index < childCount; index++) {
            children[index] = index == 0 ? firstChild : new ProbeElement("child-" + index);
            children[index].setName("same-name");
            childTree.add(children[index]);
        }
        JmxTestPlan plan = new JmxTestPlan(tree);
        SourceSnapshot<JmxTestPlan> snapshot = SourceSnapshot.read(document, ignored -> plan);
        return new Fixture(DocumentIdentity.of(home, document), plan, snapshot, children);
    }

    private static byte[] bytes(int value) {
        byte[] bytes = new byte[12];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final class SequenceRandomBytes implements ReferenceTokenGenerator.RandomBytes {
        private final Deque<byte[]> values = new ArrayDeque<>();

        private SequenceRandomBytes(byte[]... values) {
            this.values.addAll(Arrays.asList(values));
        }

        @Override
        public void nextBytes(byte[] target) {
            byte[] value = values.removeFirst();
            System.arraycopy(value, 0, target, 0, target.length);
        }
    }

    private static class ProbeElement extends AbstractTestElement {
        private static final long serialVersionUID = 1L;

        private ProbeElement(String name) {
            setName(name);
        }
    }

    private static final class LeftTypes {
        private static final class Duplicate extends ProbeElement {
            private static final long serialVersionUID = 1L;

            private Duplicate() {
                super("same-name");
            }
        }
    }

    private static final class RightTypes {
        private static final class Duplicate extends ProbeElement {
            private static final long serialVersionUID = 1L;

            private Duplicate() {
                super("same-name");
            }
        }
    }

    private static final class Fixture {
        private final DocumentIdentity identity;
        private final JmxTestPlan plan;
        private final SourceSnapshot<JmxTestPlan> snapshot;
        private final ProbeElement[] children;

        private Fixture(
                DocumentIdentity identity,
                JmxTestPlan plan,
                SourceSnapshot<JmxTestPlan> snapshot,
                ProbeElement[] children) {
            this.identity = identity;
            this.plan = plan;
            this.snapshot = snapshot;
            this.children = children;
        }
    }
}
