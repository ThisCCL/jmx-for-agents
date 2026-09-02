package io.github.thisccl.j4a.validation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;

final class CanonicalStructuredMenuFixture {
    static final String HTTP_COMPONENT = "io.github.thisccl.j4a.synthetic.canonical.HTTPSamplerProxy";
    static final String KCBP_COMPONENT = "io.github.thisccl.j4a.synthetic.canonical.KcbpSampler";
    static final String ARGUMENTS_COMPONENT = "io.github.thisccl.j4a.synthetic.canonical.Arguments";

    private CanonicalStructuredMenuFixture() {
    }

    static void install(Path home) throws Exception {
        Path jar = home.resolve("lib/ext/j4a-canonical-structured-menu.jar");
        Path work = jar.resolveSibling("canonical-structured-menu.work");
        Path sources = work.resolve("src");
        Path classes = work.resolve("classes");
        List<java.io.File> files = new ArrayList<java.io.File>();
        List<String> names = new ArrayList<String>();
        add(sources, HTTP_COMPONENT, sampler(HTTP_COMPONENT, "HTTPsampler.Arguments",
                DefaultLocalProfilePluginFixtures.DESCRIPTOR_PACKAGE + ".SetterCoupledArgument"), files, names);
        add(sources, KCBP_COMPONENT, sampler(KCBP_COMPONENT, "kingdomSampler.arguments",
                DefaultLocalProfileQaFixtures.KP_ARGUMENT_CLASS), files, names);
        add(sources, ARGUMENTS_COMPONENT, sampler(ARGUMENTS_COMPONENT, "Arguments.arguments",
                DefaultLocalProfilePluginFixtures.DESCRIPTOR_PACKAGE + ".CustomBooleanArgument"), files, names);
        JavaCompiler compiler = SyntheticPluginJarCompiler.compiler();
        SyntheticPluginJarCompiler.compile(compiler, files, classes);
        SyntheticPluginJarCompiler.writeJar(jar, classes, names.toArray(new String[names.size()]));
    }

    private static void add(Path root, String name, String source,
            List<java.io.File> files, List<String> names) throws Exception {
        Path file = root.resolve(name.replace('.', '/') + ".java");
        SyntheticPluginJarCompiler.writeSource(file, source);
        files.add(file.toFile());
        names.add(name);
    }

    private static String sampler(String className, String property, String rowClass) {
        int separator = className.lastIndexOf('.');
        String pkg = className.substring(0, separator);
        String type = className.substring(separator + 1);
        return packageLine(pkg) + "public class " + type + " extends org.apache.jmeter.samplers.AbstractSampler"
                + " implements org.apache.jmeter.testbeans.TestBean {\n"
                + "  public " + type + "() { try { org.apache.jmeter.config.Arguments a = new org.apache.jmeter.config.Arguments();"
                + " a.addArgument((org.apache.jmeter.config.Argument) Class.forName(\"" + rowClass
                + "\").getDeclaredConstructor().newInstance()); setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(\""
                + property + "\", a)); } catch (Exception e) { throw new IllegalStateException(e); } }\n"
                + "  public org.apache.jmeter.samplers.SampleResult sample(org.apache.jmeter.samplers.Entry e) { return new org.apache.jmeter.samplers.SampleResult(); }\n"
                + "}\n";
    }

    private static String packageLine(String pkg) {
        return pkg.isEmpty() ? "" : "package " + pkg + ";\n";
    }
}
