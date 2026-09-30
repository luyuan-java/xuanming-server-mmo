package com.game.login.dispatch;

import com.game.proto.TipInfoMessage;
import java.util.List;

/** 组 {@link TipInfoMessage}。tip 码一律取导表器生成的枚举常量（{@code XxxErrorTip.xxx_error.kXxx_VALUE}），不手写数字。 */
public final class Tips {

    private Tips() {
    }

    public static TipInfoMessage of(int tipId, String... parameters) {
        return TipInfoMessage.newBuilder().setId(tipId).addAllParameters(List.of(parameters)).build();
    }
}
