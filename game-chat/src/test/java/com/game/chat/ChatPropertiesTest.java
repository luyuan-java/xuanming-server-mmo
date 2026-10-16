package com.game.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatPropertiesTest {

    @Test
    void 标准配置绑定词表_缺省业务阈值保留_词表不可变() {
        ChatProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "game.chat.sensitive-words[0]", "广告", "game.chat.sensitive-words[1]", "Spam")))
                .bind("game.chat", Bindable.of(ChatProperties.class)).get();
        assertThat(props.sensitiveWords()).containsExactly("广告", "Spam");
        assertThat(props.maxContentBytes()).isEqualTo(512);
        assertThatThrownBy(() -> props.sensitiveWords().add("other")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 绑定非法词表在装配连接之前失败() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "game.chat.sensitive-words[0]", "x".repeat(257))));
        assertThatThrownBy(() -> binder.bind("game.chat", Bindable.of(ChatProperties.class)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
