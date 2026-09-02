package io.github.thisccl.j4a.validation;

import java.util.Objects;
import java.util.Optional;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;

public final class RuntimeComponentIdentityResolver {
    private final LocalJMeterMenuRegistry registry;

    RuntimeComponentIdentityResolver(LocalJMeterMenuRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    private RuntimeComponentIdentityResolver() {
        this.registry = null;
    }

    public static RuntimeComponentIdentityResolver selected() {
        return LocalJMeterMenuRegistry.current().identityResolver();
    }

    public static RuntimeComponentIdentityResolver actualClasses() {
        return new RuntimeComponentIdentityResolver();
    }

    public String resolve(TestElement element) {
        Objects.requireNonNull(element, "element");
        if (registry == null) {
            return element.getClass().getName();
        }
        Optional<String> direct = registry.resolveActualClass(element.getClass().getName());
        if (direct.isPresent()) {
            return direct.get();
        }
        LocalJMeterMenuRegistry.Entry guiEntry = registeredGuiEntry(element);
        if (guiEntry != null) {
            TestElement probe = LocalJMeterElementMaterializer.createForIdentityProof(guiEntry);
            if (!probe.getClass().equals(element.getClass())) {
                throw mismatch(guiEntry, element);
            }
            return registeredIdentity(guiEntry);
        }
        return element.getClass().getName();
    }

    String registeredIdentity(LocalJMeterMenuRegistry.Entry entry) {
        requireOwned(entry);
        return entry.component();
    }

    String resolveRegistered(
            LocalJMeterMenuRegistry.Entry entry, TestElement element) {
        requireOwned(entry);
        Objects.requireNonNull(element, "element");
        if (entry.kind() != LocalJMeterMenuRegistry.RegistrationKind.GUI_COMPONENT) {
            if (!entry.component().equals(element.getClass().getName())) {
                throw mismatch(entry, element);
            }
            return entry.component();
        }
        return entry.component();
    }

    private LocalJMeterMenuRegistry.Entry registeredGuiEntry(TestElement element) {
        String guiClass = canonicalClassName(
                element.getPropertyAsString(TestElement.GUI_CLASS));
        if (guiClass == null) {
            return null;
        }
        LocalJMeterMenuRegistry.Entry entry = registry.resolve(guiClass).orElse(null);
        return entry != null
                && entry.kind() == LocalJMeterMenuRegistry.RegistrationKind.GUI_COMPONENT
                ? entry : null;
    }

    private void requireOwned(LocalJMeterMenuRegistry.Entry entry) {
        if (registry == null
                || !registry.owns(Objects.requireNonNull(entry, "entry"))) {
            throw new IllegalArgumentException(
                    "Component registration does not belong to the selected runtime registry");
        }
    }

    private static String canonicalClassName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String resolved = SaveService.aliasToClass(value);
        return resolved == null || resolved.trim().isEmpty() ? value : resolved;
    }

    private static IllegalStateException mismatch(
            LocalJMeterMenuRegistry.Entry entry, TestElement element) {
        return new IllegalStateException(
                "Selected runtime component identity mismatch for " + entry.component()
                        + ": materialized " + element.getClass().getName());
    }
}
