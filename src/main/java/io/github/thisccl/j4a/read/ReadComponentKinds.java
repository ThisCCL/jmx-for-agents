package io.github.thisccl.j4a.read;

import java.util.LinkedHashSet;
import java.util.Set;

final class ReadComponentKinds {
    private ReadComponentKinds() {
    }

    static Set<String> keyPropertyNames(String componentKind) {
        Set<String> names = new LinkedHashSet<>();
        switch (componentKind) {
            case "org.apache.jmeter.control.gui.TestPlanGui":
                names.add("TestElement.name");
                names.add("TestPlan.comments");
                break;
            case "org.apache.jmeter.threads.gui.ThreadGroupGui":
                names.add("TestElement.name");
                names.add("ThreadGroup.num_threads");
                names.add("ThreadGroup.ramp_time");
                names.add("ThreadGroup.on_sample_error");
                break;
            case "org.apache.jmeter.control.gui.LoopControlPanel":
                names.add("TestElement.name");
                names.add("LoopController.loops");
                names.add("LoopController.continue_forever");
                break;
            case "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui":
                names.add("TestElement.name");
                names.add("HTTPSampler.domain");
                names.add("HTTPSampler.path");
                names.add("HTTPSampler.method");
                break;
            default:
                break;
        }
        return names;
    }
}
