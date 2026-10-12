package com.game.gate.session;

import com.game.contract.MessageIdRegistry;

/**
 * gate 自己组包下发的两个服务端推送的消息号（号会被契约生成器复用空洞，不许写死，启动时按「服务裸名 + 方法名」解析）。
 *
 * @param tip            {@code SceneClientPlayerCommon.SendTipToClient}（现为 23）：推 {@code TipInfoMessage}
 * @param redirectToGate {@code SceneClientPlayerCommon.RedirectToGate}（现为 124，批次 5.4）：推 {@code RedirectToGateNotify}。
 *                       0 = 没装配（只给不关心重定向的测试装配，见 {@link #tipOnly}）：没有号就发不出 124，
 *                       会话层遇到重定向帧 / 重定向指令时必须按「内容非法」收口，不许发一条消息号为 0 的包
 */
public record PushMessageIds(int tip, int redirectToGate) {

    private static final String COMMON_SERVICE = "SceneClientPlayerCommon";

    public PushMessageIds {
        if (tip <= 0) {
            throw new IllegalArgumentException("推 tip 的消息号必须为正: " + tip);
        }
        if (redirectToGate < 0) {
            throw new IllegalArgumentException("推重定向通知的消息号不能为负: " + redirectToGate);
        }
        if (redirectToGate == tip) {
            throw new IllegalArgumentException("重定向通知与 tip 不能是同一个消息号: " + tip);
        }
    }

    /** 生产装配：两个号都从契约解析；缺任何一个即启动失败（同步产物与代码不一致）。 */
    public static PushMessageIds resolve(MessageIdRegistry registry) {
        return new PushMessageIds(registry.requireId(COMMON_SERVICE, "SendTipToClient"),
                registry.requireId(COMMON_SERVICE, "RedirectToGate"));
    }

    /** 只有 tip 的号（批次 5.4 之前的装配形式，测试用）：重定向通知的号为 0。 */
    public static PushMessageIds tipOnly(int tip) {
        return new PushMessageIds(tip, 0);
    }

    /** 是否装配了重定向通知的号。 */
    public boolean canRedirect() {
        return redirectToGate != 0;
    }
}
