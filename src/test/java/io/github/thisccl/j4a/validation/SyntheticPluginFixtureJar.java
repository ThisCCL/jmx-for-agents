package io.github.thisccl.j4a.validation;

import static io.github.thisccl.j4a.validation.SyntheticPluginJarCompiler.compile;
import static io.github.thisccl.j4a.validation.SyntheticPluginJarCompiler.compiler;
import static io.github.thisccl.j4a.validation.SyntheticPluginJarCompiler.writeJar;
import static io.github.thisccl.j4a.validation.SyntheticPluginJarCompiler.writeSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.classpathDependentSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.clearGuiSensitiveGuiSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.dependencySource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.guiBaseClass;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.guiSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.kpArgumentSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.malformedSchemaSamplerSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.materializationFailingGuiSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.metadataBackedSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.missingBaseSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.noMenuTestBeanSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.packageName;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.pluginSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.testBeanInfoSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.testBeanSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.unloadableSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.unseenPropertySamplerSource;
import static io.github.thisccl.j4a.validation.SyntheticPluginSourceTemplates.unseenStringPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.JavaCompiler;

final class SyntheticPluginFixtureJar {
    private SyntheticPluginFixtureJar() {
    }

    static void create(Path jar, String className, boolean slow, boolean includeUnloadable)
            throws IOException {
        create(jar, className, slow, includeUnloadable, "org.apache.jmeter.testelement.AbstractTestElement");
    }

    static void create(Path jar, String className, boolean slow, boolean includeUnloadable, boolean sampler)
            throws IOException {
        if (sampler) {
            create(jar, className, slow, includeUnloadable, "org.apache.jmeter.samplers.AbstractSampler");
            return;
        }
        create(jar, className, slow, includeUnloadable);
    }

    static void createWithKpArgument(
            Path jar, String className, boolean slow, boolean includeUnloadable, boolean sampler)
            throws IOException {
        String baseClass = sampler
                ? "org.apache.jmeter.samplers.AbstractSampler"
                : "org.apache.jmeter.testelement.AbstractTestElement";
        create(jar, className, slow, includeUnloadable, baseClass, false, true);
    }

    static void createThreadGroup(Path jar, String className) throws IOException {
        create(jar, className, false, false, "org.apache.jmeter.threads.ThreadGroup");
    }

    static void createGenericController(Path jar, String className) throws IOException {
        create(jar, className, false, false, "org.apache.jmeter.control.GenericController");
    }

    static void createTestFragment(Path jar, String className) throws IOException {
        create(jar, className, false, false, "org.apache.jmeter.control.TestFragmentController");
    }

    static void createRequestDefaults(Path jar, String className) throws IOException {
        create(jar, className, false, false, "org.apache.jmeter.config.ConfigTestElement");
    }

    static void createAbstractSampler(Path jar, String className) throws IOException {
        create(jar, className, false, false, "org.apache.jmeter.samplers.AbstractSampler", true);
    }

    static void createNonStandardGuiSampler(Path jar, String className, String guiClassName) throws IOException {
        if (Files.exists(jar)) {
            return;
        }
        createGuiBackedSampler(jar, className, guiClassName, "sampler", false, false);
    }

    static void createClearGuiSensitiveSampler(Path jar, String className, String guiClassName) throws IOException {
        if (Files.exists(jar)) {
            return;
        }
        JavaCompiler compiler = compiler();
        Path work = jar.getParent().resolve("plugin-src-" + className.substring(className.lastIndexOf('.') + 1));
        Path source = work.resolve(className.replace('.', '/') + ".java");
        Path guiSource = work.resolve(guiClassName.replace('.', '/') + ".java");
        writeSource(source, pluginSource(className, false, "org.apache.jmeter.samplers.AbstractSampler", false));
        writeSource(guiSource, clearGuiSensitiveGuiSource(className, guiClassName));
        compile(compiler, java.util.Arrays.asList(source.toFile(), guiSource.toFile()), work.resolve("classes"));
        writeJar(jar, work.resolve("classes"), className, guiClassName);
    }

