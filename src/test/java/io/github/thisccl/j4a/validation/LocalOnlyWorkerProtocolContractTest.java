package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class LocalOnlyWorkerProtocolContractTest {
    @Test
    void requestAndResponseJsonContainNoProfileFields() {
        LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.validate(
                Paths.get("plan.jmx"), Paths.get("jmeter-home"));
        LocalJMeterWorkerResponse response = LocalJMeterWorkerResponse.failure(
                request, "LOCAL_JMETER_RUNTIME_ERROR", "runtime", null,
                "runtime failed", "configure JMeter home", "", "");

        assertThat(request.toJsonLine()).doesNotContain("profile", "selectedProfile");
        assertThat(response.toJsonLine()).doesNotContain("profile", "selectedProfile", "local-profile");
        assertThat(LocalJMeterWorkerResponse.fromJsonLine(response.toJsonLine()).errorCode())
                .isEqualTo("LOCAL_JMETER_RUNTIME_ERROR");
    }
}
