package io.github.thisccl.j4a.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class DefaultLocalProfilePluginFixtures {
    static final String NONSTANDARD_GUI_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.NonstandardGuiSampler";
    static final String NONSTANDARD_GUI_CLASS =
            "io.github.thisccl.j4a.synthetic.gui.NonstandardSamplerPanel";
    static final String CLEAR_GUI_SENSITIVE_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.ClearGuiSensitiveSampler";
    static final String CLEAR_GUI_SENSITIVE_GUI_CLASS =
            "io.github.thisccl.j4a.synthetic.gui.ClearGuiSensitiveSamplerPanel";
    static final String TEST_BEAN_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.BeanBackedSampler";
    static final String NO_MENU_TEST_BEAN_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.NoMenuTestBean";
    static final String NON_MENU_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.NonMenuSampler";
    static final String GUI_FAILURE_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.ExplodingGuiSampler";
    public static final String MATERIALIZATION_FAILURE_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.MaterializationFailureSampler";
    static final String METADATA_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.MetadataBackedSampler";
    static final String DISABLED_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.DisabledMenuSampler";
    static final String USER_CLASSPATH_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.UserClasspathSampler";
    static final String USER_CLASSPATH_DEPENDENCY_CLASS =
            "io.github.thisccl.j4a.synthetic.deps.UserClasspathDependency";
    static final String PLUGIN_DEPENDENCY_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.PluginDependencySampler";
    static final String PLUGIN_DEPENDENCY_CLASS =
            "io.github.thisccl.j4a.synthetic.deps.PluginDependency";
    static final String USER_CLASSPATH_SCAN_ONLY_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.classpath.UserClasspathOnlyMenuSampler";
    static final String USER_CLASSPATH_SCAN_ONLY_GUI_CLASS =
            "io.github.thisccl.j4a.synthetic.classpath.UserClasspathOnlyMenuSamplerGui";
    static final String PLUGIN_DEPENDENCY_SCAN_ONLY_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.classpath.PluginDependencyOnlyMenuSampler";
    static final String PLUGIN_DEPENDENCY_SCAN_ONLY_GUI_CLASS =
            "io.github.thisccl.j4a.synthetic.classpath.PluginDependencyOnlyMenuSamplerGui";
    static final String DESCRIPTOR_PACKAGE = "io.github.thisccl.j4a.synthetic.descriptor";

    private DefaultLocalProfilePluginFixtures() {
    }

    static void createAddabilityPlugins(Path home) throws IOException {
        SyntheticPluginJarCompiler.createArgumentDescriptorFixtures(
                home.resolve("lib").resolve("ext").resolve("synthetic-argument-descriptors.jar"),
                DESCRIPTOR_PACKAGE);
        SyntheticPluginFixtureJar.createNonStandardGuiSampler(
                home.resolve("lib").resolve("ext").resolve("synthetic-nonstandard-gui-plugin.jar"),
                NONSTANDARD_GUI_PLUGIN_CLASS, NONSTANDARD_GUI_CLASS);
        SyntheticPluginFixtureJar.createClearGuiSensitiveSampler(
                home.resolve("lib").resolve("ext").resolve("synthetic-clear-gui-sensitive-plugin.jar"),
                CLEAR_GUI_SENSITIVE_PLUGIN_CLASS, CLEAR_GUI_SENSITIVE_GUI_CLASS);
        SyntheticPluginFixtureJar.createTestBeanSampler(
                home.resolve("lib").resolve("ext").resolve("synthetic-testbean-plugin.jar"),
                TEST_BEAN_PLUGIN_CLASS);
        SyntheticPluginFixtureJar.createNoMenuTestBean(
                home.resolve("lib").resolve("ext").resolve("synthetic-no-menu-testbean-plugin.jar"),
                NO_MENU_TEST_BEAN_PLUGIN_CLASS);
        SyntheticPluginFixtureJar.createNonMenuGuiSampler(
                home.resolve("lib").resolve("ext").resolve("synthetic-non-menu-plugin.jar"),
                NON_MENU_PLUGIN_CLASS);
        SyntheticPluginFixtureJar.createExplodingGuiSampler(
                home.resolve("lib").resolve("ext").resolve("synthetic-gui-failure-plugin.jar"),
                GUI_FAILURE_PLUGIN_CLASS);
        SyntheticPluginFixtureJar.createMaterializationFailingGuiSampler(
                home.resolve("lib").resolve("ext").resolve("synthetic-materialization-failure-plugin.jar"),
                MATERIALIZATION_FAILURE_PLUGIN_CLASS);
        SyntheticPluginFixtureJar.createMetadataBackedSampler(
                home.resolve("lib").resolve("ext").resolve("synthetic-metadata-plugin.jar"),
                METADATA_PLUGIN_CLASS);
        SyntheticPluginFixtureJar.createDisabledGuiSampler(
                home.resolve("lib").resolve("ext").resolve("synthetic-disabled-plugin.jar"),
                DISABLED_PLUGIN_CLASS);
        SyntheticPluginFixtureJar.createClasspathDependentSampler(
                home.resolve("lib").resolve("ext").resolve("synthetic-user-classpath-plugin.jar"),
                home.resolve("property-dependencies").resolve("user-classpath-dependency.jar"),
                USER_CLASSPATH_PLUGIN_CLASS,
                USER_CLASSPATH_DEPENDENCY_CLASS);
        SyntheticPluginFixtureJar.createClasspathDependentSampler(
                home.resolve("lib").resolve("ext").resolve("synthetic-plugin-dependency-plugin.jar"),
                home.resolve("plugin-dependencies").resolve("plugin-dependency.jar"),
                PLUGIN_DEPENDENCY_PLUGIN_CLASS,
                PLUGIN_DEPENDENCY_CLASS);
        SyntheticPluginFixtureJar.createNonStandardGuiSampler(
                home.resolve("property-dependencies").resolve("user-classpath-scan-only-plugin.jar"),
                USER_CLASSPATH_SCAN_ONLY_PLUGIN_CLASS,
                USER_CLASSPATH_SCAN_ONLY_GUI_CLASS);
        SyntheticPluginFixtureJar.createNonStandardGuiSampler(
                home.resolve("plugin-dependencies").resolve("plugin-dependency-scan-only-plugin.jar"),
                PLUGIN_DEPENDENCY_SCAN_ONLY_PLUGIN_CLASS,
                PLUGIN_DEPENDENCY_SCAN_ONLY_GUI_CLASS);
        appendClasspathProperties(home);
    }

    private static void appendClasspathProperties(Path home) throws IOException {
        String pathSeparator = System.getProperty("path.separator");
        String userClasspath = propertyPath(home.resolve("property-dependencies").resolve("user-classpath-dependency.jar"))
                + pathSeparator
                + propertyPath(home.resolve("property-dependencies").resolve("user-classpath-scan-only-plugin.jar"));
        String pluginDependencyPath = propertyPath(home.resolve("plugin-dependencies").resolve("plugin-dependency.jar"))
                + ";"
                + propertyPath(home.resolve("plugin-dependencies").resolve("plugin-dependency-scan-only-plugin.jar"));
        Files.write(home.resolve("bin").resolve("user.properties"),
                ("user.classpath=" + userClasspath + pathSeparator + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        Files.write(home.resolve("bin").resolve("jmeter.properties"),
                ("plugin_dependency_paths=" + pluginDependencyPath + ";" + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static String propertyPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
