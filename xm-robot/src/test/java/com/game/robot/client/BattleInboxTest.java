package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** battle 直连的收件箱与记录：三种信封形状、标签、关闭标记只记一次且之后不再记帧、按序号等待。 */
class BattleInboxTest {

    private static MessageContent push(int messageId) {
        return MessageContent.newBuilder().setMessageId(messageId).setSerializedMessage(ByteString.copyFromUtf8("x")).build();
    }

    private static MessageContent reply(int messageId, long id) {
        return MessageContent.newBuilder().setMessageId(messageId).setId(id).build();
    }

    private static MessageContent envelopeError(int messageId, long id, int tip) {
        return MessageContent.newBuilder().setMessageId(messageId).setId(id).setErrorMessage(TipInfoMessage.newBuilder().setId(tip)).build();
    }

    @Test
    void 三种信封形状_推送_应答_信封错误_标签一眼可读() {
        BattleInbox inbox = new BattleInbox();
        BattleFrame p = inbox.addContent(push(139), 1);
        BattleFrame r = inbox.addContent(reply(140, 7), 2);
        BattleFrame e = inbox.addContent(envelopeError(157, 8, 1005), 3);
        BattleFrame ok = inbox.addVerify(BattleTokenVerifyResponse.newBuilder().setSuccess(true).setBattleId(-1L).build(), 4);
        BattleFrame fail = inbox.addVerify(BattleTokenVerifyResponse.newBuilder().setError("invalid ticket signature").build(), 5);

        assertThat(p.isPush()).isTrue();
        assertThat(p.isPush(139)).isTrue();
        assertThat(p.isReply()).isFalse();
        assertThat(p.label()).isEqualTo("push:139");
        assertThat(r.isReplyTo(140, 7)).isTrue();
        assertThat(r.isReplyTo(140, 8)).isFalse();
        assertThat(r.label()).isEqualTo("reply:140");
        assertThat(e.isEnvelopeError()).isTrue();
        assertThat(e.isReply()).as("信封错误不算应答形状").isFalse();
        assertThat(e.envelopeTipId()).isEqualTo(1005);
        assertThat(e.label()).isEqualTo("error:157:1005");
        assertThat(ok.label()).as("battle_id 按无符号打印").isEqualTo("verify-ok:18446744073709551615");
        assertThat(fail.label()).isEqualTo("verify-fail:invalid ticket signature");
        assertThat(ok.messageId()).isEqualTo(-1);
        assertThat(ok.requestId()).isEqualTo(-1);
        assertThat(inbox.labelsSince(1)).isEqualTo("[reply:140, error:157:1005, verify-ok:18446744073709551615, "
                + "verify-fail:invalid ticket signature]");
    }

    @Test
    void 关闭标记只记第一次_之后的帧不再记账() {
        BattleInbox inbox = new BattleInbox();
        inbox.addContent(push(150), 1);
        inbox.markClosed(BattleFrame.FIN, 2);
        inbox.markClosed(BattleFrame.RESET, 3);
        inbox.addContent(push(139), 4);

        assertThat(inbox.closedBy()).isEqualTo(BattleFrame.FIN);
        assertThat(inbox.snapshot(0)).extracting(BattleFrame::label).containsExactly("push:150", "closed:fin");
        assertThat(inbox.size()).isEqualTo(2);
    }

    @Test
    void 记录必须恰好是三种之一() {
        assertThatThrownBy(() -> new BattleFrame(0, 0, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BattleFrame(0, 0, BattleTokenVerifyResponse.getDefaultInstance(), push(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 等待_从序号起找_关闭后不再等_超时为空() throws Exception {
        BattleInbox inbox = new BattleInbox();
        inbox.addContent(push(139), 1);
        assertThat(inbox.await(1, f -> f.isPush(139), Duration.ofMillis(20))).as("序号之前的不算").isEmpty();

        CompletableFuture<Optional<BattleFrame>> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return inbox.await(1, f -> f.isPush(150), Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(50);
        inbox.addContent(push(150), 2);
        Optional<BattleFrame> got = waiting.get(5, TimeUnit.SECONDS);
        assertThat(got).isPresent();
        assertThat(got.get().index()).isEqualTo(1);

        inbox.markClosed(BattleFrame.FIN, 3);
        long start = System.nanoTime();
        assertThat(inbox.await(0, f -> f.isPush(166), Duration.ofSeconds(5))).as("已关闭：不再等").isEmpty();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1000);
        Optional<List<BattleFrame>> closed = inbox.awaitClosed(1, Duration.ofSeconds(1));
        assertThat(closed).isPresent();
        assertThat(closed.get()).extracting(BattleFrame::label).containsExactly("push:150", "closed:fin");
    }

    @Test
    void 解析信封体_不是信封时抛出_parseOrNull返回空() throws Exception {
        BattleInbox inbox = new BattleInbox();
        BattleFrame tip = inbox.addContent(MessageContent.newBuilder().setMessageId(23)
                .setSerializedMessage(TipInfoMessage.newBuilder().setId(1003).build().toByteString()).build(), 1);
        assertThat(tip.parse(TipInfoMessage.parser()).getId()).isEqualTo(1003);
        BattleFrame verify = inbox.addVerify(BattleTokenVerifyResponse.getDefaultInstance(), 2);
        assertThatThrownBy(() -> verify.parse(TipInfoMessage.parser())).isInstanceOf(RobotException.class);
        assertThat(verify.parseOrNull(TipInfoMessage.parser())).isNull();
    }
}
