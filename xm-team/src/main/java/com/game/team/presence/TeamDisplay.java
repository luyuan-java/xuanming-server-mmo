package com.game.team.presence;

import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerProfiles.Profile;
import com.game.discovery.proto.PlayerPresence;
import com.game.team.view.MemberDisplay;
import com.game.team.view.TeamViews;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 视图的展示缓存（基线 presence.go:84-147 loadDisplay，team-spec §4.3 / §6.6 / §6.7）：
 * <ul>
 *   <li>name / level / class_id / gender / appearance_id：读 {@code xm_java.player}（{@code PlayerProfiles.load}：每批 64 人，
 *       某批失败记 ERROR、填零值，不让 RPC 失败）。与基线 PlayerAllData 的差别（D4）：name 恒非空、新角色 level 为 1、level 到存盘才更新；</li>
 *   <li>is_online：{@code xm:presence} 宽松批量读（读失败按离线处理，基线 loadSessions 失败 → Online=false）；</li>
 *   <li>in_battle：回合制战斗锁 {@code xm:battle:{pid}:lock} 在不在（scene-battle-spec §2.4、§7.13 世界内部第 4 条；批次 6.3 收掉 team-spec D10）。
 *       锁是 Hash：{@code MGET} 对 Hash 键回 nil、不报错，照搬基线的 MGET 会让 in_battle 静默恒为 false，
 *       所以批量读是逐键 EXISTS 并发发出（{@code BattleLockReader.existsAll}，任何一个读失败整体失败）。
 *       锁从备战起就在，到结算销账（或锁过期 / 判废）才没——所以备战中、战斗中、已结算待销账都显示为战斗中。
 *       咨询性：读失败、超时、结果里缺这个人，一律按「不在战斗」（基线 MGET 失败 → false），只记一行 WARN。</li>
 * </ul>
 * 一次视图构建只读一份，覆盖 rosterIds（≤ 5 成员 + 10 申请人 + 10 被邀请人 + 邀请人；基线三次 MGET 也是对全体 id 做的）。
 * 两路 Redis 读与 MySQL 并行：先发出异步的在线读与战斗锁读，再在调用线程上做阻塞的资料读，最后在请求预算内依次等这两路
 * （已经完成的那一路不受预算用尽影响，照样取到结果）。只在工作线程 / 推送线程上调用。线程安全。
 */
public final class TeamDisplay implements DisplayLoader {

    private static final Logger log = LoggerFactory.getLogger(TeamDisplay.class);

    private final BiFunction<List<Long>, Deadline, Map<Long, Profile>> profiles;
    private final Function<Collection<Long>, CompletionStage<Map<Long, PlayerPresence>>> presence;
    private final Function<Collection<Long>, CompletionStage<Map<Long, Boolean>>> battleLocks;

    /**
     * @param profiles    批量读资料（生产 {@code PlayerProfiles::load}：失败返回已读到的部分，永不抛）
     * @param presence    宽松批量读在线目录（生产 {@code PlayerPresenceDirectory::findAllAsync}：只含在线的玩家）
     * @param battleLocks 批量读战斗锁在不在（生产 {@code BattleLockReader::existsAll}：player_id → 锁在不在；不得阻塞，
     *                    失败以异常完成或直接抛出都可以，这里一律按「都不在战斗」处理）
     */
    public TeamDisplay(BiFunction<List<Long>, Deadline, Map<Long, Profile>> profiles,
                       Function<Collection<Long>, CompletionStage<Map<Long, PlayerPresence>>> presence,
                       Function<Collection<Long>, CompletionStage<Map<Long, Boolean>>> battleLocks) {
        this.profiles = profiles;
        this.presence = presence;
        this.battleLocks = battleLocks;
    }

    @Override
    public Map<Long, MemberDisplay> load(Collection<Long> playerIds, Deadline deadline) {
        List<Long> ids = TeamViews.uniqueIds(playerIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        CompletableFuture<Map<Long, PlayerPresence>> online;
        try {
            online = presence.apply(ids).toCompletableFuture();
        } catch (RuntimeException e) {
            online = CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Map<Long, Boolean>> locked;
        try {
            locked = battleLocks.apply(ids).toCompletableFuture();
        } catch (RuntimeException e) {
            locked = CompletableFuture.failedFuture(e);
        }
        Map<Long, Profile> brief;
        try {
            brief = profiles.apply(ids, deadline);
        } catch (RuntimeException e) {
            log.error("[team] 读展示资料失败，{} 人的昵称 / 等级 / 职业填零值: {}", ids.size(), e.toString());
            brief = Map.of();
        }
        Map<Long, PlayerPresence> onlineNow;
        try {
            onlineNow = deadline.await(online, "宽松批量读在线目录");
        } catch (RuntimeException e) {
            log.warn("[team] 读在线目录失败，{} 人按离线显示: {}", ids.size(), e.toString());
            onlineNow = Map.of();
        }
        Map<Long, Boolean> lockedNow = awaitBattleLocks(locked, ids.size(), deadline);
        Map<Long, MemberDisplay> out = new LinkedHashMap<>();
        for (long id : ids) {
            Profile p = brief.get(id);
            boolean isOnline = onlineNow.containsKey(id);
            // 缺项与 null 值都按 false（结果是别人给的 Map，不假设每个 id 都有非 null 的值）
            boolean inBattle = Boolean.TRUE.equals(lockedNow.get(id));
            out.put(id, p == null
                    ? new MemberDisplay(isOnline, inBattle, 0, 0, "", "", 0)
                    : new MemberDisplay(isOnline, inBattle, p.level(), p.classId(), p.name(), p.appearanceId(), p.gender()));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * 在请求预算内等战斗锁的批量读。发出时同步抛出（已折成失败的 future）、整体异常完成、超时、被中断、结果为 null——全部只记一行 WARN、
     * 按「都不在战斗」返回空表；不抛出、不让 RPC 失败（in_battle 是咨询性字段，基线 MGET 失败同样为 false）。
     */
    private static Map<Long, Boolean> awaitBattleLocks(CompletableFuture<Map<Long, Boolean>> locked, int count, Deadline deadline) {
        String problem;
        try {
            Map<Long, Boolean> result = deadline.await(locked, "批量读战斗锁");
            if (result != null) {
                return result;
            }
            problem = "结果为空";
        } catch (RuntimeException e) {
            // Deadline.await 把原始原因挂在 cause 上（超时 = TimeoutException），一并打出来，否则只看得到「失败」两个字
            problem = e.getCause() == null ? e.toString() : e.getMessage() + " <- " + e.getCause();
        }
        log.warn("[team] 读战斗锁失败，{} 人的 in_battle 按 false 显示: {}", count, problem);
        return Map.of();
    }
}
