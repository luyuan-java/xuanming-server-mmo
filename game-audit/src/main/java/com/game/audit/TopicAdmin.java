package com.game.audit;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;

/**
 * topic 管理的窄接口（{@link KafkaTopicAdmin} 包 Kafka {@code Admin}）：启动期核对只需要这几样，单测用假实现即可。
 * 连不上 broker / 超时一律抛 {@link AuditBrokerUnavailableException}。
 */
public interface TopicAdmin extends AutoCloseable {

    /** 已存在的 topic → 分区数；不存在的不出现在结果里。 */
    Map<String, Integer> partitionCounts(Collection<String> topics, Duration timeout);
    /** 各 topic 所有分区的最小不同副本数；不能验证时必须失败关闭，禁止从期望配置推断。 */
    default Map<String,Integer> minimumReplicaCounts(Collection<String> topics,Duration timeout){
        throw new AuditTopicContractException("topic 管理端口不能验证实际副本数");
    }

    /** 按规格创建；并发下已被别人建好的视为成功（随后的核对会查分区数）。 */
    void create(Collection<TopicSpec> specs, short replicationFactor, Duration timeout);

    /** 读 topic 级配置的当前值（只取给定的键；没有的键不出现在结果里）。 */
    Map<String, String> configs(String topic, Collection<String> keys, Duration timeout);

    /** 把给定的 topic 级配置设成指定值（增量修改，不动其它键）。 */
    void alterConfigs(String topic, Map<String, String> configs, Duration timeout);

    @Override
    void close();
}
