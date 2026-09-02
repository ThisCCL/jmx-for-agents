package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import java.lang.reflect.InvocationTargetException;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.apache.jmeter.util.JMeterUtils;

final class LocalJMeterGuiOpenabilityValidator {
    private LocalJMeterGuiOpenabilityValidator() {
    }

    static void validate(JmxTestPlan testPlan, String jmeterHome) {
        Map<TestElement, Boolean> seen = new IdentityHashMap<>();
        java.util.List<TestElement> elements = new java.util.ArrayList<>();
        for (TestElement element : testPlan.depthFirstTestElements()) {
            if (seen.put(element, Boolean.TRUE) == null) {
                elements.add(element);
            }
        }
        validate(testPlan, jmeterHome, MutationImpact.from(testPlan, elements));
    }

    static void validate(JmxTestPlan testPlan, String jmeterHome, MutationImpact impact) {
        for (MutationImpact.AffectedElement affected : impact.affectedElements()) {
            TestElement element;
            try {
                element = LocalWorkerTreeAddress.resolve(testPlan, affected.address());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Local JMeter GUI validation failed. Phase: correlate"
                        + ". Address: " + affected.address() + ". Selected local JMeter home: " + jmeterHome
                        + ". Root cause: " + exception.getMessage(), exception);
            }
            MutationImpact.ElementSignature actualSignature = MutationImpact.ElementSignature.of(element);
            if (!affected.signature().matches(actualSignature)) {
                throw failure("correlate", affected.address(), element, element.getPropertyAsString(TestElement.GUI_CLASS),
                        jmeterHome, new IllegalArgumentException("Mutation target structural signature mismatch. Expected: "
                                + affected.signature() + ". Actual: " + actualSignature));
            }
            String guiClass = element.getPropertyAsString(TestElement.GUI_CLASS);
            if (guiClass == null || guiClass.trim().isEmpty()) {
                continue;
            }
            validateElement(element, affected.address(), guiClass, jmeterHome);
        }
    }

    private static void validateElement(TestElement source, java.util.List<Integer> address, String guiClass,
            String jmeterHome) {
        TestElement clone = cloneElement(source, address, guiClass, jmeterHome);
        if (clone == source) {
            throw failure("clone", address, source, guiClass, jmeterHome,
                    new IllegalStateException("TestElement clone must be distinct"));
        }
        try {
            SwingUtilities.invokeAndWait(
                    () -> runLifecycle(source, clone, address, guiClass, jmeterHome));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("interrupt", address, source, guiClass, jmeterHome, exception);
        } catch (InvocationTargetException exception) {
            rethrow(exception.getCause());
        }
    }

    private static void runLifecycle(TestElement source, TestElement clone,
            java.util.List<Integer> address, String guiClass, String jmeterHome) {
        JMeterGUIComponent component = null;
        try {
            component = construct(source, address, guiClass, jmeterHome);
            clear(component, source, address, guiClass, jmeterHome);
            configure(component, clone, source, address, guiClass, jmeterHome);
            modify(component, clone, source, address, guiClass, jmeterHome);
        } finally {
            if (component instanceof TestBeanGUI) {
                JMeterUtils.removeLocaleChangeListener((TestBeanGUI) component);
            }
        }
    }

    private static TestElement cloneElement(TestElement source, java.util.List<Integer> address,
            String guiClass, String jmeterHome) {
        try {
            return (TestElement) source.clone();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable throwable) {
            throw lifecycleFailure("clone", address, source, guiClass, jmeterHome, throwable);
        }
    }

    private static JMeterGUIComponent construct(TestElement source, java.util.List<Integer> address,
            String guiClass, String jmeterHome) {
        try {
            if (TestBeanGUI.class.getName().equals(guiClass)) {
                return new TestBeanGUI(source.getClass());
            }
            Class<?> type = Class.forName(
                    guiClass, true, LocalJMeterValidationWorker.class.getClassLoader());
            Object instance = type.getDeclaredConstructor().newInstance();
            if (!(instance instanceof JMeterGUIComponent)) {
                throw new IllegalArgumentException(
                        "GUI metadata does not name a JMeterGUIComponent: " + guiClass);
            }
            return (JMeterGUIComponent) instance;
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable throwable) {
            throw lifecycleFailure("construct", address, source, guiClass, jmeterHome, throwable);
        }
    }

    private static void clear(JMeterGUIComponent component, TestElement source,
            java.util.List<Integer> address, String guiClass, String jmeterHome) {
        try {
            component.clearGui();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable throwable) {
            throw lifecycleFailure("clearGui", address, source, guiClass, jmeterHome, throwable);
        }
    }

    private static void configure(JMeterGUIComponent component, TestElement clone, TestElement source,
            java.util.List<Integer> address, String guiClass, String jmeterHome) {
        try {
            component.configure(clone);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable throwable) {
            throw lifecycleFailure("configure", address, source, guiClass, jmeterHome, throwable);
        }
    }

    private static void modify(JMeterGUIComponent component, TestElement clone, TestElement source,
            java.util.List<Integer> address, String guiClass, String jmeterHome) {
        try {
            component.modifyTestElement(clone);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable throwable) {
            throw lifecycleFailure("modifyTestElement", address, source, guiClass, jmeterHome, throwable);
        }
    }

    private static IllegalArgumentException lifecycleFailure(String phase,
            java.util.List<Integer> address, TestElement source, String guiClass,
            String jmeterHome, Throwable throwable) {
        Throwable fatal = fatalCause(throwable);
        if (fatal instanceof VirtualMachineError) {
            throw (VirtualMachineError) fatal;
        }
        if (fatal instanceof ThreadDeath) {
            throw (ThreadDeath) fatal;
        }
        if (containsInterruptedException(throwable)) {
            Thread.currentThread().interrupt();
        }
        return failure(phase, address, source, guiClass, jmeterHome, throwable);
    }

    private static void rethrow(Throwable throwable) {
        if (throwable instanceof VirtualMachineError) {
            throw (VirtualMachineError) throwable;
        }
        if (throwable instanceof ThreadDeath) {
            throw (ThreadDeath) throwable;
        }
        if (containsInterruptedException(throwable)) {
            Thread.currentThread().interrupt();
        }
        if (throwable instanceof RuntimeException) {
            throw (RuntimeException) throwable;
        }
        throw new IllegalArgumentException(throwable);
    }

    private static IllegalArgumentException failure(String phase, java.util.List<Integer> address, TestElement element,
            String guiClass, String jmeterHome, Throwable throwable) {
        Throwable root = rootCause(throwable);
        return new IllegalArgumentException("Local JMeter GUI validation failed. Phase: " + phase
                + ". Address: " + address + ". Component: " + element.getName()
                + ". Component class: " + element.getClass().getName() + ". GUI class: " + guiClass
                + ". Selected local JMeter home: " + jmeterHome + ". Root cause: " + root.getClass().getName()
                + ": " + (root.getMessage() == null ? "" : root.getMessage()), throwable);
    }

    private static boolean containsInterruptedException(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    private static Throwable fatalCause(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof VirtualMachineError || current instanceof ThreadDeath) {
                return current;
            }
        }
        return null;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
