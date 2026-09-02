package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.thisccl.j4a.components.ComponentCatalog;
import io.github.thisccl.j4a.jmx.SaveServiceJmxLoader;
import java.nio.file.Paths;
import java.util.List;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.control.DNSCacheManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

class LocalComponentPropertiesHeaderManagerTest {
    @BeforeAll
    static void initializeSelectedSaveService() {
        new SaveServiceJmxLoader(io.github.thisccl.j4a.TestJMeterRuntime.home());
    }

    @Test
    void exposesHeaderRowsAsAnExactStructuredAdapter() {
        HeaderManager manager = new HeaderManager();
        List<ComponentCatalog.ComponentProperty> properties =
                LocalComponentProperties.properties(manager, HeaderManager.class);

        assertThat(properties).anySatisfy(property -> assertThat(property)
                .extracting(
                        ComponentCatalog.ComponentProperty::address,
                        ComponentCatalog.ComponentProperty::type,
                        ComponentCatalog.ComponentProperty::writable,
                        ComponentCatalog.ComponentProperty::representationSource,
                        ComponentCatalog.ComponentProperty::valueShape,
                        ComponentCatalog.ComponentProperty::rowType,
                        ComponentCatalog.ComponentProperty::rowProperties)
                .containsExactly(
                        java.util.Collections.<Object>singletonList("HeaderManager.headers"),
                        "rows",
                        true,
                        "runtime_observation",
                        "rows",
                        "org.apache.jmeter.protocol.http.control.Header",
                        java.util.Arrays.asList("Header.name", "Header.value")));
    }

    @Test
    void doesNotAuthorizeAbsentDnsRowsWithoutRuntimeStorageEvidence() {
        List<ComponentCatalog.ComponentProperty> properties =
                LocalComponentProperties.properties(new DNSCacheManager(), DNSCacheManager.class);

        assertThat(properties).extracting(ComponentCatalog.ComponentProperty::address)
                .contains(java.util.Collections.<Object>singletonList("DNSCacheManager.servers"))
                .doesNotContain(java.util.Collections.<Object>singletonList("DNSCacheManager.hosts"));
    }

    @Test
    void ambiguousEmptyRuntimeMutatorsFailClosedToGenericCollection() {
        List<ComponentCatalog.ComponentProperty> properties = LocalComponentProperties.properties(
                new AmbiguousHeaderManager(), AmbiguousHeaderManager.class);

        assertThat(properties)
                .filteredOn(property -> java.util.Collections.<Object>singletonList("HeaderManager.headers")
                        .equals(property.address()))
                .extracting(ComponentCatalog.ComponentProperty::type)
                .containsExactly("collection");
    }

    @Test
    void emptyDirectCollectionWithoutRuntimeProofStaysGeneric() {
        NoProofRows rows = new NoProofRows();

        assertThat(LocalComponentProperties.properties(rows, NoProofRows.class))
                .filteredOn(property -> java.util.Collections.<Object>singletonList("fixture.rows")
                        .equals(property.address()))
                .extracting(
                        ComponentCatalog.ComponentProperty::type,
                        ComponentCatalog.ComponentProperty::writable,
                        ComponentCatalog.ComponentProperty::rowType)
                .containsExactly(tuple("collection", true, null));
    }

    @Test
    void exposesUnregisteredArgumentsThroughTheRuntimeRowsContract() {
        UnregisteredArgumentsElement defaults = new UnregisteredArgumentsElement();
        defaults.setProperty(new TestElementProperty("custom.arguments", new Arguments()));

        assertThat(LocalComponentProperties.properties(defaults, UnregisteredArgumentsElement.class))
                .filteredOn(property -> java.util.Collections.<Object>singletonList("custom.arguments")
                        .equals(property.address()))
                .extracting(
                        ComponentCatalog.ComponentProperty::type,
                        ComponentCatalog.ComponentProperty::writable,
                        ComponentCatalog.ComponentProperty::rowType)
                .containsExactly(tuple("rows", true, "org.apache.jmeter.config.Argument"));
    }

    public static final class UnregisteredArgumentsElement extends AbstractTestElement {
    }

    public static final class AmbiguousHeaderManager extends HeaderManager {
        public void append(Header header) {
            getHeaders().addItem(header);
        }
    }

    public static final class NoProofRows extends AbstractTestElement {
        public NoProofRows() {
            setProperty(new CollectionProperty("fixture.rows", java.util.Collections.emptyList()));
        }
    }
}
