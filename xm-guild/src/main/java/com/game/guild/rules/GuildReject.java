package com.game.guild.rules;

/**
 * 写事务的业务拒绝（基线仓储哨兵错误 guild_repo.go:25-84、guild_manage_repo.go:86 的 Java 版；guild-spec §2.7、§7.6）。
 *
 * <p>Java 的事务基座用结果枚举返回拒绝并回滚，不用异常（§7.6：避免框架把事务标成 rollback-only，也让审批通过的 1062 子分支能在
 * InnoDB 只回滚那一条语句后继续提交）。哪个拒绝回什么 tip、要不要顺手自愈映射，统一由 {@link GuildTips#forReject} 决定。
 *
 * <p>{@link #LEADER_MISMATCH} 与 {@link #LEVEL_CONFIG_MISSING} 不是玩家能修的：映射成故障（信封 1003，记 ERROR）。
 * 4.5 / 4.6 的经济、活动哨兵（ErrGuildLevelTooLow、ErrDonateLimit……）随各自批次追加；{@link GuildTips#forReject} 用穷举 switch，
 * 漏写映射编译不过。
 */
public enum GuildReject {

    /** 目标帮会在 MySQL 里不存在（可能刚被解散）。 */
    GUILD_GONE("guild does not exist"),
    /** 目标帮会不属于要求的 zone（按区隔离的入帮 / 审批）。对客户端与「不存在」同一答复。 */
    ZONE_MISMATCH("guild belongs to another zone"),
    /** 操作者在 MySQL 权威表里已经不是该帮成员（缓存映射落后于真相）。 */
    NOT_MEMBER("operator is not a member of the guild"),
    /** 操作目标不在该帮。 */
    TARGET_NOT_MEMBER("target is not a member of the guild"),
    /** 锁内复核的权威 role 档位不够（{@link GuildRoles#rank}）。解散在调用点改回 14005（{@link GuildTips#forDisbandReject}）。 */
    RANK_TOO_LOW("operator rank too low"),
    /** 长老数已达 GuildLevel[guild.level].max_officers。 */
    OFFICER_LIMIT("officer limit reached"),
    /** 帮主不能退帮（role 为帮主，或 leader_id 是自己）。 */
    LEADER_CANT_LEAVE("leader cannot leave"),
    /** 事务内按权威行数判定已满。 */
    GUILD_FULL("guild is full"),
    /** uk_guild_member(player_id) 拒绝了跨帮的重复成员行，或事务内发现已在帮。 */
    ALREADY_IN_GUILD("player already belongs to a guild"),
    /** uk_guild(name_norm) 拒绝了重名（帮名全局唯一，不分 zone）。只出现在建帮。 */
    NAME_TAKEN("guild name already taken"),
    /** 改公告：MySQL 权威成员行不存在或档位不到长老。只出现在改公告。 */
    ANNOUNCEMENT_FORBIDDEN("guild announcement update is not authorized"),
    /** 申请不存在、已过期、已被别人处理，或申请人已入他帮 / 归属区与帮会不符。 */
    APPLICATION_NOT_FOUND("guild application not found or expired"),
    /** 本人待审申请数已达 GuildRule.max_pending_applications_per_player。 */
    APPLICATION_LIMIT("player pending application limit reached"),
    /** 目标帮会待审申请数已达 GuildRule.max_pending_applications_per_guild。 */
    QUEUE_FULL("guild pending application queue full"),
    /** 事务内合服闸门拒绝，或闸门状态读不出来（fail-closed）。 */
    ZONE_MERGING("guild zone is merging or merge fence unreadable"),
    /** 死锁重跑用尽 / 1205 / 子预算用完而请求还活着 / COMMIT 结果不明（guild_manage_repo.go:82-86）。 */
    WRITE_CONFLICT("guild write conflict (deadlock retries exhausted or lock wait timeout)"),
    /** guild.leader_id 与 role=3 的成员行对不上（双存储已被破坏）：故障。 */
    LEADER_MISMATCH("guild leader_id disagrees with member roles"),
    /** GuildLevel 配表缺该等级行，长老上限无从判定：故障。 */
    LEVEL_CONFIG_MISSING("guild level row missing in GuildLevel table");

    private final String sentinel;

    GuildReject(String sentinel) {
        this.sentinel = sentinel;
    }

    /** 基线哨兵的 {@code errors.New} 文本，只进日志（与基线日志逐字一致，便于对照排障）；不会发给客户端。 */
    public String sentinel() {
        return sentinel;
    }
}
