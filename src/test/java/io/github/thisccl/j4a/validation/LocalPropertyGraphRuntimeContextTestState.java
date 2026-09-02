package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.property.RuntimeContext;
import java.lang.reflect.Field;
import java.nio.file.Path;

final class LocalPropertyGraphRuntimeContextTestState {
    private final Path prewarmedHome;
    private final RuntimeContext prewarmedContext;

    private LocalPropertyGraphRuntimeContextTestState(
            Path prewarmedHome, RuntimeContext prewarmedContext) {
        this.prewarmedHome = prewarmedHome;
        this.prewarmedContext = prewarmedContext;
    }

    static LocalPropertyGraphRuntimeContextTestState captureAndClear() throws Exception {
        synchronized (LocalPropertyGraphRuntimeContext.class) {
            Field home = field("prewarmedHome");
            Field context = field("prewarmedContext");
            LocalPropertyGraphRuntimeContextTestState state = new LocalPropertyGraphRuntimeContextTestState(
                    (Path) home.get(null), (RuntimeContext) context.get(null));
            home.set(null, null);
            context.set(null, null);
            return state;
        }
    }

    void restore() throws Exception {
        synchronized (LocalPropertyGraphRuntimeContext.class) {
            field("prewarmedHome").set(null, prewarmedHome);
            field("prewarmedContext").set(null, prewarmedContext);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = LocalPropertyGraphRuntimeContext.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
