package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.proto.Empty;
import com.game.proto.RedirectToGateNotify;
import com.game.proto.TipInfoMessage;
import org.junit.jupiter.api.Test;

/** gate 自己组包下发的两个推送消息号（批次 5.4 先行件）：按名字从契约解析，不写死。 */
class PushMessageIdsTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();

    @Test
    void 从契约解析_tip与重定向通知各是SceneClientPlayerCommon的一个方法_请求体类型对得上() {
        PushMessageIds ids = PushMessageIds.resolve(REGISTRY);

        assertThat(ids.tip()).isEqualTo(REGISTRY.requireId("SceneClientPlayerCommon", "SendTipToClient"));
        assertThat(ids.redirectToGate()).isEqualTo(REGISTRY.requireId("SceneClientPlayerCommon", "RedirectToGate"));
        assertThat(ids.tip()).isNotEqualTo(ids.redirectToGate());
        assertThat(ids.canRedirect()).isTrue();
        MessageMethod tip = REGISTRY.byId(ids.tip()).orElseThrow();
        MessageMethod redirect = REGISTRY.byId(ids.redirectToGate()).orElseThrow();
        assertThat(tip.requestPrototype()).as("23 的包体").isInstanceOf(TipInfoMessage.class);
        assertThat(redirect.requestPrototype()).as("124 的包体").isInstanceOf(RedirectToGateNotify.class);
        assertThat(redirect.responsePrototype()).as("124 是服务端推送，没有应答").isInstanceOf(Empty.class);
    }

    @Test
    void 只给tip的装配形式_重定向通知的号为0_不能重定向() {
        PushMessageIds ids = PushMessageIds.tipOnly(23);

        assertThat(ids.tip()).isEqualTo(23);
        assertThat(ids.redirectToGate()).isZero();
        assertThat(ids.canRedirect()).isFalse();
        assertThat(new PushMessageIds(23, 124).canRedirect()).isTrue();
    }

    @Test
    void 非法的号拒绝_tip必须为正_重定向不能为负也不能与tip同号() {
        assertThatThrownBy(() -> new PushMessageIds(0, 124)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PushMessageIds(-1, 124)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PushMessageIds(23, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PushMessageIds(23, 23)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushMessageIds.tipOnly(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
