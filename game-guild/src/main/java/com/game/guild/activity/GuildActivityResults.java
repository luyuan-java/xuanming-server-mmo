package com.game.guild.activity;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

/** 独立结果消费接缝：处理提交后才确认位点，依赖失败暂停并重放同一条，撤分区不带走旧任务。 */
public final class GuildActivityResults implements Runnable {
    @FunctionalInterface
    public interface Handler {
        void accept(ConsumerRecord<String, byte[]> record) throws Exception;
    }
    private final org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer;
    private final String topic;
    private final Handler handler;
    private final Consumer<Exception> failed;
    private volatile boolean running = true;
    public GuildActivityResults(org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer, String topic, Handler handler,
            Consumer<Exception> failed) {
        this.consumer = consumer;
        this.topic = topic;
        this.handler = handler;
        this.failed = failed;
    }
    public void stop() {
        running = false;
        consumer.wakeup();
    }
    @Override
    public void run() {
        var pending = new ArrayDeque<ConsumerRecord<String, byte[]>>();
        boolean[] paused = {false};
        consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
            public void onPartitionsRevoked(Collection<TopicPartition> revoked) {
                pending.removeIf(r -> revoked.contains(new TopicPartition(r.topic(), r.partition())));
            }
            public void onPartitionsAssigned(Collection<TopicPartition> assigned) {
                if (paused[0])
                    consumer.pause(assigned);
            }
        });
        try {
            while (running) {
                if (pending.isEmpty()) {
                    paused[0] = false;
                    consumer.resume(consumer.assignment());
                    consumer.poll(Duration.ofMillis(500)).forEach(pending::add);
                    continue;
                }
                var record = pending.getFirst();
                var partition = new TopicPartition(record.topic(), record.partition());
                if (!consumer.assignment().contains(partition)) {
                    pending.removeFirst();
                    continue;
                }
                try {
                    handler.accept(record);
                    consumer.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)), Duration.ofSeconds(3));
                    pending.removeFirst();
                } catch (WakeupException stop) {
                    throw stop;
                } catch (Exception failure) {
                    failed.accept(failure);
                    paused[0] = true;
                    consumer.pause(consumer.assignment());
                    long until = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                    while (running && System.nanoTime() < until) consumer.poll(Duration.ofMillis(200)).forEach(pending::add);
                }
            }
        } catch (WakeupException stop) {
            if (running)
                throw stop;
        }
    }
}
