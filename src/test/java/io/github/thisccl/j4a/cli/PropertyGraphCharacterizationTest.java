package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.SaveServiceJmxLoader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.control.Cookie;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.DNSCacheManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.control.StaticHost;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jorphan.collections.HashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

class PropertyGraphCharacterizationTest {
    private static final String JMETER_HOME_PROPERTY = "j4a.test.jmeterHome";
    private static final String DEFAULT_JMETER_HOME =
            io.github.thisccl.j4a.TestJMeterRuntime.home().toString();
    private static final String FIXTURE_PLAN_NAME = "Property Graph Conformance";
    private static final String FIXTURE_DOCUMENTATION =
            "Sanitized repository fixture for property graph conformance.";
    private static final String ASSERTION_REF = "jmx_99fb165049e0";
    private static final String HTTP_REQUEST_REF = "jmx_330976848c8e";
    private static final String HTTP_COMPONENT =
            "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui";
    private static final String TEST_STRINGS = "Asserion.test_strings";

    private static final String EXPECTED_PUBLIC_PROPERTY_YAML = ""
            + "- property:\n"
            + "  - HTTPsampler.Arguments\n"
            + "  value:\n"
            + "    row_type: org.apache.jmeter.config.Argument\n"
            + "    row_properties:\n"
            + "    - name: Argument.name\n"
            + "      type: string\n"
            + "      required: true\n"
            + "    - name: Argument.value\n"
            + "      type: string\n"
            + "      required: true\n"
            + "    - name: Argument.metadata\n"
            + "      type: string\n"
            + "      required: true\n"
            + "    rows:\n"
            + "    - Argument.name: branch_no\n"
            + "      Argument.value: '1001'\n"
            + "      Argument.metadata: =\n"
            + "  type: rows\n"
            + "- property:\n"
            + "  - HeaderManager.headers\n"
            + "  value:\n"
            + "    row_type: org.apache.jmeter.protocol.http.control.Header\n"
            + "    row_properties:\n"
            + "    - name: Header.name\n"
            + "      type: string\n"
            + "      required: false\n"
            + "      default: ''\n"
            + "    - name: Header.value\n"
            + "      type: string\n"
            + "      required: false\n"
            + "      default: ''\n"
            + "    rows:\n"
            + "    - Header.name: X-Trace\n"
            + "      Header.value: trace-123\n"
            + "  type: rows\n"
            + "- property:\n"
            + "  - CookieManager.cookies\n"
            + "  value:\n"
            + "    row_type: org.apache.jmeter.protocol.http.control.Cookie\n"
            + "    row_properties:\n"
            + "    - name: TestElement.name\n"
            + "      type: string\n"
            + "      required: true\n"
            + "    - name: Cookie.value\n"
            + "      type: string\n"
            + "      required: true\n"
            + "    - name: Cookie.domain\n"
            + "      type: string\n"
            + "      required: true\n"
            + "    - name: Cookie.path\n"
            + "      type: string\n"
            + "      required: true\n"
            + "    - name: Cookie.secure\n"
            + "      type: boolean\n"
            + "      required: true\n"
            + "    - name: Cookie.expires\n"
            + "      type: long\n"
            + "      required: true\n"
            + "    - name: Cookie.path_specified\n"
            + "      type: boolean\n"
            + "      required: true\n"
            + "    - name: Cookie.domain_specified\n"
            + "      type: boolean\n"
            + "      required: true\n"
            + "    rows:\n"
            + "    - TestElement.name: sid\n"
            + "      Cookie.value: cookie-value\n"
            + "      Cookie.domain: example.test\n"
            + "      Cookie.path: /\n"
            + "      Cookie.secure: true\n"
            + "      Cookie.expires: 1700000000\n"
            + "      Cookie.path_specified: true\n"
            + "      Cookie.domain_specified: true\n"
            + "  type: rows\n"
            + "- property:\n"
            + "  - AuthManager.auth_list\n"
            + "  value:\n"
            + "    row_type: org.apache.jmeter.protocol.http.control.Authorization\n"
            + "    row_properties:\n"
            + "    - name: Authorization.url\n"
            + "      type: string\n"
            + "      required: true\n"
            + "    - name: Authorization.username\n"
            + "      type: string\n"
            + "      required: true\n"
            + "    - name: Authorization.password\n"
            + "      type: string\n"
            + "      required: true\n"
            + "    - name: Authorization.domain\n"
            + "      type: string\n"
            + "      required: true\n"
            + "    - name: Authorization.realm\n"
            + "      type: string\n"
            + "      required: true\n"
            + "    rows:\n"
            + "    - Authorization.url: https://example.test\n"
            + "      Authorization.username: agent-user\n"
            + "      Authorization.password: example-password\n"
            + "      Authorization.domain: example.test\n"
            + "      Authorization.realm: example-realm\n"
            + "  type: rows\n"
            + "- property:\n"
            + "  - HTTPsampler.Files\n"
            + "  value:\n"
            + "    row_type: org.apache.jmeter.protocol.http.util.HTTPFileArg\n"
            + "    row_properties:\n"
            + "    - name: File.mimetype\n"
            + "      type: string\n"
            + "      required: false\n"
            + "      default: ''\n"
            + "    - name: File.path\n"
            + "      type: string\n"
            + "      required: false\n"
            + "      default: ''\n"
            + "    - name: File.paramname\n"
            + "      type: string\n"
            + "      required: false\n"
            + "      default: ''\n"
            + "    rows:\n"
            + "    - File.mimetype: text/plain\n"
            + "      File.path: /tmp/payload.txt\n"
            + "      File.paramname: upload\n"
            + "  type: rows\n"
            + "- property:\n"
            + "  - DNSCacheManager.servers\n"
            + "  value:\n"
            + "    presence: present\n"
            + "    property_class: org.apache.jmeter.testelement.property.CollectionProperty\n"
            + "    items:\n"
            + "    - type: string\n"
            + "      presence: present\n"
            + "      property_class: org.apache.jmeter.testelement.property.StringProperty\n"
            + "      value: 192.0.2.53\n"
            + "  type: collection\n"
            + "- property:\n"
            + "  - DNSCacheManager.hosts\n"
            + "  value:\n"
            + "    row_type: org.apache.jmeter.protocol.http.control.StaticHost\n"
            + "    row_properties:\n"
            + "    - name: StaticHost.Name\n"
            + "      type: string\n"
            + "      required: true\n"
            + "    - name: StaticHost.Address\n"
            + "      type: string\n"
            + "      required: true\n"
            + "    rows:\n"
            + "    - StaticHost.Name: service.example.test\n"
            + "      StaticHost.Address: 192.0.2.10\n"
            + "  type: rows\n";

