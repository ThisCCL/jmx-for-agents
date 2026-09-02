package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RuntimeEmptyDirectRowProofTest {
    @BeforeAll
    static void initializeSelectedSaveService() {
        Todo8OpaqueFixtures.initializeSaveService();
    }

    @Test
    void acceptsExactlyOneStableCandidate() {
        UniqueRows owner = new UniqueRows();

        assertThat(RuntimeEmptyDirectRowProof.observe(owner, owner.rows())).isPresent();
        assertThat(owner.rows().size()).isZero();
    }

    @Test
    void rejectsAmbiguousAndSideEffectCandidates() {
        AmbiguousRows ambiguous = new AmbiguousRows();
        SideEffectRows sideEffect = new SideEffectRows();

        assertThat(RuntimeEmptyDirectRowProof.observe(ambiguous, ambiguous.rows())).isEmpty();
        assertThat(RuntimeEmptyDirectRowProof.observe(sideEffect, sideEffect.rows())).isEmpty();
        assertThat(ambiguous.rows().size()).isZero();
        assertThat(sideEffect.rows().size()).isZero();
        assertThat(sideEffect.getPropertyOrNull("fixture.side")).isNull();
    }

    @Test
    void rejectsNoProofAndDoesNotExerciseAnIncompleteCandidateDomain() {
        NoProofRows noProof = new NoProofRows();
        BoundedRows bounded = new BoundedRows();
        BoundedRows.inBoundInvocations = 0;
        BoundedRows.beyondBoundInvocations = 0;

        assertThat(RuntimeEmptyDirectRowProof.observe(noProof, noProof.rows())).isEmpty();
        assertThat(RuntimeEmptyDirectRowProof.observe(bounded, bounded.rows())).isEmpty();
        assertThat(BoundedRows.inBoundInvocations).isZero();
        assertThat(BoundedRows.beyondBoundInvocations).isZero();
        assertThat(bounded.rows().size()).isZero();
    }

    public static class DirectRows extends AbstractTestElement {
        DirectRows() {
            setProperty(new CollectionProperty("fixture.rows", Collections.emptyList()));
        }

        CollectionProperty rows() {
            return (CollectionProperty) getProperty("fixture.rows");
        }
    }

    public static final class UniqueRows extends DirectRows {
        public void add(Header row) { rows().addItem(row); }
    }

    public static final class AmbiguousRows extends DirectRows {
        public void add(Header row) { rows().addItem(row); }
        public void append(Header row) { rows().addItem(row); }
    }

    public static final class SideEffectRows extends DirectRows {
        public void add(Header row) {
            rows().addItem(row);
            setProperty("fixture.side", "changed");
        }
    }

    public static final class NoProofRows extends DirectRows {
    }

    public static final class BoundedRows extends DirectRows {
        private static int inBoundInvocations;
        private static int beyondBoundInvocations;

        public void a00(Header row) {
            inBoundInvocations++;
            rows().addItem(row);
        }
        public void a01(Header row) { }
        public void a02(Header row) { }
        public void a03(Header row) { }
        public void a04(Header row) { }
        public void a05(Header row) { }
        public void a06(Header row) { }
        public void a07(Header row) { }
        public void a08(Header row) { }
        public void a09(Header row) { }
        public void a10(Header row) { }
        public void a11(Header row) { }
        public void a12(Header row) { }
        public void a13(Header row) { }
        public void a14(Header row) { }
        public void a15(Header row) { }
        public void a16(Header row) { }
        public void a17(Header row) { }
        public void a18(Header row) { }
        public void a19(Header row) { }
        public void a20(Header row) { }
        public void a21(Header row) { }
        public void a22(Header row) { }
        public void a23(Header row) { }
        public void a24(Header row) { }
        public void a25(Header row) { }
        public void a26(Header row) { }
        public void a27(Header row) { }
        public void a28(Header row) { }
        public void a29(Header row) { }
        public void a30(Header row) { }
        public void a31(Header row) { }
        public void a32(Header row) {
            beyondBoundInvocations++;
            rows().addItem(row);
        }
    }
}
