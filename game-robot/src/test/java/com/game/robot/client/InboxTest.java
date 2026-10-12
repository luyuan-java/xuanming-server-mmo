package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.MessageContent;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InboxTest {

    private static MessageContent msg(int messageId, long id) {
        return MessageContent.newBuilder().setMessageId(messageId).setId(id).build();
    }

    @Test
    void 按到达顺序编号_快照从指定序号起() {
        Inbox inbox = new Inbox();
        inbox.add(msg(79, 0), 1);
        inbox.add(msg(21, 0), 2);
        inbox.add(msg(48, 1), 3);
        assertThat(inbox.size()).isEqualTo(3);
        assertThat(inbox.snapshot(1)).extracting(Received::messageId).containsExactly(21, 48);
        assertThat(inbox.snapshot(1).get(0).index()).isEqualTo(1);
        assertThat(inbox.snapshot(9)).isEmpty();
    }

    @Test
    void 已有匹配立即返回_从序号起找() throws Exception {
        Inbox inbox = new Inbox();
        inbox.add(msg(66, 0), 1);
        inbox.add(msg(66, 0), 2);
        Optional<Received> found = inbox.await(1, r -> r.messageId() == 66, Duration.ZERO);
        assertThat(found).get().extracting(Received::index).isEqualTo(1);
    }

    @Test
    void 等到另一线程加入的匹配() throws Exception {
        Inbox inbox = new Inbox();
        CompletableFuture<Optional<Received>> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return inbox.await(0, r -> r.messageId() == 26 && r.requestId() == 3, Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(50);
        inbox.add(msg(26, 2), System.nanoTime());
        inbox.add(msg(26, 3), System.nanoTime());
        assertThat(waiting.get(5, TimeUnit.SECONDS)).get().extracting(Received::requestId).isEqualTo(3L);
    }

    @Test
    void 超时返回空() throws Exception {
        Inbox inbox = new Inbox();
        inbox.add(msg(21, 0), 1);
        long start = System.nanoTime();
        assertThat(inbox.await(0, r -> r.messageId() == 79, Duration.ofMillis(80))).isEmpty();
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(70));
    }

    @Test
    void 连接关闭唤醒等待者_只记第一个原因() throws Exception {
        Inbox inbox = new Inbox();
        CompletableFuture<Optional<Received>> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return inbox.await(0, r -> true, Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(50);
        inbox.markClosed("对端关闭");
        inbox.markClosed("第二个原因");
        assertThat(waiting.get(5, TimeUnit.SECONDS)).isEmpty();
        assertThat(inbox.closedReason()).isEqualTo("对端关闭");
    }

    @Test
    void 关闭前已到的匹配仍然返回() throws Exception {
        Inbox inbox = new Inbox();
        inbox.add(msg(51, 0), 1);
        inbox.markClosed("x");
        assertThat(inbox.await(0, r -> r.messageId() == 51, Duration.ofSeconds(1))).isPresent();
    }
}
