package com.game.guild.activity;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class GuildActivityResultsTest {
    @Test
    void SQL未知不确认位点且恢复重试原记录后才确认() throws Exception {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var partition = new TopicPartition("fixture", 0);
        var attempts = new AtomicInteger();
        var committed = new CountDownLatch(1);
        var failed = new CountDownLatch(1);
        consumer.schedulePollTask(() -> {
            consumer.rebalance(List.of(partition));
            consumer.updateBeginningOffsets(Map.of(partition, 0L));
            consumer.addRecord(new ConsumerRecord<>("fixture", 0, 0, "7", new byte[] {1}));
        });
        var loop = new GuildActivityResults(consumer, "fixture", record -> {
            assertEquals(0, record.offset());
            if (attempts.incrementAndGet() == 1)
                throw new java.sql.SQLException("未知提交");
            committed.countDown();
        }, failure -> failed.countDown());
        var executor = Executors.newSingleThreadExecutor();
        Future<?> task = executor.submit(loop);
        try {
            assertTrue(failed.await(2, TimeUnit.SECONDS));
            assertNull(consumer.committed(Set.of(partition)).get(partition));
            assertTrue(committed.await(3, TimeUnit.SECONDS));
            long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (consumer.committed(Set.of(partition)).get(partition) == null && System.nanoTime() < until) Thread.onSpinWait();
            assertEquals(1, consumer.committed(Set.of(partition)).get(partition).offset());
            assertEquals(2, attempts.get());
        } finally {
            loop.stop();
            task.get(3, TimeUnit.SECONDS);
            executor.shutdownNow();
            consumer.close();
        }
    }
    @Test
    void 暂停重试时撤走分区不再处理旧记录且停止可唤醒() throws Exception {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var partition = new TopicPartition("fixture", 0);
        var attempts = new AtomicInteger();
        var failed = new CountDownLatch(1);
        var revoked = new CountDownLatch(1);
        consumer.schedulePollTask(() -> {
            consumer.rebalance(List.of(partition));
            consumer.updateBeginningOffsets(Map.of(partition, 0L));
            consumer.addRecord(new ConsumerRecord<>("fixture", 0, 0, "7", new byte[] {1}));
        });
        var loop = new GuildActivityResults(consumer, "fixture", record -> {
            attempts.incrementAndGet();
            consumer.schedulePollTask(() -> {
                consumer.rebalance(List.of());
                revoked.countDown();
            });
            throw new java.sql.SQLException("依赖断开");
        }, failure -> failed.countDown());
        var executor = Executors.newSingleThreadExecutor();
        Future<?> task = executor.submit(loop);
        try {
            assertTrue(failed.await(2, TimeUnit.SECONDS));
            assertTrue(revoked.await(2, TimeUnit.SECONDS));
            assertEquals(1, attempts.get());
            assertNull(consumer.committed(Set.of(partition)).get(partition));
        } finally {
            loop.stop();
            task.get(3, TimeUnit.SECONDS);
            executor.shutdownNow();
            consumer.close();
        }
    }
}
