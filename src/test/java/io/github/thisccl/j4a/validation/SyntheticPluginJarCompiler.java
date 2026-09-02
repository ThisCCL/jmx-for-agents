package io.github.thisccl.j4a.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

final class SyntheticPluginJarCompiler {
    private SyntheticPluginJarCompiler() {
    }

    static void writeSource(Path source, String contents) throws IOException {
        Files.createDirectories(source.getParent());
        Files.write(source, contents.getBytes(StandardCharsets.UTF_8));
    }

    static void compile(JavaCompiler compiler, java.util.List<java.io.File> sources, Path classes)
            throws IOException {
        compile(compiler, sources, classes, null);
    }

    static void compile(JavaCompiler compiler, java.util.List<java.io.File> sources, Path classes,
            String extraClasspath)
            throws IOException {
        Files.createDirectories(classes);
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(sources);
            String classpath = System.getProperty("java.class.path");
            if (extraClasspath != null && !extraClasspath.trim().isEmpty()) {
                classpath = classpath + System.getProperty("path.separator") + extraClasspath;
            }
            Boolean ok = compiler.getTask(null, fileManager, null,
                    Arrays.asList("-classpath", classpath, "-d", classes.toString()),
                    null, units).call();
            if (!Boolean.TRUE.equals(ok)) {
                throw new IllegalStateException("Unable to compile synthetic plugin fixture.");
            }
        }
    }

    static JavaCompiler compiler() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK compiler is required to generate synthetic plugin fixtures.");
        }
        return compiler;
    }

    static void createArgumentDescriptorFixtures(Path jar, String packageName) throws IOException {
        Path work = jar.resolveSibling(jar.getFileName().toString() + ".work");
        Path sources = work.resolve("src");
        Path classes = work.resolve("classes");
        String customRow = packageName + ".CustomBooleanArgument";
        String coupledRow = packageName + ".SetterCoupledArgument";
        List<java.io.File> sourceFiles = new ArrayList<>();
        List<String> classNames = new ArrayList<>();
        writeFixtureSource(sources, customRow,
                SyntheticPluginSourceTemplates.descriptorRowSource(customRow, false), sourceFiles, classNames);
        writeFixtureSource(sources, coupledRow,
                SyntheticPluginSourceTemplates.descriptorRowSource(coupledRow, true), sourceFiles, classNames);
        descriptorPair(sources, packageName, "EmptyBaseDescriptorSampler", "base.arguments",
                "org.apache.jmeter.config.Argument", "unique", sourceFiles, classNames);
        descriptorPair(sources, packageName, "CustomBooleanDescriptorSampler", "custom.arguments",
                customRow, "unique", sourceFiles, classNames);
        descriptorPair(sources, packageName, "IndependentDescriptorSampler", "first.arguments,second.arguments",
                customRow, "independent", sourceFiles, classNames);
        descriptorPair(sources, packageName, "AmbiguousDescriptorSampler", "ambiguous.arguments",
                customRow, "ambiguous", sourceFiles, classNames);
        descriptorPair(sources, packageName, "UnboundDescriptorSampler", "unbound.arguments",
                customRow, "unbound", sourceFiles, classNames);
        descriptorPair(sources, packageName, "ThrowingDescriptorSampler", "throwing.arguments",
                customRow, "throwing", sourceFiles, classNames);
        descriptorPair(sources, packageName, "CoupledDescriptorSampler", "coupled.arguments",
                coupledRow, "unique", sourceFiles, classNames);
        compile(compiler(), sourceFiles, classes);
        writeJar(jar, classes, classNames.toArray(new String[0]));
    }

    private static void descriptorPair(
            Path sources, String packageName, String simpleName, String properties, String rowClass, String mode,
            List<java.io.File> sourceFiles, List<String> classNames) throws IOException {
        String sampler = packageName + "." + simpleName;
        String gui = sampler + "Gui";
        String[] propertyNames = properties.split(",");
        writeFixtureSource(sources, sampler,
                SyntheticPluginSourceTemplates.descriptorSamplerSource(sampler, propertyNames), sourceFiles, classNames);
        writeFixtureSource(sources, gui,
                SyntheticPluginSourceTemplates.descriptorGuiSource(sampler, gui, rowClass, mode, propertyNames),
                sourceFiles, classNames);
    }

    private static void writeFixtureSource(
            Path sources, String className, String contents, List<java.io.File> sourceFiles, List<String> classNames)
            throws IOException {
        Path source = sources.resolve(className.replace('.', '/') + ".java");
        writeSource(source, contents);
        sourceFiles.add(source.toFile());
        classNames.add(className);
    }

    static void writeJar(Path jar, Path classes, String... classNames) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest())) {
            for (String className : classNames) {
                writeClass(output, classes, className);
            }
        }
    }

    static void writeJar(
            Path jar,
            Path classes,
            String className,
            String guiClassName,
            String unloadableClassName,
            boolean includeUnloadable,
            boolean includeGui,
            String... extraClassNames)
            throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest())) {
            writeClass(output, classes, className);
            if (includeGui) {
                writeClass(output, classes, guiClassName);
            }
            if (includeUnloadable) {
                writeClass(output, classes, unloadableClassName);
            }
            for (String extraClassName : extraClassNames) {
                writeClass(output, classes, extraClassName);
            }
        }
    }

    private static Manifest manifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("JMeter-Skip-Class-Scanning", "true");
        return manifest;
    }

    private static void writeClass(JarOutputStream output, Path classes, String className) throws IOException {
        Path classFile = classes.resolve(className.replace('.', '/') + ".class");
        output.putNextEntry(new JarEntry(className.replace('.', '/') + ".class"));
        output.write(Files.readAllBytes(classFile));
        output.closeEntry();
    }
}
