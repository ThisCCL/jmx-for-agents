package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.reference.BoundReferences;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionReferenceRegistryTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultsAreBoundedAndTestsCanInjectSmallerLimits() {
        SessionReferenceRegistry production = new SessionReferenceRegistry();
        SessionReferenceRegistry small = new SessionReferenceRegistry(3, 6);

        assertThat(production.documentLimit()).isEqualTo(64);
        assertThat(production.referenceLimit()).isEqualTo(100_000);
        assertThat(small.documentLimit()).isEqualTo(3);
        assertThat(small.referenceLimit()).isEqualTo(6);
    }

    @Test
    void publishedSuccessfulUseEvictsOnlyTheExactLeastRecentlyUsedWholeDocument() throws Exception {
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 6);
        Path home = Files.createDirectory(tempDir.resolve("home"));
        DocumentFixture first = fixture(home, "first", 2);
        DocumentFixture second = fixture(home, "second", 2);
        DocumentFixture third = fixture(home, "third", 1);
        DocumentFixture fourth = fixture(home, "fourth", 1);

        String[] firstTokens = exposeAndPublish(registry, first);
        String[] secondTokens = exposeAndPublish(registry, second);
        String[] thirdTokens = exposeAndPublish(registry, third);
        assertThat(exposeAndPublish(registry, first)).containsExactly(firstTokens);

        SessionReferenceRegistry.Snapshot beforeFourth = registry.snapshot();
        String[] fourthTokens = exposeAndPublish(registry, fourth);
        SessionReferenceRegistry.Snapshot afterFourth = registry.snapshot();

        assertThat(beforeFourth.identitiesInLruOrder())
                .containsExactly(second.identity, third.identity, first.identity);
        assertThat(afterFourth.identitiesInLruOrder())
                .containsExactly(third.identity, first.identity, fourth.identity);
        assertThat(afterFourth.contains(second.identity)).isFalse();
        assertThat(afterFourth.tokens(first.identity)).containsExactlyInAnyOrder(firstTokens);
        assertThat(afterFourth.tokens(third.identity)).containsExactlyInAnyOrder(thirdTokens);
        assertThat(afterFourth.tokens(fourth.identity)).containsExactlyInAnyOrder(fourthTokens);
        assertThat(afterFourth.referenceCount()).isEqualTo(4);

        System.out.println("SESSION_REGISTRY_LRU limits=3/6"
                + " before=" + beforeFourth.identitiesInLruOrder()
                + " after=" + afterFourth.identitiesInLruOrder()
                + " evicted=second"
                + " firstTokensStable=" + Arrays.equals(firstTokens, afterFourth.tokens(first.identity).toArray())
                + " fourthTokens=" + Arrays.toString(fourthTokens));
    }

    @Test
    void activeOverflowAndProtectedSourceCapacityFailuresLeaveLiveStateExactlyUnchanged() throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("capacity-home"));
        SessionReferenceRegistry registry = new SessionReferenceRegistry(1, 2);
        DocumentFixture source = fixture(home, "source", 1);
        DocumentFixture oversized = fixture(home, "oversized", 3);
        DocumentFixture target = fixture(home, "target", 1);
        exposeAndPublish(registry, source);
        SessionReferenceRegistry.Snapshot live = registry.snapshot();
        byte[] output = "unchanged-output".getBytes(StandardCharsets.UTF_8);
        byte[] sourceBytes = Files.readAllBytes(source.identity.documentPath());
        byte[] targetBytes = Files.readAllBytes(target.identity.documentPath());

        assertThatThrownBy(() -> exposeWithoutPublishing(registry, oversized, Collections.<DocumentIdentity>emptySet()))
                .isInstanceOf(SessionReferenceRegistry.CapacityExceededException.class)
                .satisfies(failure -> assertThat(
                        ((SessionReferenceRegistry.CapacityExceededException) failure).code())
                        .isEqualTo("MCP_REF_CAPACITY_EXCEEDED"));
        assertThat(registry.snapshot()).isEqualTo(live);
        assertThat(output).containsExactly("unchanged-output".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readAllBytes(source.identity.documentPath())).containsExactly(sourceBytes);

        assertThatThrownBy(() -> exposeWithoutPublishing(registry, target, Collections.singleton(source.identity)))
                .isInstanceOf(SessionReferenceRegistry.CapacityExceededException.class)
                .satisfies(failure -> assertThat(
                        ((SessionReferenceRegistry.CapacityExceededException) failure).code())
                        .isEqualTo("MCP_REF_CAPACITY_EXCEEDED"));
        assertThat(registry.snapshot()).isEqualTo(live);
        assertThat(Files.readAllBytes(source.identity.documentPath())).containsExactly(sourceBytes);
        assertThat(Files.readAllBytes(target.identity.documentPath())).containsExactly(targetBytes);

        System.out.println("SESSION_REGISTRY_ROLLBACK activeOverflow=MCP_REF_CAPACITY_EXCEEDED"
                + " protectedSource=MCP_REF_CAPACITY_EXCEEDED"
                + " outputBytesUnchanged=true targetBytesUnchanged=true liveStateUnchanged=true"
                + " live=" + live);
    }

    @Test
    void staleRefreshCapacityFailureAndDiscardedPreparationsPreserveFingerprintAndRecency() throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("refresh-home"));
        SessionReferenceRegistry registry = new SessionReferenceRegistry(2, 1);
        DocumentFixture original = fixture(home, "refresh", 1, "old-bytes");
        exposeAndPublish(registry, original);
        SessionReferenceRegistry.Snapshot live = registry.snapshot();

        DocumentFixture refreshed = fixture(home, "refresh", 2, "new-bytes");
        assertThatThrownBy(() -> exposeWithoutPublishing(
                registry, refreshed, Collections.<DocumentIdentity>emptySet()))
                .isInstanceOf(SessionReferenceRegistry.CapacityExceededException.class);
        assertThat(registry.snapshot()).isEqualTo(live);

        for (int index = 0; index < 3; index++) {
            SessionReferenceRegistry.PreparedState discarded = registry.prepare(original.identity);
            registry.discard(discarded);
            registry.discard(discarded);
        }
        assertThat(registry.snapshot()).isEqualTo(live);
        assertThat(registry.snapshot().fingerprint(original.identity)).isEqualTo(original.snapshot.fingerprint());
    }

    @Test
    void stalePreparedPublicationCannotOverwriteNewerLiveState() throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("stale-state-home"));
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 6);
        DocumentFixture first = fixture(home, "stale-first", 1);
        DocumentFixture second = fixture(home, "stale-second", 1);

        SessionReferenceRegistry.PreparedState stale = registry.prepare(first.identity);
        SessionReferenceSpace staleSpace = new SessionReferenceSpace(stale, first.identity, first.snapshot);
        BoundReferences staleBound = staleSpace.bind(first.plan);
        io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(staleBound, first.nodes[0]);
        staleSpace.prepareSuccessfulUse();

        exposeAndPublish(registry, second);
        SessionReferenceRegistry.Snapshot newer = registry.snapshot();

        assertThat(registry.publish(stale)).isFalse();
        assertThat(registry.snapshot()).isEqualTo(newer);
        assertThat(registry.snapshot().contains(first.identity)).isFalse();
        assertThat(registry.snapshot().contains(second.identity)).isTrue();
    }

    @Test
    void replacementWithIdenticalFingerprintKeepsOldRefsLiveUntilPreparedPublication() throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("replacement-home"));
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 6);
        DocumentFixture fixture = fixture(home, "replacement", 1, "identical-bytes");
        String oldToken = exposeAndPublish(registry, fixture)[0];
        SessionReferenceRegistry.Snapshot before = registry.snapshot();

        SessionReferenceRegistry.PreparedState replacement = registry.prepareReplacement(fixture.identity);
        SessionReferenceSpace replacementSpace = new SessionReferenceSpace(
                replacement, fixture.identity, fixture.snapshot);
        BoundReferences replacementBound = replacementSpace.bind(fixture.plan);
        String newToken = io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(replacementBound, fixture.nodes[0]);
        replacementSpace.prepareSuccessfulUse();
        SessionReferenceRegistry.PreparedPublication publication = registry.preparePublication(replacement);

        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(registry.snapshot()).isEqualTo(before);
        assertThat(registry.snapshot().tokens(fixture.identity)).containsExactly(oldToken);

        publication.publish();

        assertThat(registry.snapshot().fingerprint(fixture.identity)).isEqualTo(fixture.snapshot.fingerprint());
        assertThat(registry.snapshot().tokens(fixture.identity)).containsExactly(newToken);
    }

    @Test
    void invalidationIsPreparedNoOpSafeAndPreservesOtherDocumentsAndLru() throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("invalidation-home"));
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 6);
        DocumentFixture affected = fixture(home, "affected", 1);
        DocumentFixture other = fixture(home, "other", 1);
        String affectedToken = exposeAndPublish(registry, affected)[0];
        String otherToken = exposeAndPublish(registry, other)[0];
        SessionReferenceRegistry.Snapshot before = registry.snapshot();

        SessionReferenceRegistry.PreparedState invalidation = registry.prepareInvalidation(affected.identity);

        assertThat(registry.snapshot()).isEqualTo(before);
        assertThat(registry.publish(invalidation)).isTrue();
        assertThat(registry.snapshot().contains(affected.identity)).isFalse();
        assertThat(registry.snapshot().tokens(other.identity)).containsExactly(otherToken);
        assertThat(registry.snapshot().identitiesInLruOrder()).containsExactly(other.identity);
        assertThat(registry.documentStatus(affected.identity, affected.snapshot.fingerprint()))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.ABSENT);
        assertThat(registry.documentStatus(other.identity, other.snapshot.fingerprint()))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.MATCH);
        assertThat(registry.documentStatus(other.identity, "different-fingerprint"))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.MISMATCH);

        SessionReferenceRegistry.Snapshot after = registry.snapshot();
        assertThat(registry.publish(registry.prepareInvalidation(affected.identity))).isTrue();
        assertThat(registry.snapshot()).isEqualTo(after);
        assertThat(affectedToken).isNotEqualTo(otherToken);
    }

    @Test
    void publicationPreparationRejectsAStaleBaseBeforeTargetCommit() throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("publication-home"));
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 6);
        DocumentFixture staleFixture = fixture(home, "publication-stale", 1);
        DocumentFixture newerFixture = fixture(home, "publication-newer", 1);

        SessionReferenceRegistry.PreparedState stale = registry.prepare(staleFixture.identity);
        SessionReferenceSpace staleSpace = new SessionReferenceSpace(
                stale, staleFixture.identity, staleFixture.snapshot);
        io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(
                staleSpace.bind(staleFixture.plan), staleFixture.nodes[0]);
        staleSpace.prepareSuccessfulUse();

        exposeAndPublish(registry, newerFixture);
        SessionReferenceRegistry.Snapshot newer = registry.snapshot();

        assertThatThrownBy(() -> registry.preparePublication(stale))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed before publication");
        assertThat(registry.snapshot()).isEqualTo(newer);
    }

    private static String[] exposeAndPublish(SessionReferenceRegistry registry, DocumentFixture fixture) {
        SessionReferenceRegistry.PreparedState prepared = registry.prepare(fixture.identity);
        SessionReferenceSpace space = new SessionReferenceSpace(prepared, fixture.identity, fixture.snapshot);
        BoundReferences bound = space.bind(fixture.plan);
        String[] tokens = expose(bound, fixture.nodes);
        space.prepareSuccessfulUse();
        assertThat(registry.publish(prepared)).isTrue();
        return tokens;
    }

    private static void exposeWithoutPublishing(
            SessionReferenceRegistry registry,
            DocumentFixture fixture,
            java.util.Set<DocumentIdentity> additionallyProtected) {
        SessionReferenceRegistry.PreparedState prepared = registry.prepare(additionallyProtected);
        SessionReferenceSpace space = new SessionReferenceSpace(prepared, fixture.identity, fixture.snapshot);
        BoundReferences bound = space.bind(fixture.plan);
        expose(bound, fixture.nodes);
        space.prepareSuccessfulUse();
    }

    private static String[] expose(BoundReferences bound, ProbeElement[] nodes) {
        String[] tokens = new String[nodes.length];
        for (int index = 0; index < nodes.length; index++) {
            tokens[index] = io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(bound, nodes[index]);
        }
        return tokens;
    }

    private DocumentFixture fixture(Path home, String name, int nodeCount) throws Exception {
        return fixture(home, name, nodeCount, name + "-bytes");
    }

    private DocumentFixture fixture(Path home, String name, int nodeCount, String bytes) throws Exception {
        Path document = tempDir.resolve(name + ".jmx");
        Files.write(document, bytes.getBytes(StandardCharsets.UTF_8));
        ProbeElement root = new ProbeElement(name + "-root");
        ProbeElement[] nodes = new ProbeElement[nodeCount];
        ListedHashTree tree = new ListedHashTree();
        HashTree children = tree.add(root);
        for (int index = 0; index < nodeCount; index++) {
            nodes[index] = new ProbeElement(name + "-node-" + index);
            children.add(nodes[index]);
        }
        JmxTestPlan plan = new JmxTestPlan(tree);
        SourceSnapshot<JmxTestPlan> snapshot = SourceSnapshot.read(document, ignored -> plan);
        return new DocumentFixture(DocumentIdentity.of(home, document), plan, snapshot, nodes);
    }

    private static final class DocumentFixture {
        private final DocumentIdentity identity;
        private final JmxTestPlan plan;
        private final SourceSnapshot<JmxTestPlan> snapshot;
        private final ProbeElement[] nodes;

        private DocumentFixture(
                DocumentIdentity identity,
                JmxTestPlan plan,
                SourceSnapshot<JmxTestPlan> snapshot,
                ProbeElement[] nodes) {
            this.identity = identity;
            this.plan = plan;
            this.snapshot = snapshot;
            this.nodes = nodes;
        }
    }

    private static final class ProbeElement extends AbstractTestElement {
        private static final long serialVersionUID = 1L;

        private ProbeElement(String name) {
            setName(name);
        }
    }
}
