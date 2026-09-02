package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HttpArgumentSaveServiceCompatibilityTest {
    @BeforeAll
    static void initializeSelectedSaveService() {
        Todo8OpaqueFixtures.initializeSaveService();
    }

    @Test
    void httpDefaultsAndSamplerKeepExactHttpArgumentRowsAfterSaveServiceReload()
            throws Exception {
        TestElement defaults = emptyElement(new HttpDefaultsGui());
        TestElement sampler = emptyElement(new HttpTestSampleGui());
        defaults.setProperty(arguments("defaults", "one", "text/plain", false, false));
        sampler.setProperty(arguments("sampler", "two", "application/json", false, true));

        TestElement reloadedDefaults = (TestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(defaults));
        TestElement reloadedSampler = (TestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(sampler));

        assertHttpArgument(reloadedDefaults, "defaults", "one", "text/plain", false, false);
        assertHttpArgument(reloadedSampler, "sampler", "two", "application/json", false, true);
    }

    private static TestElement emptyElement(JMeterGUIComponent gui) {
        gui.clearGui();
        return gui.createTestElement();
    }

    private static TestElementProperty arguments(
            String name, String value, String contentType, boolean alwaysEncode, boolean useEquals) {
        HTTPArgument row = new HTTPArgument(name, value, "=", false, "UTF-8");
        row.setAlwaysEncoded(alwaysEncode);
        row.setContentType(contentType);
        row.setDescription("description-" + name);
        row.setUseEquals(useEquals);
        Arguments rows = new Arguments();
        rows.addArgument(row);
        return new TestElementProperty("HTTPsampler.Arguments", rows);
    }

    private static void assertHttpArgument(
            TestElement element,
            String name,
            String value,
            String contentType,
            boolean alwaysEncode,
            boolean useEquals) {
        Arguments rows = (Arguments) element.getProperty("HTTPsampler.Arguments").getObjectValue();
        assertThat(rows.getArgumentCount()).isEqualTo(1);
        assertThat(rows.getArgument(0)).isExactlyInstanceOf(HTTPArgument.class);
        Argument row = rows.getArgument(0);
        assertThat(row.getName()).isEqualTo(name);
        assertThat(row.getValue()).isEqualTo(value);
        assertThat(row.getMetaData()).isEqualTo(useEquals ? "=" : "");
        assertThat(row.getDescription()).isEqualTo("description-" + name);
        assertThat(((HTTPArgument) row).getContentType()).isEqualTo(contentType);
        assertThat(((HTTPArgument) row).isAlwaysEncoded()).isEqualTo(alwaysEncode);
        assertThat(row.getPropertyAsBoolean("HTTPArgument.use_equals")).isEqualTo(useEquals);
    }
}
