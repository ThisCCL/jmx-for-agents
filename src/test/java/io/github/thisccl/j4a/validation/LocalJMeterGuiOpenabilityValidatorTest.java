package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;
import java.util.Collections;
import java.lang.reflect.Field;
import javax.swing.SwingUtilities;
import org.apache.jmeter.gui.util.MenuFactory;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalJMeterGuiOpenabilityValidatorTest {
    @BeforeEach
    void resetProbe() {
        LifecycleGui.reset();
    }

    @Test
    void blankGuiMetadataSkipsValidation() {
        ProbeElement element = element("blank", "   ");

        LocalJMeterGuiOpenabilityValidator.validate(plan(element), "synthetic-home");

        assertThat(LifecycleGui.constructed.get()).isZero();
    }

    @Test
    void unknownSimpleGuiMetadataFailsClosed() {
        ProbeElement element = element("simple-unknown", "DefinitelyMissingGui");

        assertThatThrownBy(() -> LocalJMeterGuiOpenabilityValidator.validate(plan(element), "synthetic-home"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DefinitelyMissingGui");
    }

    @Test
    void unknownFullyQualifiedGuiMetadataFailsClosed() {
        ProbeElement element = element("fqcn-unknown", "qa.missing.DefinitelyMissingGui");

        assertThatThrownBy(() -> LocalJMeterGuiOpenabilityValidator.validate(plan(element), "synthetic-home"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qa.missing.DefinitelyMissingGui");
    }

    @Test
    void nonComponentGuiMetadataFailsClosed() {
        ProbeElement element = element("wrong-type", String.class.getName());

        assertThatThrownBy(() -> LocalJMeterGuiOpenabilityValidator.validate(plan(element), "synthetic-home"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.class.getName());
    }

    @Test
    void freshGuiRunsCompleteLifecycleOnEdtAgainstDistinctClone() {
        ProbeElement element = element("lifecycle", LifecycleGui.class.getName());

        LocalJMeterGuiOpenabilityValidator.validate(plan(element), "synthetic-home");

        assertThat(LifecycleGui.constructed.get()).isEqualTo(1);
        assertThat(LifecycleGui.clearCalls.get()).isEqualTo(1);
        assertThat(LifecycleGui.configureCalls.get()).isEqualTo(1);
        assertThat(LifecycleGui.modifyCalls.get()).isEqualTo(1);
        assertThat(LifecycleGui.allCallsOnEdt).isTrue();
        assertThat(LifecycleGui.configuredElement).isNotSameAs(element);
        assertThat(LifecycleGui.modifiedElement).isSameAs(LifecycleGui.configuredElement);
        assertThat(element.getPropertyAsString("qa.gui.copyback")).isEmpty();
    }

    @Test
    void sameTargetIsValidatedOnce() {
        ProbeElement element = element("same-target", LifecycleGui.class.getName());

        LocalJMeterGuiOpenabilityValidator.validate(plan(element, element), "synthetic-home");

        assertThat(LifecycleGui.constructed.get()).isEqualTo(1);
    }

    @Test
    void repeatedMutationIdentityIsDeduplicatedByFinalAddress() {
        ProbeElement element = element("same-target", LifecycleGui.class.getName());
        JmxTestPlan plan = plan(element);
        MutationImpact impact = MutationImpact.from(plan, Arrays.asList(element, element));

        LocalJMeterGuiOpenabilityValidator.validate(plan, "synthetic-home", impact);

        assertThat(impact.affectedElements()).hasSize(1);
        assertThat(LifecycleGui.constructed.get()).isEqualTo(1);
    }

    @Test
    void deletedMutationIdentityIsDropped() {
        ProbeElement deleted = element("deleted", LifecycleGui.class.getName());
        JmxTestPlan finalPlan = plan(element("survivor", " "));

        MutationImpact impact = MutationImpact.from(finalPlan, Collections.singletonList(deleted));
        LocalJMeterGuiOpenabilityValidator.validate(finalPlan, "synthetic-home", impact);

        assertThat(impact.affectedElements()).isEmpty();
        assertThat(LifecycleGui.constructed.get()).isZero();
    }

    @Test
    void duplicateIdenticalSiblingsRemainDistinctTargets() {
        ProbeElement first = element("duplicate", LifecycleGui.class.getName());
        ProbeElement second = element("duplicate", LifecycleGui.class.getName());

        LocalJMeterGuiOpenabilityValidator.validate(plan(first, second), "synthetic-home");

        assertThat(LifecycleGui.constructed.get()).isEqualTo(2);
    }

    @Test
    void exactTestBeanGuiMetadataUsesTestBeanAwareConstructor() {
        BeanElement element = new BeanElement();
        element.setName("bean");
        element.setProperty(TestElement.GUI_CLASS, TestBeanGUI.class.getName());
        element.setProperty(TestElement.TEST_CLASS, BeanElement.class.getName());

        LocalJMeterGuiOpenabilityValidator.validate(plan(element), "synthetic-home");

        assertThat(element.getName()).isEqualTo("bean");
    }

    @Test
    void testBeanLocaleListenerIsRemovedAfterSuccess() throws Exception {
        BeanElement element = new BeanElement();
        element.setName("listener-bean");
        element.setProperty(TestElement.GUI_CLASS, TestBeanGUI.class.getName());
        element.setProperty(TestElement.TEST_CLASS, BeanElement.class.getName());
        int listenersBefore = localeListenerCount();

        LocalJMeterGuiOpenabilityValidator.validate(plan(element), "synthetic-home");

        assertThat(localeListenerCount()).isEqualTo(listenersBefore);
    }

    @Test
    void fatalErrorsPropagateWithoutDiagnosticWrapping() {
        ProbeElement element = element("fatal", FatalGui.class.getName());

        assertThatThrownBy(() -> LocalJMeterGuiOpenabilityValidator.validate(plan(element), "synthetic-home"))
                .isInstanceOf(ProbeVirtualMachineError.class);
    }

    @Test
    void ordinaryCloneFailureHasContextualPhaseDiagnostic() {
        ProbeElement element = new CloneFailureElement();
        element.setName("clone-failure");
        element.setProperty(TestElement.GUI_CLASS, LifecycleGui.class.getName());
        element.setProperty(TestElement.TEST_CLASS, CloneFailureElement.class.getName());

        assertThatThrownBy(() -> LocalJMeterGuiOpenabilityValidator.validate(plan(element), "synthetic-home"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phase: clone", "Address: [0]", "clone-failure",
                        CloneFailureElement.class.getName(), LifecycleGui.class.getName(), "synthetic-home",
                        "clone failed");
    }

    @Test
    void clearFailureNamesLifecyclePhase() {
        assertPhaseFailure(ClearFailureGui.class, "clearGui");
    }

    @Test
    void configureFailureNamesLifecyclePhase() {
        assertPhaseFailure(ConfigureFailureGui.class, "configure");
    }

    @Test
    void modifyFailureNamesLifecyclePhase() {
        assertPhaseFailure(ModifyFailureGui.class, "modifyTestElement");
    }

    @Test
    void interruptionIsRestoredAndAbortsValidation() {
        ProbeElement element = element("interrupt", InterruptingGui.class.getName());
        Thread.interrupted();

        try {
            assertThatThrownBy(() -> LocalJMeterGuiOpenabilityValidator.validate(plan(element), "synthetic-home"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("interrupt");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void missingMutationAddressFailsClosedWithCorrelationDiagnostics() {
        ProbeElement element = element("present", LifecycleGui.class.getName());
        MutationImpact impact = new MutationImpact(Collections.singletonList(
                new MutationImpact.AffectedElement(Arrays.asList(1), MutationImpact.ElementSignature.of(element))));

        assertThatThrownBy(() -> LocalJMeterGuiOpenabilityValidator.validate(plan(element), "synthetic-home", impact))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phase: correlate")
                .hasMessageContaining("Address: [1]");
    }

    @Test
    void addedThenMovedTargetUsesItsFinalAddress() {
        ProbeElement first = element("first", " ");
        ProbeElement moved = element("moved", LifecycleGui.class.getName());
        ListedHashTree tree = new ListedHashTree();
        tree.add(first);
        JmxTestPlan plan = new JmxTestPlan(tree);
        tree.add(moved);
        tree.remove(moved);
        ListedHashTree reordered = new ListedHashTree();
        reordered.add(moved);
        reordered.add(first);
        plan = new JmxTestPlan(reordered);

        MutationImpact impact = MutationImpact.from(plan, Collections.singletonList(moved));
        LocalJMeterGuiOpenabilityValidator.validate(plan, "synthetic-home", impact);

        assertThat(impact.affectedElements().get(0).address()).containsExactly(0);
        assertThat(LifecycleGui.constructed.get()).isEqualTo(1);
    }

    @Test
    void structuralSignatureMismatchFailsClosed() {
        ProbeElement expected = element("target", LifecycleGui.class.getName());
        MutationImpact impact = MutationImpact.from(plan(expected), Collections.singletonList(expected));
        ProbeElement reloaded = element("target", LifecycleGui.class.getName());
        reloaded.setProperty("different.shape", "value");

        assertThatThrownBy(() -> LocalJMeterGuiOpenabilityValidator.validate(
                plan(reloaded), "synthetic-home", impact))
                .hasMessageContaining("Phase: correlate", "structural signature mismatch");
    }

    @Test
    void structuralSignatureAllowsOneShortAndOneQualifiedMetadataClass() {
        ProbeElement shortMetadata = element("target", "HttpTestSampleGui");
        ProbeElement qualifiedMetadata = element(
                "target", "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");

        assertThat(MutationImpact.ElementSignature.of(shortMetadata)
                .matches(MutationImpact.ElementSignature.of(qualifiedMetadata))).isTrue();
        assertThat(MutationImpact.ElementSignature.of(qualifiedMetadata)
                .matches(MutationImpact.ElementSignature.of(shortMetadata))).isTrue();
    }

    @Test
    void structuralSignatureAllowsIdenticalQualifiedMetadataClasses() {
        ProbeElement expected = element("target", "alpha.Gui");
        ProbeElement actual = element("target", "alpha.Gui");

        assertThat(MutationImpact.ElementSignature.of(expected)
                .matches(MutationImpact.ElementSignature.of(actual))).isTrue();
    }

    @Test
    void structuralSignatureRejectsDifferentPackagesWithSameSimpleMetadataClass() {
        ProbeElement alpha = element("target", "alpha.Gui");
        ProbeElement beta = element("target", "beta.Gui");

        assertThat(MutationImpact.ElementSignature.of(alpha)
                .matches(MutationImpact.ElementSignature.of(beta))).isFalse();
    }

    @Test
    void structuralSignatureRejectsWrongSimpleRuntimeAndPropertyShape() {
        ProbeElement expected = element("target", "ExpectedGui");
        ProbeElement wrongSimple = element("target", "WrongGui");
        BeanElement wrongRuntime = new BeanElement();
        wrongRuntime.setName("target");
        wrongRuntime.setProperty(TestElement.GUI_CLASS, "ExpectedGui");
        wrongRuntime.setProperty(TestElement.TEST_CLASS, ProbeElement.class.getName());
        ProbeElement wrongShape = element("target", "ExpectedGui");
        wrongShape.setProperty("different.shape", "value");

        MutationImpact.ElementSignature signature = MutationImpact.ElementSignature.of(expected);
        assertThat(signature.matches(MutationImpact.ElementSignature.of(wrongSimple))).isFalse();
        assertThat(signature.matches(MutationImpact.ElementSignature.of(wrongRuntime))).isFalse();
        assertThat(signature.matches(MutationImpact.ElementSignature.of(wrongShape))).isFalse();
    }

    @Test
    void emptyMutationImpactSkipsGuiValidation() {
        ProbeElement invalid = element("unchanged", "DefinitelyMissingGui");

        LocalJMeterGuiOpenabilityValidator.validate(plan(invalid), "synthetic-home", MutationImpact.none());

        assertThat(LifecycleGui.constructed.get()).isZero();
    }

    private static void assertPhaseFailure(Class<? extends LifecycleGui> guiClass, String phase) {
        ProbeElement element = element("phase-probe", guiClass.getName());

        assertThatThrownBy(() -> LocalJMeterGuiOpenabilityValidator.validate(plan(element), "synthetic-home"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(phase)
                .hasMessageContaining("phase-probe")
                .hasMessageContaining(guiClass.getName());
    }

    private static ProbeElement element(String name, String guiClass) {
        ProbeElement element = new ProbeElement();
        element.setName(name);
        element.setProperty(TestElement.GUI_CLASS, guiClass);
        element.setProperty(TestElement.TEST_CLASS, ProbeElement.class.getName());
        return element;
    }

    private static int localeListenerCount() throws Exception {
        Field field = org.apache.jmeter.util.JMeterUtils.class.getDeclaredField("localeChangeListeners");
        field.setAccessible(true);
        return ((java.util.Collection<?>) field.get(null)).size();
    }

    private static JmxTestPlan plan(TestElement... elements) {
        ListedHashTree tree = new ListedHashTree();
        for (TestElement element : elements) {
            tree.add(element);
        }
        return new JmxTestPlan(tree);
    }

    public static class ProbeElement extends AbstractTestElement {
        private static final long serialVersionUID = 1L;
    }

    public static final class BeanElement extends ProbeElement implements TestBean {
        private static final long serialVersionUID = 1L;
        private String endpoint;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
    }

    public static final class CloneFailureElement extends ProbeElement {
        private static final long serialVersionUID = 1L;

        @Override
        public Object clone() {
            throw new IllegalStateException("clone failed");
        }
    }

    public static class LifecycleGui extends AbstractSamplerGui {
        private static final long serialVersionUID = 1L;
        static final AtomicInteger constructed = new AtomicInteger();
        static final AtomicInteger clearCalls = new AtomicInteger();
        static final AtomicInteger configureCalls = new AtomicInteger();
        static final AtomicInteger modifyCalls = new AtomicInteger();
        static volatile boolean allCallsOnEdt;
        static volatile TestElement configuredElement;
        static volatile TestElement modifiedElement;

        public LifecycleGui() {
            constructed.incrementAndGet();
            allCallsOnEdt = SwingUtilities.isEventDispatchThread();
        }

        static void reset() {
            constructed.set(0);
            clearCalls.set(0);
            configureCalls.set(0);
            modifyCalls.set(0);
            allCallsOnEdt = true;
            configuredElement = null;
            modifiedElement = null;
        }

        @Override
        public void clearGui() {
            clearCalls.incrementAndGet();
            allCallsOnEdt &= SwingUtilities.isEventDispatchThread();
            super.clearGui();
        }

        @Override
        public void configure(TestElement element) {
            configureCalls.incrementAndGet();
            allCallsOnEdt &= SwingUtilities.isEventDispatchThread();
            configuredElement = element;
            super.configure(element);
        }

        @Override
        public void modifyTestElement(TestElement element) {
            modifyCalls.incrementAndGet();
            allCallsOnEdt &= SwingUtilities.isEventDispatchThread();
            modifiedElement = element;
            element.setProperty("qa.gui.copyback", "must-stay-on-clone");
        }

        @Override
        public TestElement createTestElement() {
            return new ProbeSampler();
        }

        @Override
        public String getLabelResource() {
            return "qa_lifecycle";
        }

        @Override
        public java.util.Collection<String> getMenuCategories() {
            return java.util.Collections.singletonList(MenuFactory.SAMPLERS);
        }
    }

    public static final class FatalGui extends LifecycleGui {
        public FatalGui() {
            throw new ProbeVirtualMachineError();
        }
    }

    public static final class ClearFailureGui extends LifecycleGui {
        @Override
        public void clearGui() {
            throw new IllegalStateException("clear failure");
        }
    }

    public static final class ConfigureFailureGui extends LifecycleGui {
        @Override
        public void configure(TestElement element) {
            throw new IllegalStateException("configure failure");
        }
    }

    public static final class ModifyFailureGui extends LifecycleGui {
        @Override
        public void modifyTestElement(TestElement element) {
            throw new IllegalStateException("modify failure");
        }
    }

    public static final class InterruptingGui extends LifecycleGui {
        @Override
        public void configure(TestElement element) {
            throw new IllegalStateException("interrupted configure", new InterruptedException("stop"));
        }
    }

    public static final class ProbeSampler extends org.apache.jmeter.samplers.AbstractSampler {
        private static final long serialVersionUID = 1L;

        @Override
        public SampleResult sample(Entry entry) {
            return new SampleResult();
        }
    }

    private static final class ProbeVirtualMachineError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
