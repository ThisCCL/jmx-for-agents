package io.github.thisccl.j4a.validation;

import java.nio.file.Path;
import java.util.List;

public final class LocalJmxValidator {
    private final LocalJMeterWorkerClient workerClient;

    public LocalJmxValidator() {
        this(new LocalJMeterWorkerClient());
    }

    public LocalJmxValidator(LocalJMeterWorkerClient workerClient) {
        this.workerClient = workerClient;
    }

    public ValidationResult validate(Path jmxPath, Path jmeterHome) {
        try {
            LocalJMeterWorkerResult result = workerClient.execute(LocalJMeterWorkerRequest.validate(jmxPath, jmeterHome));
            LocalJMeterWorkerResponse response = result.response();
            if (response.success()) {
                return ValidationResult.success();
            }
            if ("PLUGIN_CLASS_MISSING".equals(response.errorCode())) {
                return ValidationResult.invalid(ValidationErrorCode.PLUGIN_CLASS_MISSING, response.message());
            }
            if ("SEMANTIC_LOAD_ERROR".equals(response.errorCode())) {
                return ValidationResult.invalid(ValidationErrorCode.SEMANTIC_LOAD_ERROR, response.message());
            }
            if ("XML_PARSE_ERROR".equals(response.errorCode())) {
                return ValidationResult.invalid(ValidationErrorCode.XML_PARSE_ERROR, response.message());
            }
            throw new LocalJMeterEnvironmentException(response.message());
        } catch (LocalJMeterEnvironmentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LocalJMeterEnvironmentException(
                    "Unable to run isolated local JMeter validation worker: " + exception.getMessage(), exception);
        }
    }

    static List<Path> localJars(Path jmeterHome) {
        return LocalJMeterClasspath.localJars(jmeterHome);
    }
}