    static void createNonMenuGuiSampler(Path jar, String className) throws IOException {
        if (Files.exists(jar)) {
            return;
        }
        createGuiBackedSampler(jar, className, className + "Gui", "none", false, false);
    }

    static void createExplodingGuiSampler(Path jar, String className) throws IOException {
        if (Files.exists(jar)) {
            return;
        }
        createGuiBackedSampler(jar, className, className + "Gui", "sampler", true, false);
    }

    static void createMaterializationFailingGuiSampler(Path jar, String className) throws IOException {
        if (Files.exists(jar)) {
            return;
        }
        JavaCompiler compiler = compiler();
        Path work = jar.getParent().resolve("plugin-src-" + className.substring(className.lastIndexOf('.') + 1));
        Path source = work.resolve(className.replace('.', '/') + ".java");
        String guiClassName = className + "Gui";
        Path guiSource = work.resolve(guiClassName.replace('.', '/') + ".java");
        writeSource(source, pluginSource(className, false, "org.apache.jmeter.samplers.AbstractSampler", false));
        writeSource(guiSource, materializationFailingGuiSource(className, guiClassName));
        compile(compiler, java.util.Arrays.asList(source.toFile(), guiSource.toFile()), work.resolve("classes"));
        writeJar(jar, work.resolve("classes"), className, guiClassName);
    }

    static void createDisabledGuiSampler(Path jar, String className) throws IOException {
        if (Files.exists(jar)) {
            return;
        }
        createGuiBackedSampler(jar, className, className + "Gui", "sampler", false, true);
    }

    static void createMetadataBackedSampler(Path jar, String className) throws IOException {
        if (Files.exists(jar)) {
            return;
        }
        JavaCompiler compiler = compiler();
        Path work = jar.getParent().resolve("plugin-src-" + className.substring(className.lastIndexOf('.') + 1));
        Path source = work.resolve(className.replace('.', '/') + ".java");
        writeSource(source, metadataBackedSource(className));
        compile(compiler, java.util.Collections.singletonList(source.toFile()), work.resolve("classes"));
        writeJar(jar, work.resolve("classes"), className);
    }

    static void createMalformedSchemaSampler(Path jar, String className) throws IOException {
        if (Files.exists(jar)) {
            return;
        }
        JavaCompiler compiler = compiler();
        Path work = jar.getParent().resolve("plugin-src-" + className.substring(className.lastIndexOf('.') + 1));
        Path source = work.resolve(className.replace('.', '/') + ".java");
        Path guiSource = work.resolve((className + "Gui").replace('.', '/') + ".java");
        writeSource(source, malformedSchemaSamplerSource(className));
        writeSource(guiSource, guiSource(className, className + "Gui",
                "org.apache.jmeter.samplers.gui.AbstractSamplerGui"));
        compile(compiler, java.util.Arrays.asList(source.toFile(), guiSource.toFile()), work.resolve("classes"));
        writeJar(jar, work.resolve("classes"), className, className + "$MalformedSchema",
                className + "$ThrowingDescriptor", className + "Gui");
    }

    static void createUnseenPropertySampler(
            Path jar, String className, String propertyClassName, String propertyName, String propertyValue)
            throws IOException {
        if (Files.exists(jar)) {
            return;
        }
        JavaCompiler compiler = compiler();
        Path work = jar.getParent().resolve("plugin-src-" + className.substring(className.lastIndexOf('.') + 1));
        Path source = work.resolve(className.replace('.', '/') + ".java");
        Path propertySource = work.resolve(propertyClassName.replace('.', '/') + ".java");
        String guiClassName = className + "Gui";
        Path guiSource = work.resolve(guiClassName.replace('.', '/') + ".java");
        writeSource(source, unseenPropertySamplerSource(className, propertyClassName, propertyName, propertyValue));
        writeSource(propertySource, unseenStringPropertySource(propertyClassName));
        writeSource(guiSource, guiSource(className, guiClassName,
                "org.apache.jmeter.samplers.gui.AbstractSamplerGui"));
        compileForJava8(compiler, java.util.Arrays.asList(
                source.toFile(), propertySource.toFile(), guiSource.toFile()), work.resolve("classes"));
        writeJar(jar, work.resolve("classes"), className, propertyClassName, guiClassName);
    }

