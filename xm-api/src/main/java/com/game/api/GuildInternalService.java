package com.game.api;

import com.game.api.proto.ListAppliedAssetOpsSinceRequest;
import com.game.api.proto.ListAppliedAssetOpsSinceResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 帮会的服务间内部查询（Dubbo，xm-guild 提供，group {@link DubboGroups#GUILD}；基线 {@code GuildInternal}，
 * {@code proto/guild/guild_internal.proto}、{@code go/guild/internal/server/guild_internal_server.go}；guild-economy-spec §3.6 / §7.11 / E11）。
 * 不占消息号、不进客户端白名单；调用方鉴权靠 Dubbo 调用方 MAC（{@code XM_DUBBO_SECRET}），基线这里没有凭据、只靠网络隔离。
 *
 * <p>消费方是回档闸（路线图 7.2）：回档写玩家数据之前先问「这些玩家自快照时刻以来，有没有已终结为已应用的帮会资产指令」，有就拒绝回档。
 * 4.5 只交付提供方与测试。<b>失败方向</b>：除 {@code OK} 外的任何结果（含 future 异常完成）消费方都必须按「问不到」拒绝回档（fail-closed）；
 * 唯一可以据以继续的是 {@code RETENTION_REJECTED}：用应答里的 {@code cutoff_ms} 钳位重查（这一段已不可证明的玩家记为不可证明）。
 */
public interface GuildInternalService {

    /**
     * 列出这些玩家自 {@code since_ms} 之后终结为 APPLIED / APPLIED_PARTIAL 的帮会资产指令（按 op_id 升序翻页）。
     *
     * <p>判定顺序（全部先于 SQL，同基线 {@code guild_internal_server.go:146-226}）：入参（{@code INVALID_ARGUMENT}：
     * player_ids 为空 / 超过 100 / 含 0 / 重复，since_ms 为 0，limit 超过 500）→ 装配（{@code UNAVAILABLE}）→
     * 保留期（{@code RETENTION_REJECTED}，cutoff_ms = now + 1 h 余量 − 终态保留期，下溢取 0）→ 查询（失败为 {@code ERROR}）。
     * 不保证跨页快照一致，由消费方的写后复查兜底。
     */
    CompletableFuture<ListAppliedAssetOpsSinceResponse> listAppliedAssetOpsSince(ListAppliedAssetOpsSinceRequest request);
}
