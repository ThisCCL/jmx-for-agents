package io.github.thisccl.j4a.validation;

import java.nio.charset.StandardCharsets;
import org.yaml.snakeyaml.Yaml;

final class BoundedYamlMessage {
    private BoundedYamlMessage() {
    }

    static String scalar(String value, int maxSerializedBytes) {
        StringBuilder safe = new StringBuilder(Math.min(value.length(), 2048));
        for (int index = 0; index < value.length(); index++) {
            if (safe.length() >= 2048) {
                break;
            }
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                if (safe.length() > 2046) {
                    break;
                }
                safe.append(character).append(value.charAt(++index));
            } else if (character == '\r' || character == '\n' || character == '\t') {
                safe.append(' ');
            } else if (character == '\\') {
                safe.append('/');
            } else if (Character.isISOControl(character)) {
                safe.append('?');
            } else {
                safe.append(character);
            }
        }
        String candidate = safe.toString();
        Yaml yaml = new Yaml();
        while (!candidate.isEmpty()
                && yaml.dump(candidate).getBytes(StandardCharsets.UTF_8).length > maxSerializedBytes) {
            int end = candidate.length() - 1;
            if (end > 0 && Character.isHighSurrogate(candidate.charAt(end - 1))) {
                end--;
            }
            candidate = candidate.substring(0, end);
        }
        return candidate;
    }
}
