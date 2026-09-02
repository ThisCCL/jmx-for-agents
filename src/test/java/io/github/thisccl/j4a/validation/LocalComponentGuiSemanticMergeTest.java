package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.components.ComponentCatalog;
import java.util.Collections;
import java.util.List;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.junit.jupiter.api.Test;

class LocalComponentGuiSemanticMergeTest {
    @Test
    void exactTargetSchemaAndBeanInfoRemainStrongerThanGuiDescriptors() {
        ConfigTestElement exactTarget = new ConfigTestElement();
        exactTarget.setProperty("qa.target", "persisted");
        ComponentCatalog.ComponentProperty targetProperty = find(
                LocalComponentProperties.propertiesWithMetadata(
                        exactTarget, exactTarget.getClass(), LocalPropertyGraphRuntimeContext.inProcess(),
                        observation(scalar("qa.target", "string", "gui-default"))).properties(),
                "qa.target");

        TestPlan schemaTarget = new TestPlan();
        ComponentCatalog.ComponentProperty schemaProperty = find(
                LocalComponentProperties.propertiesWithMetadata(
                        schemaTarget, schemaTarget.getClass(), LocalPropertyGraphRuntimeContext.inProcess(),
                        observation(scalar("TestPlan.comments", "int", Integer.valueOf(7)))).properties(),
                "TestPlan.comments");

        SyntheticTestBean beanTarget = new SyntheticTestBean();
        ComponentCatalog.ComponentProperty beanProperty = find(
                LocalComponentProperties.propertiesWithMetadata(
                        beanTarget, beanTarget.getClass(), LocalPropertyGraphRuntimeContext.inProcess(),
                        observation(scalar("qaBean", "int", Integer.valueOf(9)))).properties(),
                "qaBean");

        assertThat(targetProperty.representationSource()).isEqualTo("runtime");
        assertThat(targetProperty.requiredPropertyClass())
                .isEqualTo("org.apache.jmeter.testelement.property.StringProperty");
        assertThat(schemaProperty.type()).isEqualTo("string");
        assertThat(schemaProperty.representationSource()).isNotEqualTo("gui_semantic_descriptor");
        assertThat(beanProperty.type()).isEqualTo("string");
        assertThat(beanProperty.representationSource()).isNotEqualTo("gui_semantic_descriptor");
    }

    @Test
    void failedGuiObservationChangesOnlyMetadataSourceStatus() {
        ConfigTestElement target = new ConfigTestElement();
        LocalJMeterGuiSemanticMetadata.Observation unsupported =
                new LocalJMeterGuiSemanticMetadata.Observation(
                        Collections.<LocalJMeterGuiSemanticMetadata.ScalarDescriptor>emptyList(),
                        Collections.singletonList(new LocalJMeterGuiSemanticMetadata.Failure(
                                LocalJMeterGuiSemanticMetadata.FailureReason.UNSUPPORTED_VERSION, "5.7.0")),
                        new LocalJMeterGuiSemanticMetadata.Stats(0, 0, 0, 0, 0, 1L));

        LocalComponentProperties.MetadataProjection projection =
                LocalComponentProperties.propertiesWithMetadata(
                        target, target.getClass(), LocalPropertyGraphRuntimeContext.inProcess(), unsupported);

        assertThat(projection.runtimeMetadataStatus()).isEqualTo("runtime-metadata-unavailable");
        assertThat(projection.properties()).extracting(property -> property.address())
                .doesNotContain(Collections.<Object>singletonList("HTTPSampler.domain"));
    }

    @Test
    void diagnosticStatusIsExactlyProvenOrUnavailableForAllSourceOutcomes() {
        assertThat(LocalJMeterGuiSemanticMetadata.Observation.provenEmpty().status())
                .isEqualTo(LocalJMeterGuiSemanticMetadata.SourceStatus.RUNTIME_PROVEN);
        for (LocalJMeterGuiSemanticMetadata.FailureReason reason : new LocalJMeterGuiSemanticMetadata.FailureReason[] {
                LocalJMeterGuiSemanticMetadata.FailureReason.PROBE_AMBIGUOUS,
                LocalJMeterGuiSemanticMetadata.FailureReason.INACCESSIBLE_FIELD,
                LocalJMeterGuiSemanticMetadata.FailureReason.ELAPSED_TIME_BUDGET,
                LocalJMeterGuiSemanticMetadata.FailureReason.OUTPUT_BUDGET}) {
            LocalJMeterGuiSemanticMetadata.Observation unavailable =
                    new LocalJMeterGuiSemanticMetadata.Observation(
                            Collections.<LocalJMeterGuiSemanticMetadata.ScalarDescriptor>emptyList(),
                            Collections.singletonList(new LocalJMeterGuiSemanticMetadata.Failure(reason, "qa")),
                            new LocalJMeterGuiSemanticMetadata.Stats(0, 0, 0, 0, 0, 1L));

            assertThat(unavailable.status())
                    .as(reason.name())
                    .isEqualTo(LocalJMeterGuiSemanticMetadata.SourceStatus.RUNTIME_METADATA_UNAVAILABLE);
        }
        assertThat(LocalJMeterGuiSemanticMetadata.SourceStatus.values()).extracting(Enum::name)
                .containsExactly("RUNTIME_PROVEN", "RUNTIME_METADATA_UNAVAILABLE");
    }

    private static LocalJMeterGuiSemanticMetadata.ScalarDescriptor scalar(
            String property, String type, Object defaultValue) {
        return new LocalJMeterGuiSemanticMetadata.ScalarDescriptor(property, type, defaultValue);
    }

    private static LocalJMeterGuiSemanticMetadata.Observation observation(
            LocalJMeterGuiSemanticMetadata.ScalarDescriptor descriptor) {
        return new LocalJMeterGuiSemanticMetadata.Observation(
                Collections.singletonList(descriptor),
                Collections.<LocalJMeterGuiSemanticMetadata.Failure>emptyList(),
                new LocalJMeterGuiSemanticMetadata.Stats(1, 1, 1, 1, 0, 1L));
    }

    private static ComponentCatalog.ComponentProperty find(
            List<ComponentCatalog.ComponentProperty> properties, String property) {
        for (ComponentCatalog.ComponentProperty candidate : properties) {
            if (Collections.<Object>singletonList(property).equals(candidate.address())) return candidate;
        }
        throw new AssertionError("Missing property " + property);
    }

    public static final class SyntheticTestBean extends AbstractTestElement implements TestBean {
        private static final long serialVersionUID = 1L;

        public String getQaBean() {
            return getPropertyAsString("qaBean");
        }

        public void setQaBean(String value) {
            setProperty("qaBean", value);
        }
    }
}
