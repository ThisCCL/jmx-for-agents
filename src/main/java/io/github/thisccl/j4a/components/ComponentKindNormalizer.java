package io.github.thisccl.j4a.components;

import java.util.Locale;

public final class ComponentKindNormalizer {
    private ComponentKindNormalizer() {
    }

    public static String componentKind(String componentClass) {
        String simpleName = simpleName(componentClass);
        switch (simpleName) {
            case "TestPlan":
                return "test.plan";
            case "ThreadGroup":
                return "thread.group";
            case "LoopController":
                return "logic.loop-controller";
            case "HTTPSamplerProxy":
                return "http.request";
            default:
                return dotSeparated(simpleName);
        }
    }

    private static String simpleName(String componentClass) {
        int lastPackageSeparator = componentClass.lastIndexOf('.');
        return lastPackageSeparator >= 0 ? componentClass.substring(lastPackageSeparator + 1) : componentClass;
    }

    private static String dotSeparated(String value) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (index > 0 && Character.isUpperCase(current) && shouldSeparate(value, index)) {
                output.append('.');
            }
            output.append(Character.toLowerCase(current));
        }
        return output.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean shouldSeparate(String value, int index) {
        char previous = value.charAt(index - 1);
        if (previous == '-' || previous == '.') {
            return false;
        }
        if (Character.isLowerCase(previous) || Character.isDigit(previous)) {
            return true;
        }
        return index + 1 < value.length() && Character.isLowerCase(value.charAt(index + 1));
    }
}
