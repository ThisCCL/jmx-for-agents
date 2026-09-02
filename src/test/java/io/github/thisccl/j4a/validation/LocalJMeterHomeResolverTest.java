package io.github.thisccl.j4a.validation;


import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalJMeterHomeResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void explicitHomeTakesPrecedenceOverAgentAndJMeterEnvironmentHomes() throws IOException {
        Path explicit = validHome("explicit");
        Path agent = validHome("agent");
        Path jmeter = validHome("jmeter");

        Map<String, String> environment = new java.util.LinkedHashMap<>();
        environment.put("JMX_AGENT_JMETER_HOME", agent.toString());
        environment.put("JMETER_HOME", jmeter.toString());

        Path resolved = new LocalJMeterHomeResolver()
                .resolve(explicit.toString(), environment)
                .get();

        assertThat(resolved).isEqualTo(explicit.toAbsolutePath().normalize());
    }

    @Test
    void agentEnvironmentHomeTakesPrecedenceOverJMeterHome() throws IOException {
        Path agent = validHome("agent");
        Path jmeter = validHome("jmeter");

        Map<String, String> environment = new java.util.LinkedHashMap<>();
        environment.put("JMX_AGENT_JMETER_HOME", agent.toString());
        environment.put("JMETER_HOME", jmeter.toString());

        Path resolved = new LocalJMeterHomeResolver()
                .resolve(null, environment)
                .get();

        assertThat(resolved).isEqualTo(agent.toAbsolutePath().normalize());
    }

    @Test
    void jmeterHomeIsUsedWhenAgentEnvironmentHomeIsAbsent() throws IOException {
        Path jmeter = validHome("jmeter");

        Path resolved = new LocalJMeterHomeResolver()
                .resolve(null, Collections.singletonMap("JMETER_HOME", jmeter.toString()))
                .get();

        assertThat(resolved).isEqualTo(jmeter.toAbsolutePath().normalize());
    }

    @Test
    void invalidHomeReportsRequiredLocalDirectoriesAndProperties() {
        Path invalid = tempDir.resolve("invalid");

        assertThatThrownBy(() -> new LocalJMeterHomeResolver().resolve(invalid.toString(), Collections.emptyMap()))
                .isInstanceOf(LocalJMeterEnvironmentException.class)
                .hasMessageContaining(invalid.resolve("bin").toAbsolutePath().normalize().toString())
                .hasMessageContaining(invalid.resolve("lib").toAbsolutePath().normalize().toString())
                .hasMessageContaining(invalid.resolve("lib").resolve("ext").toAbsolutePath().normalize().toString())
                .hasMessageContaining(invalid.resolve("bin").resolve("jmeter.properties").toAbsolutePath().normalize().toString())
                .hasMessageContaining(invalid.resolve("bin").resolve("saveservice.properties").toAbsolutePath().normalize().toString())
                .hasMessageContaining(invalid.resolve("bin").resolve("upgrade.properties").toAbsolutePath().normalize().toString());
    }

    @Test
    void validHomeDoesNotRequireOptionalUserOrSystemProperties() throws IOException {
        Path home = validHome("without-optional-properties");

        Path resolved = new LocalJMeterHomeResolver()
                .resolve(home.toString(), Collections.emptyMap())
                .get();

        assertThat(resolved).isEqualTo(home.toAbsolutePath().normalize());
    }

    @Test
    void invalidExplicitHomeDoesNotFallThroughToValidEnvironmentHome() throws IOException {
        Path invalid = tempDir.resolve("invalid-explicit");
        Path environmentHome = validHome("environment");

        assertThatThrownBy(() -> new LocalJMeterHomeResolver().resolve(
                invalid.toString(),
                Collections.singletonMap("JMX_AGENT_JMETER_HOME", environmentHome.toString())))
                .isInstanceOf(LocalJMeterEnvironmentException.class)
                .hasMessageContaining("--jmeter-home")
                .hasMessageContaining(invalid.toAbsolutePath().normalize().toString());
    }

    @Test
    void malformedExplicitHomeFailsWithoutEnvironmentFallback() throws IOException {
        Path environmentHome = validHome("environment-for-malformed-input");

        assertThatThrownBy(() -> new LocalJMeterHomeResolver().resolve(
                "malformed\u0000home",
                Collections.singletonMap("JMX_AGENT_JMETER_HOME", environmentHome.toString())))
                .isInstanceOf(LocalJMeterEnvironmentException.class)
                .hasMessageContaining("--jmeter-home")
                .hasMessageContaining("malformed");
    }

    @Test
    void symlinkAliasResolvesToSameRealHome() throws IOException {
        Path home = validHome("real-home");
        Path alias = tempDir.resolve("home-alias");
        Files.createSymbolicLink(alias, home);

        Path resolvedHome = new LocalJMeterHomeResolver()
                .resolve(home.toString(), Collections.emptyMap()).get();
        Path resolvedAlias = new LocalJMeterHomeResolver()
                .resolve(alias.toString(), Collections.emptyMap()).get();

        assertThat(resolvedAlias).isEqualTo(resolvedHome);
        assertThat(resolvedHome).isEqualTo(home.toRealPath());
    }

    private Path validHome(String name) throws IOException {
        Path home = tempDir.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib").resolve("ext"));
        Files.write(home.resolve("bin").resolve("jmeter.properties"), "".getBytes(StandardCharsets.UTF_8));
        Files.write(home.resolve("bin").resolve("saveservice.properties"), "".getBytes(StandardCharsets.UTF_8));
        Files.write(home.resolve("bin").resolve("upgrade.properties"), "".getBytes(StandardCharsets.UTF_8));
        return home;
    }
}
