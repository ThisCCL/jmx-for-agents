package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import java.util.Optional;
import org.apache.jmeter.testelement.TestElement;

public final class JmxSemanticValidator {
    private JmxSemanticValidator() {
    }

    public static Optional<String> validate(JmxTestPlan testPlan) {
        for (TestElement element : testPlan.depthFirstTestElements()) {
            Optional<String> testClassError = validateTestClass(element);
            if (testClassError.isPresent()) {
                return testClassError;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validateTestClass(TestElement element) {
        String configured = element.getPropertyAsString(TestElement.TEST_CLASS);
        if (configured == null || configured.trim().isEmpty()) {
            return Optional.empty();
        }
        Class<?> actualClass = element.getClass();
        if (configured.equals(actualClass.getName()) || configured.equals(actualClass.getSimpleName())) {
            return Optional.empty();
        }
        return Optional.of("TestElement.test_class '" + configured
                + "' does not match loaded element class " + actualClass.getName()
                + " for element '" + element.getName() + "'.");
    }
}
