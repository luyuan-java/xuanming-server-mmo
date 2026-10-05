package com.game.battle.room;

import java.util.Locale;

/**
 * 战斗配表指纹闸的模式（基线 {@code battle_table_fingerprint_mode}，{@code room.cpp:306-451}；battle-node-spec §4.3.3）。
 * 配置 {@code xm.battle.table-fingerprint-mode}（环境变量 {@code XM_BATTLE_TABLE_FINGERPRINT_MODE}），缺省 {@link #WARN}；
 * 取值写错由 Spring 枚举绑定拒启（基线回落 warn，§11 N18）。
 */
public enum FingerprintMode {
    /** 不比。 */
    OFF,
    /** 不一致时计数 + 打日志，照常开局。 */
    WARN,
    /** 不一致时拒绝开局：1006，{@code parameters[0] = "battle table fingerprint mismatch: node=<本节点> request=<request.table_fingerprint>"}。 */
    ENFORCE;

    /** off / warn / enforce（日志与指标标签用）。 */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
