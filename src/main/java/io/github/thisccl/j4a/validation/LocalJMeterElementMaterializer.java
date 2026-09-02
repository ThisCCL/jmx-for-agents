package io.github.thisccl.j4a.validation;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.menu.StaticJMeterGUIComponent;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.apache.jmeter.testelement.TestElement;

final class LocalJMeterElementMaterializer {
    private static final Pattern RESOURCE_MARKER = Pattern.compile("^\\[res_key=.*]$");

    private LocalJMeterElementMaterializer() {
    }

    static TestElement create(LocalJMeterMenuRegistry.Entry entry) {
        return create(entry, entry.label());
    }

    static TestElement create(LocalJMeterMenuRegistry.Entry entry, String resolvedLabel) {
        TestElement element = createUnverified(entry, resolvedLabel);
        entry.identityResolver().resolveRegistered(entry, element);
        return element;
    }

    static TestElement createForIdentityProof(LocalJMeterMenuRegistry.Entry entry) {
        return createUnverified(entry, entry.label());
    }

    private static TestElement createUnverified(
            LocalJMeterMenuRegistry.Entry entry, String resolvedLabel) {
        Throwable primaryFailure;
        try {
            JMeterGUIComponent gui = primaryGui(entry);
            try {
                gui.clearGui();
                TestElement element = gui.createTestElement();
                if (element == null) throw new IllegalArgumentException("GUI creation returned no TestElement.");
                repairDefaultName(element, resolvedLabel);
                return element;
            } finally {
                if (gui instanceof TestBeanGUI) {
                    org.apache.jmeter.util.JMeterUtils.removeLocaleChangeListener((TestBeanGUI) gui);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            primaryFailure = exception instanceof InvocationTargetException
                    && ((InvocationTargetException) exception).getTargetException() != null
                    ? ((InvocationTargetException) exception).getTargetException() : exception;
        }
        TestElement fallback = directFallback(entry, resolvedLabel);
        if (fallback == null) throw sourceFaithful(entry, primaryFailure);
        return fallback;
    }

    private static JMeterGUIComponent primaryGui(LocalJMeterMenuRegistry.Entry entry)
            throws ReflectiveOperationException {
        Class<?> menuClass = Class.forName(entry.menuClassName(), true,
                LocalJMeterValidationWorker.class.getClassLoader());
        if (TestBean.class.isAssignableFrom(menuClass)
                || entry.kind() == LocalJMeterMenuRegistry.RegistrationKind.TEST_BEAN) {
            return new TestBeanGUI(menuClass);
        }
        if (entry.kind() == LocalJMeterMenuRegistry.RegistrationKind.METADATA_TEST_ELEMENT) {
            TestElementMetadata metadata = menuClass.getAnnotation(TestElementMetadata.class);
            return new StaticJMeterGUIComponent(menuClass, metadata);
        }
        if (!JMeterGUIComponent.class.isAssignableFrom(menuClass)) {
            throw new IllegalArgumentException("JMeter has no primary GUI materializer for " + entry.menuClassName());
        }
        return (JMeterGUIComponent) menuClass.getDeclaredConstructor().newInstance();
    }

    private static TestElement directFallback(LocalJMeterMenuRegistry.Entry entry, String resolvedLabel) {
        Class<?> type = entry.fallbackTestElementClass();
        if (type == null || Modifier.isAbstract(type.getModifiers()) || !Modifier.isPublic(type.getModifiers())) return null;
        try {
            Constructor<?> constructor = type.getConstructor();
            TestElement element = (TestElement) constructor.newInstance();
            String providedName = element.getName();
            String guiIdentity = TestBean.class.isAssignableFrom(type) ? TestBeanGUI.class.getName() : null;
            if (guiIdentity == null) return null;
            element.clear();
            element.setProperty(TestElement.TEST_CLASS, type.getName());
            element.setProperty(TestElement.GUI_CLASS, guiIdentity);
            element.setName(providedName);
            element.setEnabled(true);
            repairDefaultName(element, resolvedLabel);
            return element;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return null;
        }
    }

    private static void repairDefaultName(TestElement element, String resolvedLabel) {
        String name = element.getName();
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || RESOURCE_MARKER.matcher(trimmed).matches()) {
            element.setName(resolvedLabel);
        }
    }

    private static RuntimeException sourceFaithful(LocalJMeterMenuRegistry.Entry entry, Throwable failure) {
        return new MaterializationException("JMeter materialization failed for " + entry.menuClassName()
                + ": " + failure.getClass().getName() + ": " + failure.getMessage(), failure);
    }

    static final class MaterializationException extends IllegalArgumentException {
        MaterializationException(String message, Throwable cause) { super(message, cause); }
    }
}
