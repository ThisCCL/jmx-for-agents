package io.github.thisccl.j4a.validation;

final class SyntheticPluginSourceTemplates {
    private SyntheticPluginSourceTemplates() {
    }

    static String packageName(String className) {
        return className.substring(0, className.lastIndexOf('.'));
    }

    static String missingBaseSource(String packageName) {
        return "package " + packageName + ";\n"
                + "public abstract class MissingSamplerBase extends org.apache.jmeter.testelement.AbstractTestElement {\n"
                + "}\n";
    }

    static String unloadableSource(String className) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        return "package " + packageName + ";\n"
                + "public class " + simpleName + " extends MissingSamplerBase {\n"
                + "}\n";
    }

    static String pluginSource(String className, boolean slow, String baseClass, boolean abstractClass) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        String sleep = slow
                ? "try { Thread.sleep(5000L); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }"
                : "";
        String referenceProperties = "ExtOnlySampler".equals(simpleName)
                ? " setProperty(\"referenceName\", \"default-reference\");"
                        + " setProperty(\"qa.ref\", \"default-plugin-reference\");"
                : "";
        String declaration = abstractClass ? "public abstract class " : "public class ";
        return "package " + packageName + ";\n"
                + declaration + simpleName + " extends " + baseClass + " {\n"
                + "  static { System.out.println(\"SYNTHETIC_STDOUT_" + simpleName
                + " root: plugin stdout marker profile: plugin stdout marker component: plugin stdout marker\");"
                + " System.err.println(\"SYNTHETIC_STDERR_" + simpleName + "\");" + sleep + " }\n"
                + "  public " + simpleName + "() { setName(\"" + simpleName + "\");"
                + referenceProperties
                + " setProperty(\"kingdomSampler.address\", \"127.0.0.1\");"
                + " setProperty(\"kingdomSampler.port\", \"8080\");"
                + " setProperty(\"kingdomSampler.func_code\", \"health\");"
                + " setProperty(\"kingdomSampler.server_type\", \"kcbp\");"
                + " org.apache.jmeter.config.Arguments arguments = new org.apache.jmeter.config.Arguments();"
                + " arguments.addArgument(new org.apache.jmeter.config.Argument(\"branch_no\", \"1001\", \"=\"));"
                + " setProperty(new org.apache.jmeter.testelement.property.TestElementProperty("
                + "\"kingdomSampler.arguments\", arguments)); }\n"
                + samplerMethod(baseClass, abstractClass)
                + "}\n";
    }

    static String unseenStringPropertySource(String className) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        return "package " + packageName + ";\n"
                + "public final class " + simpleName
                + " extends org.apache.jmeter.testelement.property.StringProperty {\n"
                + "  public " + simpleName + "() { super(); }\n"
                + "  public " + simpleName + "(String name, String value) { super(name, value); }\n"
                + "}\n";
    }

    static String unseenPropertySamplerSource(
            String className, String propertyClassName, String propertyName, String propertyValue) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        return "package " + packageName + ";\n"
                + "public class " + simpleName + " extends org.apache.jmeter.samplers.AbstractSampler {\n"
                + "  public " + simpleName + "() { setName(\"Unseen Property Sampler\");"
                + " setProperty(new " + propertyClassName + "(\"" + propertyName + "\", \""
                + propertyValue + "\")); }\n"
                + samplerMethod("org.apache.jmeter.samplers.AbstractSampler", false)
                + "}\n";
    }

    static String kpArgumentSource(String className) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        return "package " + packageName + ";\n"
                + "public class " + simpleName + " extends org.apache.jmeter.config.Argument {\n"
                + "  public " + simpleName + "() { super(); }\n"
                + "  public " + simpleName + "(String name, String value, String metadata) {\n"
                + "    super(name, value, metadata);\n"
                + "  }\n"
                + "  public boolean isEncrypted() {\n"
                + "    return getPropertyAsBoolean(\"kingdomArgument.encrypted\");\n"
                + "  }\n"
                + "  public void setEncrypted(boolean encrypted) {\n"
                + "    setProperty(\"kingdomArgument.encrypted\", encrypted);\n"
                + "  }\n"
                + "}\n";
    }

    static String descriptorRowSource(String className, boolean coupled) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        String setter = coupled
                ? "setProperty(\"qa.encoded\", value); setProperty(\"qa.equals\", value);"
                : "setProperty(\"qa.enabled\", value);";
        String getter = coupled ? "qa.encoded" : "qa.enabled";
        return "package " + packageName + ";\n"
                + "public class " + simpleName + " extends org.apache.jmeter.config.Argument {\n"
                + "  public " + simpleName + "() { super(); }\n"
                + "  public boolean isEnabledFlag() { return getPropertyAsBoolean(\"" + getter + "\"); }\n"
                + "  public void setEnabledFlag(boolean value) { " + setter + " }\n"
                + "}\n";
    }

    static String descriptorSamplerSource(String className, String... propertyNames) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        StringBuilder defaults = new StringBuilder();
        for (String propertyName : propertyNames) {
            defaults.append("    setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(\"")
                    .append(propertyName).append("\", new org.apache.jmeter.config.Arguments()));\n");
        }
        return "package " + packageName + ";\n"
                + "public class " + simpleName + " extends org.apache.jmeter.samplers.AbstractSampler {\n"
                + "  public " + simpleName + "() {\n" + defaults + "  }\n"
                + samplerMethod("org.apache.jmeter.samplers.AbstractSampler", false)
                + "}\n";
    }

    static String descriptorGuiSource(
            String samplerClass, String guiClass, String rowClass, String mode, String... propertyNames) {
        int lastDot = guiClass.lastIndexOf('.');
        String packageName = guiClass.substring(0, lastDot);
        String simpleName = guiClass.substring(lastDot + 1);
        boolean base = rowClass.equals("org.apache.jmeter.config.Argument");
        String creator = "throwing".equals(mode)
                ? "values -> { throw new IllegalStateException(\"throwing descriptor factory\"); }"
                : base
                        ? "values -> new org.apache.jmeter.config.Argument(values[0], values[1], values[2])"
                        : "values -> { " + rowClass + " row = new " + rowClass
                                + "(); row.setName(values[0]); row.setValue(values[1]); return row; }";
        String model = base
                ? "new org.apache.jorphan.gui.ObjectTableModel(new String[]{\"name\",\"value\",\"metadata\"}, "
                        + "org.apache.jmeter.config.Argument.class, new org.apache.jorphan.reflect.Functor[]{"
                        + "new org.apache.jorphan.reflect.Functor(\"getName\"),new org.apache.jorphan.reflect.Functor(\"getValue\"),new org.apache.jorphan.reflect.Functor(\"getMetaData\")},"
                        + "new org.apache.jorphan.reflect.Functor[]{new org.apache.jorphan.reflect.Functor(\"setName\"),new org.apache.jorphan.reflect.Functor(\"setValue\"),new org.apache.jorphan.reflect.Functor(\"setMetaData\")},"
                        + "new Class[]{String.class,String.class,String.class})"
                : "new org.apache.jorphan.gui.ObjectTableModel(new String[]{\"name\",\"value\",\"enabled\"}, "
                        + rowClass + ".class, new org.apache.jorphan.reflect.Functor[]{new org.apache.jorphan.reflect.Functor(\"getName\"),new org.apache.jorphan.reflect.Functor(\"getValue\"),new org.apache.jorphan.reflect.Functor(\"isEnabledFlag\")},"
                        + "new org.apache.jorphan.reflect.Functor[]{new org.apache.jorphan.reflect.Functor(\"setName\"),new org.apache.jorphan.reflect.Functor(\"setValue\"),new org.apache.jorphan.reflect.Functor(\"setEnabledFlag\")},"
                        + "new Class[]{String.class,String.class,Boolean.class})";
        int panelCount = "independent".equals(mode) || "ambiguous".equals(mode) ? 2 : 1;
        StringBuilder fields = new StringBuilder();
        StringBuilder add = new StringBuilder();
        StringBuilder configure = new StringBuilder();
        StringBuilder modify = new StringBuilder();
        for (int index = 0; index < panelCount; index++) {
            fields.append("  private final org.apache.jmeter.config.gui.ArgumentsPanel panel").append(index)
                    .append(" = new org.apache.jmeter.config.gui.ArgumentsPanel(\"panel").append(index)
                    .append("\", java.awt.Color.WHITE, true, false, ").append(model)
                    .append(", true, ").append(creator).append(");\n");
            add.append("    add(panel").append(index).append(");\n");
            if (!"unbound".equals(mode)) {
                String property = "ambiguous".equals(mode) ? propertyNames[0] : propertyNames[index];
                configure.append("    panel").append(index).append(".configure((org.apache.jmeter.config.Arguments) element.getProperty(\"")
                        .append(property).append("\").getObjectValue());\n");
                modify.append("    org.apache.jmeter.config.Arguments a").append(index)
                        .append(" = (org.apache.jmeter.config.Arguments) panel").append(index).append(".createTestElement();\n")
                        .append("    element.setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(\"")
                        .append(property).append("\", a").append(index).append("));\n");
            }
        }
        return "package " + packageName + ";\npublic class " + simpleName
                + " extends org.apache.jmeter.samplers.gui.AbstractSamplerGui {\n" + fields
                + "  public " + simpleName + "() { setLayout(new java.awt.GridLayout(0,1));\n" + add + "  }\n"
                + "  public String getLabelResource(){return \"" + simpleName + "\";}\n"
                + "  public String getStaticLabel(){return \"" + simpleName + "\";}\n"
                + "  public java.util.Collection<String> getMenuCategories(){return java.util.Collections.singletonList(org.apache.jmeter.gui.util.MenuFactory.SAMPLERS);}\n"
                + "  public org.apache.jmeter.testelement.TestElement createTestElement(){" + samplerClass + " e=new " + samplerClass + "(); modifyTestElement(e); return e;}\n"
                + "  public void configure(org.apache.jmeter.testelement.TestElement element){super.configure(element);\n" + configure + "  }\n"
                + "  public void modifyTestElement(org.apache.jmeter.testelement.TestElement element){configureTestElement(element);\n" + modify + "  }\n"
                + "}\n";
    }

    static String malformedSchemaSamplerSource(String className) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        return "package " + packageName + ";\n"
                + "public class " + simpleName + " extends org.apache.jmeter.samplers.AbstractSampler {\n"
                + "  private static final MalformedSchema SCHEMA = new MalformedSchema();\n"
                + "  public " + simpleName + "() { setProperty(\"qa.fallback\", \"fallback-value\"); }\n"
                + "  public MalformedSchema getSchema() { return SCHEMA; }\n"
                + samplerMethod("org.apache.jmeter.samplers.AbstractSampler", false)
                + "  public static final class MalformedSchema extends org.apache.jmeter.testelement.TestElementSchema {\n"
                + "    public MalformedSchema() {\n"
                + "      try {\n"
                + "        java.lang.reflect.Field field = org.apache.jmeter.testelement.schema.BaseTestElementSchema.class.getDeclaredField(\"properties\");\n"
                + "        field.setAccessible(true);\n"
                + "        @SuppressWarnings(\"unchecked\")\n"
                + "        java.util.Map<String, org.apache.jmeter.testelement.schema.PropertyDescriptor<?, ?>> properties = "
                + "(java.util.Map<String, org.apache.jmeter.testelement.schema.PropertyDescriptor<?, ?>>) field.get(this);\n"
                + "        properties.put(\"qa.null\", null);\n"
                + "        properties.put(\"qa.bad\", new ThrowingDescriptor());\n"
                + "        org.apache.jmeter.testelement.schema.TestElementPropertyDescriptor<org.apache.jmeter.testelement.TestElementSchema, org.apache.jmeter.config.Arguments> badAdapter = new org.apache.jmeter.testelement.schema.TestElementPropertyDescriptor<org.apache.jmeter.testelement.TestElementSchema, org.apache.jmeter.config.Arguments>(\"badAdapter\", org.apache.jmeter.config.Arguments.class, \"qa.badAdapter\");\n"
                + "        java.lang.reflect.Field valueClass = org.apache.jmeter.testelement.schema.TestElementPropertyDescriptor.class.getDeclaredField(\"klass\");\n"
                + "        valueClass.setAccessible(true);\n"
                + "        valueClass.set(badAdapter, null);\n"
                + "        properties.put(\"qa.badAdapter\", badAdapter);\n"
                + "        properties.put(\"qa.after\", new org.apache.jmeter.testelement.schema.StringPropertyDescriptor<org.apache.jmeter.testelement.TestElementSchema>(\"after\", \"qa.after\", \"schema-default\"));\n"
                + "        properties.put(\"qa.arguments\", new org.apache.jmeter.testelement.schema.TestElementPropertyDescriptor<org.apache.jmeter.testelement.TestElementSchema, org.apache.jmeter.config.Arguments>(\"arguments\", org.apache.jmeter.config.Arguments.class, \"qa.arguments\"));\n"
                + "      } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }\n"
                + "    }\n"
                + "  }\n"
                + "  public static final class ThrowingDescriptor implements "
                + "org.apache.jmeter.testelement.schema.PropertyDescriptor<org.apache.jmeter.testelement.TestElementSchema, String> {\n"
                + "    public String getShortName() { return \"bad\"; }\n"
                + "    public String getName() { throw new IllegalStateException(\"malformed descriptor\"); }\n"
                + "    public String getDefaultValue() { return \"bad-default\"; }\n"
                + "  }\n"
                + "}\n";
    }

    static String samplerMethod(String baseClass, boolean abstractClass) {
        if (abstractClass || !baseClass.equals("org.apache.jmeter.samplers.AbstractSampler")) {
            return "";
        }
        return "  public org.apache.jmeter.samplers.SampleResult sample(org.apache.jmeter.samplers.Entry entry) {\n"
                + "    org.apache.jmeter.samplers.SampleResult result = new org.apache.jmeter.samplers.SampleResult();\n"
                + "    result.sampleStart();\n"
                + "    result.setSuccessful(true);\n"
                + "    result.setResponseCodeOK();\n"
                + "    result.setResponseMessage(\"OK\");\n"
                + "    result.sampleEnd();\n"
                + "    return result;\n"
                + "  }\n";
    }

    static String guiBaseClass(String elementBaseClass) {
        if (elementBaseClass.equals("org.apache.jmeter.samplers.AbstractSampler")) {
            return "org.apache.jmeter.samplers.gui.AbstractSamplerGui";
        }
        if (elementBaseClass.equals("org.apache.jmeter.config.ConfigTestElement")) {
            return "org.apache.jmeter.config.gui.AbstractConfigGui";
        }
        return null;
    }

    static String guiSource(String className, String guiClassName, String guiBaseClass) {
        return guiSource(className, guiClassName, guiBaseClass, menuModeFor(guiBaseClass), false, false);
    }

    static String guiSource(
            String className, String guiClassName, String guiBaseClass, String menuMode, boolean exploding,
            boolean disabled) {
        int lastDot = guiClassName.lastIndexOf('.');
        String packageName = guiClassName.substring(0, lastDot);
        String simpleName = guiClassName.substring(lastDot + 1);
        String constructor = exploding
                ? "  public " + simpleName + "() { throw new IllegalStateException(\"GUI creation failed\"); }\n"
                : "";
        String label = displayLabel(className.substring(className.lastIndexOf('.') + 1));
        String menuCategories = "none".equals(menuMode)
                ? "  public java.util.Collection<String> getMenuCategories() { return null; }\n"
                : "  public java.util.Collection<String> getMenuCategories() { return java.util.Collections.singletonList("
                        + menuFactoryConstant(menuMode) + "); }\n";
        String canBeAdded = disabled ? "  public boolean canBeAdded() { return false; }\n" : "";
        return "package " + packageName + ";\n"
                + "public class " + simpleName + " extends " + guiBaseClass + " {\n"
                + constructor
                + "  public String getLabelResource() { return \"" + simpleName + "\"; }\n"
                + "  public String getStaticLabel() { return \"" + label + "\"; }\n"
                + menuCategories
                + canBeAdded
                + "  public org.apache.jmeter.testelement.TestElement createTestElement() {\n"
                + "    " + className + " element = new " + className + "();\n"
                + "    modifyTestElement(element);\n"
                + "    element.setProperty(\"qa.creation.path\", \"" + simpleName + "\");\n"
                + "    return element;\n"
                + "  }\n"
                + "  public void modifyTestElement(org.apache.jmeter.testelement.TestElement element) {\n"
                + "    configureTestElement(element);\n"
                + "  }\n"
                + "}\n";
    }

    static String materializationFailingGuiSource(String className, String guiClassName) {
        int lastDot = guiClassName.lastIndexOf('.');
        String packageName = guiClassName.substring(0, lastDot);
        String simpleName = guiClassName.substring(lastDot + 1);
        return "package " + packageName + ";\n"
                + "public class " + simpleName
                + " extends org.apache.jmeter.samplers.gui.AbstractSamplerGui {\n"
                + "  public String getLabelResource() { return \"" + simpleName + "\"; }\n"
                + "  public String getStaticLabel() { return \"Materialization Failure Sampler\"; }\n"
                + "  public java.util.Collection<String> getMenuCategories() { return java.util.Collections.singletonList(org.apache.jmeter.gui.util.MenuFactory.SAMPLERS); }\n"
                + "  public org.apache.jmeter.testelement.TestElement createTestElement() { throw new IllegalStateException(\"materialization failed: sentinel-no-stack\"); }\n"
                + "  public void modifyTestElement(org.apache.jmeter.testelement.TestElement element) { configureTestElement(element); }\n"
                + "}\n";
    }

    static String clearGuiSensitiveGuiSource(String className, String guiClassName) {
        int lastDot = guiClassName.lastIndexOf('.');
        String packageName = guiClassName.substring(0, lastDot);
        String simpleName = guiClassName.substring(lastDot + 1);
        String label = displayLabel(className.substring(className.lastIndexOf('.') + 1));
        return "package " + packageName + ";\n"
                + "public class " + simpleName + " extends org.apache.jmeter.samplers.gui.AbstractSamplerGui {\n"
                + "  private boolean cleared;\n"
                + "  public String getLabelResource() { return \"" + simpleName + "\"; }\n"
                + "  public String getStaticLabel() { return \"" + label + "\"; }\n"
                + "  public java.util.Collection<String> getMenuCategories() { return java.util.Collections.singletonList("
                + "org.apache.jmeter.gui.util.MenuFactory.SAMPLERS); }\n"
                + "  public void clearGui() { super.clearGui(); cleared = true; }\n"
                + "  public org.apache.jmeter.testelement.TestElement createTestElement() {\n"
                + "    " + className + " element = new " + className + "();\n"
                + "    modifyTestElement(element);\n"
                + "    element.setProperty(\"qa.clear.gui.state\", cleared ? \"cleared\" : \"stale\");\n"
                + "    return element;\n"
                + "  }\n"
                + "  public void modifyTestElement(org.apache.jmeter.testelement.TestElement element) {\n"
                + "    configureTestElement(element);\n"
                + "  }\n"
                + "}\n";
    }

    static String metadataBackedSource(String className) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        return "package " + packageName + ";\n"
                + "@org.apache.jmeter.gui.TestElementMetadata(labelResource=\"qa_metadata_sampler\", "
                + "actionGroups={org.apache.jmeter.gui.util.MenuFactory.SAMPLERS})\n"
                + "public class " + simpleName + " extends org.apache.jmeter.samplers.AbstractSampler {\n"
                + samplerMethod("org.apache.jmeter.samplers.AbstractSampler", false)
                + "}\n";
    }

    static String testBeanSource(String className) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        return "package " + packageName + ";\n"
                + "public class " + simpleName
                + " extends org.apache.jmeter.samplers.AbstractSampler implements org.apache.jmeter.testbeans.TestBean {\n"
                + "  private String endpoint = \"bean-default\";\n"
                + "  public String getEndpoint() { return endpoint; }\n"
                + "  public void setEndpoint(String endpoint) { this.endpoint = endpoint; }\n"
                + samplerMethod("org.apache.jmeter.samplers.AbstractSampler", false)
                + "}\n";
    }

    static String noMenuTestBeanSource(String className) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        return "package " + packageName + ";\n"
                + "public class " + simpleName
                + " extends org.apache.jmeter.testelement.AbstractTestElement"
                + " implements org.apache.jmeter.testbeans.TestBean {\n"
                + "  private String endpoint = \"no-menu-default\";\n"
                + "  public String getEndpoint() { return endpoint; }\n"
                + "  public void setEndpoint(String endpoint) { this.endpoint = endpoint; }\n"
                + "}\n";
    }

    static String testBeanInfoSource(String className) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        return "package " + packageName + ";\n"
                + "public class " + simpleName + "BeanInfo extends java.beans.SimpleBeanInfo {\n"
                + "  public java.beans.BeanDescriptor getBeanDescriptor() {\n"
                + "    java.beans.BeanDescriptor descriptor = new java.beans.BeanDescriptor(" + simpleName + ".class);\n"
                + "    descriptor.setDisplayName(\"QA Bean Backed Sampler\");\n"
                + "    return descriptor;\n"
                + "  }\n"
                + "}\n";
    }

    static String dependencySource(String className) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        return "package " + packageName + ";\n"
                + "public final class " + simpleName + " {\n"
                + "  private " + simpleName + "() { }\n"
                + "  public static String marker() { return \"dependency-loaded\"; }\n"
                + "}\n";
    }

    static String classpathDependentSource(String className, String dependencyClassName) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        return "package " + packageName + ";\n"
                + "public class " + simpleName + " extends org.apache.jmeter.samplers.AbstractSampler {\n"
                + "  static { " + dependencyClassName + ".marker(); }\n"
                + "  public " + simpleName + "() { setProperty(\"qa.dependency\", " + dependencyClassName
                + ".marker()); }\n"
                + samplerMethod("org.apache.jmeter.samplers.AbstractSampler", false)
                + "}\n";
    }

    private static String menuModeFor(String guiBaseClass) {
        if (guiBaseClass.equals("org.apache.jmeter.config.gui.AbstractConfigGui")) {
            return "config";
        }
        return "sampler";
    }

    private static String displayLabel(String simpleName) {
        return simpleName.replaceAll("(?<!^)([A-Z])", " $1").trim();
    }

    private static String menuFactoryConstant(String menuMode) {
        if ("config".equals(menuMode)) {
            return "org.apache.jmeter.gui.util.MenuFactory.CONFIG_ELEMENTS";
        }
        return "org.apache.jmeter.gui.util.MenuFactory.SAMPLERS";
    }
}
