package com.game.discovery;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 滚动重启 / 缩容期间的部署代次识别与新一代优先选择器（对齐无感重启收尾批）：
 * <ul>
 *   <li>{@link #composeBuildId(String, String)} 把镜像构建标识与工作负载修订（Deployment 的 {@code pod-template-hash} /
 *       StatefulSet 的 {@code controller-revision-hash}）拼成单一部署代次，使同镜像仅改配置的滚动重启也能分出新旧；</li>
 *   <li>凡是有节点正在排空/疏散的构建均视为「外出构建」（outgoing build）；</li>
 *   <li>在合格候选中优先挑选不属于外出构建的节点，一个都没有时再回退到全部合格候选。</li>
 * </ul>
 */
public final class BuildGenerationSelector {

    private BuildGenerationSelector() {
    }

    /** 组合构建标识与工作负载修订（任意一侧为空时返回非空侧，两者均空返回空串）。 */
    public static String composeBuildId(String imageBuild, String workloadRevision) {
        String img = imageBuild == null ? "" : imageBuild.trim();
        String rev = workloadRevision == null ? "" : workloadRevision.trim();
        if (img.isEmpty()) {
            return rev;
        }
        if (rev.isEmpty()) {
            return img;
        }
        return img + "@" + rev;
    }

    /** 收集所有正在排空/疏散节点的非空部署代次作为外出构建集合。 */
    public static <T> Set<String> outgoingBuilds(Collection<T> allNodes, Predicate<T> draining, Function<T, String> buildIdOf) {
        Set<String> outgoing = new HashSet<>();
        for (T node : allNodes) {
            if (!draining.test(node)) {
                continue;
            }
            String build = buildIdOf.apply(node);
            if (build != null && !build.isBlank()) {
                outgoing.add(build.trim());
            }
        }
        return outgoing;
    }

    /** 在合格候选中优先返回不属于外出构建的子集；若无新一代候选则回退全部合格候选。 */
    public static <T> List<T> preferNonOutgoing(List<T> healthyCandidates, Set<String> outgoingBuilds,
                                                Function<T, String> buildIdOf) {
        if (healthyCandidates.isEmpty() || outgoingBuilds.isEmpty()) {
            return healthyCandidates;
        }
        List<T> preferred = healthyCandidates.stream()
                .filter(n -> {
                    String b = buildIdOf.apply(n);
                    return b == null || b.isBlank() || !outgoingBuilds.contains(b.trim());
                })
                .toList();
        return preferred.isEmpty() ? healthyCandidates : preferred;
    }
}
