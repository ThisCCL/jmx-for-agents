package io.github.thisccl.j4a.jmx;

import java.nio.file.Path;

public interface JmxLoader {
    JmxTestPlan load(Path jmxPath);
}
