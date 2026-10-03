package com.game.audit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动期核对审计 topic：不存在就按规格创建（谁先启动谁建），存在就核对分区数，不符抛 {@link AuditTopicContractException}。
 * {@link Mode#OWN}（消费方）还会把保留策略等 topic 配置校正到规格并读回确认。连不上 broker 抛 {@link AuditBrokerUnavailableException}。
 *
 * <p>broker 若开着自动建 topic，生产方在核对通过之前不得发送（自动建出来的是默认分区数）——见 scene 的审计管线。
 */
public final class AuditTopicInitializer {

    private static final Logger log = LoggerFactory.getLogger(AuditTopicInitializer.class);
    /** 刚创建的 topic 在元数据里可见之前的轮询间隔。 */
    private static final long VISIBLE_POLL_MILLIS = 200;

    /** 核对方式。 */
    public enum Mode {
        /** 生产方：缺就建、核对分区数。 */
        CREATE_AND_VERIFY,
        /** 消费方（topic 的主人）：另外把 topic 配置校正到规格。 */
        OWN
    }

    private AuditTopicInitializer() {
    }

    public static void ensure(TopicAdmin admin, List<TopicSpec> specs, Mode mode, short replicationFactor,
                              Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<String> names = specs.stream().map(TopicSpec::name).toList();
        Map<String, Integer> existing = admin.partitionCounts(names, remaining(deadline));
        List<TopicSpec> missing = new ArrayList<>();
        for (TopicSpec spec : specs) {
            if (!existing.containsKey(spec.name())) {
                missing.add(spec);
            }
        }
        if (!missing.isEmpty()) {
            log.info("创建审计 topic {}", missing);
            admin.create(missing, replicationFactor, remaining(deadline));
            existing = awaitVisible(admin, names, deadline);
        }
        for (TopicSpec spec : specs) {
            int actual = existing.get(spec.name());
            if (actual != spec.partitions()) {
                throw new AuditTopicContractException("审计 topic " + spec.name() + " 分区数是 " + actual + "，契约是 "
                        + spec.partitions() + "；不能原地改分区数，请升 topic 代次（XM_AUDIT_TOPIC_GENERATION）");
            }
        }
        if (mode == Mode.OWN) {
            for (TopicSpec spec : specs) {
                enforceConfigs(admin, spec, deadline);
            }
        }
        log.info("审计 topic 核对通过 {}", names);
    }

    private static Map<String, Integer> awaitVisible(TopicAdmin admin, List<String> names, long deadline) {
        while (true) {
            Map<String, Integer> found = admin.partitionCounts(names, remaining(deadline));
            if (found.keySet().containsAll(names)) {
                return found;
            }
            if (System.nanoTime() >= deadline) {
                throw new AuditBrokerUnavailableException("创建的审计 topic 在时限内仍不可见: " + names, null);
            }
            try {
                Thread.sleep(VISIBLE_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuditBrokerUnavailableException("等待审计 topic 可见时被中断", e);
            }
        }
    }

    private static void enforceConfigs(TopicAdmin admin, TopicSpec spec, long deadline) {
        Map<String, String> current = admin.configs(spec.name(), spec.configs().keySet(), remaining(deadline));
        Map<String, String> differing = differing(spec.configs(), current);
        if (differing.isEmpty()) {
            return;
        }
        log.info("校正审计 topic {} 的配置 {}", spec.name(), differing);
        admin.alterConfigs(spec.name(), differing, remaining(deadline));
        Map<String, String> after = differing(spec.configs(),
                admin.configs(spec.name(), spec.configs().keySet(), remaining(deadline)));
        if (!after.isEmpty()) {
            throw new AuditTopicContractException("审计 topic " + spec.name() + " 的配置改不过来: " + after);
        }
    }

    private static Map<String, String> differing(Map<String, String> wanted, Map<String, String> current) {
        Map<String, String> out = new HashMap<>();
        wanted.forEach((key, value) -> {
            if (!value.equals(current.get(key))) {
                out.put(key, value);
            }
        });
        return out;
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
    }
}
