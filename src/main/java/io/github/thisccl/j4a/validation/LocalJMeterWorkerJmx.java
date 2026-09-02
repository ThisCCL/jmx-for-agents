package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.JmxSourceLineIndex;
import io.github.thisccl.j4a.locator.LocatorNotFoundException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.jmeter.save.SaveService;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;

final class LocalJMeterWorkerJmx {
    private LocalJMeterWorkerJmx() {
    }

    static JmxTestPlan load(Path jmxPath, Path jmeterHome) throws Exception {
        HashTree loadedTree = SaveService.loadTree(jmxPath.toFile());
        if (!(loadedTree instanceof ListedHashTree)) {
            throw new IllegalArgumentException("Unable to load JMX as an ordered ListedHashTree: " + jmxPath);
        }
        return new JmxTestPlan((ListedHashTree) loadedTree, sourceLineIndex(jmxPath));
    }

    private static JmxSourceLineIndex sourceLineIndex(Path jmxPath) throws Exception {
        try {
            return JmxSourceLineIndex.from(jmxPath);
        } catch (org.xml.sax.SAXException exception) {
            return JmxSourceLineIndex.empty();
        }
    }

    static void save(JmxTestPlan testPlan, Path target) throws Exception {
        try (OutputStream output = Files.newOutputStream(target)) {
            SaveService.saveTree(testPlan.tree(), output);
        }
    }

    static void prepareSavedFile(Path jmxPath, Path jmeterHome) throws Exception {
        Class<?> fileServerClass = Class.forName("org.apache.jmeter.services.FileServer");
        Object fileServer = fileServerClass.getMethod("getFileServer").invoke(null);
        fileServerClass.getMethod("setBaseForScript", java.io.File.class).invoke(fileServer, jmxPath.toFile());
        HashTree loadedTree = SaveService.loadTree(jmxPath.toFile());
        Object jmeter = Class.forName("org.apache.jmeter.JMeter").getDeclaredConstructor().newInstance();
        Method convertSubTree = jmeter.getClass().getDeclaredMethod("convertSubTree", HashTree.class);
        convertSubTree.setAccessible(true);
        convertSubTree.invoke(jmeter, loadedTree);
    }

    static void initializeJMeter(Path jmeterHome) throws Exception {
        Class<?> jmeterUtils = Class.forName("org.apache.jmeter.util.JMeterUtils");

        invoke(jmeterUtils, "setJMeterHome", new Class<?>[] {String.class}, jmeterHome.toString());
        invoke(jmeterUtils, "loadJMeterProperties", new Class<?>[] {String.class}, jmeterHome.resolve("bin").resolve("jmeter.properties").toString());
        loadOptionalJMeterProperties(jmeterUtils, jmeterHome.resolve("bin").resolve("user.properties"));
        loadOptionalSystemProperties(jmeterUtils, jmeterHome.resolve("bin").resolve("system.properties"));
        invoke(jmeterUtils, "initLocale", new Class<?>[0]);
        String saveServiceProperties = jmeterHome.resolve("bin").resolve("saveservice.properties").toString();
        System.setProperty("saveservice_properties", saveServiceProperties);
        System.setProperty("upgrade_properties", "bin/upgrade.properties");
        invoke(jmeterUtils, "setProperty", new Class<?>[] {String.class, String.class}, "saveservice_properties", saveServiceProperties);
        invoke(jmeterUtils, "setProperty", new Class<?>[] {String.class, String.class}, "upgrade_properties", "bin/upgrade.properties");
        Class<?> saveService = Class.forName("org.apache.jmeter.save.SaveService");
        invoke(saveService, "loadProperties", new Class<?>[0]);
    }

    static String semanticMessage(Throwable throwable) {
        UnknownComponentCategoryException unknownCategory =
                cause(throwable, UnknownComponentCategoryException.class);
        if (unknownCategory != null) {
            return unknownCategory.getMessage();
        }
        ComponentsCursorException cursorFailure = cause(throwable, ComponentsCursorException.class);
        if (cursorFailure != null) {
            return cursorFailure.getMessage();
        }
        LocalJMeterFileCommitter.CommitException filesystemFailure =
                cause(throwable, LocalJMeterFileCommitter.CommitException.class);
        if (filesystemFailure != null) {
            return filesystemFailure.getMessage();
        }
        LocatorNotFoundException locatorFailure = cause(throwable, LocatorNotFoundException.class);
        if (locatorFailure != null) {
            return locatorFailure.getMessage();
        }
        String unresolvedClassName = unresolvedClassName(throwable);
        if (!unresolvedClassName.trim().isEmpty()) {
            return "Local JMeter classpath is missing class: " + unresolvedClassName;
        }
        String message = throwable.getMessage();
        String summary = message == null || message.trim().isEmpty()
                ? "Local JMeter validation failed: " + throwable.getClass().getSimpleName()
                : "Local JMeter validation failed: " + message;
        return summary + ". Cause chain: " + causeChain(throwable);
    }

