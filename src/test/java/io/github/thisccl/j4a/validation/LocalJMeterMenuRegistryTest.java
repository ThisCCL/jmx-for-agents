package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.jmeter.util.JMeterUtils;

class LocalJMeterMenuRegistryTest {
    private Locale previousLocale;
    private Object previousResources;
    private boolean previousIgnoreResources;

    @BeforeEach
    void initializeJMeterResources() throws Exception {
        previousLocale = JMeterUtils.getLocale();
        java.lang.reflect.Field resources = JMeterUtils.class.getDeclaredField("resources");
        resources.setAccessible(true);
        previousResources = resources.get(null);
        java.lang.reflect.Field ignoreResources = JMeterUtils.class.getDeclaredField("ignoreResources");
        ignoreResources.setAccessible(true);
        previousIgnoreResources = ignoreResources.getBoolean(null);
        JMeterUtils.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void restoreJMeterResources() throws Exception {
        java.lang.reflect.Field locale = JMeterUtils.class.getDeclaredField("locale");
        locale.setAccessible(true);
        locale.set(null, previousLocale);
        java.lang.reflect.Field resources = JMeterUtils.class.getDeclaredField("resources");
        resources.setAccessible(true);
        resources.set(null, previousResources);
        java.lang.reflect.Field ignoreResources = JMeterUtils.class.getDeclaredField("ignoreResources");
        ignoreResources.setAccessible(true);
        ignoreResources.setBoolean(null, previousIgnoreResources);
    }

    @Test
    void metadataProcessorsRetainRawIdentityGroupAndKind() {
        LocalJMeterMenuRegistry registry = LocalJMeterMenuRegistry.reflect(SyntheticMenuFactory.class);

        assertThat(registry.entries()).extracting(LocalJMeterMenuRegistry.Entry::component)
                .containsExactly(
                        "org.apache.jmeter.modifiers.JSR223PreProcessor",
                        "org.apache.jmeter.extractor.JSR223PostProcessor");
        assertThat(registry.entries()).extracting(LocalJMeterMenuRegistry.Entry::group)
                .containsExactly("menu_pre_processors", "menu_post_processors");
        assertThat(registry.entries()).extracting(LocalJMeterMenuRegistry.Entry::kind)
                .containsOnly(LocalJMeterMenuRegistry.RegistrationKind.METADATA_TEST_ELEMENT);
        assertThat(registry.entries()).extracting(LocalJMeterMenuRegistry.Entry::menuClassName)
                .containsExactly(
                        "org.apache.jmeter.modifiers.JSR223PreProcessor",
                        "org.apache.jmeter.extractor.JSR223PostProcessor");
    }

    @Test
    void nonMenuTestElementIsNeverDiscovered() {
        LocalJMeterMenuRegistry registry = LocalJMeterMenuRegistry.reflect(SyntheticMenuFactory.class);

        assertThat(registry.resolve("org.apache.jmeter.sampler.DebugSampler")).isEmpty();
    }

    @Test
    void separatorAndEmptyGroupsAreExcludedFromRawCategoryCounts() {
        LocalJMeterMenuRegistry registry = LocalJMeterMenuRegistry.reflect(SyntheticMenuFactory.class);

        assertThat(registry.categories()).containsExactly(
                new LocalJMeterMenuRegistry.Category("menu_pre_processors", "Pre Processors", 1),
                new LocalJMeterMenuRegistry.Category("menu_post_processors", "Post Processors", 1));
    }

    @Test
    void registryResolvesMarkersAndBlankLabelsWithoutChangingRawIdentity() {
        LocalJMeterMenuRegistry registry = LocalJMeterMenuRegistry.reflect(LabelFallbackMenuFactory.class);

        assertThat(registry.entries()).extracting(
                LocalJMeterMenuRegistry.Entry::label,
                LocalJMeterMenuRegistry.Entry::group,
                LocalJMeterMenuRegistry.Entry::component)
                .containsExactly(
                        tuple("Some Plugin", "menu_config_element",
                                "org.apache.jmeter.modifiers.JSR223PreProcessor"),
                        tuple("Jsr223 Post Processor", "menu_config_element",
                                "org.apache.jmeter.extractor.JSR223PostProcessor"));
        assertThat(registry.categories()).containsExactly(
                new LocalJMeterMenuRegistry.Category("menu_config_element", "Config Element", 2));
    }

    @Test
    void missingCategoryResourceIsLookedUpExactlyOnceBeforeFallback() {
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger(JMeterUtils.class);
        Level previousLevel = logger.getLevel();
        MessageAppender appender = new MessageAppender();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            LocalJMeterMenuRegistry registry = LocalJMeterMenuRegistry.reflect(MissingCategoryMenuFactory.class);

            assertThat(registry.categories()).extracting(LocalJMeterMenuRegistry.Category::label)
                    .containsExactly("Menu Reviewer Missing");
            assertThat(appender.messages).filteredOn(message ->
                    message.contains("Resource string not found: [menu_reviewer_missing]"))
                    .hasSize(1);
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void allCoreCategoryResourceKeysAreResolvedOnceEvenWhenNoCategoryHasVisibleComponents()
            throws Exception {
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger(JMeterUtils.class);
        Level previousLevel = logger.getLevel();
        java.lang.reflect.Field resources = JMeterUtils.class.getDeclaredField("resources");
        resources.setAccessible(true);
        Object selectedResources = resources.get(null);
        MessageAppender appender = new MessageAppender();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        resources.set(null, new EmptyResourceBundle());
        try {
            LocalJMeterMenuRegistry.reflect(EmptyMenuFactory.class);

            List<String> missing = new java.util.ArrayList<>();
            for (String message : appender.messages) {
                if (message.startsWith("Resource string not found: [menu_")) {
                    missing.add(message);
                }
            }
            assertThat(missing).hasSize(11);
            assertThat(missing).allSatisfy(message ->
                    assertThat(Collections.frequency(missing, message)).isEqualTo(1));
        } finally {
            resources.set(null, selectedResources);
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void reflectionIncompatibilityFailsWithoutScannerFallback() {
        assertThatThrownBy(() -> LocalJMeterMenuRegistry.reflect(IncompatibleMenuFactory.class))
                .isInstanceOf(LocalJMeterMenuRegistry.IncompatibleRegistryException.class)
                .hasMessageContaining("getMenuMap")
                .hasMessageNotContaining("scanner");
    }

    @Test
    void unknownFqcnMapsToComponentIdentityNotFound() {
        ComponentIdentityNotFoundException failure =
                new ComponentIdentityNotFoundException("org.apache.jmeter.sampler.DebugSampler");

        assertThat(LocalJMeterWorkerJmx.errorCode(failure)).isEqualTo("COMPONENT_IDENTITY_NOT_FOUND");
        assertThat(LocalJMeterWorkerJmx.category("COMPONENT_IDENTITY_NOT_FOUND", "componentDetails"))
                .isEqualTo("operation");
    }

    static final class SyntheticMenuFactory {
        static Map<String, List<Object>> getMenuMap() {
            Map<String, List<Object>> menus = new LinkedHashMap<>();
            menus.put("menu_pre_processors", Collections.<Object>singletonList(new SyntheticMenuInfo(
                    "JSR223 PreProcessor", "org.apache.jmeter.modifiers.JSR223PreProcessor")));
            menus.put("menu_post_processors", Collections.<Object>singletonList(new SyntheticMenuInfo(
                    "JSR223 PostProcessor", "org.apache.jmeter.extractor.JSR223PostProcessor")));
            menus.put("menu_empty", Collections.emptyList());
            menus.put("menu_separator", Arrays.<Object>asList(new SyntheticSeparatorInfo()));
            return menus;
        }
    }

    static final class LabelFallbackMenuFactory {
        static Map<String, List<Object>> getMenuMap() {
            Map<String, List<Object>> menus = new LinkedHashMap<>();
            menus.put("menu_config_element", Arrays.<Object>asList(
                    new SyntheticMenuInfo("[res_key=some_plugin_title]",
                            "org.apache.jmeter.modifiers.JSR223PreProcessor"),
                    new SyntheticMenuInfo("", "org.apache.jmeter.extractor.JSR223PostProcessor")));
            return menus;
        }
    }

    static final class MissingCategoryMenuFactory {
        static Map<String, List<Object>> getMenuMap() {
            Map<String, List<Object>> menus = new LinkedHashMap<>();
            menus.put("menu_reviewer_missing", Collections.<Object>singletonList(
                    new SyntheticMenuInfo("Valid plugin label",
                            "org.apache.jmeter.modifiers.JSR223PreProcessor")));
            return menus;
        }
    }

    static final class EmptyMenuFactory {
        static Map<String, List<Object>> getMenuMap() {
            return Collections.emptyMap();
        }
    }

    static final class SyntheticMenuInfo {
        private final String label;
        private final String className;

        SyntheticMenuInfo(String label, String className) {
            this.label = label;
            this.className = className;
        }

        public String getLabel() {
            return label;
        }

        public String getClassName() {
            return className;
        }
    }

    static final class SyntheticSeparatorInfo {
    }

    static final class IncompatibleMenuFactory {
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }

    private static final class MessageAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private MessageAppender() {
            super("todo3-resource-lookups", null, PatternLayout.createDefaultLayout(), false, null);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }
    }

    private static final class EmptyResourceBundle extends ResourceBundle {
        @Override
        protected Object handleGetObject(String key) {
            return null;
        }

        @Override
        public java.util.Enumeration<String> getKeys() {
            return Collections.enumeration(Collections.<String>emptyList());
        }
    }
}