    @TempDir
    Path tempDir;

    @Test
    void repositoryFixturePreservesRefAndIdentityOmission() throws Exception {
        Path fixture = fixture("response-assertion.jmx");
        Path refFixture = fixture("response-assertion-ref.txt");

        assertThat(Files.readAllLines(refFixture, StandardCharsets.UTF_8)).containsExactly(ASSERTION_REF);

        CliTestResult result = runCli("", "read", fixture.toString(), "--depth", "4", "--properties", "all",
                "--jmeter-home", jmeterHome().toString());

        assertSuccessful(result);
        Map<String, Object> root = mapping(CliYamlAssertions.parseMapping(result.stdout()).get("root"));
        assertThat(root.get("name")).isEqualTo(FIXTURE_PLAN_NAME);
        assertThat(requireProperty(list(root.get("properties")), "TestPlan\\.comments"))
                .isEqualTo(scalar("TestPlan\\.comments", FIXTURE_DOCUMENTATION, "string"));
        Map<String, Object> assertion = requireNode(root, "Sanitized Response Assertion");
        assertThat(assertion.get("ref")).isEqualTo(ASSERTION_REF);
        assertThat(allPropertyPaths(root)).noneMatch(path -> path.contains("TestElement.gui_class")
                || path.contains("TestElement.test_class"));
        System.out.println("ASSERTION_REF=" + ASSERTION_REF);
        System.out.println("FIXTURE_DOCUMENTATION=" + FIXTURE_DOCUMENTATION);
    }

    @Test
    void rawSaveServicePersistsResponseAssertionTestStrings() {
        SaveServiceJmxLoader loader = new SaveServiceJmxLoader(jmeterHome());
        JmxTestPlan loaded = loader.load(fixture("response-assertion.jmx"));

        assertResponseAssertion(loaded);

        Path saved = tempDir.resolve("response-assertion-round-trip.jmx");
        loader.save(loaded, saved);
        assertThat(saved).isRegularFile().isNotEmptyFile();
        assertResponseAssertion(loader.load(saved));
    }

