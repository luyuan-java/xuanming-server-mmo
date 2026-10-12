package com.game.scene;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 金样：缺省部署命名空间（{@code game}）下，scene 审计生产者的 client.id 与引入命名空间之前逐字节相同。 */
class SceneKafkaNamesGoldenTest {

    @BeforeAll
    static void 只在缺省命名空间里跑() {
        assertThat(GameNamespace.get())
                .as("金样只在缺省命名空间里成立；不要给 mvn 传 -Dgame.namespace（surefire 会让它覆盖 POM 钉的 game）")
                .isEqualTo("game");
    }

    @Test
    void 审计client_id与金样相同() {
        assertThat(SceneNode.auditClientId(1, 2)).isEqualTo("game-scene-audit-z1-n2");
        assertThat(SceneNode.auditClientId(1, 2) + "-admin").isEqualTo("game-scene-audit-z1-n2-admin");
    }
}
