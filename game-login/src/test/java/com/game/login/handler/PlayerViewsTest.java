package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerViewsTest {
    @Test
    void removedAppearanceIsHiddenWithoutChangingRows() {
        var removed = LoginHandlerTest.row(1, "robot", "removed");
        removed.setAppearanceId("06_thunder_caster_boy");
        var legacy = LoginHandlerTest.row(2, "robot", "legacy");
        legacy.setAppearanceId(null);
        var active = LoginHandlerTest.row(3, "robot", "active");
        active.setAppearanceId("08_alchemy_prodigy_boy");
        var rows = List.of(removed, legacy, active);
        assertThat(PlayerViews.wrapAll(rows)).extracting(v -> v.getPlayer().getPlayerId())
                .containsExactly(2L, 3L);
        assertThat(rows).hasSize(3);
    }
}