    static boolean isUnknownComponentCategory(Throwable throwable) {
        return cause(throwable, UnknownComponentCategoryException.class) != null;
    }

    static String errorCode(Throwable throwable) {
        if (cause(throwable, UnknownComponentCategoryException.class) != null) {
            return "USAGE_ERROR";
        }
        if (cause(throwable, ComponentsCursorException.class) != null) {
            return "USAGE_ERROR";
        }
        if (cause(throwable, ComponentIdentityNotFoundException.class) != null) {
            return "COMPONENT_IDENTITY_NOT_FOUND";
        }
        if (cause(throwable, JMeterAddDisabledException.class) != null) {
            return "JMETER_ADD_DISABLED";
        }
        if (cause(throwable, JMeterPlacementRejectedException.class) != null) {
            return "JMETER_PLACEMENT_REJECTED";
        }
        if (cause(throwable, io.github.thisccl.j4a.apply.InvalidPlacementException.class) != null) {
            return "INVALID_PLACEMENT";
        }
        if (cause(throwable, CandidatePreservationException.class) != null) {
            return "CANDIDATE_PRESERVATION_FAILED";
        }
        if (cause(throwable, LocalJMeterMenuRegistry.IncompatibleRegistryException.class) != null) {
            return "JMETER_MENU_REGISTRY_INCOMPATIBLE";
        }
        LocalJMeterFileCommitter.CommitException filesystemFailure =
                cause(throwable, LocalJMeterFileCommitter.CommitException.class);
        if (filesystemFailure != null) {
            return filesystemFailure.errorCode();
        }
        if (cause(throwable, LocatorNotFoundException.class) != null) {
            return "LOCATOR_NOT_FOUND";
        }
        if (cause(throwable, io.github.thisccl.j4a.path.PropertyPathResolutionException.class) != null) {
            return "MISSING_PROPERTY";
        }
        if (cause(throwable, java.nio.file.FileAlreadyExistsException.class) != null) {
            return "FILESYSTEM_WRITE_ERROR";
        }
        if (cause(throwable, java.nio.file.AccessDeniedException.class) != null) {
            return "FILESYSTEM_WRITE_ERROR";
        }
        if (cause(throwable, java.io.FileNotFoundException.class) != null
                || cause(throwable, java.nio.file.NoSuchFileException.class) != null) {
            return "JMX_READ_ERROR";
        }
        if (hasCauseNamed(throwable, "XmlPullParserException") || hasCauseNamed(throwable, "StreamException")) {
            return "XML_PARSE_ERROR";
        }
        return environmentFailure(throwable) ? "PLUGIN_CLASS_MISSING" : "SEMANTIC_LOAD_ERROR";
    }

    static String category(String errorCode, String operation) {
        if ("COMPONENT_IDENTITY_NOT_FOUND".equals(errorCode)
                || "JMETER_ADD_DISABLED".equals(errorCode)
                || "JMETER_PLACEMENT_REJECTED".equals(errorCode)
                || "INVALID_PLACEMENT".equals(errorCode)
                || "CANDIDATE_PRESERVATION_FAILED".equals(errorCode)) {
            return "operation";
        }
        if ("JMETER_MENU_REGISTRY_INCOMPATIBLE".equals(errorCode)) {
            return "infrastructure";
        }
        if ("FILESYSTEM_WRITE_ERROR".equals(errorCode) || "JMX_READ_ERROR".equals(errorCode)) {
            return "filesystem";
        }
        if ("USAGE_ERROR".equals(errorCode)) {
            return "usage";
        }
        if ("LOCATOR_NOT_FOUND".equals(errorCode)) {
            return "locator";
        }
        if ("MISSING_PROPERTY".equals(errorCode)) {
            return "property-path";
        }
        if ("ARGUMENT_ROW_TYPE_ERROR".equals(errorCode)) {
            return "apply";
        }
        if ("XML_PARSE_ERROR".equals(errorCode)) {
            return "renderReadData".equals(operation) ? "read" : "validation";
        }
        return "runtime";
    }

