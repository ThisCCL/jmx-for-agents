package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RuntimeStructuredRowValueTest {
    private static int constructorAttempts;

    @BeforeAll
    static void initializeSelectedSaveService() {
        Todo8OpaqueFixtures.initializeSaveService();
    }

    @Test
    void documentsCurrentObservedArgumentRowsInRuntimeFieldOrder() {
        Arguments arguments = new Arguments();
        arguments.addArgument(new Argument("observed", "value", "=", "description"));

        RuntimeStructuredRowDocument.Projection document = RuntimeStructuredRowDocument.observe(
                new TestElementProperty("arguments", arguments), context()).get();

        assertThat(document.rowType()).isEqualTo(Argument.class.getName());
        assertThat(document.fields()).containsExactly(
                Argument.ARG_NAME, Argument.VALUE, Argument.METADATA, Argument.DESCRIPTION);
        assertThat(document.value().keySet())
                .containsExactly("row_type", "row_properties", "rows");
        assertThat(document.value().get("row_type")).isEqualTo(Argument.class.getName());
        assertThat(document.value().get("rows")).isEqualTo(Collections.singletonList(
                argumentRow("observed", "value", "=", "description")));
    }

    @Test
    void serializesEmptyAndPopulatedRowsWithCompleteOrderedDescriptors() {
        Arguments populated = new Arguments();
        populated.addArgument(new Argument("observed", "value", "=", "description"));
        RuntimeStructuredRowDocument.Projection populatedDocument =
                RuntimeStructuredRowDocument.observe(
                        new TestElementProperty("arguments", populated), context()).get();
        RuntimeStructuredRowDocument.Projection emptyDocument = RuntimeStructuredRowDocument.observe(
                new TestElementProperty("arguments", new Arguments()), context()).get();

        assertThat(populatedDocument.value().keySet())
                .containsExactly("row_type", "row_properties", "rows");
        assertThat(emptyDocument.value().keySet())
                .containsExactly("row_type", "row_properties", "rows");
        assertThat(populatedDocument.value().get("row_properties")).isEqualTo(Arrays.asList(
                descriptor(Argument.ARG_NAME, "string", false, ""),
                descriptor(Argument.VALUE, "string", false, ""),
                descriptor(Argument.METADATA, "string", false, ""),
                descriptor(Argument.DESCRIPTION, "string", false, "")));
        assertThat(emptyDocument.value().get("row_properties")).isEqualTo(Arrays.asList(
                descriptor(Argument.ARG_NAME, "string", false, ""),
                descriptor(Argument.VALUE, "string", false, ""),
                descriptor(Argument.DESCRIPTION, "string", false, ""),
                descriptor(Argument.METADATA, "string", false, "")));
    }

    @Test
    void fillsEveryOmittedOptionalFieldFromItsDistinctProvenDefault() {
        StructuredRowShape shape = defaultedShape();
        Map<String, Object> supplied = defaultedRow();
        Map<String, Object> wrapper = rowsDocument(defaultDescriptors(),
                Collections.singletonList(supplied));

        StructuredRowValue normalized = shape.normalize(wrapper);
        StructuredRowValue defaulted = shape.normalize(rowsDocument(defaultDescriptors(),
                Collections.singletonList(row("required", "present"))));

        assertThat(normalized.rowProperties()).extracting(StructuredRowField::descriptor)
                .containsExactlyElementsOf(defaultDescriptors());
        assertThat(normalized.rows()).containsExactly(supplied);
        assertThat(defaulted.rows()).containsExactly(defaultedRow());
    }

    @Test
    void rejectsRequiredOmissionAndDefaultAssertionDrift() {
        StructuredRowShape shape = defaultedShape();
        Map<String, Object> supplied = defaultedRow();

        assertThatThrownBy(() -> shape.normalize(rowsDocument(Arrays.asList(
                descriptor("nullable", "null", false, null),
                descriptor("empty", "string", false, ""),
                descriptor("zero", "int", false, Integer.valueOf(0)),
                descriptor("enabled", "boolean", false, Boolean.TRUE),
                descriptor("required", "string", true)), Collections.singletonList(supplied))))
                .isInstanceOf(StructuredRowValueException.class)
                .hasMessageContaining("row_properties");
        assertThatThrownBy(() -> shape.normalize(rowsDocument(defaultDescriptors(),
                Collections.singletonList(row(
                        "nullable", null,
                        "empty", "",
                        "zero", Integer.valueOf(0),
                        "enabled", Boolean.FALSE)))))
                .isInstanceOf(StructuredRowValueException.class)
                .hasMessageContaining("required");
    }

    @Test
    void populatedUserValuesNeverReplacePristineDefaults() {
        StructuredRowShape shape = argumentsShape();

        StructuredRowValue normalized = shape.normalize(Collections.singletonList(row(
                Argument.ARG_NAME, "next",
                Argument.VALUE, "value",
                Argument.METADATA, "=")));

        assertThat(normalized.rows()).containsExactly(argumentRow("next", "value", "=", ""));
        assertThat(normalized.rows().get(0).get(Argument.DESCRIPTION))
                .isNotEqualTo("description");
    }

    @Test
    void rejectsIllegalDefaultPresenceMetadataAtConstruction() {
        assertThatThrownBy(() -> new StructuredRowField(
                "required", GraphType.STRING, StringProperty.class.getName(), true, true, "value"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredRowField(
                "optional", GraphType.STRING, StringProperty.class.getName(), false, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredRowField(
                "text", GraphType.STRING, StringProperty.class.getName(), false, true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesBareAndWrappedRowsIdenticallyWithoutLoadingRowType() {
        StructuredRowShape shape = argumentsShape();
        Map<String, Object> row = argumentRow("name", "value", "=", "description");
        Map<String, Object> wrapped = new LinkedHashMap<String, Object>();
        wrapped.put("row_type", Argument.class.getName());
        wrapped.put("rows", Collections.singletonList(row));

        StructuredRowValue bare = shape.normalize(Collections.singletonList(row));
        StructuredRowValue asserted = shape.normalize(wrapped);

        assertThat(asserted).isEqualTo(bare);
        assertThat(bare.rowType()).isEqualTo(Argument.class.getName());
        assertThat(bare.rows()).containsExactly(row);
        System.out.println("TODO5_ARGUMENT_ROWS=" + bare.rows());
        System.out.println("TODO5_SHAPES_IDENTICAL=" + bare.equals(asserted));
    }

    @Test
    void rejectsMismatchUnknownFieldsAndWrongExactScalarTypes() {
        StructuredRowShape shape = argumentsShape();
        Map<String, Object> mismatch = new LinkedHashMap<String, Object>();
        mismatch.put("row_type", "not.loaded.CallerSelectedRow");
        mismatch.put("rows", Collections.emptyList());
        Map<String, Object> unknown = argumentRow("name", "value", "=", "description");
        unknown.put("unknown", "nope");
        Map<String, Object> wrong = argumentRow("name", "value", "=", "description");
        wrong.put(Argument.VALUE, Integer.valueOf(42));

        Throwable mismatchFailure = catchThrowable(() -> shape.normalize(mismatch));
        assertThat(mismatchFailure)
                .isInstanceOf(StructuredRowValueException.class)
                .hasMessageContaining("row_type").hasMessageContaining("does not match");
        assertThat(mismatchFailure.getMessage()).hasSizeLessThanOrEqualTo(512);
        StructuredRowValue before = shape.observedValue();
        assertThatThrownBy(() -> shape.normalize(Collections.singletonList(unknown)))
                .isInstanceOf(StructuredRowValueException.class)
                .hasMessageContaining("unknown field");
        assertThat(shape.observedValue()).isSameAs(before);
        assertThatThrownBy(() -> shape.normalize(Collections.singletonList(wrong)))
                .isInstanceOf(StructuredRowValueException.class)
                .hasMessageContaining(Argument.VALUE).hasMessageContaining("string");
    }

    @Test
    void maliciousRowTypeCausesZeroContextClassLoaderAttempts() {
        StructuredRowShape shape = argumentsShape();
        CountingClassLoader loader = new CountingClassLoader(Thread.currentThread().getContextClassLoader());
        Map<String, Object> wrapped = new LinkedHashMap<String, Object>();
        wrapped.put("row_type", MaliciousRow.class.getName());
        wrapped.put("rows", Collections.emptyList());
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();

        try {
            thread.setContextClassLoader(loader);
            assertThatThrownBy(() -> shape.normalize(wrapped))
                    .isInstanceOf(StructuredRowValueException.class);
        } finally {
            thread.setContextClassLoader(original);
        }

        assertThat(loader.attempts).isZero();
        assertThat(constructorAttempts).isZero();
    }

    @Test
    void presentNullRowTypeFailsClosedWithoutMutationOrClassloading() {
        StructuredRowShape shape = argumentsShape();
        StructuredRowValue before = shape.observedValue();
        CountingClassLoader loader = new CountingClassLoader(Thread.currentThread().getContextClassLoader());
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();

        try {
            thread.setContextClassLoader(loader);
            Map<String, Object> wrapped = new LinkedHashMap<String, Object>();
            wrapped.put("row_type", null);
            wrapped.put("rows", Collections.emptyList());
            Throwable failure = catchThrowable(() -> shape.normalize(wrapped));
            assertThat(failure).isInstanceOf(StructuredRowValueException.class);
            assertThat(failure.getMessage()).contains("row_type").hasSizeLessThanOrEqualTo(512);
            assertThat(shape.observedValue()).isSameAs(before);
        } finally {
            thread.setContextClassLoader(original);
        }

        assertThat(loader.attempts).isZero();
        assertThat(constructorAttempts).isZero();
    }

    @Test
    void presentNumberOrEmptyRowTypeFailsClosed() {
        StructuredRowShape shape = argumentsShape();
        StructuredRowValue before = shape.observedValue();

        for (Object invalid : Arrays.<Object>asList(Integer.valueOf(7), "")) {
            Map<String, Object> wrapped = new LinkedHashMap<String, Object>();
            wrapped.put("row_type", invalid);
            wrapped.put("rows", Collections.emptyList());
            Throwable failure = catchThrowable(() -> shape.normalize(wrapped));
            assertThat(failure).isInstanceOf(StructuredRowValueException.class);
            assertThat(failure.getMessage()).contains("row_type").hasSizeLessThanOrEqualTo(512);
            assertThat(shape.observedValue()).isSameAs(before);
        }
    }

    private static StructuredRowShape argumentsShape() {
        Arguments arguments = new Arguments();
        arguments.addArgument(new Argument("observed", "value", "=", "description"));
        return RuntimeStructuredRows.observe(new TestElementProperty("arguments", arguments), context()).get();
    }

    private static RuntimeContext context() {
        return new RuntimeContext("todo5", new RuntimeFingerprint(
                "/opt/jmeter-fixture/apache-jmeter-5.6.3", "5.6.3",
                Collections.singletonMap("core", "abc")));
    }

    private static Map<String, Object> argumentRow(
            String name, String value, String metadata, String description) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put(Argument.ARG_NAME, name);
        row.put(Argument.VALUE, value);
        row.put(Argument.METADATA, metadata);
        row.put(Argument.DESCRIPTION, description);
        return row;
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }

    private static Map<String, Object> descriptor(String name, String type, boolean required) {
        Map<String, Object> descriptor = new LinkedHashMap<String, Object>();
        descriptor.put("name", name);
        descriptor.put("type", type);
        descriptor.put("required", Boolean.valueOf(required));
        return descriptor;
    }

    private static Map<String, Object> descriptor(
            String name, String type, boolean required, Object defaultValue) {
        Map<String, Object> descriptor = descriptor(name, type, required);
        descriptor.put("default", defaultValue);
        return descriptor;
    }

    private static List<Map<String, Object>> defaultDescriptors() {
        return Arrays.asList(
                descriptor("nullable", "null", false, null),
                descriptor("empty", "string", false, ""),
                descriptor("zero", "int", false, Integer.valueOf(0)),
                descriptor("enabled", "boolean", false, Boolean.FALSE),
                descriptor("required", "string", true));
    }

    private static Map<String, Object> defaultedRow() {
        return row(
                "nullable", null,
                "empty", "",
                "zero", Integer.valueOf(0),
                "enabled", Boolean.FALSE,
                "required", "present");
    }

    private static Map<String, Object> rowsDocument(
            List<Map<String, Object>> descriptors, List<Map<String, Object>> rows) {
        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("row_type", Argument.class.getName());
        document.put("row_properties", descriptors);
        document.put("rows", rows);
        return document;
    }

    private static StructuredRowShape defaultedShape() {
        List<StructuredRowField> fields = Arrays.asList(
                new StructuredRowField(
                        "nullable", GraphType.NULL, NullProperty.class.getName(), false, true, null),
                new StructuredRowField(
                        "empty", GraphType.STRING, StringProperty.class.getName(), false, true, ""),
                new StructuredRowField(
                        "zero", GraphType.INT, IntegerProperty.class.getName(), false, true,
                        Integer.valueOf(0)),
                new StructuredRowField(
                        "enabled", GraphType.BOOLEAN, BooleanProperty.class.getName(), false, true,
                        Boolean.FALSE),
                new StructuredRowField(
                        "required", GraphType.STRING, StringProperty.class.getName()));
        try {
            RuntimeRowObservation observation = new RuntimeRowObservation(
                    TestElementProperty.class.getName(),
                    Arguments.class.getName(),
                    CollectionProperty.class.getName(),
                    TestElementProperty.class.getName(),
                    Argument.class.getName(),
                    fields,
                    false,
                    new StructuredRowReconstruction(
                            Argument.class.getConstructor(),
                            Arguments.class.getMethod("setArguments", List.class)));
            return new StructuredRowShape(
                    observation,
                    context(),
                    new StructuredRowValue(Argument.class.getName(), fields,
                            Collections.<Map<String, Object>>emptyList()),
                    new TestElementProperty("arguments", new Arguments()));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class CountingClassLoader extends ClassLoader {
        private int attempts;

        private CountingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            attempts++;
            return super.loadClass(name, resolve);
        }
    }

    private static final class MaliciousRow {
        private MaliciousRow() {
            constructorAttempts++;
        }
    }
}
