package com.game.common.release;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 灰度与金丝雀发布轨道（对齐主仓 {@code docs/design/release-track-gray-canary.md} §2）：
 * <ul>
 *   <li>只有 {@link #STABLE} 与 {@link #CANARY} 两条轨道，由进程环境变量 {@link #ENV_NAME} 注入（缺省 {@code stable}，其余值启动拒启）；</li>
 *   <li>客户端请求选点规则：{@code canary} 优先选 {@code canary}、无可用时退回 {@code stable}；{@code stable} 永不进 {@code canary}；</li>
 *   <li>节点未写轨道（空串/null）按 {@code stable} 算；无法识别的轨道值跳过并交由回调告警；</li>
 *   <li>全局作用域的后台工作（选主、消费组、租约、清理）与服务间调用仅由 {@code stable} 承担；</li>
 *   <li>战斗按整局：全部成员均来自 {@code canary} 会话才进 {@code canary} 战斗节点，否则进 {@code stable}。</li>
 * </ul>
 */
public enum ReleaseTrack {
    STABLE("stable"),
    CANARY("canary");

    /** 环境变量名（不设按 stable；非法值启动即抛异常）。 */
    public static final String ENV_NAME = "MMORPG_RELEASE_TRACK";
    /** Dubbo 调用附件键：会话发布轨道。 */
    public static final String ATTACHMENT_KEY = "x-release-track";
    /** Dubbo 提供者 URL 参数键：实例所属发布轨道。 */
    public static final String URL_PARAM_KEY = "release.track";

    private final String value;

    ReleaseTrack(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean isStable() {
        return this == STABLE;
    }

    public boolean isCanary() {
        return this == CANARY;
    }

    /** 全局作用域的后台工作（全局选主、Kafka 消费组、全服清理等）仅由 stable 实例承担。 */
    public boolean runsGlobalBackgroundWork() {
        return this == STABLE;
    }

    /** 从进程环境变量 {@value #ENV_NAME} 读取发布轨道；非法值直接抛 {@link IllegalArgumentException} 拒启。 */
    public static ReleaseTrack fromEnv() {
        return parse(System.getenv(ENV_NAME));
    }

    /** 解析进程配置/环境变量值：null 或空白按 {@link #STABLE}，仅接受 {@code stable}/{@code canary}（不区分大小写）。 */
    public static ReleaseTrack parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return STABLE;
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "stable" -> STABLE;
            case "canary" -> CANARY;
            default -> throw new IllegalArgumentException(
                    "非法发布轨道 " + ENV_NAME + "=\"" + raw + "\"，仅允许 stable 或 canary");
        };
    }

    /**
     * 解析服务发现/节点目录里的轨道字段：空串或 null 视为 {@link #STABLE}（兼容旧版本节点）；
     * 无法识别的值返回空，由调用方记录 ERROR 日志并跳过该节点。
     */
    public static Optional<ReleaseTrack> parseDiscoveryTag(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(STABLE);
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "stable" -> Optional.of(STABLE);
            case "canary" -> Optional.of(CANARY);
            default -> Optional.empty();
        };
    }

    /**
     * 客户端请求链路的统一选点过滤（§2.4）：
     * <ul>
     *   <li>{@code callerTrack == CANARY}：若存在 {@code canary} 候选则只返回 {@code canary}，否则退回 {@code stable}；</li>
     *   <li>{@code callerTrack == STABLE}：只返回 {@code stable}，绝不进入 {@code canary}；</li>
     *   <li>无法识别轨道的节点跳过并通知 {@code onUnknownTrack}。</li>
     * </ul>
     */
    public static <T> List<T> filterCandidates(ReleaseTrack callerTrack,
                                               Collection<T> nodes,
                                               Function<T, String> trackExtractor,
                                               Consumer<T> onUnknownTrack) {
        Objects.requireNonNull(callerTrack, "callerTrack");
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(trackExtractor, "trackExtractor");
        List<T> stable = new ArrayList<>();
        List<T> canary = new ArrayList<>();
        for (T node : nodes) {
            Optional<ReleaseTrack> parsed = parseDiscoveryTag(trackExtractor.apply(node));
            if (parsed.isEmpty()) {
                if (onUnknownTrack != null) {
                    onUnknownTrack.accept(node);
                }
                continue;
            }
            if (parsed.get() == CANARY) {
                canary.add(node);
            } else {
                stable.add(node);
            }
        }
        if (callerTrack == CANARY && !canary.isEmpty()) {
            return canary;
        }
        return stable;
    }

    /** 战斗按整局判轨（§2.6）：成员非空且全部为 {@link #CANARY} 才选 {@link #CANARY}，其余一律 {@link #STABLE}。 */
    public static ReleaseTrack battleTrackForMembers(Collection<ReleaseTrack> memberTracks) {
        if (memberTracks == null || memberTracks.isEmpty()) {
            return STABLE;
        }
        for (ReleaseTrack track : memberTracks) {
            if (track != CANARY) {
                return STABLE;
            }
        }
        return CANARY;
    }
}
