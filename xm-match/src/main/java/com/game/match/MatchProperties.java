package com.game.match;

import com.game.api.match.MatchBudgets;
import com.game.match.gather.FingerprintMode;
import com.game.match.support.MatchModes;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * xm-match 业务配置（{@code xm.match.*}；match-spec §10.1）。缺省值与基线 {@code go/match/etc/match_service.yaml} / {@code cfg.go} 相同；不合法即拒启。
 *
 * <p><b>不在这里的</b>：各跳超时、matched TTL 公式、补偿续期、开战锁、战斗最长时限、落点记录 TTL 是代码常量（{@link MatchBudgets}）——
 * 它们出现在跨进程不等式里，改一处要连带核对，不适合做成运行期配置（M21）。运行模式读 {@code xm.run-mode}；秘密只从环境变量读。
 *
 * <p>「键必须在 Dungeon 表里」这类要查配置表的校验不在本类（本类不碰配置表），由启动检查做。
 *
 * @param worker                客户端请求与内部接口的工作池（{@link Worker}）
 * @param requestBudget         整请求预算（缺省 4500 ms = gate 调 match 的 5 s Dubbo 超时 − 500；须在 [500 ms, 4500 ms] 内：
 *                              先于调用方超时结束，in-band 结果才不会被吞掉）
 * @param matcher               凑单循环（{@link Matcher}）
 * @param ticketTtl             queued 票据的 TTL（缺省 6 h；客户端可见：QUEUED 最长保持这么久）
 * @param readyTicketTtl        ready 票据的 TTL（缺省 60 s；客户端可见：READY 窗口）
 * @param challengeTtl          切磋邀请的 TTL（缺省 60 s；客户端可见：156 的 {@code expires_at_ms}）
 * @param rating                评分（{@link Rating}）
 * @param pveTeamSizeByConfigId PVE 组队各副本（{@code battle_config_id} = DungeonTable id）的凑满人数，缺省 {@code {1: 5}}；值必须 ≥ 1，用时按
 *                              {@value MatchBudgets#MAX_TEAM_SIZE} 收口；没列出的副本 = 未开放组队。<b>不能改成查表</b>：表里 id 2 / 3 也有人数，
 *                              查表会把它们从「未开放」变成可排队（客户端可见，要两版同改）
 * @param tableFingerprintMode  战斗配表指纹的比对策略（缺省 warn；枚举绑定，写错拒启）
 * @param gatherMaxInflight     同时在途的 gather 上限（缺省 256，同 battle 单节点的 {@code rpc-max-inflight}）：超限的开局回 {@code overloaded}、无副作用
 * @param requeueBackoff        无肇事者的 gather 失败后，回队首的票多久之内不参与凑单（缺省 2 s；0 = 关闭，M11）
 * @param kafka                 对局结果 topic 的连接（{@link Kafka}）
 * @param spectate              观战（{@link Spectate}；批次 6.5）
 */
@ConfigurationProperties("xm.match")
public record MatchProperties(
        Worker worker,
        Duration requestBudget,
        Matcher matcher,
        Duration ticketTtl,
        Duration readyTicketTtl,
        Duration challengeTtl,
        Rating rating,
        Map<Integer, Integer> pveTeamSizeByConfigId,
        FingerprintMode tableFingerprintMode,
        Integer gatherMaxInflight,
        Duration requeueBackoff,
        Kafka kafka,
        Spectate spectate) {

    /** 整请求预算的区间。上限就是缺省值：gate 调 match 的 Dubbo 超时是 5 s。 */
    public static final Duration MIN_REQUEST_BUDGET = Duration.ofMillis(500);
    public static final Duration MAX_REQUEST_BUDGET = Duration.ofMillis(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS);
    /** 退避的上限：再长就等于把排队的人晾着。 */
    public static final Duration MAX_REQUEUE_BACKOFF = Duration.ofSeconds(60);
    /** PVE 组队人数的缺省配置（同基线 yaml:91-92）。 */
    public static final Map<Integer, Integer> DEFAULT_PVE_TEAM_SIZES = Map.of(1, 5);

    public MatchProperties {
        worker = worker == null ? new Worker(null, null) : worker;
        requestBudget = positiveOr(requestBudget, MAX_REQUEST_BUDGET, "request-budget");
        if (requestBudget.compareTo(MIN_REQUEST_BUDGET) < 0 || requestBudget.compareTo(MAX_REQUEST_BUDGET) > 0) {
            throw new IllegalArgumentException("xm.match.request-budget 必须在 [" + MIN_REQUEST_BUDGET + ", " + MAX_REQUEST_BUDGET
                    + "] 内（gate 调 match 的 Dubbo 超时 5 s 先到会让客户端看到信封 1003 而不是 in-band 结果）: " + requestBudget);
        }
        matcher = matcher == null ? new Matcher(null, null) : matcher;
        ticketTtl = positiveOr(ticketTtl, Duration.ofHours(6), "ticket-ttl");
        readyTicketTtl = positiveOr(readyTicketTtl, Duration.ofSeconds(60), "ready-ticket-ttl");
        challengeTtl = positiveOr(challengeTtl, Duration.ofSeconds(60), "challenge-ttl");
        requireWholeMillis(ticketTtl, "ticket-ttl");
        requireWholeMillis(readyTicketTtl, "ready-ticket-ttl");
        requireWholeMillis(challengeTtl, "challenge-ttl");
        if (ticketTtl.toSeconds() <= MatchBudgets.MAX_MATCHED_TTL_SECONDS) {
            throw new IllegalArgumentException("xm.match.ticket-ttl 必须大于最长的 matched TTL（" + MatchBudgets.MAX_MATCHED_TTL_SECONDS
                    + " s）：回队首的票要恢复成比它长的寿命: " + ticketTtl);
        }
        rating = rating == null ? new Rating(null, null, null, null, null) : rating;
        pveTeamSizeByConfigId = validTeamSizes(pveTeamSizeByConfigId);
        tableFingerprintMode = tableFingerprintMode == null ? FingerprintMode.WARN : tableFingerprintMode;
        gatherMaxInflight = positiveOr(gatherMaxInflight, 256, "gather-max-inflight");
        if (requeueBackoff == null) {
            requeueBackoff = Duration.ofSeconds(2);
        } else if (requeueBackoff.isNegative() || requeueBackoff.compareTo(MAX_REQUEUE_BACKOFF) > 0) {
            throw new IllegalArgumentException("xm.match.requeue-backoff 必须在 [0, " + MAX_REQUEUE_BACKOFF + "] 内（0 = 关闭）: " + requeueBackoff);
        }
        kafka = kafka == null ? new Kafka(null, null, null, null) : kafka;
        spectate = spectate == null ? new Spectate(null, null) : spectate;
    }

    /**
     * 这个副本的 PVE 组队凑满人数 = min(配置值, {@value MatchBudgets#MAX_TEAM_SIZE})；没配置为 0（「该副本未开放组队」）。
     * 排队 157、凑单与整队开战预检都用它（基线 {@code PveTeamSizeFor} + {@code gather.go:36} 的收口）。
     *
     * @param configId {@code battle_config_id}（uint32 的位模式；≥ 2^31 的值不可能配置，返回 0）
     */
    public int pveTeamSizeFor(int configId) {
        Integer size = pveTeamSizeByConfigId.get(configId);
        return size == null ? 0 : Math.min(size, MatchBudgets.MAX_TEAM_SIZE);
    }

    /**
     * 这条队列（模式数值 + 副本）凑满一局的人数，排队入口与凑单同一个口径（{@link MatchModes#requiredPlayers}）：
     * PVE_SOLO 1、1V1 2、5V5 10、PVE_TEAM = {@link #pveTeamSizeFor}；0 = 不能排队。
     */
    public int requiredPlayers(int mode, int configId) {
        return MatchModes.requiredPlayers(mode, pveTeamSizeFor(configId));
    }

    private static Map<Integer, Integer> validTeamSizes(Map<Integer, Integer> configured) {
        if (configured == null) {
            return DEFAULT_PVE_TEAM_SIZES;
        }
        Map<Integer, Integer> out = new TreeMap<>();
        configured.forEach((configId, size) -> {
            if (configId == null || configId <= 0) {
                throw new IllegalArgumentException("xm.match.pve-team-size-by-config-id 的键必须是正的副本 id: " + configId);
            }
            if (size == null || size < 1) {
                throw new IllegalArgumentException("xm.match.pve-team-size-by-config-id[" + configId + "] 必须 ≥ 1（不开放的副本不要列出来）: " + size);
            }
            out.put(configId, size);
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * 工作池（{@code xm.match.worker.*}）：客户端请求、{@code MatchTeamService} 的前三个方法、{@code MatchInternalService} 都在它上面阻塞等 Redis / MySQL。
     *
     * @param threads 线程数（缺省 16）
     * @param queue   队列上限（缺省 1024）；满了按各方法的「过载」应答回，不无限堆积
     */
    public record Worker(Integer threads, Integer queue) {

        public Worker {
            threads = positiveOr(threads, 16, "worker.threads");
            queue = positiveOr(queue, 1024, "worker.queue");
        }
    }

    /**
     * 凑单循环（{@code xm.match.matcher.*}）。
     *
     * @param interval 两轮之间的间隔（缺省 500 ms；上一轮结束后再等这么久）
     * @param lockTtl  每条队列的凑单锁 TTL（缺省 10 s；必须 ≥ 1 s 且不短于间隔）。锁只是效率手段，过期后两个实例同时处理同一队列也不会双弹
     */
    public record Matcher(Duration interval, Duration lockTtl) {

        public Matcher {
            interval = positiveOr(interval, Duration.ofMillis(500), "matcher.interval");
            lockTtl = positiveOr(lockTtl, Duration.ofSeconds(10), "matcher.lock-ttl");
            requireWholeMillis(interval, "matcher.interval");
            if (lockTtl.compareTo(Duration.ofSeconds(1)) < 0 || lockTtl.compareTo(interval) < 0) {
                throw new IllegalArgumentException("xm.match.matcher.lock-ttl 必须 ≥ 1 s 且不短于 matcher.interval（" + interval + "）: " + lockTtl);
            }
        }
    }

    /**
     * 评分（{@code xm.match.rating.*}；match-spec §5、§2.8）。
     *
     * @param enabled                 是否消费对局结果更新评分（缺省 true）。关掉后评分停在已有值（新号 1500），凑单退化为纯等待序；评分镜像照常维护
     * @param tolerance               凑单的评分容差曲线（{@link Tolerance}）
     * @param drawRoundCap            「回合打满按平局」的阈值（缺省 30；<b>0 = 关闭这条判定</b>，同基线 {@code cfg.go:139-149}）：
     *                                {@code total_rounds ≥} 它的胜负结果按平局结算
     * @param drawRoundCapByConfigId  按副本覆盖阈值（缺省空；值为 0 视为没配，同基线 {@code cfg.go:177-189}）。照搬基线：缺省不配，
     *                                阈值对所有副本都是 30，不按引擎的回合上限推导（§5.5）
     * @param consumerGroup           结果 topic 的消费组（缺省 {@code xm-match-rating}；多个 match 实例同组分摊分区）
     */
    public record Rating(Boolean enabled, Tolerance tolerance, Integer drawRoundCap, Map<Integer, Integer> drawRoundCapByConfigId,
                         String consumerGroup) {

        public Rating {
            enabled = enabled == null || enabled;
            tolerance = tolerance == null ? new Tolerance(null, null, null, null, null) : tolerance;
            if (drawRoundCap == null) {
                drawRoundCap = 30;
            } else if (drawRoundCap < 0) {
                throw new IllegalArgumentException("xm.match.rating.draw-round-cap 不能为负（0 = 关闭）: " + drawRoundCap);
            }
            Map<Integer, Integer> caps = new TreeMap<>();
            if (drawRoundCapByConfigId != null) {
                drawRoundCapByConfigId.forEach((configId, cap) -> {
                    if (configId == null || cap == null || cap < 0) {
                        throw new IllegalArgumentException("xm.match.rating.draw-round-cap-by-config-id[" + configId + "] 不能为负或缺值: " + cap);
                    }
                    caps.put(configId, cap);
                });
            }
            drawRoundCapByConfigId = Collections.unmodifiableMap(caps);
            consumerGroup = consumerGroup == null || consumerGroup.isBlank() ? "xm-match-rating" : consumerGroup.trim();
        }

        /**
         * 这个副本的「回合打满按平局」阈值：有非 0 的覆盖取覆盖，否则取 {@link #drawRoundCap}。返回 0 = 不做这条判定。
         *
         * @param configId {@code battle_config_id}（uint32 的位模式）
         */
        public int drawRoundCapFor(int configId) {
            Integer override = drawRoundCapByConfigId.get(configId);
            return override != null && override > 0 ? override : drawRoundCap;
        }
    }

    /**
     * 评分容差曲线（{@code xm.match.rating.tolerance.*}；基线 {@code rating.go:154-233}）：{@code tol = min(max, base + ⌊已等秒数 / stepSeconds⌋ × stepDelta)}，
     * 已等 ≥ {@code maxWaitSeconds} 时容差无穷大（纯等待序）。单位是评分点（不是 centi）。<b>0 或漏配按缺省值</b>（同基线）；终态兜底不能关闭。
     *
     * @param base           起始容差（缺省 100）
     * @param stepSeconds    每多等这么多秒放宽一档（缺省 5）
     * @param stepDelta      每档放宽多少（缺省 100）
     * @param max            曲线上限（缺省 1000）
     * @param maxWaitSeconds 终态兜底的秒数（缺省 90）
     */
    public record Tolerance(Integer base, Integer stepSeconds, Integer stepDelta, Integer max, Integer maxWaitSeconds) {

        public Tolerance {
            base = zeroAsDefault(base, 100, "rating.tolerance.base");
            stepSeconds = zeroAsDefault(stepSeconds, 5, "rating.tolerance.step-seconds");
            stepDelta = zeroAsDefault(stepDelta, 100, "rating.tolerance.step-delta");
            max = zeroAsDefault(max, 1000, "rating.tolerance.max");
            maxWaitSeconds = zeroAsDefault(maxWaitSeconds, 90, "rating.tolerance.max-wait-seconds");
        }
    }

    /**
     * 对局结果 topic 的连接（{@code xm.match.kafka.*}；规格没给这几个键，按 {@code xm.audit.*} 的口径补，lead 裁决问题 8）。
     * xm-battle 是生产方（{@code xm.battle.result.*} 同样四个键），两边的代次必须一致。
     *
     * @param bootstrapServers  Kafka 地址（环境变量 {@code XM_KAFKA_BOOTSTRAP_SERVERS}，缺省 {@code 127.0.0.1:9092}）
     * @param topicGeneration   topic 代次（{@code XM_BATTLE_RESULT_TOPIC_GENERATION}，≥ 1，缺省 1）；分区数改了就升代次
     * @param replicationFactor 新建 topic 的副本数（只在创建时用，缺省 1）
     * @param initTimeout       核对 / 创建 topic 一次的上限（缺省 10 s）
     */
    public record Kafka(String bootstrapServers, Integer topicGeneration, Short replicationFactor, Duration initTimeout) {

        public Kafka {
            bootstrapServers = bootstrapServers == null || bootstrapServers.isBlank() ? "127.0.0.1:9092" : bootstrapServers.trim();
            topicGeneration = positiveOr(topicGeneration, 1, "kafka.topic-generation");
            if (replicationFactor == null) {
                replicationFactor = 1;
            } else if (replicationFactor < 1) {
                throw new IllegalArgumentException("xm.match.kafka.replication-factor 必须 ≥ 1: " + replicationFactor);
            }
            initTimeout = positiveOr(initTimeout, Duration.ofSeconds(10), "kafka.init-timeout");
        }
    }

    /**
     * 观战（{@code xm.match.spectate.*}；spectate-spec §5.2）。163 的预算沿用 {@code xm.match.request-budget}；Add / Remove 的超时、标记与索引的时限、
     * 列表条数、随机选场的轮数都是代码常量（{@link MatchBudgets}，理由同类注释：出现在跨进程不等式里，或客户端可见）。
     *
     * @param sweepInterval 清扫可观战索引的间隔（缺省 10 s；必须 &gt; 0 且不小于 1 ms）：每轮摘掉分数早于「Redis 时间 − 360 s」的成员并采样索引大小。
     *                      读路径（随机选场、列表）自己按分数过滤，所以它只是兜底，不必更勤（W9）
     * @param maxInflight   同时在途的 163 上限（缺省 128；必须 ≥ 1）：163 每个请求一条虚拟线程、要同步等 battle 的 RPC，超限的请求当场回
     *                      in-band 16004「服务器繁忙,请稍后再试」（W10）
     */
    public record Spectate(Duration sweepInterval, Integer maxInflight) {

        public Spectate {
            sweepInterval = positiveOr(sweepInterval, Duration.ofSeconds(10), "spectate.sweep-interval");
            requireWholeMillis(sweepInterval, "spectate.sweep-interval");
            maxInflight = positiveOr(maxInflight, 128, "spectate.max-inflight");
        }
    }

    private static Duration positiveOr(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("xm.match." + name + " 必须为正: " + value);
        }
        return value;
    }

    private static int positiveOr(Integer value, int fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("xm.match." + name + " 必须为正: " + value);
        }
        return value;
    }

    private static int zeroAsDefault(Integer value, int fallback, String name) {
        if (value == null || value == 0) {
            return fallback;
        }
        if (value < 0) {
            throw new IllegalArgumentException("xm.match." + name + " 不能为负（0 = 取缺省值）: " + value);
        }
        return value;
    }

    /** TTL 与间隔都按毫秒下发给 Redis（PEXPIRE / PX）：不足 1 ms 的值会被截成 0。 */
    private static void requireWholeMillis(Duration value, String name) {
        if (value.toMillis() < 1) {
            throw new IllegalArgumentException("xm.match." + name + " 不能小于 1 ms: " + value);
        }
    }
}