    private static void compileForJava8(
            JavaCompiler compiler, java.util.List<java.io.File> sources, Path classes) throws IOException {
        Files.createDirectories(classes);
        java.util.List<String> arguments = new java.util.ArrayList<String>();
        arguments.add("-source");
        arguments.add("8");
        arguments.add("-target");
        arguments.add("8");
        arguments.add("-classpath");
        arguments.add(System.getProperty("java.class.path"));
        arguments.add("-d");
        arguments.add(classes.toString());
        for (java.io.File source : sources) {
            arguments.add(source.toString());
        }
        int exitCode = compiler.run(null, null, null, arguments.toArray(new String[arguments.size()]));
        if (exitCode != 0) {
            throw new IllegalStateException("Unable to compile Java 8 synthetic plugin fixture.");
        }
    }

    static void createTestBeanSampler(Path jar, String className) throws IOException {
        if (Files.exists(jar)) {
            return;
        }
        JavaCompiler compiler = compiler();
        Path work = jar.getParent().resolve("plugin-src-" + className.substring(className.lastIndexOf('.') + 1));
        Path source = work.resolve(className.replace('.', '/') + ".java");
        Path beanInfo = work.resolve((className + "BeanInfo").replace('.', '/') + ".java");
        writeSource(source, testBeanSource(className));
        writeSource(beanInfo, testBeanInfoSource(className));
        compile(compiler, java.util.Arrays.asList(source.toFile(), beanInfo.toFile()), work.resolve("classes"));
        writeJar(jar, work.resolve("classes"), className, className + "BeanInfo");
    }

    static void createNoMenuTestBean(Path jar, String className) throws IOException {
        if (Files.exists(jar)) {
            return;
        }
        JavaCompiler compiler = compiler();
        Path work = jar.getParent().resolve("plugin-src-" + className.substring(className.lastIndexOf('.') + 1));
        Path source = work.resolve(className.replace('.', '/') + ".java");
        Path beanInfo = work.resolve((className + "BeanInfo").replace('.', '/') + ".java");
        writeSource(source, noMenuTestBeanSource(className));
        writeSource(beanInfo, testBeanInfoSource(className));
        compile(compiler, java.util.Arrays.asList(source.toFile(), beanInfo.toFile()), work.resolve("classes"));
        writeJar(jar, work.resolve("classes"), className, className + "BeanInfo");
    }

    static void createClasspathDependentSampler(
            Path pluginJar, Path dependencyJar, String className, String dependencyClassName) throws IOException {
        if (Files.exists(pluginJar) && Files.exists(dependencyJar)) {
            return;
        }
        JavaCompiler compiler = compiler();
        Path dependencyWork = dependencyJar.getParent().resolve("dependency-src-"
                + dependencyClassName.substring(dependencyClassName.lastIndexOf('.') + 1));
        Path dependencySource = dependencyWork.resolve(dependencyClassName.replace('.', '/') + ".java");
        writeSource(dependencySource, dependencySource(dependencyClassName));
        compile(compiler, java.util.Collections.singletonList(dependencySource.toFile()),
                dependencyWork.resolve("classes"));
        writeJar(dependencyJar, dependencyWork.resolve("classes"), dependencyClassName);

        Path pluginWork = pluginJar.getParent().resolve("plugin-src-" + className.substring(className.lastIndexOf('.') + 1));
        Path source = pluginWork.resolve(className.replace('.', '/') + ".java");
        Path guiSource = pluginWork.resolve((className + "Gui").replace('.', '/') + ".java");
        writeSource(source, classpathDependentSource(className, dependencyClassName));
        writeSource(guiSource, guiSource(className, className + "Gui",
                "org.apache.jmeter.samplers.gui.AbstractSamplerGui"));
        compile(compiler, java.util.Arrays.asList(source.toFile(), guiSource.toFile()),
                pluginWork.resolve("classes"), dependencyWork.resolve("classes").toString());
        writeJar(pluginJar, pluginWork.resolve("classes"), className, className + "Gui");
    }

