package com.game.guild.presence;

import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerProfiles;
import com.game.common.player.PlayerProfiles.Profile;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 成员 / 帮主 / 申请人的展示名（基线 PlayerNameResolver + DataServicePlayerNames，player_name_resolver.go；guild-spec §4.7，D5）。
 *
 * <p>纪律（player_name_resolver.go:3-34）：名字是展示数据，读取 <b>fail-open</b>、永不抛；查不到的 id 不在结果里（取值得空串）；
 * 自己处理 0 与重复 id；每个填充点一次请求只调一次（成员 + 帮主一次、榜单一页的帮主一次、待审名单一次……）。
 *
 * <p>数据源（D5）：{@code xm_java.player.name}（恒非空），代替基线的 data_service {@code BatchGetPlayerName}。分批读法与
 * {@link PlayerProfiles#load} 相同（每批 {@value PlayerProfiles#BATCH} 人，某批失败 → 记 ERROR、停在这一批、返回已读到的部分）；
 * 这里逐批调用一条 IN 语句（{@code PlayerProfiles::loadStrictOnce}）而不是直接调 {@code load}，只为拿到「这一批失败了」的信号去计
 * {@code xm_guild_profile_lookup_failures_total}（按批计，同基线 {@code guild_player_name_lookup_failed_total}；{@code load} 把失败吞在内部）。
 * 请求预算在发查询之前就已用完时只记 INFO、不计失败（基线 :114-122：病根在上游，不把告警引到 player 表）；整次取名另有
 * {@value #LOOKUP_TIMEOUT_MS} ms 上限（同基线 800 ms，取它与剩余预算的较小者），查询途中超时按取名失败计。
 *
 * <p>阻塞 JDBC，只在工作线程上调用。线程安全。
 */
public final class PlayerNames {

    private static final Logger log = LoggerFactory.getLogger(PlayerNames.class);

    /** 一批展示资料读失败（{@code GuildMetrics} 实现；必须便宜、不抛）。 */
    @FunctionalInterface
    public interface Metrics {
        void lookupFailed();

        Metrics NONE = () -> {
        };
    }

    /** 整次取名的等待上限（毫秒；基线 DefaultPlayerNameLookupTimeout，player_name_resolver.go:45），实际取它与请求剩余预算的较小者。 */
    public static final long LOOKUP_TIMEOUT_MS = 800L;

    private final BiFunction<List<Long>, Deadline, Map<Long, Profile>> readBatch;
    private final Metrics metrics;
    private final long timeoutMillis;

    /**
     * @param readBatch 一条 IN 语句读一批资料，失败抛异常（生产 {@code PlayerProfiles::loadStrictOnce}）
     */
    public PlayerNames(BiFunction<List<Long>, Deadline, Map<Long, Profile>> readBatch, Metrics metrics) {
        this(readBatch, metrics, LOOKUP_TIMEOUT_MS);
    }

    PlayerNames(BiFunction<List<Long>, Deadline, Map<Long, Profile>> readBatch, Metrics metrics, long timeoutMillis) {
        this.readBatch = readBatch;
        this.metrics = metrics;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 批量取名（去重、丢 0、丢空名）。永不抛；查不到 / 读失败的 id 不在结果里。
     */
    public Map<Long, String> namesOf(Collection<Long> playerIds, Deadline deadline) {
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long id : playerIds) {
            if (id != null && id != 0) {
                unique.add(id);
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (unique.isEmpty()) {
            return names;
        }
        if (deadline.expired()) {
            log.info("[guild] 请求预算已用完，跳过取名 n={}", unique.size());
            return names;
        }
        List<Long> ids = List.copyOf(unique);
        // 名字查不到只是少显示几个字：整次取名最多等 800 ms（同基线），不让它吃掉回包装配的预算
        Deadline lookup = Deadline.after(Math.min(timeoutMillis, deadline.remainingMillis()));
        for (int from = 0; from < ids.size(); from += PlayerProfiles.BATCH) {
            List<Long> batch = ids.subList(from, Math.min(ids.size(), from + PlayerProfiles.BATCH));
            Map<Long, Profile> profiles;
            try {
                profiles = readBatch.apply(batch, lookup);
            } catch (RuntimeException e) {
                // 只记条数不记 id 列表（同基线 :129-131）
                log.error("[guild] 取展示名失败（第 {} 批起留空，n={}）: {}", from / PlayerProfiles.BATCH + 1, ids.size(),
                        e.toString());
                try {
                    metrics.lookupFailed();
                } catch (RuntimeException ignored) {
                    // 指标出口不得影响读
                }
                break;
            }
            if (profiles == null) {
                continue;
            }
            for (Profile p : profiles.values()) {
                if (p != null && p.name() != null && !p.name().isEmpty()) {
                    names.put(p.playerId(), p.name());
                }
            }
        }
        return names;
    }
}
