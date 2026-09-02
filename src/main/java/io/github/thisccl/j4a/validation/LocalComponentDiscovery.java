package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.components.ComponentCatalog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class LocalComponentDiscovery {
    private LocalComponentDiscovery() {
    }

    static List<ComponentCatalog.ComponentDefinition> discover(Path jmeterHome) {
        return discover(LocalJMeterMenuRegistry.current());
    }

    static List<ComponentCatalog.ComponentDefinition> discover(LocalJMeterMenuRegistry registry) {
        Map<String, String> categoryLabels = registry.categoryLabels();
        List<ComponentCatalog.ComponentDefinition> definitions = new ArrayList<>();
        for (LocalJMeterMenuRegistry.Entry entry : registry.entries()) {
            String category = LocalComponentCategoryCatalog.resolveCategoryId(
                    entry.group(), Collections.<ComponentCatalog.ComponentDefinition>emptyList())
                    .orElse("local");
            String categoryLabel = "local".equals(category)
                    ? "Local Components"
                    : categoryLabels.get(entry.group());
            if (categoryLabel == null || categoryLabel.trim().isEmpty()) {
                categoryLabel = LocalComponentCategoryCatalog.fallbackLabel(entry.group())
                        .orElse("Local Components");
            }
            definitions.add(new ComponentCatalog.ComponentDefinition(
                    category, categoryLabel,
                    registry.identityResolver().registeredIdentity(entry), entry.label(),
                    Collections.<ComponentCatalog.ComponentProperty>emptyList(), entry.menuClassName(),
                    entry.kind().name()));
        }
        return Collections.unmodifiableList(definitions);
    }

    static ComponentCatalog.ComponentDefinition withProperties(
            ComponentCatalog.ComponentDefinition definition,
            LocalJMeterMenuRegistry.Entry entry,
            io.github.thisccl.j4a.jmx.property.RuntimeContext runtimeContext) throws Exception {
        MaterializedDefaults materialized = materializeDefaults(entry, runtimeContext);
        org.apache.jmeter.testelement.TestElement defaults = materialized.defaults;
        Class<?> componentClass = defaults == null ? null : defaults.getClass();
        List<ComponentCatalog.ComponentProperty> properties = Collections.emptyList();
        String runtimeMetadataStatus = "runtime-proven";
        if (defaults != null) {
            LocalComponentProperties.MetadataProjection projection = LocalComponentProperties.propertiesWithMetadata(
                    defaults, componentClass, runtimeContext, materialized.guiSemanticMetadata);
            properties = projection.properties();
            runtimeMetadataStatus = projection.runtimeMetadataStatus();
        }
        String component = defaults == null
                ? entry.identityResolver().registeredIdentity(entry)
                : entry.identityResolver().resolveRegistered(entry, defaults);
        return new ComponentCatalog.ComponentDefinition(
                definition.category(), definition.categoryLabel(), component, definition.label(),
                properties, definition.menuClassName(), definition.registrationKind(), runtimeMetadataStatus);
    }

    private static MaterializedDefaults materializeDefaults(
            LocalJMeterMenuRegistry.Entry entry,
            io.github.thisccl.j4a.jmx.property.RuntimeContext runtimeContext) throws Exception {
        Class<?> fallback = entry.fallbackTestElementClass();
        if (fallback != null) {
            java.lang.reflect.Constructor<?> constructor = fallback.getDeclaredConstructor();
            if (java.lang.reflect.Modifier.isPublic(constructor.getModifiers())
                    && java.lang.reflect.Modifier.isPublic(fallback.getModifiers())) {
                return new MaterializedDefaults(
                        (org.apache.jmeter.testelement.TestElement) constructor.newInstance(),
                        LocalJMeterGuiSemanticMetadata.Observation.provenEmpty());
            }
            return MaterializedDefaults.empty();
        }
        if (entry.kind() != LocalJMeterMenuRegistry.RegistrationKind.GUI_COMPONENT) {
            return MaterializedDefaults.empty();
        }
        Class<?> menuClass = Class.forName(entry.menuClassName(), true,
                LocalJMeterValidationWorker.class.getClassLoader());
        org.apache.jmeter.gui.JMeterGUIComponent gui =
                (org.apache.jmeter.gui.JMeterGUIComponent) menuClass.getDeclaredConstructor().newInstance();
        LocalJMeterGuiSemanticInstrumentation.guiConstructed();
        try {
            org.apache.jmeter.testelement.TestElement defaults = gui.createTestElement();
            defaults.setProperty(org.apache.jmeter.testelement.TestElement.GUI_CLASS, entry.menuClassName());
            LocalJMeterGuiSemanticMetadata.Observation semantic = semanticMetadata(
                    entry, runtimeContext);
            return new MaterializedDefaults(defaults, semantic);
        } finally {
            if (gui instanceof org.apache.jmeter.testbeans.gui.TestBeanGUI) {
                org.apache.jmeter.util.JMeterUtils.removeLocaleChangeListener(
                        (org.apache.jmeter.testbeans.gui.TestBeanGUI) gui);
            }
        }
    }

    static LocalJMeterGuiSemanticMetadata.Observation semanticMetadata(
            LocalJMeterMenuRegistry.Entry entry,
            io.github.thisccl.j4a.jmx.property.RuntimeContext runtimeContext) {
        if (LocalJMeterGuiSemanticMetadata.disabled()
                || entry.kind() != LocalJMeterMenuRegistry.RegistrationKind.GUI_COMPONENT) {
            return LocalJMeterGuiSemanticMetadata.Observation.provenEmpty();
        }
        return LocalJMeterWorkerComponents.semanticMetadata(entry, runtimeContext);
    }

    private static final class MaterializedDefaults {
        private final org.apache.jmeter.testelement.TestElement defaults;
        private final LocalJMeterGuiSemanticMetadata.Observation guiSemanticMetadata;

        private MaterializedDefaults(
                org.apache.jmeter.testelement.TestElement defaults,
                LocalJMeterGuiSemanticMetadata.Observation guiSemanticMetadata) {
            this.defaults = defaults;
            this.guiSemanticMetadata = guiSemanticMetadata;
        }

        private static MaterializedDefaults empty() {
            return new MaterializedDefaults(
                    null, LocalJMeterGuiSemanticMetadata.Observation.provenEmpty());
        }
    }
}
