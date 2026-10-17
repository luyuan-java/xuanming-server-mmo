package com.game.mail;

import com.game.discovery.presence.PlayerPushes;
import com.game.proto.MessageContent;
import com.game.proto.mail.MailChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SQL提交后的定向提醒；沿既有玩家在线目录/实例围栏推送，只使客户端重拉。 */
public final class MailNotifications {
    private static final Logger LOG = LoggerFactory.getLogger(MailNotifications.class);
    private final PlayerPushes pushes;
    private final int messageId;
    private final MeterRegistry metrics;
    public MailNotifications(PlayerPushes pushes, int messageId, MeterRegistry metrics) {
        this.pushes = pushes;
        this.messageId = messageId;
        this.metrics = metrics;
        for (String result : java.util.List.of("sent", "offline", "error"))
            metrics.counter("game_mail_notifications_total", "outcome", result);
    }
    public void personal(long player, long mail) {
        try {
            var content = MessageContent.newBuilder()
                                  .setMessageId(messageId)
                                  .setSerializedMessage(MailChangedEvent.newBuilder().setMailId(mail).build().toByteString())
                                  .build();
            pushes.pushToPlayer(player, content)
                    .toCompletableFuture()
                    .orTimeout(500, TimeUnit.MILLISECONDS)
                    .whenComplete((result, error) -> {
                        String label = error != null                     ? "error"
                                : result == PlayerPushes.Outcome.OFFLINE ? "offline"
                                : result == PlayerPushes.Outcome.SENT    ? "sent"
                                                                         : "error";
                        metrics.counter("game_mail_notifications_total", "outcome", label).increment();
                        if (error != null)
                            LOG.warn("邮件提醒未确认，权威邮件已保留 failure={}", error.getClass().getSimpleName());
                    });
        } catch (RuntimeException failure) {
            metrics.counter("game_mail_notifications_total", "outcome", "error").increment();
            LOG.warn("邮件提醒未发布，权威邮件已保留 failure={}", failure.getClass().getSimpleName());
        }
    }
}
