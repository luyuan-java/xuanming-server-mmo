package com.game.audit;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

/** {@link TopicAdmin} 的 Kafka 实现。不是线程安全的使用约定：只在一个线程上用（启动 / 重试线程）。 */
public final class KafkaTopicAdmin implements TopicAdmin {

    private final Admin admin;

    public KafkaTopicAdmin(String bootstrapServers, String clientId) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.CLIENT_ID_CONFIG, clientId);
        // 单次请求的上限交给调用方的 timeout；这里只防客户端在后台无限重试
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "15000");
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        try {
            this.admin = Admin.create(props);
        } catch (KafkaException e) {
            // 地址解析不了（容器 / k8s 服务名尚未注册）等：与连不上同样当作可恢复，调用方稍后重试
            throw unavailable("创建 Kafka 管理客户端失败", e);
        }
    }

    @Override
    public Map<String, Integer> partitionCounts(Collection<String> topics, Duration timeout) {
        Map<String, KafkaFuture<TopicDescription>> futures = admin.describeTopics(topics).topicNameValues();
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, KafkaFuture<TopicDescription>> e : futures.entrySet()) {
            try {
                out.put(e.getKey(), e.getValue().get(timeout.toMillis(), TimeUnit.MILLISECONDS).partitions().size());
            } catch (ExecutionException ex) {
                if (!(ex.getCause() instanceof UnknownTopicOrPartitionException)) {
                    throw unavailable("查询 topic " + e.getKey() + " 失败", ex.getCause());
                }
            } catch (TimeoutException ex) {
                throw unavailable("查询 topic " + e.getKey() + " 超时", ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw unavailable("查询 topic 被中断", ex);
            }
        }
        return out;
    }

    @Override
    public void create(Collection<TopicSpec> specs, short replicationFactor, Duration timeout) {
        List<NewTopic> topics = specs.stream()
                .map(s -> new NewTopic(s.name(), s.partitions(), replicationFactor).configs(s.configs()))
                .toList();
        for (Map.Entry<String, KafkaFuture<Void>> e : admin.createTopics(topics).values().entrySet()) {
            try {
                e.getValue().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException ex) {
                if (!(ex.getCause() instanceof TopicExistsException)) {
                    throw unavailable("创建 topic " + e.getKey() + " 失败", ex.getCause());
                }
            } catch (TimeoutException ex) {
                throw unavailable("创建 topic " + e.getKey() + " 超时", ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw unavailable("创建 topic 被中断", ex);
            }
        }
    }

    @Override
    public Map<String, String> configs(String topic, Collection<String> keys, Duration timeout) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        try {
            Config config = admin.describeConfigs(List.of(resource)).all()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS).get(resource);
            Map<String, String> out = new HashMap<>();
            for (String key : keys) {
                ConfigEntry entry = config.get(key);
                if (entry != null && entry.value() != null) {
                    out.put(key, entry.value());
                }
            }
            return out;
        } catch (ExecutionException ex) {
            throw unavailable("读 topic " + topic + " 配置失败", ex.getCause());
        } catch (TimeoutException ex) {
            throw unavailable("读 topic " + topic + " 配置超时", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable("读 topic 配置被中断", ex);
        }
    }

    @Override
    public void alterConfigs(String topic, Map<String, String> configs, Duration timeout) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        List<AlterConfigOp> ops = configs.entrySet().stream()
                .map(e -> new AlterConfigOp(new ConfigEntry(e.getKey(), e.getValue()), AlterConfigOp.OpType.SET))
                .toList();
        try {
            admin.incrementalAlterConfigs(Map.of(resource, ops)).all().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException ex) {
            throw unavailable("改 topic " + topic + " 配置失败", ex.getCause());
        } catch (TimeoutException ex) {
            throw unavailable("改 topic " + topic + " 配置超时", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable("改 topic 配置被中断", ex);
        }
    }

    @Override
    public void close() {
        // 到这里每个调用不是已完成就是已按超时放弃：不再等它们，免得启动期核对失败后再多卡几秒
        admin.close(Duration.ZERO);
    }

    private static AuditBrokerUnavailableException unavailable(String message, Throwable cause) {
        return new AuditBrokerUnavailableException(message + ": " + cause, cause);
    }
}
