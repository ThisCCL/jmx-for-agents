package org.apache.jmeter.testelement.property;

import java.util.concurrent.atomic.AtomicInteger;

public final class Todo8ConstructorSideEffectProperty extends StringProperty {
    private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    public Todo8ConstructorSideEffectProperty() {
        CONSTRUCTIONS.incrementAndGet();
    }

    public static void resetConstructions() {
        CONSTRUCTIONS.set(0);
    }

    public static int constructions() {
        return CONSTRUCTIONS.get();
    }
}
