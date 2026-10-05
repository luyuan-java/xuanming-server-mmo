package com.game.api;

import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 通用资产通道的跨进程入口（Dubbo Triple，xm-scene 提供；基线 {@code SceneNodeGrpc.AssetDebit / AssetAbortDebit / AssetCredit}，
 * {@code scene_node_service.proto:31-36}；guild-economy-spec §4.5 / E1）。
 *
 * <p><b>按节点直连，不进注册中心</b>：每个 scene 节点一份，导出时 {@code register = false}、group {@link DubboGroups#SCENE_ASSET}；
 * 调用方从 Redis 节点目录（{@code SceneNodeInfo.rpc_host / rpc_port}）拿地址，经 {@code com.game.api.asset.SceneAssetOpClients} 直连。
 * 地址只有这一个发现源（与 gate 连 scene 同一份目录），不造第二份真相。
 *
 * <p>契约（调用方必须知道的全部，细节见 scene 的 {@code AssetOpService}）：
 * <ul>
 *   <li>结局在应答的 {@code outcome}；只有 {@code APPLIED / REJECTED} 且 {@code durable} 才能终结这条 seq，否则用<b>同一请求</b>
 *       （同 (玩家, 流, 纪元, seq)，每次现签）重查；RETRY / NOT_HERE / UNKNOWN 都没记账、不得终结。</li>
 *   <li>future <b>异常完成</b> = 传输失败（scene 过载、未就绪、逻辑线程已停、Dubbo 超时 / 断连、调用方鉴权失败）：结局未知，
 *       调用方按 Retry 处理、用同一 seq 重投；绝不当成「没记账」去改发别的 seq。</li>
 *   <li>调用方的引用必须 {@code retries = 0}：Dubbo 缺省 failover 会重发，打乱预算与指标；重投由调用方的循环负责。</li>
 *   <li>请求体带 HMAC 签名（{@code com.game.api.asset.AssetOpSignatures}）；Dubbo 调用方 MAC（{@code XM_DUBBO_SECRET}）另外照常校验。</li>
 * </ul>
 * 三个方法分别对应 {@code com.game.api.asset.AssetRpc} 的 DEBIT / ABORT_DEBIT / CREDIT；签名规范串第 3 行用
 * {@code AssetRpc.wireName()}，与这里的 Java 方法名无关。
 */
public interface SceneAssetOpService {

    /** 扣款（*_DEBIT 流）。 */
    CompletableFuture<AssetOpResponse> debit(AssetOpRequest request);

    /** 中止扣款：给未见 seq 记一个拒绝占位（原因 0）；已见的 seq 只读答复原结局。 */
    CompletableFuture<AssetOpResponse> abortDebit(AssetOpRequest request);

    /** 发放（*_CREDIT 流）。 */
    CompletableFuture<AssetOpResponse> credit(AssetOpRequest request);
}
