package com.game.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.token.DubboCallAuth;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** scene 跑资产通道的 Dubbo 提供方之后，{@code XM_DUBBO_SECRET} 变成必填：缺失在单例创建阶段就拒绝启动（guild-economy-spec §7.6）。 */
class SceneDubboSecretTest {

    @Test
    void 缺XM_DUBBO_SECRET拒绝启动_配了就放行() {
        SceneNodeConfiguration configuration = new SceneNodeConfiguration();
        assertThatThrownBy(() -> configuration.sceneDubboCallAuth(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(DubboCallAuth.SECRET_ENV);
        assertThatThrownBy(() -> configuration.sceneDubboCallAuth(
                new MockEnvironment().withProperty(DubboCallAuth.SECRET_ENV, "   ")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(configuration.sceneDubboCallAuth(
                new MockEnvironment().withProperty(DubboCallAuth.SECRET_ENV, "scene-test-secret"))).isNotNull();
    }
}
