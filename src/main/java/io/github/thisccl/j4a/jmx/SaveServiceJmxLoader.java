package io.github.thisccl.j4a.jmx;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import org.apache.jmeter.save.SaveService;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jmeter.util.JMeterUtils;

public final class SaveServiceJmxLoader implements JmxLoader {
    private static boolean initialized;

    public SaveServiceJmxLoader() {
        this(selectedJMeterHome());
    }

    public SaveServiceJmxLoader(Path jmeterHome) {
        initializeSaveService(jmeterHome);
    }

    private static Path selectedJMeterHome() {
        String home = JMeterUtils.getJMeterHome();
        if (home == null || home.trim().isEmpty()) {
            throw new JmxLoadException("No initialized local JMeter runtime is selected for SaveService", null);
        }
        return java.nio.file.Paths.get(home);
    }

    private static synchronized void initializeSaveService(Path jmeterHome) {
        if (initialized) {
            return;
        }

        try {
            Path bin = jmeterHome.resolve("bin");
            requireRuntimeConfig(bin.resolve("jmeter.properties"));
            requireRuntimeConfig(bin.resolve("saveservice.properties"));
            requireRuntimeConfig(bin.resolve("upgrade.properties"));
            JMeterUtils.setJMeterHome(jmeterHome.toString());
            JMeterUtils.loadJMeterProperties(bin.resolve("jmeter.properties").toString());
            JMeterUtils.setProperty("saveservice_properties", bin.resolve("saveservice.properties").toString());
            JMeterUtils.setProperty("upgrade_properties", "bin/upgrade.properties");
            SaveService.loadProperties();
            initialized = true;
        } catch (IOException e) {
            throw new JmxLoadException("Unable to initialize JMeter SaveService", e);
        }
    }

    private static void requireRuntimeConfig(Path config) throws IOException {
        if (!Files.isRegularFile(config)) {
            throw new IOException("Missing local JMeter runtime config: " + config);
        }
    }

    @Override
    public JmxTestPlan load(Path jmxPath) {
        try {
            JmxSourceLineIndex sourceLineIndex = sourceLineIndex(jmxPath);
            HashTree loadedTree = SaveService.loadTree(jmxPath.toFile());
            if (!(loadedTree instanceof ListedHashTree)) {
                throw new JmxLoadException("Unable to load JMX as an ordered ListedHashTree: " + jmxPath, null);
            }
            return new JmxTestPlan((ListedHashTree) loadedTree, sourceLineIndex);
        } catch (JmxLoadException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new JmxLoadException("Unable to load JMX: " + jmxPath, e);
        }
    }

    private static JmxSourceLineIndex sourceLineIndex(Path jmxPath) throws IOException {
        try {
            return JmxSourceLineIndex.from(jmxPath);
        } catch (org.xml.sax.SAXException exception) {
            return JmxSourceLineIndex.empty();
        }
    }

    public void save(JmxTestPlan testPlan, Path jmxPath) {
        try (OutputStream outputStream = Files.newOutputStream(jmxPath)) {
            SaveService.saveTree(testPlan.tree(), outputStream);
        } catch (IOException | RuntimeException e) {
            throw new JmxLoadException("Unable to save JMX: " + jmxPath, e);
        }
    }
}
