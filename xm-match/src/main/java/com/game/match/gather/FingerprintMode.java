package com.game.match.gather;

import java.util.Locale;

/**
 * 战斗配表指纹的比对策略（{@code xm.match.table-fingerprint-mode}；基线 {@code TableFingerprintMode}，{@code cfg.go:90}、{@code gather.go:462-507}）。
 * scene 在备战应答里回报本节点战斗表的内容指纹，gather 收齐全员之后比对。配置按枚举绑定：写错即拒绝启动（与基线 go-zero 的
 * {@code options=off|warn|enforce} 同效）。
 */
public enum FingerprintMode {

    /** 不比对、不透传（建房请求的 {@code table_fingerprint} 留空）。 */
    OFF,
    /** 缺省：不一致或部分为空只记 ERROR 与指标，照常开局；全员非空且两两一致才把指纹透传给 battle。 */
    WARN,
    /** 不一致视为备战失败（outcome {@code fingerprint_mismatch}）：与多数派不一致的人里顺序最靠前者是肇事者，按 prepare_failed 的补偿路径处理。 */
    ENFORCE;

    /** 指标标签 {@code fp_mode} 的取值（小写）。 */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
