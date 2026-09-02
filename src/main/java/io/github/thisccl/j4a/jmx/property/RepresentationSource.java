package io.github.thisccl.j4a.jmx.property;

public enum RepresentationSource {
    RUNTIME("runtime"),
    JMETER_SCHEMA("jmeter_schema"),
    TEST_BEAN("test_bean"),
    OPAQUE_FALLBACK("opaque_fallback");

    private final String wireName;

    RepresentationSource(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
