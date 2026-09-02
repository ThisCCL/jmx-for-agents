package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class BoundedYamlMessageTest {
    @Test
    void adversarialMessageSerializedScalarNeverExceeds512Utf8Bytes() {
        StringBuilder input = new StringBuilder();
        for (int index = 0; index < 600; index++) {
            input.append("\n\\汉😀\t");
        }

        String bounded = BoundedYamlMessage.scalar(input.toString(), 512);

        assertThat(new Yaml().dump(bounded).getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(512);
        assertThat(bounded).doesNotContain("\n", "\r", "\t", "\\");
    }
}
