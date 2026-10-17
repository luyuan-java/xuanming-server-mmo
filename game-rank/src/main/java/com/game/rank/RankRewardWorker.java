package com.game.rank;

import com.game.api.DubboGroups;
import com.game.api.MailAdminService;
import com.game.common.deadline.Deadline;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.proto.mail.MailAttachments;
import com.game.proto.mail.SendMailRequest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** rank奖先可靠变成个人邮件，再由mail独占SYSTEM_CREDIT，不争用资产流号源。 */
public final class RankRewardWorker implements SmartLifecycle, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RankRewardWorker.class);
    @DubboReference(
            group = DubboGroups.MAIL, url = "${game.rank.mail-url:tri://127.0.0.1:20916}", check = false, retries = 0, timeout = 2000)
    private MailAdminService mail;
    private final RankStore store;
    private final String secret;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService workers;
    public RankRewardWorker(RankStore store, String secret) {
        this.store = store;
        if (secret == null || secret.strip().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("缺少MAIL_PRODUCER_SECRET_RANK");
        this.secret = secret;
    }
    /** 类型化传输接缝供确定性测试；生产引用由Dubbo按配置装配。 */
    RankRewardWorker(RankStore store, String secret, MailAdminService mail) {
        this(store, secret);
        this.mail = mail;
    }
    public synchronized void start() {
        if (!running.compareAndSet(false, true))
            return;
        workers = Executors.newScheduledThreadPool(4, Thread.ofPlatform().name("rank-mail-", 0).daemon(true).factory());
        for (int i = 0; i < 4; i++) workers.scheduleWithFixedDelay(this::tick, i * 100L, 1000, TimeUnit.MILLISECONDS);
    }
    private void tick() {
        if (!running.get())
            return;
        try {
            for (long id : store.due(Deadline.after(2000), System.currentTimeMillis())) {
                if (!running.get())
                    break;
                process(id);
            }
        } catch (Exception failure) {
            LOG.warn("榜奖投信失败，下轮按原奖励号恢复，类型={}", failure.getClass().getSimpleName());
        }
    }
    void process(long id) throws Exception {
        var reward =
                store.lease(Deadline.after(2000), id, System.currentTimeMillis(), ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        if (reward == null)
            return;
        long mailId = 0;
        try {
            var request = SendMailRequest.newBuilder()
                                  .setSenderKind(3)
                                  .setRequestId(reward.id())
                                  .setExpireMs(reward.created() + 30L * 86400000)
                                  .setRecipientPlayerId(reward.player())
                                  .setTitle("排行榜结算奖励")
                                  .setBody("本次结算名次：" + Long.toUnsignedString(reward.rank()))
                                  .setAttachments(MailAttachments.parseFrom(reward.attachments()))
                                  .build();
            var signed = ServiceCallSignatures.sign(MailAdminService.PERSONAL_METHOD, request, "rank", secret, System.currentTimeMillis());
            var response = mail.sendPersonalMail(signed).get(2200, TimeUnit.MILLISECONDS);
            if (response.getErrorMessage().getId() == 0)
                mailId = response.getMailId();
        } catch (Exception unknown) {
            LOG.warn("榜奖发信未确认，将保持原幂等键重投，类型={}", unknown.getClass().getSimpleName());
        }
        store.settle(Deadline.after(1500), reward, mailId);
    }
    public synchronized void stop() {
        running.set(false);
        if (workers != null) {
            workers.shutdown();
            try {
                if (!workers.awaitTermination(10, TimeUnit.SECONDS))
                    workers.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                workers.shutdownNow();
            }
            workers = null;
        }
    }
    public boolean isRunning() {
        return running.get();
    }
    public void close() {
        stop();
    }
}
