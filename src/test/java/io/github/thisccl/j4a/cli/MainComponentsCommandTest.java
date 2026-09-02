package io.github.thisccl.j4a.cli;

import static io.github.thisccl.j4a.cli.MainCliTestSupport.assertUsageError;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.runMain;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class MainComponentsCommandTest {
    @Test
    void componentsListsRuntimeDiscoveredComponents() throws IOException {
        DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();
        Map<String, String> environment = localEnvironment(fixtures);

        CliTestResult result = runMain(environment, "components");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout()).contains(
                "component: io.github.thisccl.j4a.synthetic.ExtOnlySamplerGui",
                "component: org.apache.jmeter.protocol.http.control.gui.GraphQLHTTPSamplerGui");
        assertThat(result.stdout()).doesNotContain(
                "profile:", "status:", "allowed_parents:", "placements:");
    }

    @Test
    void selectedComponentDefaultsToCompactWritableAuthoringDetails() throws IOException {
        DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();

        CliTestResult result = runMain(localEnvironment(fixtures),
                "components", "io.github.thisccl.j4a.synthetic.ExtOnlySamplerGui");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stderr()).isEmpty();
        Map<String, Object> document = mapping(new Yaml().load(result.stdout()));
        assertThat(document).containsEntry(
                "component", "io.github.thisccl.j4a.synthetic.ExtOnlySamplerGui");
        for (Object value : list(document.get("properties"))) {
            assertThat(mapping(value).keySet()).isSubsetOf(
                    "property", "type", "default", "value_shape", "row_type",
                    "row_properties", "value_template");
        }
        assertThat(result.stdout()).doesNotContain(
                "key:", "writable:", "reason:", "ownership:", "representation_source:",
                "required_property_class:", "required_value_class:");
    }

    @Test
    void componentsAlwaysRenderScalarArraysAndPunctuationNeedsNoRecovery() throws IOException {
        DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();
        Map<String, String> environment = localEnvironment(fixtures);
        String ordinary = "io.github.thisccl.j4a.synthetic.ExtOnlySamplerGui";
        String unrepresentable = DefaultLocalProfileQaFixtures.UNREPRESENTABLE_ADDRESS_PLUGIN_CLASS + "Gui";

        CliTestResult defaultResult = runMain(environment, "components", ordinary);
        CliTestResult removedMode = runMain(Collections.<String, String>emptyMap(),
                "components", ordinary, "--property-address", "segments");
        CliTestResult punctuation = runMain(environment, "components", unrepresentable);

        assertThat(defaultResult.exitCode()).as(defaultResult.stderr()).isZero();
        assertUsageError(removedMode, "Unknown option");
        assertThat(removedMode.stderr()).doesNotContain("LOCAL_JMETER_RUNTIME_ERROR");
        assertThat(list(mapping(new Yaml().load(defaultResult.stdout())).get("properties")))
                .allSatisfy(property -> assertThat(mapping(property).get("property"))
                        .isInstanceOf(List.class));
        assertThat(punctuation.exitCode()).as(punctuation.stderr()).isZero();
    }

    @Test
    void componentsRendersPunctuationAddressesAsScalarArraysWithoutARenderingException() throws IOException {
        DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();
        Map<String, String> environment = localEnvironment(fixtures);
        String component = DefaultLocalProfileQaFixtures.UNREPRESENTABLE_ADDRESS_PLUGIN_CLASS + "Gui";

        CliTestResult result = runMain(environment, "components", component);

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        List<Object> properties = list(mapping(new Yaml().load(result.stdout())).get("properties"));
        assertThat(properties).allSatisfy(property -> {
            Object address = mapping(property).get("property");
            assertThat(address).isInstanceOf(List.class);
            assertThat(list(address)).isNotEmpty().allSatisfy(segment -> assertThat(segment)
                    .isInstanceOfAny(String.class, Integer.class));
        });
    }

    @Test
    void detailsTrueKeepsOrdinaryProjectionAndDiagnosticsTrueSelectsCapabilityProjection() throws IOException {
        DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();
        Map<String, String> environment = localEnvironment(fixtures);
        String component = "io.github.thisccl.j4a.synthetic.ExtOnlySamplerGui";

        CliTestResult ordinary = runMain(environment, "components", component);
        CliTestResult details = runMain(environment, "components", component, "--details", "true");
        CliTestResult diagnostics = runMain(
                environment, "components", component, "--diagnostics", "true");

        assertThat(details.exitCode()).as(details.stderr()).isZero();
        assertThat(diagnostics.exitCode()).as(diagnostics.stderr()).isZero();
        assertThat(details.stdout()).isEqualTo(ordinary.stdout());
        Map<String, Object> ordinaryDocument = mapping(new Yaml().load(ordinary.stdout()));
        Map<String, Object> diagnosticDocument = mapping(new Yaml().load(diagnostics.stdout()));
        assertThat(ordinaryDocument).doesNotContainKey("runtime_metadata_status");
        assertThat(diagnosticDocument).containsEntry("runtime_metadata_status", "runtime-proven");
        assertThat(diagnosticDocument.keySet()).containsExactly(
                "component", "label", "category", "runtime_metadata_status", "properties");
        assertThat(diagnostics.stdout()).contains(
                "key:", "writable:", "ownership:", "representation_source:");
    }

    @Test
    void componentsHelpDefinesMetadataAvailabilityWithoutClaimingGuiCompleteness() {
        CliTestResult help = runMain(Collections.<String, String>emptyMap(), "components", "--help");

        assertThat(help.exitCode()).as(help.stderr()).isZero();
        assertThat(help.stdout()).contains(
                "runtime_metadata_status", "metadata-source availability", "not GUI completeness");
    }

    @Test
    void componentsStrictlyRejectsInvalidCategoryCombinations() {
        assertUsageError(runMain(Collections.<String, String>emptyMap(), "components", "--details"),
                "--details requires the value true");
        assertUsageError(runMain(Collections.<String, String>emptyMap(),
                "components", "--details", "true"), "--details requires a component");
        assertUsageError(runMain(Collections.<String, String>emptyMap(),
                "components", "--diagnostics", "true"), "--diagnostics requires a component");
        assertUsageError(runMain(Collections.<String, String>emptyMap(),
                "components", "example.Component", "--details", "false"),
                "--details must be true");
        assertUsageError(runMain(Collections.<String, String>emptyMap(),
                "components", "example.Component", "--diagnostics", "false"),
                "--diagnostics must be true");
        assertUsageError(runMain(Collections.<String, String>emptyMap(),
                "components", "--category", "sampler", "--diagnostics", "true"),
                "--category cannot be combined with --diagnostics");
        assertUsageError(runMain(Collections.<String, String>emptyMap(),
                "components", "--category", "sampler", "http.request"),
                "--category cannot be combined with a component");
        assertUsageError(runMain(Collections.<String, String>emptyMap(),
                "components", "--category", "sampler", "--limit", "2"),
                "--limit and --cursor require --category with --details true");
    }

    @Test
    void componentsRejectsStaleFormatFlag() {
        CliTestResult result = runMain(Collections.<String, String>emptyMap(), "components", "--format");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains(
                "Error code: USAGE_ERROR", "--format is not supported", "components emits YAML");
    }

    @Test
    void componentsWithoutResolvedHomeFailsClosed() {
        CliTestResult result = runMain(Collections.<String, String>emptyMap(), "components");

        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains(
                "Error code: LOCAL_JMETER_RUNTIME_ERROR",
                "No valid local JMeter home resolved",
                "Component: local JMeter runtime");
        assertThat(result.stderr()).doesNotContain("component: http.request");
    }

    @Test
    void componentsUnknownRuntimeIdentityFailsWithUsageError() throws IOException {
        DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();

        CliTestResult result = runMain(localEnvironment(fixtures), "components", "not.supported");

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains(
                "Error code: COMPONENT_IDENTITY_NOT_FOUND", "not.supported", "rerun components");
    }

    @Test
    void componentsAcceptsPublicCategoryAndApprovedAliasButEmitsOnlyPublicCategory() throws IOException {
        DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();
        Map<String, String> environment = localEnvironment(fixtures);

        CliTestResult publicResult = runMain(environment, "components", "--category", "sampler");
        CliTestResult aliasResult = runMain(
                environment, "components", "--category", "menu_generative_controller");

        assertThat(publicResult.exitCode()).as(publicResult.stderr()).isZero();
        assertThat(aliasResult.exitCode()).as(aliasResult.stderr()).isZero();
        assertThat(aliasResult.stdout()).isEqualTo(publicResult.stdout());
        assertThat(publicResult.stdout()).contains(
                "category: sampler",
                "component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        assertThat(publicResult.stdout()).doesNotContain("menu_generative_controller");
    }

    @Test
    void componentsUnknownCategoriesUseTypedUsageErrorAndExactRecovery() throws IOException {
        DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();
        Map<String, String> environment = localEnvironment(fixtures);

        for (String category : new String[] {
                "menu_plugin_x", "not-a-category", " sampler ", "../sampler"}) {
            CliTestResult result = runMain(environment, "components", "--category", category);

            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(result.stdout()).isEmpty();
            assertThat(result.stderr()).contains(
                    "Error code: USAGE_ERROR",
                    "Category: usage",
                    "Unknown component category: " + category,
                    "rerun categories ls to list valid category ids, then retry components --category.");
            assertThat(result.stderr().toLowerCase()).doesNotContain(
                    "cause chain", "runtime menu", "unavailable");
        }
    }

    private static Map<String, String> localEnvironment(DefaultLocalProfileQaFixtures fixtures) throws IOException {
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("JMX_AGENT_JMETER_HOME", fixtures.localHome().toString());
        return environment;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
