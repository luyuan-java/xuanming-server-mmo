package com.game.guild.asset;

import com.game.guild.rules.GuildLimits;
import java.util.Objects;

/**
 * 一次人工终结的完整输入（基线 assetop.ManualResolution，reconcile.go:198-233；guild-economy-spec §2.12）。
 *
 * <p>入参校验写在结构上而不是某一个调用者里（{@link #validate}）：assetopfix CLI 在调 Store 之前校验一次，
 * {@link GuildAssetStore#resolveManually} 在碰库之前<b>再</b>校验一次（validateManualAudit，asset_store.go:593-634）——审计两列落库是
 * MEDIUMTEXT，库既不截断也不报错；Store 的方法是公开的，单测与日后的管理入口都能绕开 CLI 直接调它。列契约归本表所有，由写入者兜底。
 * 长度按<b>码点数</b>计（基线按 rune），两边口径相同，不会出现「CLI 放行、Store 拒绝」的分叉。
 *
 * @param opId     要终结的指令
 * @param status   终局；只能是四个终态之一（CLI 只放 applied / aborted，裁决 E：REJECTED 是 scene 的判定、PARTIAL 需逐件核对）
 * @param operator 操作人，非空、≤ {@value GuildLimits#RESOLVED_BY_MAX_CHARS} 字（进 resolved_by 列与审计行）
 * @param reason   人工判定依据，≤ {@value GuildLimits#RESOLVE_REASON_MAX_CHARS} 字（进 resolve_reason 列）
 */
public record ManualResolution(long opId, AssetOpStatus status, String operator, String reason) {

    public ManualResolution {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * 校验（validate + validateManualAudit）。
     *
     * @return null = 合法；否则是不合法的原因（英文短句，只进日志与 CLI 输出）
     */
    public String validate() {
        if (status == null || !status.terminal()) {
            return "manual resolution status must be terminal, got " + status;
        }
        if (operator.isEmpty()) {
            return "resolved_by (operator) is required";
        }
        int operatorChars = operator.codePointCount(0, operator.length());
        if (operatorChars > GuildLimits.RESOLVED_BY_MAX_CHARS) {
            return "resolved_by (operator) has " + operatorChars + " characters, limit " + GuildLimits.RESOLVED_BY_MAX_CHARS;
        }
        int reasonChars = reason.codePointCount(0, reason.length());
        if (reasonChars > GuildLimits.RESOLVE_REASON_MAX_CHARS) {
            return "resolve_reason has " + reasonChars + " characters, limit " + GuildLimits.RESOLVE_REASON_MAX_CHARS;
        }
        return null;
    }
}
