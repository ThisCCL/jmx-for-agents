package io.github.thisccl.j4a.path;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.apply.ApplyPatch;
import io.github.thisccl.j4a.apply.ApplyPatchParser;
import io.github.thisccl.j4a.jmx.property.GraphCapability;
import io.github.thisccl.j4a.jmx.property.GraphNode;
import io.github.thisccl.j4a.jmx.property.GraphOwnership;
import io.github.thisccl.j4a.jmx.property.GraphSnapshot;
import io.github.thisccl.j4a.jmx.property.GraphType;
import io.github.thisccl.j4a.jmx.property.RecursiveValue;
import io.github.thisccl.j4a.jmx.property.RepresentationSource;
import io.github.thisccl.j4a.jmx.property.RuntimeClassConstraint;
import io.github.thisccl.j4a.jmx.property.RuntimeContext;
import io.github.thisccl.j4a.jmx.property.RuntimeFingerprint;
import io.github.thisccl.j4a.jmx.property.StorageKeyStatus;
import io.github.thisccl.j4a.jmx.property.WritableState;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class PropertyAddressInProcessQaTest {
    @Test
    void jsonSetAddressAndNativeYamlAddressResolveTheSameLoopControllerNode() {
        ApplyPatchParser parser = new ApplyPatchParser();
        PropertyAddress jsonAddress = parser.parsePropertyAddress(
                "[\"ThreadGroup.main_controller\",\"LoopController.loops\"]");
        ApplyPatch patch = parser.parse("changes:\n"
                + "  - set:\n"
                + "      ref: thread-group-1\n"
                + "      properties:\n"
                + "        - property:\n"
                + "            - ThreadGroup.main_controller\n"
                + "            - LoopController.loops\n"
                + "          type: int\n"
                + "          value: 9\n");
        PropertyAddress yamlAddress = ((ApplyPatch.SetOperation)
                patch.changes().get(0).operation()).properties().get(0).property();
        GraphSnapshot snapshot = threadGroup();

        assertThat(jsonAddress).isEqualTo(yamlAddress);
        assertThat(snapshot.resolve(jsonAddress)).isSameAs(snapshot.resolve(yamlAddress));
        assertThat(snapshot.resolve(jsonAddress).path().segments()).containsExactly(
                PropertyPathSegment.property("ThreadGroup.main_controller"),
                PropertyPathSegment.property("LoopController.loops"));
    }

    private static GraphSnapshot threadGroup() {
        PropertyPath controllerPath = new PropertyPath(Collections.singletonList(
                PropertyPathSegment.property("ThreadGroup.main_controller")));
        PropertyPath loopsPath = new PropertyPath(Arrays.asList(
                PropertyPathSegment.property("ThreadGroup.main_controller"),
                PropertyPathSegment.property("LoopController.loops")));
        GraphNode controller = node(
                controllerPath,
                GraphType.ELEMENT,
                RecursiveValue.element("ElementProperty", "LoopController", Collections.emptyList()));
        GraphNode loops = node(
                loopsPath,
                GraphType.INT,
                RecursiveValue.scalar(GraphType.INT, "IntegerProperty", Integer.valueOf(1)));
        return new GraphSnapshot(runtimeContext(), Arrays.asList(controller, loops));
    }

    private static GraphNode node(PropertyPath path, GraphType type, RecursiveValue value) {
        return new GraphNode(path, type, value, new GraphCapability(
                StorageKeyStatus.NON_KEY,
                WritableState.WRITABLE,
                null,
                GraphOwnership.USER,
                RepresentationSource.RUNTIME,
                RuntimeClassConstraint.propertyClass(value.propertyClass())));
    }

    private static RuntimeContext runtimeContext() {
        return new RuntimeContext(
                "property-address-in-process-qa",
                new RuntimeFingerprint(
                        "/opt/jmeter-fixture/apache-jmeter-5.6.3",
                        "5.6.3",
                        Collections.singletonMap("lib/ApacheJMeter_core.jar", "test-sha256")));
    }
}
