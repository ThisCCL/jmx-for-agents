package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.junit.jupiter.api.Test;

class RuntimeComponentIdentityContractTest {
    private static final String HTTP_COMPONENT =
            "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui";
    private static final String CSV_COMPONENT =
            "org.apache.jmeter.config.CSVDataSet";
    private static final String JSR223_COMPONENT =
            "org.apache.jmeter.modifiers.JSR223PreProcessor";
    private static final String GENERIC_TEST_BEAN_GUI =
            "org.apache.jmeter.testbeans.gui.TestBeanGUI";

    private final DefaultLocalProfileQaFixtures fixtures =
            new DefaultLocalProfileQaFixtures();

    @Test
    void nonTestBeanRegistryEntryMaterializesItsExactRuntimeElementClass() throws Exception {
        LocalJMeterWorkerRuntime.initialize(io.github.thisccl.j4a.TestJMeterRuntime.home());
        LocalJMeterMenuRegistry registry = LocalJMeterMenuRegistry.current();
        LocalJMeterMenuRegistry.Entry entry = registry.resolve(HTTP_COMPONENT)
                .orElseThrow(() -> new AssertionError("Missing HTTP sampler registry entry"));

        TestElement materialized = LocalJMeterElementMaterializer.create(entry);

        assertThat(entry.component()).isEqualTo(HTTP_COMPONENT);
        assertThat(materialized).isExactlyInstanceOf(HTTPSamplerProxy.class);
        assertThat(materialized.getClass().getName())
                .isEqualTo(HTTPSamplerProxy.class.getName());
        assertThat(registry.identityResolver().resolve(materialized))
                .isEqualTo(HTTP_COMPONENT);
    }

    @Test
    void genericTestBeansPublishExactRuntimeIdentityAcrossCatalogAddAndReloadedRead()
            throws Exception {
        fixtures.ensure();
        LocalJMeterWorkerClient client = new LocalJMeterWorkerClient();

        LocalJMeterWorkerResponse catalog = client.execute(
                LocalJMeterWorkerRequest.discoverComponents(fixtures.localHome())).response();

        assertThat(catalog.success()).as(catalog.toJsonLine()).isTrue();
        assertThat(catalog.payload()).contains(
                "component: " + CSV_COMPONENT,
                "component: " + JSR223_COMPONENT);
        assertThat(catalog.payload()).doesNotContain("component: " + GENERIC_TEST_BEAN_GUI);

        Path output = fixtures.root().resolve("out/runtime-component-identity.jmx");
        String patch = "changes:\n"
                + "  - add:\n"
                + "      parent: jmx_19871e6efa95\n"
                + "      position: last\n"
                + "      component: " + CSV_COMPONENT + "\n"
                + "  - add:\n"
                + "      parent: jmx_19871e6efa95\n"
                + "      position: last\n"
                + "      component: " + JSR223_COMPONENT + "\n";

        LocalJMeterWorkerResponse applied = client.execute(
                LocalJMeterWorkerRequest.applyPatchYaml(
                        fixtures.root().resolve("basic.jmx"), fixtures.localHome(),
                        patch, output, true)).response();

        assertThat(applied.success()).as(applied.toJsonLine()).isTrue();
        String persisted = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertThat(persisted)
                .contains("<CSVDataSet guiclass=\"TestBeanGUI\"",
                        "<JSR223PreProcessor guiclass=\"TestBeanGUI\"")
                .doesNotContain("testclass=\"TestBeanGUI\"");

        LocalJMeterWorkerResponse reloadedRead = client.execute(
                LocalJMeterWorkerRequest.renderReadData(
                        output, fixtures.localHome(), "2", null, "NONE", "false"))
                .response();

        assertThat(reloadedRead.success()).as(reloadedRead.toJsonLine()).isTrue();
        assertThat(reloadedRead.payload()).contains(
                "component: " + CSV_COMPONENT,
                "component: " + JSR223_COMPONENT);
        assertThat(reloadedRead.payload()).doesNotContain(
                "component: " + GENERIC_TEST_BEAN_GUI);

        Path rejectedOutput = fixtures.root().resolve("out/rejected-generic-gui.jmx");
        LocalJMeterWorkerResponse rejected = client.execute(
                LocalJMeterWorkerRequest.applyPatchYaml(
                        fixtures.root().resolve("basic.jmx"), fixtures.localHome(),
                        "changes:\n"
                                + "  - add:\n"
                                + "      parent: jmx_19871e6efa95\n"
                                + "      position: last\n"
                                + "      component: " + GENERIC_TEST_BEAN_GUI + "\n",
                        rejectedOutput, true)).response();

        assertThat(rejected.success()).isFalse();
        assertThat(rejected.errorCode()).isEqualTo("COMPONENT_IDENTITY_NOT_FOUND");
        assertThat(rejectedOutput).doesNotExist();
    }
}