    private static void create(Path jar, String className, boolean slow, boolean includeUnloadable, String baseClass)
            throws IOException {
        create(jar, className, slow, includeUnloadable, baseClass, false);
    }

    private static void create(
            Path jar, String className, boolean slow, boolean includeUnloadable, String baseClass, boolean abstractClass)
            throws IOException {
        create(jar, className, slow, includeUnloadable, baseClass, abstractClass, false);
    }

    private static void create(
            Path jar, String className, boolean slow, boolean includeUnloadable, String baseClass,
            boolean abstractClass, boolean includeKpArgument)
            throws IOException {
        if (Files.exists(jar)) {
            return;
        }
        JavaCompiler compiler = compiler();
        Path work = jar.getParent().resolve("plugin-src-" + className.substring(className.lastIndexOf('.') + 1));
        Path source = work.resolve(className.replace('.', '/') + ".java");
        writeSource(source, pluginSource(className, slow, baseClass, abstractClass));
        java.util.List<java.io.File> sources = new java.util.ArrayList<>();
        sources.add(source.toFile());
        if (includeKpArgument) {
            sources.add(writeKpArgumentSource(work).toFile());
        }
        String guiClassName = className + "Gui";
        String guiBaseClass = guiBaseClass(baseClass);
        if (!abstractClass && guiBaseClass != null) {
            Path guiSource = work.resolve(guiClassName.replace('.', '/') + ".java");
            writeSource(guiSource, guiSource(className, guiClassName, guiBaseClass));
            sources.add(guiSource.toFile());
        }
        String unloadableClassName = packageName(className) + ".UnloadableSampler";
        if (includeUnloadable) {
            Path missingBase = work.resolve(packageName(className).replace('.', '/')).resolve("MissingSamplerBase.java");
            Path unloadable = work.resolve(unloadableClassName.replace('.', '/') + ".java");
            writeSource(missingBase, missingBaseSource(packageName(className)));
            writeSource(unloadable, unloadableSource(unloadableClassName));
            sources.add(missingBase.toFile());
            sources.add(unloadable.toFile());
        }
        compile(compiler, sources, work.resolve("classes"));
        if (includeKpArgument) {
            writeJar(jar, work.resolve("classes"), className, guiClassName, unloadableClassName, includeUnloadable,
                    !abstractClass && guiBaseClass != null, DefaultLocalProfileQaFixtures.KP_ARGUMENT_CLASS);
        } else {
            writeJar(jar, work.resolve("classes"), className, guiClassName, unloadableClassName, includeUnloadable,
                    !abstractClass && guiBaseClass != null);
        }
    }

    private static void createGuiBackedSampler(
            Path jar, String className, String guiClassName, String menuMode, boolean exploding, boolean disabled)
            throws IOException {
        JavaCompiler compiler = compiler();
        Path work = jar.getParent().resolve("plugin-src-" + className.substring(className.lastIndexOf('.') + 1));
        Path source = work.resolve(className.replace('.', '/') + ".java");
        Path guiSource = work.resolve(guiClassName.replace('.', '/') + ".java");
        writeSource(source, pluginSource(className, false, "org.apache.jmeter.samplers.AbstractSampler", false));
        writeSource(guiSource, guiSource(className, guiClassName,
                "org.apache.jmeter.samplers.gui.AbstractSamplerGui", menuMode, exploding, disabled));
        compile(compiler, java.util.Arrays.asList(source.toFile(), guiSource.toFile()), work.resolve("classes"));
        writeJar(jar, work.resolve("classes"), className, guiClassName);
    }

    private static Path writeKpArgumentSource(Path work) throws IOException {
        Path source = work.resolve(DefaultLocalProfileQaFixtures.KP_ARGUMENT_CLASS.replace('.', '/') + ".java");
        writeSource(source, kpArgumentSource(DefaultLocalProfileQaFixtures.KP_ARGUMENT_CLASS));
        return source;
    }
}
