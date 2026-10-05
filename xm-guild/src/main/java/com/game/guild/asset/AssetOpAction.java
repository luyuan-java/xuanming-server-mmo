package com.game.guild.asset;

/**
 * 调用方对一行待办该做的事（基线 assetop.Action，decide.go:16-45；guild-economy-spec §2.5）。{@link #label()} 进重排指标
 * （{@code xm_guild_assetop_reschedule_total{reason}}）。
 */
public enum AssetOpAction {
    /** 结局固定且已落盘：终结这一行，并做对侧账。 */
    FINALIZE("finalize"),
    /** 结局有了但还没落盘：很短的延迟后用同一请求再查一次。 */
    AWAIT_DURABLE("await_durable"),
    /** 暂时条件（冻结 / 战斗 / 背包满 / 不在线 / 传输失败）：按退避重投。 */
    RETRY("retry"),
    /** 不该发生的情况（UNKNOWN、结局翻转、坏流号）：打日志 + 计数 + 长退避，<b>绝不终结</b>。 */
    ALERT("alert");

    private final String label;

    AssetOpAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
