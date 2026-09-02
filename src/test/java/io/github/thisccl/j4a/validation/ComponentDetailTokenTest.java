package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.components.ComponentCatalog;
import io.github.thisccl.j4a.jmx.property.RuntimeFingerprint;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComponentDetailTokenTest {
    @TempDir java.nio.file.Path tempDir;
    private String previousUserHome;

    @BeforeEach
    void isolateUserHome() throws Exception {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(tempDir.resolve("home")).toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (previousUserHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousUserHome);
    }

    @Test
    void tokenIsFixedSizeNonIdentifyingSignedAndStatelesslyResolvesOneExactEntry() {
        RuntimeFingerprint runtime = runtime("/runtime");
        String component = "org.example.SecretComponentName";
        String token = ComponentDetailToken.encode(
                runtime, "sampler", ComponentCategoryCursor.PROJECTION, component);
        ComponentCatalog.ComponentDefinition definition = definition("sampler", component);

        assertThat(token).startsWith("ct1.").hasSizeLessThanOrEqualTo(512)
                .doesNotContain(component, "SecretComponentName");
        assertThat(ComponentDetailToken.resolve(token, runtime, Collections.singletonList(definition)))
                .isEqualTo(component);

        int signature = token.lastIndexOf('.') + 1;
        String tampered = token.substring(0, signature)
                + (token.charAt(signature) == 'A' ? "B" : "A") + token.substring(signature + 1);
        assertThatThrownBy(() -> ComponentDetailToken.resolve(
                tampered, runtime, Collections.singletonList(definition)))
                .isInstanceOf(ComponentsCursorException.class)
                .hasMessageContaining("Invalid component token");
        assertThatThrownBy(() -> ComponentDetailToken.resolve(
                token, runtime("/other"), Collections.singletonList(definition)))
                .isInstanceOf(ComponentsCursorException.class);
        assertThatThrownBy(() -> ComponentDetailToken.resolve(
                token, runtime, Arrays.asList(definition("sampler", "org.example.Other"))))
                .isInstanceOf(ComponentsCursorException.class)
                .hasMessageContaining("no longer matches");
        assertThatThrownBy(() -> ComponentDetailToken.resolve(
                token, runtime, Arrays.asList(definition, definition("sampler", component))))
                .isInstanceOf(ComponentsCursorException.class)
                .hasMessageContaining("more than one");
        assertThatThrownBy(() -> ComponentDetailToken.resolve(
                token, runtime, Collections.singletonList(definition("timer", component))))
                .isInstanceOf(ComponentsCursorException.class)
                .hasMessageContaining("category or projection");
    }

    private static ComponentCatalog.ComponentDefinition definition(String category, String component) {
        return new ComponentCatalog.ComponentDefinition(category, category, component, component,
                Collections.<ComponentCatalog.ComponentProperty>emptyList(), component, "test");
    }

    private static RuntimeFingerprint runtime(String home) {
        return new RuntimeFingerprint(home, "5.6.3",
                Collections.singletonMap("lib/example.jar", "abc123"));
    }
}
