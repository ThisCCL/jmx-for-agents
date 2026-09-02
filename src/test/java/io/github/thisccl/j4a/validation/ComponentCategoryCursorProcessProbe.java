package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.property.RuntimeFingerprint;
import java.util.Collections;

public final class ComponentCategoryCursorProcessProbe {
    private static final String PROJECTION = "authoring:scalar-array-v1";
    private ComponentCategoryCursorProcessProbe() {
    }

    public static void main(String[] args) {
        RuntimeFingerprint runtime = new RuntimeFingerprint(
                "/runtime", "5.6.3", Collections.singletonMap("lib/example.jar", "abc123"));
        try {
            if (args.length == 1 && "encode".equals(args[0])) {
                System.out.print(ComponentCategoryCursor.encode(
                        runtime, "sampler", PROJECTION, 20, "a.Component"));
                return;
            }
            if (args.length == 2 && "require".equals(args[0])) {
                System.out.print(ComponentCategoryCursor.requireLastComponent(
                        args[1], runtime, "sampler", PROJECTION, 20));
                return;
            }
            if (args.length == 2 && "require-wrong-projection".equals(args[0])) {
                System.out.print(ComponentCategoryCursor.requireLastComponent(
                        args[1], runtime, "sampler", "authoring:canonical", 20));
                return;
            }
            System.err.print("invalid probe invocation");
            System.exit(3);
        } catch (ComponentsCursorException exception) {
            System.err.print(exception.getMessage());
            System.exit(2);
        }
    }
}
