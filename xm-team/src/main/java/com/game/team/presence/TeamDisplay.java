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
 * 视图的展示缓存（基线 presence.go:84-147 loadDisplay，team-spec §6.6 / §6.7）：
 * <ul>
 *   <li>name / level / class_id / gender / appearance_id：读 {@code xm_java.player}（{@code PlayerProfiles.load}：每批 64 人，
 *       某批失败记 ERROR、填零值，不让 RPC 失败）。与基线 PlayerAllData 的差别（D4）：name 恒非空、新角色 level 为 1、level 到存盘才更新；</li>
 *   <li>is_online：{@code xm:presence} 宽松批量读（读失败按离线处理，基线 loadSessions 失败 → Online=false）；</li>
 *   <li>in_battle：Java 在批次 6.3 之前没有战斗锁，恒为 false（D10）。</li>
 * </ul>
 * 一次视图构建只读一份，覆盖 rosterIds（≤ 5 成员 + 10 申请人 + 10 被邀请人 + 邀请人）。在线目录与 MySQL 并行：先发出异步的在线读，
 * 再在调用线程上做阻塞的资料读，最后等在线读。只在工作线程 / 推送线程上调用。线程安全。
 */
public final class TeamDisplay implements DisplayLoader {

    private static final Logger log = LoggerFactory.getLogger(TeamDisplay.class);

    private final BiFunction<List<Long>, Deadline, Map<Long, Profile>> profiles;
    private final Function<Collection<Long>, CompletionStage<Map<Long, PlayerPresence>>> presence;

    /**
     * @param profiles 批量读资料（生产 {@code PlayerProfiles::load}：失败返回已读到的部分，永不抛）
     * @param presence 宽松批量读在线目录（生产 {@code PlayerPresenceDirectory::findAllAsync}：只含在线的玩家）
     */
    public TeamDisplay(BiFunction<List<Long>, Deadline, Map<Long, Profile>> profiles,
                       Function<Collection<Long>, CompletionStage<Map<Long, PlayerPresence>>> presence) {
        this.profiles = profiles;
        this.presence = presence;
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
        Map<Long, MemberDisplay> out = new LinkedHashMap<>();
        for (long id : ids) {
            Profile p = brief.get(id);
            boolean isOnline = onlineNow.containsKey(id);
            out.put(id, p == null
                    ? new MemberDisplay(isOnline, false, 0, 0, "", "", 0)
                    : new MemberDisplay(isOnline, false, p.level(), p.classId(), p.name(), p.appearanceId(), p.gender()));
        }
        return Collections.unmodifiableMap(out);
    }
}
