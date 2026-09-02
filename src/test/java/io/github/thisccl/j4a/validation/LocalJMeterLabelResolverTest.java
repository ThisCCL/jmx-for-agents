package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalJMeterLabelResolverTest {
    private Locale previousJMeterLocale;
    private ResourceBundle previousResources;
    private boolean previousIgnoreResources;
    private Locale previousDefaultLocale;
    private Properties previousJMeterProperties;
    private String previousJMeterHome;
    private String previousJMeterBin;
    private String previousSaveServiceProperties;
    private String previousUpgradeProperties;

    @BeforeEach
    void selectEnglishResources() throws Exception {
        previousJMeterLocale = JMeterUtils.getLocale();
        previousResources = (ResourceBundle) field("resources").get(null);
        previousIgnoreResources = field("ignoreResources").getBoolean(null);
        previousDefaultLocale = Locale.getDefault();
        previousJMeterProperties = (Properties) field("appProperties").get(null);
        previousJMeterHome = (String) field("jmDir").get(null);
        previousJMeterBin = (String) field("jmBin").get(null);
        previousSaveServiceProperties = System.getProperty("saveservice_properties");
        previousUpgradeProperties = System.getProperty("upgrade_properties");
        JMeterUtils.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void restoreJMeterLocale() throws Exception {
        field("locale").set(null, previousJMeterLocale);
        field("resources").set(null, previousResources);
        field("ignoreResources").setBoolean(null, previousIgnoreResources);
        Locale.setDefault(previousDefaultLocale);
        field("appProperties").set(null, previousJMeterProperties);
        field("jmDir").set(null, previousJMeterHome);
        field("jmBin").set(null, previousJMeterBin);
        restoreSystemProperty("saveservice_properties", previousSaveServiceProperties);
        restoreSystemProperty("upgrade_properties", previousUpgradeProperties);
    }

    @Test
    void validLocalizedOrPluginLabelIsPreservedExactly() {
        assertThat(LocalJMeterLabelResolver.resolve(
                "插件提供的标题", "plugin_title", "example.ExampleGui", "Plugin Fallback"))
                .isEqualTo("插件提供的标题");
    }

    @Test
    void exactResourceMarkerResolvesThroughSelectedJMeterResources() {
        assertThat(LocalJMeterLabelResolver.resolve(
                "[res_key=header_manager_title]", "header_manager_title",
                "org.apache.jmeter.protocol.http.gui.HeaderPanel", null))
                .isEqualTo("HTTP Header Manager");
    }

    @Test
    void missingResourceKeysAndCamelCaseBecomeDeterministicReadableLabels() {
        assertThat(LocalJMeterLabelResolver.resolve(
                "[res_key=some_plugin_title]", "some_plugin_title", "example.PluginGui", null))
                .isEqualTo("Some Plugin");
        assertThat(LocalJMeterLabelResolver.resolve(
                "[res_key=somePluginHTTPClient]", "somePluginHTTPClient", "example.PluginGui", null))
                .isEqualTo("Some Plugin Http Client");
    }

    @Test
    void blankLabelsHumanizeTheMenuClassAfterRemovingOnlyUsableGuiOrPanelSuffixes() {
        assertThat(Arrays.asList(
                LocalJMeterLabelResolver.resolve(" ", null, "example.ExampleGui", null),
                LocalJMeterLabelResolver.resolve(null, null, "example.ExampleGUI", null),
                LocalJMeterLabelResolver.resolve("", null, "example.ExamplePanel", null)))
                .containsExactly("Example", "Example", "Example");
    }

    @Test
    void suppliedCategoryFallbackWinsWhenRuntimeTextIsMissingOrUnresolved() {
        assertThat(LocalJMeterLabelResolver.resolve(
                "[res_key=missing_category_title]", "missing_category_title", null, "Config Elements"))
                .isEqualTo("Config Elements");
        assertThat(LocalJMeterLabelResolver.resolve("", "menu_config_element", null, "Config Elements"))
                .isEqualTo("Config Elements");
    }

    @Test
    void malformedOrBlankMarkersNeverEscapeAsMarkersOrBlankText() {
        assertThat(Arrays.asList(
                LocalJMeterLabelResolver.resolve("[res_key=]", null, "example.ExampleGui", null),
                LocalJMeterLabelResolver.resolve("[res_key=missing", null, "example.ExamplePanel", null),
                LocalJMeterLabelResolver.resolve("", null, "Gui", null)))
                .allSatisfy(label -> {
                    assertThat(label).isNotBlank();
                    assertThat(label).doesNotStartWith("[res_key=");
                });
    }

    @Test
    void workerInitializationSelectsEnglishAndZhCnAfterLoadingRuntimeProperties() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.fresh();
        Path properties = fixtures.localHome().resolve("bin").resolve("jmeter.properties");
        byte[] original = Files.readAllBytes(properties);
        try {
            Files.write(properties, appendLanguage(original, "en"));
            LocalJMeterWorkerJmx.initializeJMeter(fixtures.localHome());
            assertThat(JMeterUtils.getLocale()).isEqualTo(new Locale("en", ""));
            assertThat(JMeterUtils.getResString("header_manager_title")).isEqualTo("HTTP Header Manager");

            Files.write(properties, appendLanguage(original, "zh_CN"));
            LocalJMeterWorkerJmx.initializeJMeter(fixtures.localHome());
            assertThat(JMeterUtils.getLocale()).isEqualTo(Locale.SIMPLIFIED_CHINESE);
            assertThat(JMeterUtils.getResString("header_manager_title")).isEqualTo("HTTP信息头管理器");
        } finally {
            Files.write(properties, original);
            fixtures.delete();
        }
    }

    @Test
    void optionalUserPropertiesOverrideMainLanguageAndJoinJMeterPropertyState() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.fresh();
        Path mainProperties = fixtures.localHome().resolve("bin").resolve("jmeter.properties");
        Path userProperties = fixtures.localHome().resolve("bin").resolve("user.properties");
        byte[] originalMain = Files.readAllBytes(mainProperties);
        byte[] originalUser = Files.readAllBytes(userProperties);
        try {
            Files.write(mainProperties, appendLanguage(originalMain, "en"));
            Files.write(userProperties, appendProperties(originalUser,
                    "language=zh_CN\nreviewer_user_property=loaded\n"));

            LocalJMeterWorkerJmx.initializeJMeter(fixtures.localHome());

            assertThat(JMeterUtils.getLocale()).isEqualTo(Locale.SIMPLIFIED_CHINESE);
            assertThat(JMeterUtils.getResString("header_manager_title")).isEqualTo("HTTP信息头管理器");
            assertThat(JMeterUtils.getProperty("reviewer_user_property")).isEqualTo("loaded");
        } finally {
            Files.write(mainProperties, originalMain);
            Files.write(userProperties, originalUser);
            fixtures.delete();
        }
    }

    private static byte[] appendLanguage(byte[] original, String language) {
        return appendProperties(original, "language=" + language + "\n");
    }

    private static byte[] appendProperties(byte[] original, String properties) {
        byte[] setting = ("\n" + properties).getBytes(StandardCharsets.ISO_8859_1);
        byte[] combined = Arrays.copyOf(original, original.length + setting.length);
        System.arraycopy(setting, 0, combined, original.length, setting.length);
        return combined;
    }

    private static void restoreSystemProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static java.lang.reflect.Field field(String name) throws NoSuchFieldException {
        java.lang.reflect.Field field = JMeterUtils.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
