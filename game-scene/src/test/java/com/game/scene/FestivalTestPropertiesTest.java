package com.game.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.RunMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FestivalTestPropertiesTest {
    @Test
    void 默认关闭_生产拒绝_开发必须显式根目录() {
        assertThat(new FestivalTestProperties(false, "", "").festivalRoot(RunMode.DEV)).isNull();
        assertThatThrownBy(() -> new FestivalTestProperties(true, "maps", "").festivalRoot(RunMode.PROD))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FestivalTestProperties(true, "", "").festivalRoot(RunMode.TEST))
                .isInstanceOf(IllegalArgumentException.class);
        for (RunMode mode : new RunMode[]{RunMode.DEV, RunMode.TEST}) {
            assertThat(new FestivalTestProperties(true, "maps", "old-maps").festivalRoot(mode))
                    .isEqualTo(Path.of("maps").toAbsolutePath().normalize());
        }
        assertThat(new FestivalTestProperties(false, "maps", "old-maps").currentRoot(Path.of("existing")))
                .isEqualTo(Path.of("existing"));
    }
}
