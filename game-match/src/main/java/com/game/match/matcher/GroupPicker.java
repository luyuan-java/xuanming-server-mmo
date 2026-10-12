package com.game.match.matcher;

import com.game.match.rating.RatingReader;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore.QueueSnapshot;
import com.game.match.ticket.TicketStore.SnapshotEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一次挑组的工作集（match-spec §2.7；基线 {@code matcher.go:508-689} 的 popGroup / pickGroup）：拿一份队列快照与快照里各人的票据，按等待序逐个
 * 试锚点，给第一个凑得齐的锚点挑出一组。只挑不弹——弹组（原子核对 + 摘出 + 置 matched）由调用方交给票据存储。
 *
 * <pre>
 * for 锚点 in 快照（等待序；最多 {@value #MAX_ANCHOR_ATTEMPTS} 个<b>有效</b>锚点，无效的不计次数）:
 *     wait = ⌊(快照时刻 − 锚点票的入队时刻) / 1000⌋，夹到 ≥ 0
 *     tol  = 容差曲线(评分模式?, wait)
 *     候选 = 快照里除锚点外的<b>全部</b>成员（排在锚点之前的也算）、没被判不可用、按玩家号去重；
 *            评分模式要求 |候选的镜像分 − 锚点票里的评分| ≤ tol，按分差升序、同分按快照下标升序（稳定排序）；非评分模式按快照序
 *     按这个顺序逐个校验候选，够人数为止
 *     不够 → 这个锚点继续等（队列不动），换下一个锚点
 *     够   → 成员 = [锚点] + 按上面顺序选中的候选
 * </pre>
 *
 * <p><b>成员顺序客户端可见</b>：它就是备战与快照的顺序，决定同队站位与确定性战斗里选目标、结算 buff 的次序。「锚点在前、其余按选中顺序」不能重排。
 * 设计文档与基线注释写的「候选取锚点之后」是文档错误，代码扫描全部成员——回队首之后队列序 ≠ 入队序，两种写法结果不同，以代码为准。
 *
 * <p><b>成员的校验</b>分两半：票据这一半（存在、queued、属于这条队列、退避已过）是纯判断，在这里做；要读别人状态的那一半（战斗锁、位置）与
 * 一切副作用（把无效成员摘出队列）经 {@link Gate} 交给调用方。每个玩家在一次挑组里只校验一次（同一个人可能先后是多个锚点的候选）。
 * {@link Gate} 抛出的异常原样穿出 {@link #pick}——依赖故障时调用方结束这条队列的本轮，不做出局判定。
 *
 * <p>评分一律是 centi。评分镜像缺分的成员（只可能来自人为改数据）按票里的评分用。一个实例只用一次；不是线程安全的。
 */
final class GroupPicker {

    /** 一次挑组最多试多少个有效锚点：队首凑不到人不阻塞后面的人，但一轮内不无限扫下去（基线 {@code maxAnchorAttempts}）。 */
    static final int MAX_ANCHOR_ATTEMPTS = 32;

    /** 校验里要做 I/O 的那一半，由调用方实现。 */
    interface Gate {

        /**
         * 票据有效的成员再过外部检查（战斗锁、位置）。
         *
         * @return true = 可以参战；false = 这一次不可用（已被剔出队列，或只是本轮跳过）
         */
        boolean admit(long playerId, Ticket ticket);

        /** 这个快照成员无效（成员串不是合法玩家号，或票据缺失 / 不是 queued / 不属于这条队列）：把它摘出队列。每个成员至多调一次。 */
        void evict(SnapshotEntry entry);

        /** 一个校验通过的成员在评分镜像里没有分（已按票里的评分用）：只用于计数。 */
        default void missingScore(long playerId) {
        }
    }

    /** 挑组的结局（二选一）。 */
    sealed interface Pick permits Group, None {
    }

    /**
     * 挑出了一组。
     *
     * @param members           锚点在前，其余按选中顺序
     * @param tickets           各成员校验时读到的票号，遍历顺序同 {@code members}（弹组与之后的票据写都按它做 CAS）
     * @param anchorWaitSeconds 锚点已等的整秒数
     * @param toleranceCenti    锚点用的容差（{@link Tolerance#UNBOUNDED} = 无穷大）
     * @param ratingsCenti      各成员票里的评分，与 {@code members} 同下标
     */
    record Group(List<Long> members, Map<Long, String> tickets, long anchorWaitSeconds, long toleranceCenti, List<Long> ratingsCenti)
            implements Pick {

        Group {
            members = List.copyOf(members);
            tickets = Collections.unmodifiableMap(new LinkedHashMap<>(tickets));
            ratingsCenti = List.copyOf(ratingsCenti);
        }

        /** 组内最高分与最低分之差（centi）。 */
        long spreadCenti() {
            long lo = Long.MAX_VALUE;
            long hi = Long.MIN_VALUE;
            for (long rating : ratingsCenti) {
                lo = Math.min(lo, rating);
                hi = Math.max(hi, rating);
            }
            return Tolerance.distance(hi, lo);
        }
    }

    /**
     * 这一次没有成组。
     *
     * @param starvedWaitSeconds 试过却凑不到候选的锚点里等得最久的秒数；没有这样的锚点为 -1
     * @param saturated          其中「容差曲线已经到顶仍凑不到」的锚点里等得最久的那个（只有评分模式会有）；没有为 null
     */
    record None(long starvedWaitSeconds, Starved saturated) implements Pick {
    }

    /** 一个曲线到顶仍凑不到候选的锚点（告警用）。 */
    record Starved(long playerId, long waitSeconds, long toleranceCenti) {
    }

    private final String queueKey;
    private final int required;
    private final boolean rated;
    private final List<SnapshotEntry> entries;
    private final long nowMs;
    private final Map<Long, Ticket> tickets;
    private final Tolerance tolerance;
    private final Gate gate;
    /** 校验结论：玩家号 → 可用与否（可用的票在 {@link #tickets} 里）。 */
    private final Map<Long, Boolean> verdicts = new HashMap<>();
    /** 已经请调用方摘过的非法成员串。 */
    private final Set<String> evictedMalformed = new HashSet<>();

    /**
     * @param required 凑满一局的人数（≥ 1）
     * @param rated    评分模式（1V1 / 5V5）
     * @param snapshot 队列前缀的快照（等待序）与取快照那一刻的 Redis 时间
     * @param tickets  快照里各玩家此刻的票据（没有票的人不在里面）
     */
    GroupPicker(QueueRef queue, int required, boolean rated, QueueSnapshot snapshot, Map<Long, Ticket> tickets, Tolerance tolerance, Gate gate) {
        if (required < 1) {
            throw new IllegalArgumentException("凑满人数必须 ≥ 1: " + required);
        }
        this.queueKey = queue.queueKey();
        this.required = required;
        this.rated = rated;
        this.entries = snapshot.entries();
        this.nowMs = snapshot.redisNowMs();
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.tolerance = Objects.requireNonNull(tolerance, "tolerance");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /** 按等待序试锚点，返回第一组；一组都凑不出时返回饥饿观测。 */
    Pick pick() {
        long starvedWait = -1;
        Starved saturated = null;
        long saturation = tolerance.saturationSeconds();
        int attempts = 0;
        for (int anchorIdx = 0; anchorIdx < entries.size() && attempts < MAX_ANCHOR_ATTEMPTS; anchorIdx++) {
            Ticket anchorTicket = validate(anchorIdx);
            if (anchorTicket == null) {
                continue;
            }
            attempts++;
            long anchorId = entries.get(anchorIdx).playerId();
            long waitSeconds = waitSeconds(nowMs, anchorTicket.enqueuedAtMs());
            long tol = tolerance.anchorCenti(rated, waitSeconds);
            List<Long> members = pickGroup(anchorIdx, anchorTicket, tol);
            if (members == null) {
                // 候选不足：锚点继续等。记最久的等待；曲线到顶仍凑不到的另记下来给调用方告警
                starvedWait = Math.max(starvedWait, waitSeconds);
                if (rated && waitSeconds >= saturation && (saturated == null || waitSeconds > saturated.waitSeconds())) {
                    saturated = new Starved(anchorId, waitSeconds, tol);
                }
                continue;
            }
            Map<Long, String> ticketIds = new LinkedHashMap<>();
            List<Long> ratings = new ArrayList<>(members.size());
            for (Long member : members) {
                Ticket ticket = tickets.get(member);
                ticketIds.put(member, ticket.ticketId());
                ratings.add(ticket.ratingCenti());
            }
            return new Group(members, ticketIds, waitSeconds, tol, ratings);
        }
        return new None(starvedWait, saturated);
    }

    /** 以快照下标 {@code anchorIdx} 为锚点挑 {@code required − 1} 个候选；候选不足返回 null。 */
    private List<Long> pickGroup(int anchorIdx, Ticket anchorTicket, long tol) {
        long anchorId = entries.get(anchorIdx).playerId();
        // 锚点的评分取票里的；候选取镜像分（两者在正常数据下相等）
        long anchorRating = anchorTicket.ratingCenti();
        Set<Long> seen = new HashSet<>();
        seen.add(anchorId);
        List<Candidate> candidates = new ArrayList<>();
        for (int idx = 0; idx < entries.size(); idx++) {
            if (idx == anchorIdx) {
                continue;
            }
            long playerId = entries.get(idx).playerId();
            if (playerId == 0) {
                // 非法成员留到它自己当锚点时再摘
                continue;
            }
            if (Boolean.FALSE.equals(verdicts.get(playerId)) || seen.contains(playerId)) {
                // 已判不可用；或同一玩家的第二份（组内只留一份）
                continue;
            }
            Long rating = knownRating(idx);
            if (rated && rating != null && !Tolerance.within(rating, anchorRating, tol)) {
                continue;
            }
            seen.add(playerId);
            // 分读不出来的（镜像缺分且没有票）按缺省分排序——它过不了校验，排到哪里都只是被摘掉
            candidates.add(new Candidate(idx, Tolerance.distance(rating == null ? RatingReader.DEFAULT_CENTI : rating, anchorRating)));
        }
        if (rated) {
            // List.sort 是稳定排序：分差相同的保持快照下标升序（等得久的在前）
            candidates.sort(Comparator.comparingLong(Candidate::distance));
        }
        List<Long> members = new ArrayList<>(required);
        members.add(anchorId);
        for (Candidate candidate : candidates) {
            if (members.size() >= required) {
                break;
            }
            if (validate(candidate.idx()) != null) {
                members.add(entries.get(candidate.idx()).playerId());
            }
        }
        return members.size() < required ? null : members;
    }

    /** 候选的评分：镜像里有就用镜像分，没有就用票里的；两者都没有为 null。 */
    private Long knownRating(int idx) {
        SnapshotEntry entry = entries.get(idx);
        if (entry.ratingCenti().isPresent()) {
            return entry.ratingCenti().getAsLong();
        }
        Ticket ticket = tickets.get(entry.playerId());
        return ticket == null ? null : ticket.ratingCenti();
    }

    /**
     * 校验一个快照成员并记住结论。
     *
     * @return 可用成员的票据；不可用为 null
     */
    private Ticket validate(int idx) {
        SnapshotEntry entry = entries.get(idx);
        long playerId = entry.playerId();
        if (playerId == 0) {
            if (evictedMalformed.add(entry.member())) {
                gate.evict(entry);
            }
            return null;
        }
        Boolean known = verdicts.get(playerId);
        if (known != null) {
            return known ? tickets.get(playerId) : null;
        }
        Ticket ticket = tickets.get(playerId);
        boolean usable;
        if (ticket == null || ticket.state() != TicketState.QUEUED || !queueKey.equals(ticket.queueKey())) {
            // 已取消 / 已被别的入口带走 / 这一项是别的队列留下的残项：摘掉队列项，票不动
            gate.evict(entry);
            usable = false;
        } else if (ticket.backingOff(nowMs)) {
            // 无肇事者的 gather 失败后的退避还没到点：本轮既不当锚点也不当候选，原位保留
            usable = false;
        } else {
            usable = gate.admit(playerId, ticket);
            if (usable && entry.ratingCenti().isEmpty()) {
                gate.missingScore(playerId);
            }
        }
        verdicts.put(playerId, usable);
        return usable ? ticket : null;
    }

    /** 已等的整秒数：快照时刻不晚于入队时刻（时钟回拨 / 人为改数据）时为 0。 */
    static long waitSeconds(long nowMs, long enqueuedAtMs) {
        return enqueuedAtMs > 0 && nowMs > enqueuedAtMs ? (nowMs - enqueuedAtMs) / 1000 : 0;
    }

    /** 一个候选：快照下标与它同锚点的分差。 */
    private record Candidate(int idx, long distance) {
    }
}
