package com.game.guild.rules;

import java.util.Objects;

/**
 * 一个 {@link GuildReject} 该怎么答复客户端（基线 mapWriteErr，guild_manage_logic.go:269-329；guild-spec §2.7）。
 *
 * <p>两种形态：
 * <ul>
 *   <li>{@link Tip}：业务拒绝，把 tip 写进应答的 {@code error_message}（in-band）；必要时顺手按 MySQL 纠正陈旧的玩家→帮会映射；</li>
 *   <li>{@link Fault}：故障（双存储矛盾、配表缺行），回信封 1003 并记 ERROR，客户端随之进隔离（同基线 gRPC Internal）。</li>
 * </ul>
 * 写冲突（{@link GuildTip#WRITE_CONFLICT}）照基线记 INFO 而不是 ERROR：两个人同时改同一个帮是正常玩法。
 */
public sealed interface RejectReply {

    /**
     * mapWriteErr 里的 {@code verifyMapping(actor, cached)} 附带动作（guild_manage_logic.go:254-267 verifyMapping）：事务已经按 MySQL 判定之后，
     * 顺手把可能陈旧的 Redis 映射纠正过来。失败只记日志，不改变本次答复。
     */
    enum MappingRepair {
        /** 不复核。 */
        NONE,
        /**
         * {@code verifyMapping(actor, cachedGuildId)}：cached = 调用方以为操作者所在的帮（申请 / 撤回传 0——申请人按定义不在目标帮；
         * 公告传请求体 guild_id；其余传操作者所在帮；guild_manage_logic.go:275-280）。
         */
        VERIFY_AGAINST_CACHED,
        /** {@code verifyMapping(actor, 0)}：缓存说他没入帮、MySQL 说他入了（ErrPlayerAlreadyInGuild，guild_manage_logic.go:302-305）。 */
        VERIFY_AGAINST_ZERO
    }

    /**
     * 业务拒绝：回 in-band tip。
     *
     * @param tip    写进应答 {@code error_message} 的 tip（码 + 基线英文原因）
     * @param repair 要不要顺手复核映射
     */
    record Tip(GuildTip tip, MappingRepair repair) implements RejectReply {
        public Tip {
            Objects.requireNonNull(tip, "tip");
            Objects.requireNonNull(repair, "repair");
        }
    }

    /**
     * 故障：回信封 1003、记 ERROR（基线 {@code status.Error(codes.Internal, reason)}）。
     *
     * @param reason 基线的 gRPC 错误文案，只进日志 / 异常消息（信封不带 parameters，同路由服 rejected）
     */
    record Fault(String reason) implements RejectReply {
        public Fault {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
