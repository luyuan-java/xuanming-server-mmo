package com.game.audit;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 审计 topic 的持久性门禁；真实副本不足绝不自动重分配，只有主人可提高配置下限。 */
final class AuditTopicDurability {
    static final String MIN_ISR = "min.insync.replicas";
    static final String UNCLEAN_ELECTION = "unclean.leader.election.enable";

    private AuditTopicDurability() {}

    static void ensure(TopicAdmin admin, List<TopicSpec> specs, AuditTopicInitializer.Mode mode,
                       short replicationFactor, Duration timeout) {
        if (replicationFactor < 1) throw new IllegalArgumentException("replicationFactor 必须至少为 1");
        for (TopicSpec spec : specs) {
            int minIsr = requiredMinIsr(spec);
            if (minIsr > replicationFactor) throw new IllegalArgumentException("minISR 不能超过配置副本数");
        }
        // 历史单 broker 接口不引入新的查询；生产装配已拒绝这组弱配置。
        if (replicationFactor == 1 && specs.stream().noneMatch(s -> s.configs().containsKey(MIN_ISR))) {
            AuditTopicInitializer.ensureLabeled(admin, specs, mode, replicationFactor, timeout,
                    "审计 topic", AuditTopicInitializer.AUDIT_GENERATION_ENV);
            return;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        // 主人先核对真实副本，再修改配置；否则弱副本 topic 会被先改成无法写入的 minISR。
        AuditTopicInitializer.ensureLabeled(admin, specs,
                mode == AuditTopicInitializer.Mode.OWN ? AuditTopicInitializer.Mode.CREATE_AND_VERIFY : mode,
                replicationFactor, remaining(deadline), "审计 topic", AuditTopicInitializer.AUDIT_GENERATION_ENV);
        List<String> names = specs.stream().map(TopicSpec::name).toList();
        Map<String, Integer> replicas = admin.minimumReplicaCounts(names, remaining(deadline));
        for (TopicSpec spec : specs) {
            if (replicas.getOrDefault(spec.name(), 0) < replicationFactor) {
                throw new AuditTopicContractException("审计 topic " + spec.name() + " 实际分区最小副本数不足 "
                        + replicationFactor + "；须人工迁移副本，禁止降级或从期望配置推断");
            }
        }
        for (TopicSpec spec : specs) {
            Map<String, String> wanted = new HashMap<>(spec.configs());
            wanted.putIfAbsent(MIN_ISR, Integer.toString(requiredMinIsr(spec)));
            Map<String, String> current = admin.configs(spec.name(), wanted.keySet(), remaining(deadline));
            int currentMin = positiveInt(current.get(MIN_ISR));
            if (currentMin > replicas.get(spec.name())) {
                throw new AuditTopicContractException("审计 topic " + spec.name() + " 的 minISR 超过实际分区最小副本数");
            }
            if (mode == AuditTopicInitializer.Mode.OWN) {
                // 保留更强的已部署下限。保留期等其它契约配置仍精确校正。
                if (currentMin > requiredMinIsr(spec)) wanted.put(MIN_ISR, Integer.toString(currentMin));
                Map<String, String> changes = new HashMap<>();
                for (var expected : wanted.entrySet()) {
                    if (!expected.getValue().equals(current.get(expected.getKey())))
                        changes.put(expected.getKey(), expected.getValue());
                }
                if (!changes.isEmpty()) admin.alterConfigs(spec.name(), changes, remaining(deadline));
                current = admin.configs(spec.name(), wanted.keySet(), remaining(deadline));
                for (var expected : wanted.entrySet()) {
                    if (expected.getKey().equals(MIN_ISR)) continue;
                    if (!expected.getValue().equals(current.get(expected.getKey())))
                        throw new AuditTopicContractException("审计 topic " + spec.name() + " 的配置改不过来: " + expected.getKey());
                }
            }
            int actualMin = positiveInt(current.get(MIN_ISR));
            int required = mode == AuditTopicInitializer.Mode.OWN ? Math.max(requiredMinIsr(spec), currentMin) : requiredMinIsr(spec);
            if (actualMin < required || actualMin > replicas.get(spec.name())
                    || spec.configs().containsKey(UNCLEAN_ELECTION) && !"false".equals(current.get(UNCLEAN_ELECTION))) {
                throw new AuditTopicContractException("审计 topic " + spec.name() + " 的 minISR/unclean leader 配置不满足持久性契约");
            }
        }
    }

    private static int requiredMinIsr(TopicSpec spec) {
        int min = positiveInt(spec.configs().getOrDefault(MIN_ISR, "1"));
        if (min == 0) throw new IllegalArgumentException("minISR 契约必须为正整数");
        return min;
    }

    private static int positiveInt(String value) {
        try { int result = Integer.parseInt(value); return result > 0 ? result : 0; }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
    }
}
