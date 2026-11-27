package com.game.common.routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

/**
 * 后端连接确定性子集（Finagle / 主仓 {@code docs/design/connection-fanout-subsetting-20261106.md} §3.3 确定性 aperture）：
 * <ul>
 *   <li>同一发布轨道的消费端（如 gate）按实例身份排序得名次 {@code rank ∈ [0, P)}；</li>
 *   <li>目标提供者按实例身份排序成环，切成 {@code P × T} 格，每台目标占 {@code P} 格；</li>
 *   <li>第 {@code rank} 台消费端占弧 {@code [rank × T, (rank + m) × T)}（绕回），其中 {@code m = max(1, round(k × P / T))}；</li>
 *   <li>环上每一格恰被 {@code m} 台消费端覆盖：每台提供者分得的流量完全一致且绝无遗漏；当 {@code T > P × k} 时自动扩大子集。</li>
 * </ul>
 */
public final class DeterministicApertureSubset {

    /** 缺省子集大小（与主仓 {@code GlobalTargetSubsetSize = 4} 同口径）。 */
    public static final int DEFAULT_SUBSET_SIZE = 4;

    private DeterministicApertureSubset() {
    }

    /** 校验子集大小配置：0 表示关闭子集，否则必须 ≥ 2（1 台在单点重启时会全灭）。 */
    public static int validateSubsetSize(int subsetSize) {
        if (subsetSize != 0 && subsetSize < 2) {
            throw new IllegalArgumentException("连接子集大小只允许 0（关闭）或 ≥ 2，当前=" + subsetSize);
        }
        return subsetSize;
    }

    /** 同轨道消费端排名（自动包含自身并去重排序）。 */
    public record PeerRank(int rank, int peers) {
    }

    /** 计算本实例在同轨道消费端集合里的 0 起名次与总实例数（即使目录尚未看到自己也计入）。 */
    public static PeerRank peerRank(Collection<String> sameTrackPeerIds, String selfId) {
        Objects.requireNonNull(selfId, "selfId");
        Set<String> sorted = new TreeSet<>();
        if (!selfId.isBlank()) {
            sorted.add(selfId);
        }
        if (sameTrackPeerIds != null) {
            for (String peer : sameTrackPeerIds) {
                if (peer != null && !peer.isBlank()) {
                    sorted.add(peer);
                }
            }
        }
        if (sorted.isEmpty()) {
            return new PeerRank(-1, 0);
        }
        List<String> list = new ArrayList<>(sorted);
        int idx = Collections.binarySearch(list, selfId);
        return new PeerRank(idx, list.size());
    }

    /** 整数网格弧：全选时 {@code cellsPerTarget = 1, start = 0, width = targets}。 */
    public record Aperture(int cellsPerTarget, int cells, int start, int width) {

        /**
         * 计算弧参数。以下情形退回全选：{@code size <= 0}、{@code peers <= 1}、{@code rank} 越界、
         * {@code targets <= size}、或 {@code m >= peers}。
         */
        public static Aperture of(int rank, int peers, int targets, int subsetSize) {
            if (targets <= 0) {
                return new Aperture(1, 0, 0, 0);
            }
            Aperture all = new Aperture(1, targets, 0, targets);
            if (subsetSize <= 0 || peers <= 1 || rank < 0 || rank >= peers || targets <= subsetSize) {
                return all;
            }
            long mLong = (2L * subsetSize * peers + targets) / (2L * targets);
            int m = (int) Math.max(1L, mLong);
            if (m >= peers) {
                return all;
            }
            return new Aperture(peers, peers * targets, rank * targets, m * targets);
        }

        /** 按弧上均匀取一格返回目标下标（与主仓 {@code aperture.pick} 逐行同义）。 */
        public int pick(IntUnaryOperator intn) {
            if (width <= 0 || cells <= 0 || cellsPerTarget <= 0) {
                throw new IllegalStateException("空弧无法选点");
            }
            int cell = (start + intn.applyAsInt(width)) % cells;
            return cell / cellsPerTarget;
        }

        /** 返回弧覆盖的全部去重目标下标（按弧顺序）。 */
        public List<Integer> targetIndices() {
            if (width <= 0 || cells <= 0 || cellsPerTarget <= 0) {
                return List.of();
            }
            List<Integer> out = new ArrayList<>();
            int first = -1;
            int end = start + width;
            for (int c = start; c < end; ) {
                int idx = (c % cells) / cellsPerTarget;
                if (idx == first) {
                    break;
                }
                if (first < 0) {
                    first = idx;
                }
                out.add(idx);
                c = (c / cellsPerTarget + 1) * cellsPerTarget;
            }
            return out;
        }
    }

    /** 将候选目标按稳定实例标识排序成环，并按本实例弧选出子集列表。 */
    public static <T> List<T> selectSubset(String selfId,
                                           Collection<String> sameTrackPeerIds,
                                           List<T> targets,
                                           Function<T, String> idExtractor,
                                           int subsetSize) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(idExtractor, "idExtractor");
        if (targets.isEmpty()) {
            return List.of();
        }
        List<T> ring = new ArrayList<>(targets);
        ring.sort(Comparator.comparing(t -> {
            String id = idExtractor.apply(t);
            return id == null ? "" : id;
        }));
        PeerRank pr = peerRank(sameTrackPeerIds, selfId);
        Aperture aperture = Aperture.of(pr.rank(), pr.peers(), ring.size(), subsetSize);
        List<Integer> indices = aperture.targetIndices();
        List<T> selected = new ArrayList<>(indices.size());
        for (int idx : indices) {
            selected.add(ring.get(idx));
        }
        return selected;
    }
}
