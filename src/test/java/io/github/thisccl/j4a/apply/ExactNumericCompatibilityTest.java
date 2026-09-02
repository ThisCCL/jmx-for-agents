package io.github.thisccl.j4a.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExactNumericCompatibilityTest {
    @Test
    void structuralIntegersAndUntypedRowNumbersKeepSafeYamlValues() {
        ApplyPatch patch = new ApplyPatchParser().parse("changes:\n"
                + "  - insert:\n"
                + "      ref: header-manager-ref\n"
                + "      property: [HeaderManager.headers]\n"
                + "      index: 2\n"
                + "      row: {attempts: 3, ratio: 1.25}\n");

        ApplyPatch.InsertOperation insert =
                (ApplyPatch.InsertOperation) patch.changes().get(0).operation();
        assertThat(insert.index()).isEqualTo(2);
        assertThat(insert.row().get("attempts"))
                .isExactlyInstanceOf(Integer.class)
                .isEqualTo(3);
        assertThat(insert.row().get("ratio"))
                .isExactlyInstanceOf(Double.class)
                .isEqualTo(1.25D);
    }

    @Test
    void yamlNumbersDecodeFromTheirDeclaredNumericTypes() {
        ApplyPatch patch = new ApplyPatchParser().parse("changes:\n"
                + "  - set:\n"
                + "      ref: assertion-ref\n"
                + "      properties:\n"
                + "        - property: [qa.int]\n"
                + "          type: int\n"
                + "          value: 7\n"
                + "        - property: [qa.long]\n"
                + "          type: long\n"
                + "          value: 8\n"
                + "        - property: [qa.float]\n"
                + "          type: float\n"
                + "          value: 1.25\n"
                + "        - property: [qa.double]\n"
                + "          type: double\n"
                + "          value: 2.5\n");

        ApplyPatch.SetOperation set =
                (ApplyPatch.SetOperation) patch.changes().get(0).operation();
        assertThat(set.properties().get(0).value())
                .isExactlyInstanceOf(Integer.class)
                .isEqualTo(7);
        assertThat(set.properties().get(1).value())
                .isExactlyInstanceOf(Long.class)
                .isEqualTo(8L);
        assertThat(set.properties().get(2).value())
                .isExactlyInstanceOf(Float.class)
                .isEqualTo(1.25F);
        assertThat(set.properties().get(3).value())
                .isExactlyInstanceOf(Double.class)
                .isEqualTo(2.5D);
    }

    @Test
    void yamlNumbersRejectOverflowNonfiniteAndNonzeroUnderflow() {
        assertRejected("float", "1e39", "Java float range");
        assertRejected("float", "1e-1000", "Java float range");
        assertRejected("double", "1e309", "Java double range");
        assertRejected("double", "1e-10000", "Java double range");
        assertRejected("double", ".inf", "Java double range");
        assertRejected("double", ".nan", "Java double range");
    }

    @Test
    void recursiveScalarEnvelopeUsesTheSameNonzeroUnderflowRule() {
        assertThatThrownBy(() -> new ApplyPatchParser().parseStructuredValue(
                "presence: present\n"
                        + "property_class: org.apache.jmeter.testelement.property.FloatProperty\n"
                        + "value: 1e-1000\n",
                new io.github.thisccl.j4a.path.PropertyAddress(
                        java.util.Collections.<Object>singletonList("qa.float")),
                ApplyPatch.ValueType.FLOAT))
                .isInstanceOf(ApplyPatchParseException.class)
                .hasMessageContaining("Java float range");
    }

    private static void assertRejected(String type, String value, String expectedRange) {
        assertThatThrownBy(() -> new ApplyPatchParser().parse("changes:\n"
                + "  - set:\n"
                + "      ref: assertion-ref\n"
                + "      properties:\n"
                + "        - property: [qa.scalar]\n"
                + "          type: " + type + "\n"
                + "          value: " + value + "\n"))
                .isInstanceOf(ApplyPatchParseException.class)
                .hasMessageContaining("expected type '" + type + "'")
                .hasMessageContaining(expectedRange)
                .hasMessageContaining("address [qa.scalar]");
    }
}
