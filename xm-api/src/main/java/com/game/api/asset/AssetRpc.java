package com.game.api.asset;

/**
 * 资产通道的三个入口（同基线 {@code SceneNodeGrpc.AssetDebit / AssetAbortDebit / AssetCredit}，{@code scene_node_service.proto:31-36}）。
 * {@link #wireName()} 同时是签名规范串第 3 行（{@link AssetOpSignatures#canonical}）：改名就是改协议；它与 Dubbo 方法名无关。
 * 调用方签名、scene 验签、scene 侧按入口分派都用这一个枚举。
 */
public enum AssetRpc {
    /** 扣款（*_DEBIT 流）。 */
    DEBIT("debit"),
    /** 中止扣款：给未见 seq 记一个拒绝占位（收全部流、不校验流水原因）。 */
    ABORT_DEBIT("abort_debit"),
    /** 发放（*_CREDIT / SYSTEM_CREDIT 流）。 */
    CREDIT("credit");

    private final String wireName;

    AssetRpc(String wireName) {
        this.wireName = wireName;
    }

    /** 进签名规范串的名字（debit / abort_debit / credit），也用作指标的 rpc 标签。 */
    public String wireName() {
        return wireName;
    }
}