    static String suggestedAction(String errorCode, Throwable throwable) {
        if (cause(throwable, UnknownComponentCategoryException.class) != null) {
            return UnknownComponentCategoryException.SUGGESTED_ACTION;
        }
        if (cause(throwable, ComponentsCursorException.class) != null) {
            return cause(throwable, ComponentsCursorException.class).suggestedAction();
        }
        if ("COMPONENT_IDENTITY_NOT_FOUND".equals(errorCode)) {
            return "rerun components against this JMeter home and use an exact emitted component FQCN.";
        }
        if ("JMETER_MENU_REGISTRY_INCOMPATIBLE".equals(errorCode)) {
            return "select a JMeter runtime exposing the required MenuFactory registry capabilities.";
        }
        LocalJMeterFileCommitter.CommitException filesystemFailure =
                cause(throwable, LocalJMeterFileCommitter.CommitException.class);
        if (filesystemFailure != null) {
            return filesystemFailure.suggestedAction();
        }
        if ("FILESYSTEM_WRITE_ERROR".equals(errorCode)) {
            return "check the output path and filesystem permissions, then retry the command.";
        }
        if ("USAGE_ERROR".equals(errorCode)) {
            return "copy the exact property type and value shape from focused read or components details, "
                    + "then retry the command.";
        }
        if ("LOCATOR_NOT_FOUND".equals(errorCode)) {
            return "rerun read and rebuild the patch with fresh refs, then retry apply.";
        }
        if ("MISSING_PROPERTY".equals(errorCode)) {
            return "rerun read with properties and copy the exact property path, then retry set.";
        }
        if ("ARGUMENT_ROW_TYPE_ERROR".equals(errorCode)) {
            return "copy the emitted row_type and row fields from focused read or components details; "
                    + "row_type is a target-bound assertion, not class authorization, then retry the command.";
        }
        if ("XML_PARSE_ERROR".equals(errorCode)) {
            return "inspect the JMX in JMeter, then retry the command.";
        }
        if ("JMX_READ_ERROR".equals(errorCode)) {
            return "check that the file exists and is readable, then retry the command.";
        }
        return environmentFailure(throwable)
                ? "install or select a local JMeter home containing the missing plugin class."
                : "inspect the JMX in the selected local JMeter runtime and retry.";
    }

    static String unresolvedClassName(Throwable throwable) {
        Throwable current = throwable;
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (current != null && seen.add(current)) {
            if ("CannotResolveClassException".equals(current.getClass().getSimpleName())) {
                return current.getMessage() == null ? "" : current.getMessage();
            }
            if (current instanceof ClassNotFoundException || current instanceof NoClassDefFoundError) {
                return current.getMessage() == null ? "" : current.getMessage();
            }
            current = current.getCause();
        }
        return "";
    }

    private static Throwable causeNamed(Throwable throwable, String simpleName) {
        Throwable current = throwable;
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (current != null && seen.add(current)) {
            if (simpleName.equals(current.getClass().getSimpleName())) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }

    private static <T extends Throwable> T cause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (current != null && seen.add(current)) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static void loadOptionalJMeterProperties(Class<?> jmeterUtils, Path propertiesPath) throws Exception {
        if (Files.isRegularFile(propertiesPath)) {
            Object loaded = invoke(
                    jmeterUtils, "loadProperties", new Class<?>[] {String.class}, propertiesPath.toString());
            Object current = invoke(jmeterUtils, "getJMeterProperties", new Class<?>[0]);
            if (!(loaded instanceof Properties) || !(current instanceof Properties)) {
                throw new IllegalStateException("JMeter optional properties did not expose Properties state: "
                        + propertiesPath);
            }
            ((Properties) current).putAll((Properties) loaded);
        }
    }

    private static void loadOptionalSystemProperties(Class<?> jmeterUtils, Path propertiesPath) throws Exception {
        if (Files.isRegularFile(propertiesPath)) {
            try {
                invoke(jmeterUtils, "loadSystemProperties", new Class<?>[] {String.class}, propertiesPath.toString());
            } catch (NoSuchMethodException ignored) {
                invoke(jmeterUtils, "loadProperties", new Class<?>[] {String.class}, propertiesPath.toString());
            }
        }
    }

    private static Object invoke(Class<?> target, String methodName, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = target.getMethod(methodName, parameterTypes);
        return method.invoke(null, args);
    }

    private static boolean environmentFailure(Throwable throwable) {
        return hasCauseNamed(throwable, "CannotResolveClassException")
                || hasCauseNamed(throwable, "ClassNotFoundException")
                || hasCauseNamed(throwable, "NoClassDefFoundError")
                || throwable instanceof LinkageError;
    }

    private static boolean hasCauseNamed(Throwable throwable, String simpleName) {
        Throwable current = throwable;
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (current != null && seen.add(current)) {
            if (simpleName.equals(current.getClass().getSimpleName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String causeChain(Throwable throwable) {
        StringBuilder result = new StringBuilder();
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Throwable, Boolean>());
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 8 && seen.add(current)) {
            if (depth > 0) result.append(" -> ");
            result.append('[').append(depth).append("] ").append(current.getClass().getName())
                    .append(": ").append(current.getMessage() == null ? "" : current.getMessage());
            current = current.getCause();
            depth++;
        }
        if (current != null) {
            result.append(" -> [truncated after 8 causes");
            if (seen.contains(current)) result.append("; cycle detected");
            result.append(']');
        }
        return result.toString();
    }
}
