package com.game.guild.asset;

import com.game.guild.store.pb.GuildAssetOpStatus;

/**
 * 一条资产指令的<b>语义</b>状态（基线 assetop.Status，types.go:46-81；guild-economy-spec §2.5）。
 *
 * <p>刻意不绑库值：库值只由 {@code guild_tables.proto} 的 {@link GuildAssetOpStatus} 定，映射只在 {@link #toRecord} 一处
 * （基线 StatusToRecord，asset_store.go:288-309）。这样交易接入资产通道、把调用方库抽成共享件时（Q4），语义状态与各业务表的列值互不牵连。
 * {@link #label()} 同时是指标 label（{@code xm_guild_assetop_finalize_total{status}}），改名前先查告警规则。
 */
public enum AssetOpStatus {
    /** 待投递 / 重投中。只有它允许被终结或重排改写。 */
    PENDING("pending"),
    /** scene 已应用（全额）。 */
    APPLIED("applied"),
    /** scene 判拒（余额不足、包非法、被封禁等），结局固定。 */
    REJECTED("rejected"),
    /** 中止占位成功：Abort 打在 scene 从未见过的 seq 上（REJECTED 且 reason = 0），对应的业务占用要退还。 */
    ABORTED("aborted"),
    /** scene 只应用了一部分：只终结、不做对侧账，转人工补偿。 */
    APPLIED_PARTIAL("applied_partial");

    private final String label;

    AssetOpStatus(String label) {
        this.label = label;
    }

    /** 低基数的稳定名字（指标 label 与日志）。 */
    public String label() {
        return label;
    }

    /** 是否是四个终态之一（终结路径只接受这四个；PENDING 写进去等于没终结）。 */
    public boolean terminal() {
        return this != PENDING;
    }

    /**
     * 语义状态 → 本表库值（StatusToRecord）。null 落 {@link GuildAssetOpStatus#GUILD_ASSET_OP_STATUS_UNSPECIFIED}：既不能落成 PENDING
     * （会被循环反复领走），也不能落成 APPLIED（伪装成功）。
     */
    public static GuildAssetOpStatus toRecord(AssetOpStatus status) {
        if (status == null) {
            return GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_UNSPECIFIED;
        }
        return switch (status) {
            case PENDING -> GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING;
            case APPLIED -> GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED;
            case REJECTED -> GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED;
            case ABORTED -> GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED;
            case APPLIED_PARTIAL -> GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL;
        };
    }

    /** 库值是否是四个终态之一（isTerminalRecord，asset_store.go:311-322）。 */
    public static boolean isTerminalRecord(GuildAssetOpStatus status) {
        return switch (status) {
            case GUILD_ASSET_OP_STATUS_APPLIED, GUILD_ASSET_OP_STATUS_REJECTED, GUILD_ASSET_OP_STATUS_ABORTED,
                 GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL -> true;
            default -> false;
        };
    }
}