    @Test
    void scalarReadSetAndApplyPreserveCurrentTypesAndValues() throws Exception {
        Path repositoryFixture = fixture("response-assertion.jmx");
        byte[] repositoryBytes = Files.readAllBytes(repositoryFixture);
        SaveServiceJmxLoader loader = new SaveServiceJmxLoader(jmeterHome());
        Path scalarInput = tempDir.resolve("scalar-input.jmx");
        JmxTestPlan plan = loader.load(repositoryFixture);
        HTTPSamplerProxy sampler = plan.depthFirstTestElements().stream()
                .filter(HTTPSamplerProxy.class::isInstance)
                .map(HTTPSamplerProxy.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing HTTP sampler"));
        sampler.setProperty(new StringProperty("characterization.string", "before"));
        sampler.setProperty(new BooleanProperty("characterization.boolean", false));
        sampler.setProperty(new IntegerProperty("characterization.int", 7));
        sampler.setProperty(new LongProperty("characterization.long", 8L));
        sampler.setProperty(new DoubleProperty("characterization.double", 1.25D));
        sampler.setProperty(new StringProperty("characterization.raw", "raw-before"));
        loader.save(plan, scalarInput);

        List<Map<String, Object>> initialRecords = readScalarRecords(scalarInput);
        assertThat(initialRecords).containsExactly(
                scalar("characterization\\.string", "before", "string"),
                scalar("characterization\\.boolean", false, "boolean"),
                scalar("characterization\\.int", 7, "int"),
                scalar("characterization\\.long", 8, "long"),
                scalar("characterization\\.double", 1.25D, "double"),
                scalar("characterization\\.raw", "raw-before", "string"));

        Path setOutput = tempDir.resolve("scalar-set.jmx");
        CliTestResult set = runCli("", "set", scalarInput.toString(), "--locator", HTTP_REQUEST_REF,
                "--property", "[\"characterization.string\"]", "--value", "after-set", "--type", "string",
                "--out", setOutput.toString(), "--jmeter-home", jmeterHome().toString());
        assertSuccessful(set);
        List<Map<String, Object>> setRecords = readScalarRecords(setOutput);
        assertThat(setRecords).containsExactly(
                scalar("characterization\\.string", "after-set", "string"),
                scalar("characterization\\.boolean", false, "boolean"),
                scalar("characterization\\.int", 7, "int"),
                scalar("characterization\\.long", 8, "long"),
                scalar("characterization\\.double", 1.25D, "double"),
                scalar("characterization\\.raw", "raw-before", "string"));

        Path applyOutput = tempDir.resolve("scalar-apply.jmx");
        CliTestResult apply = runCli(scalarPatch(), "apply", setOutput.toString(), "--patch", "-",
                "--out", applyOutput.toString(), "--jmeter-home", jmeterHome().toString());
        assertSuccessful(apply);

        List<Map<String, Object>> applyRecords = readScalarRecords(applyOutput);
        assertThat(applyRecords).containsExactly(
                scalar("characterization\\.string", "after-apply", "string"),
                scalar("characterization\\.boolean", true, "boolean"),
                scalar("characterization\\.int", 42, "int"),
                scalar("characterization\\.long", 1234567890123L, "long"),
                scalar("characterization\\.double", 12.5D, "double"),
                scalar("characterization\\.raw", "raw-after", "string"));
        assertScalarPropertyClasses(loader.load(applyOutput));
        assertThat(Files.readAllBytes(repositoryFixture)).containsExactly(repositoryBytes);
        System.out.println("SCALAR_READ_EXACT=" + initialRecords);
        System.out.println("SCALAR_SET_EXACT=" + setRecords);
        System.out.println("SCALAR_APPLY_EXACT=" + applyRecords);
    }

    @Test
    void malformedYamlAndPropertyPathAreRejectedWithoutWriting() throws Exception {
        Path repositoryFixture = fixture("response-assertion.jmx");
        byte[] repositoryBytes = Files.readAllBytes(repositoryFixture);
        Path malformedOutput = tempDir.resolve("malformed-yaml-output.jmx");

        CliTestResult malformedYaml = runCli("changes:\n  - set: [\n", "apply", repositoryFixture.toString(),
                "--patch", "-", "--out", malformedOutput.toString(),
                "--jmeter-home", jmeterHome().toString());

        assertThat(malformedYaml.exitCode()).isNotZero();
        assertThat(malformedYaml.stdout()).isEmpty();
        assertThat(malformedYaml.stderr())
                .contains("PATCH_PARSE_ERROR")
                .contains("fix the YAML patch");
        assertThat(malformedOutput).doesNotExist();

        Path invalidPathOutput = tempDir.resolve("invalid-property-path-output.jmx");
        CliTestResult invalidPath = runCli("", "set", repositoryFixture.toString(),
                "--locator", ASSERTION_REF, "--property", "[\"Asserion\\qtest_strings\"]",
                "--value", "unchanged", "--type", "string", "--out", invalidPathOutput.toString(),
                "--jmeter-home", jmeterHome().toString());

        assertThat(invalidPath.exitCode()).isNotZero();
        assertThat(invalidPath.stdout()).isEmpty();
        assertThat(invalidPath.stderr())
                .contains("Error code: USAGE_ERROR")
                .contains("unsupported JSON escape")
                .contains("Suggested next action:")
                .contains("fix the structured value");
        assertThat(invalidPathOutput).doesNotExist();
        assertThat(Files.readAllBytes(repositoryFixture)).containsExactly(repositoryBytes);
        System.out.println("MALFORMED_YAML_EXIT=" + malformedYaml.exitCode());
        System.out.println("INVALID_PROPERTY_PATH_EXIT=" + invalidPath.exitCode());
        System.out.println("MALFORMED_INPUT_NO_WRITE=true");
    }

    @Test
    void publicReadEmitsExactUniversalRowsYamlWithRuntimeProvenDefaults() {
        Path semanticFixture = tempDir.resolve("universal-rows.jmx");
        SaveServiceJmxLoader loader = new SaveServiceJmxLoader(jmeterHome());
        JmxTestPlan plan = loader.load(fixture("response-assertion.jmx"));
        addSemanticValues(plan);
        loader.save(plan, semanticFixture);

        CliTestResult read = runCli("", "read", semanticFixture.toString(), "--depth", "6", "--properties", "all",
                "--jmeter-home", jmeterHome().toString());
        assertSuccessful(read);
        Map<String, Object> root = mapping(CliYamlAssertions.parseMapping(read.stdout()).get("root"));
        List<Map<String, Object>> documents = Arrays.asList(
                propertyFromNode(root, "Conformance HTTP Request", "HTTPsampler\\.Arguments"),
                propertyFromNode(root, "Characterization Headers", "HeaderManager\\.headers"),
                propertyFromNode(root, "Characterization Cookies", "CookieManager\\.cookies"),
                propertyFromNode(root, "Characterization Authorization", "AuthManager\\.auth_list"),
                propertyFromNode(root, "Conformance HTTP Request", "HTTPsampler\\.Files"),
                propertyFromNode(root, "Characterization DNS", "DNSCacheManager\\.servers"),
                propertyFromNode(root, "Characterization DNS", "DNSCacheManager\\.hosts"));

        String actualYaml = exactYaml(documents);
        assertThat(actualYaml).isEqualTo(EXPECTED_PUBLIC_PROPERTY_YAML);
        System.out.println("PUBLIC_PROPERTY_READ_ROUTE=Main read --properties all");
        System.out.println("UNIVERSAL_ROWS_YAML_BEGIN");
        System.out.print(actualYaml);
        System.out.println("UNIVERSAL_ROWS_YAML_END");
    }

    private static void assertResponseAssertion(JmxTestPlan plan) {
        ResponseAssertion assertion = plan.depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Response Assertion"));
        JMeterProperty property = assertion.getPropertyOrNull(TEST_STRINGS);

        assertThat(property).isExactlyInstanceOf(CollectionProperty.class);
        List<JMeterProperty> items = new ArrayList<>();
        for (JMeterProperty item : (CollectionProperty) property) {
            items.add(item);
        }
        assertThat(items)
                .extracting(JMeterProperty::getStringValue)
                .containsExactly("login failed", "access denied");
        assertThat(items)
                .allSatisfy(item -> assertThat(item).isExactlyInstanceOf(StringProperty.class));
    }

    private static void assertScalarPropertyClasses(JmxTestPlan plan) {
        TestElement sampler = plan.depthFirstTestElements().stream()
                .filter(element -> "Conformance HTTP Request".equals(element.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing HTTP sampler"));

        assertThat(sampler.getPropertyOrNull("characterization.string")).isExactlyInstanceOf(StringProperty.class);
        assertThat(sampler.getPropertyOrNull("characterization.boolean")).isExactlyInstanceOf(BooleanProperty.class);
        assertThat(sampler.getPropertyOrNull("characterization.int")).isExactlyInstanceOf(IntegerProperty.class);
        assertThat(sampler.getPropertyOrNull("characterization.long")).isExactlyInstanceOf(LongProperty.class);
        assertThat(sampler.getPropertyOrNull("characterization.double")).isExactlyInstanceOf(DoubleProperty.class);
        assertThat(sampler.getPropertyOrNull("characterization.raw")).isExactlyInstanceOf(StringProperty.class);
    }

    private static List<Map<String, Object>> readScalarRecords(Path file) {
        CliTestResult read = runCli("", "read", file.toString(), "--ref", HTTP_REQUEST_REF,
                "--properties", "all", "--jmeter-home", jmeterHome().toString());
        assertSuccessful(read);
        Map<String, Object> focus = mapping(CliYamlAssertions.parseMapping(read.stdout()).get("focus"));
        List<Object> properties = list(focus.get("properties"));
        List<Map<String, Object>> records = new ArrayList<>();
        for (String path : Arrays.asList(
                "characterization\\.string",
                "characterization\\.boolean",
                "characterization\\.int",
                "characterization\\.long",
                "characterization\\.double",
                "characterization\\.raw")) {
            records.add(requireProperty(properties, path));
        }
        return records;
    }

    private static Map<String, Object> scalar(String property, Object value, String type) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("property", address(property));
        record.put("value", value);
        record.put("type", type);
        return record;
    }

    private static String scalarPatch() {
        return "changes:\n"
                + "  - set:\n"
                + "      ref: " + HTTP_REQUEST_REF + "\n"
                + "      component: " + HTTP_COMPONENT + "\n"
                + "      properties:\n"
                + "        - property: [characterization.string]\n"
                + "          value: after-apply\n"
                + "          type: string\n"
                + "        - property: [characterization.boolean]\n"
                + "          value: true\n"
                + "          type: boolean\n"
                + "        - property: [characterization.int]\n"
                + "          value: 42\n"
                + "          type: int\n"
                + "        - property: [characterization.long]\n"
                + "          value: 1234567890123\n"
                + "          type: long\n"
                + "        - property: [characterization.double]\n"
                + "          value: 12.5\n"
                + "          type: double\n"
                + "        - property: [characterization.raw]\n"
                + "          value: raw-after\n"
                + "          type: string\n";
    }

    private static void addSemanticValues(JmxTestPlan plan) {
        HTTPSamplerProxy sampler = plan.depthFirstTestElements().stream()
                .filter(HTTPSamplerProxy.class::isInstance)
                .map(HTTPSamplerProxy.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing HTTP sampler"));
        Arguments arguments = new Arguments();
        arguments.addArgument(new Argument("branch_no", "1001", "="));
        sampler.setArguments(arguments);
        sampler.setHTTPFiles(new HTTPFileArg[] {
                new HTTPFileArg("/tmp/payload.txt", "upload", "text/plain")});

        HeaderManager headers = named(new HeaderManager(), "Characterization Headers");
        headers.setProperty(TestElement.GUI_CLASS, "HeaderPanel");
        headers.setProperty(TestElement.TEST_CLASS, "HeaderManager");
        headers.add(new Header("X-Trace", "trace-123"));

        CookieManager cookies = named(new CookieManager(), "Characterization Cookies");
        cookies.setProperty(TestElement.GUI_CLASS, "CookiePanel");
        cookies.setProperty(TestElement.TEST_CLASS, "CookieManager");
        cookies.add(new Cookie("sid", "cookie-value", "example.test", "/", true, 1700000000L));

        AuthManager authorization = named(new AuthManager(), "Characterization Authorization");
        authorization.setProperty(TestElement.GUI_CLASS, "AuthPanel");
        authorization.setProperty(TestElement.TEST_CLASS, "AuthManager");
        Authorization row = new Authorization();
        row.setURL("https://example.test");
        row.setUser("agent-user");
        row.setPass("example-password");
        row.setDomain("example.test");
        row.setRealm("example-realm");
        row.setMechanism(AuthManager.Mechanism.BASIC);
        authorization.addAuth(row);

        TestElement dns = new DNSCacheManager();
        named(dns, "Characterization DNS");
        dns.setProperty(TestElement.GUI_CLASS, "DNSCachePanel");
        dns.setProperty(TestElement.TEST_CLASS, "DNSCacheManager");
        dns.setProperty(new CollectionProperty(
                "DNSCacheManager.servers", Collections.<Object>singletonList("192.0.2.53")));
        dns.setProperty(new CollectionProperty("DNSCacheManager.hosts",
                Collections.<Object>singletonList(new StaticHost("service.example.test", "192.0.2.10"))));

        Object root = plan.tree().list().iterator().next();
        HashTree rootChildren = plan.tree().getTree(root);
        rootChildren.add(headers);
        rootChildren.add(cookies);
        rootChildren.add(authorization);
        rootChildren.add(dns);
    }

    private static <T extends TestElement> T named(T element, String name) {
        element.setName(name);
        element.setEnabled(true);
        return element;
    }

    private static Map<String, Object> propertyFromNode(Map<String, Object> root, String nodeName, String path) {
        return requireProperty(list(requireNode(root, nodeName).get("properties")), path);
    }

    private static String exactYaml(Object value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(value);
    }

    private static Map<String, Object> requireNode(Map<String, Object> node, String name) {
        if (name.equals(node.get("name"))) {
            return node;
        }
        Object children = node.get("children");
        if (children instanceof List<?>) {
            for (Object child : (List<?>) children) {
                Map<String, Object> found = findNode(mapping(child), name);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new AssertionError("Missing node named " + name);
    }

    private static Map<String, Object> findNode(Map<String, Object> node, String name) {
        if (name.equals(node.get("name"))) {
            return node;
        }
        Object children = node.get("children");
        if (!(children instanceof List<?>)) {
            return null;
        }
        for (Object child : (List<?>) children) {
            Map<String, Object> found = findNode(mapping(child), name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<List<Object>> allPropertyPaths(Map<String, Object> node) {
        List<List<Object>> paths = new ArrayList<>();
        Object properties = node.get("properties");
        if (properties instanceof List<?>) {
            for (Object property : (List<?>) properties) {
                paths.add(list(mapping(property).get("property")));
            }
        }
        Object children = node.get("children");
        if (children instanceof List<?>) {
            for (Object child : (List<?>) children) {
                paths.addAll(allPropertyPaths(mapping(child)));
            }
        }
        return paths;
    }

    private static Map<String, Object> requireProperty(List<Object> properties, String path) {
        return properties.stream()
                .map(PropertyGraphCharacterizationTest::mapping)
                .filter(property -> address(path).equals(property.get("property")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing property " + path));
    }

    private static List<Object> address(String internalPropertySpelling) {
        return Collections.<Object>singletonList(internalPropertySpelling.replace("\\.", "."));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }

    private static void assertSuccessful(CliTestResult result) {
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stderr()).isEmpty();
    }

    private static CliTestResult runCli(String stdin, String... args) {
        return MainCliTestSupport.runMain(stdin, Collections.<String, String>emptyMap(), args);
    }

    private static Path jmeterHome() {
        String configured = System.getProperty(JMETER_HOME_PROPERTY, DEFAULT_JMETER_HOME);
        assertThat(configured).as("selected real JMeter runtime").isNotBlank();
        Path home = Paths.get(configured).toAbsolutePath().normalize();
        assertThat(home).isDirectory();
        assertThat(home.resolve("bin").resolve("saveservice.properties")).isRegularFile();
        return home;
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(PropertyGraphCharacterizationTest.class
                    .getResource("/property-graph-conformance/" + name).toURI());
        } catch (NullPointerException | URISyntaxException exception) {
            throw new IllegalStateException("Missing property graph fixture: " + name, exception);
        }
    }

}
