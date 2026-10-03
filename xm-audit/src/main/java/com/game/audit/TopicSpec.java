package com.game.audit;

import java.util.Map;

/**
 * 一个 topic 的规格。
 *
 * @param partitions 分区数（契约：生产方与消费方启动时核对）
 * @param configs    topic 级配置（创建时带上；消费方还会把已有 topic 校正到这里）
 */
public record TopicSpec(String name, int partitions, Map<String, String> configs) {

    public TopicSpec {
        if (name == null || name.isBlank() || partitions < 1) {
            throw new IllegalArgumentException("非法 topic 规格: " + name + " partitions=" + partitions);
        }
        configs = Map.copyOf(configs);
    }
}
