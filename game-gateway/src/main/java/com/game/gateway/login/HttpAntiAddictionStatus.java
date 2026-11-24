package com.game.gateway.login;

import com.game.proto.login.AntiAddictionStatus;

/** 防沉迷状态 JSON DTO（蛇形键：{@code real_name_required}、{@code minor}、{@code play_deadline_ms}）。 */
public record HttpAntiAddictionStatus(boolean realNameRequired, boolean minor, long playDeadlineMs) {

    public static HttpAntiAddictionStatus from(AntiAddictionStatus proto) {
        if (proto == null) {
            return null;
        }
        return new HttpAntiAddictionStatus(proto.getRealNameRequired(), proto.getMinor(), proto.getPlayDeadlineMs());
    }
}
