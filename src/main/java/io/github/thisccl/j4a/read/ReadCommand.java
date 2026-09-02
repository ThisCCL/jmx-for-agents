package io.github.thisccl.j4a.read;

import io.github.thisccl.j4a.jmx.JmxLoader;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.SaveServiceJmxLoader;
import java.nio.file.Path;

public final class ReadCommand {
    private final JmxLoader loader;
    private final YamlReadRenderer renderer;

    public ReadCommand() {
        this(new SaveServiceJmxLoader(), new YamlReadRenderer());
    }

    ReadCommand(JmxLoader loader, YamlReadRenderer renderer) {
        this.loader = loader;
        this.renderer = renderer;
    }

    public String read(Path jmxPath, ReadOptions options) {
        JmxTestPlan testPlan = loader.load(jmxPath);
        return renderer.render(testPlan, options);
    }
}
