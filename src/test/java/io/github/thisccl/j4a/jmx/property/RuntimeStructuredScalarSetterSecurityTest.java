package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RuntimeStructuredScalarSetterSecurityTest {
    @BeforeAll
    static void initializeSaveService() {
        Todo8OpaqueFixtures.initializeSaveService();
    }

    @Test
    void rejectsWrongInputTypeAndArbitraryPublicMethod() {
        assertThatThrownBy(() -> RuntimeStructuredScalarSetter.prove(
                ThrowingArgument.class, "qa.encoded", "setEnabledFlag",
                String.class, Arrays.asList("qa.encoded", "qa.equals")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> RuntimeStructuredScalarSetter.prove(
                ThrowingArgument.class, "qa.encoded", "removeProperty",
                String.class, Arrays.asList("qa.encoded", "qa.equals")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setter");
    }

    @Test
    void throwingSetterCannotChangeTargetBytes() throws Exception {
        ConfigTestElement evidenceOwner = ownerWith(new ThrowingArgument());
        RuntimeContext context = context();
        RuntimeStructuredRowEvidence evidence = RuntimeStructuredRowEvidence.observe(
                evidenceOwner, "qa.rows", context).get().withScalarSetter(
                        "qa.encoded", "setEnabledFlag", Boolean.class,
                        Arrays.asList("qa.encoded", "qa.equals"));
        ConfigTestElement target = ownerWith(new ThrowingArgument());
        byte[] before = OpaqueEnvelope.save(target.getPropertyOrNull("qa.rows"));
        Map<String, Object> submitted = new LinkedHashMap<String, Object>();
        submitted.put("qa.encoded", Boolean.TRUE);

        assertThatThrownBy(() -> new RuntimeStructuredRowWriter().prepare(
                target,
                new PropertyPath(Collections.singletonList(
                        PropertyPathSegment.property("qa.rows"))),
                Collections.singletonList(submitted), context, evidence))
                .isInstanceOf(StructuredRowValueException.class)
                .hasMessageContaining("runtime scalar setter failed");

        assertThat(OpaqueEnvelope.save(target.getPropertyOrNull("qa.rows"))).isEqualTo(before);
        assertThat(target.getPropertyOrNull("qa.rows").getObjectValue())
                .isInstanceOf(Arguments.class);
    }

    private static ConfigTestElement ownerWith(ThrowingArgument row) {
        Arguments arguments = new Arguments();
        arguments.addArgument(row);
        ConfigTestElement owner = new ConfigTestElement();
        owner.setProperty(new TestElementProperty("qa.rows", arguments));
        return owner;
    }

    private static RuntimeContext context() {
        return new RuntimeContext("h41-security", new RuntimeFingerprint(
                "/mnt/d/tools/apache-jmeter-5.6.3", "5.6.3",
                Collections.singletonMap("core", "h41-security")));
    }

    public static final class ThrowingArgument extends Argument {
        public ThrowingArgument() {
            setProperty(new BooleanProperty("qa.encoded", false));
            setProperty(new BooleanProperty("qa.equals", false));
        }

        public void setEnabledFlag(boolean enabled) {
            setProperty("qa.encoded", enabled);
            setProperty("qa.equals", enabled);
            throw new IllegalStateException("setter exploded after mutation");
        }
    }
}
