package com.game.discovery.world;

import java.time.Duration;
import java.util.List;

/**
 * 主世界频道的 Redis 门面（批次 5.1，scene-channels-spec §4.2、§4.4、§4.6、§4.11）：{@code xm:world:*} 键空间的唯一读写入口。
 * 生产实现 {@link RedissonWorldChannelStore}；scene-manager / scene 的单测用假实现。
 *
 * <ul>
 *   <li><b>计划</b>（{@code xm:world:{z:<zone>}:{ch,desired,cooldown,ver}}）：只由 scene-manager 的分 zone 领导者经 {@link #write}
 *       （令牌 + 版本号 CAS 的一段 Lua）改；领导者用 {@link #snapshot} 读，scene 节点用 {@link #planVersion} 探测变化、{@link #readPlan} 整读。</li>
 *   <li><b>领导锁</b>（{@code :leader}）：{@link #tryAcquireLeader} / {@link #renewLeader} / {@link #releaseLeader}，值是持有者令牌；
 *       有效期判定在 {@link WorldLeaderLock}。</li>
 *   <li><b>软预占</b>（{@code :resv:<scene_id>}）：{@link #reserve} 在候选里原子挑最空的并记一条，{@link #reserveScene} 给指定场景记一条，
 *       {@link #releaseReservation} 撤一条，{@link #countReservations} 读未到期条数。</li>
 *   <li><b>zone 集合</b>（{@code xm:world:zones}）：{@link #registerZone} / {@link #zones}。</li>
 * </ul>
 *
 * <p>全部方法<b>阻塞</b>（Redis 往返），调用方不得在 Netty I/O 线程或场景逻辑线程上调用（AGENTS.md §3）：scene-manager 在控制面专用线程 /
 * Dubbo 业务线程上用，scene 在 {@code scene-sched} 上用。Redis 故障原样抛出 {@link RuntimeException}；读到的数据损坏（频道记录解析失败、
 * 字段与记录的 scene_id 不符、版本号不是整数）抛 {@link IllegalStateException}——调用方一律按「本次读失败、什么也不应用」处理（fail-closed，§4.10.1、§4.14）。
 * 线程安全。
 */
public interface WorldChannelStore {

    /** {@code GET xm:world:{z:<zone>}:ver}（不存在为 0）。scene 节点每秒调一次，与上次应用的相同就不整读（§4.10.1）。 */
    long planVersion(int zoneId);

    /** 版本号与整张频道表，一段只读 Lua 一次取回（scene 节点拉取，§4.10.1）。 */
    WorldPlan readPlan(int zoneId);

    /** 版本号、频道表、期望数、冷却与 Redis TIME，一段只读 Lua 一次取回（领导者一拍的输入，§4.6.1）。 */
    WorldPlanSnapshot snapshot(int zoneId);

    /**
     * 整批写入（§4.4）：领导锁的值是 {@code leaderToken}、且版本号等于 {@link WorldPlanBatch#expectedVersion()} 时，按顺序执行全部改动并
     * 把 ver 置为 {@link WorldPlanBatch#writtenVersion()}；否则一条也不写。空批次也会推进 ver（只为让节点重读，生产路径不用）。
     */
    WorldPlanWriteResult write(int zoneId, String leaderToken, WorldPlanBatch batch);

    /**
     * 竞选（{@code SET NX PX}）：成功为 true。锁已是本令牌时续期并回 true（Redisson 响应超时重发同一段脚本时幂等）。
     */
    boolean tryAcquireLeader(int zoneId, String token, Duration ttl);

    /** 续期：值仍是本令牌才 {@code PEXPIRE}，回 true；已不是（被夺或已过期）回 false。 */
    boolean renewLeader(int zoneId, String token, Duration ttl);

    /** 放锁：值是本令牌才 {@code DEL}，回是否删了。 */
    boolean releaseLeader(int zoneId, String token);

    /**
     * 选频道并软预占（§4.11 第 5 步，一段 Lua 原子完成）：先清掉各候选已到期的预占，负载 = 目录人数 + 未到期的<b>别人的</b>预占数，
     * 取负载<b>严格最小</b>的（并列取列表里靠前的），给它记一条 {@code player_id → now + ttl} 并把键 {@code PEXPIRE ttl}；
     * 同时撤掉本玩家在其它候选上的预占——同一玩家重复分配（login 重试）不重复计数、也不因自己的旧预占改变选择。
     *
     * @param candidates 非空；顺序即并列优先顺序（调用方按 (node_id, scene_id) 无符号升序排好）
     * @param ttl        &gt; 0（{@code reservation-ttl = 0} 关闭预占时调用方不调这里）
     */
    ReservationPick reserve(int zoneId, List<ReservationCandidate> candidates, long playerId, Duration ttl);

    /** 给指定场景记一条预占（{@code player_id → now + ttl}，键 {@code PEXPIRE ttl}）：分配选中原实例时用，让并发分配看得见（§4.11 第 3 步）。 */
    void reserveScene(int zoneId, long sceneId, long playerId, Duration ttl);

    /** 撤掉一条预占（{@code ZREM}），回是否撤到了。5.1 的拒绝出口不需要成对退还（D7）；给将来需要提前退还的路径用。 */
    boolean releaseReservation(int zoneId, long sceneId, long playerId);

    /**
     * 各场景未到期的预占条数（{@code ZCOUNT (now +inf}，now 取 Redis TIME），与 {@code sceneIds} 同序。
     * hash 覆盖模式择机迁移前对候选显式读一次（§4.8）。空列表回空列表。
     */
    List<Long> countReservations(int zoneId, List<Long> sceneIds);

    /** {@code SADD xm:world:zones <zone>}（scene 节点启动时，§4.10.5）。 */
    void registerZone(int zoneId);

    /** {@code SMEMBERS xm:world:zones}，按无符号升序；解析不了的成员跳过并告警。 */
    List<Integer> zones();
}
