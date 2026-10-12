package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.contract.MessageIdRegistry;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleStartS2C;
import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.MessageContent;
import com.game.proto.SetAutoBattleResponse;
import com.game.proto.TipInfoMessage;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.MatchMode;
import com.game.robot.client.BattleFrame;
import com.game.robot.client.BattleIds;
import com.game.robot.client.BattleInbox;
import com.game.robot.client.Received;
import com.game.robot.scenario.MatchSupport.JoinAttempts;
import com.game.robot.scenario.MatchSupport.Tempo;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 匹配场景公共件里不连服务端就能钉住的部分：消息号、节奏与限频的关系、过渡态重试的判据、大厅公告与直连帧的识别、时刻折算。 */
class MatchSupportTest {

    private static final long BIG = 0x9000_0000_0000_0001L;

    private final MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
    private final MatchSupport.Ids ids = MatchSupport.Ids.resolve(registry);
    private final BattleIds battleIds = BattleIds.resolve(registry);

    // ---------------------------------------------------------------- 消息号与节奏

    @Test
    void match的十个号都从契约解析_互不相同_与规格的号表一致() {
        // 号由 mmorpg 的生成器发，这里钉的是「同步来的契约此刻的取值」与 match-spec §1.4 的表一致；契约变了这条先红，提醒核对规格
        assertThat(List.of(ids.joinQueue(), ids.cancelQueue(), ids.queueStatus(), ids.challenge(), ids.respondChallenge(), ids.challengeInvite(),
                ids.challengeResult(), ids.watchBattle(), ids.listWatchable(), ids.requestTicket()))
                .containsExactly(157, 148, 153, 152, 151, 156, 154, 163, 164, 179).doesNotHaveDuplicates();
        assertThat(ids.joinQueue()).as("battle-edge 用同一个号测直连面的白名单").isEqualTo(battleIds.notWhitelisted());
    }

    @Test
    void 标准节奏让同一个号每秒不超过3条_与规格的节奏和时限一致() {
        Tempo standard = Tempo.STANDARD;
        // gate 对 match 的十个号都是缺省限频：每会话每号每秒 3 条。同一机器人任意 1 秒内最多 ceil(1000 / 间隔) 条
        long perSecond = (1000 + standard.requestSpacing().toMillis() - 1) / standard.requestSpacing().toMillis();
        assertThat(perSecond).isLessThanOrEqualTo(MatchSupport.SAME_ID_MAX_IN_WINDOW);
        assertThat(standard.requestSpacing()).as("规格：同号请求间隔 ≥ 350 ms").isGreaterThanOrEqualTo(Duration.ofMillis(350));
        assertThat(standard.requestSpacing().multipliedBy(MatchSupport.SAME_ID_MAX_IN_WINDOW)).as("机器人里同号滑动窗口的宽度")
                .isEqualTo(MatchSupport.SAME_ID_WINDOW).isGreaterThan(Duration.ofSeconds(1));
        assertThat(standard.retryInterval()).as("过渡态每 1 s 重试").isEqualTo(Duration.ofSeconds(1));
        assertThat(standard.settleTimeout()).as("过渡态上限 20 s").isEqualTo(Duration.ofSeconds(20)).isEqualTo(MatchSupport.SETTLE_TIMEOUT);
        assertThat(standard.battleStartTimeout()).as("等开战 30 s").isEqualTo(Duration.ofSeconds(30)).isEqualTo(MatchSupport.BATTLE_START_TIMEOUT);
        assertThat(MatchSupport.BATTLE_END_TIMEOUT).as("等终局 120 s").isEqualTo(Duration.ofSeconds(120));
        assertThat(standard.silence()).as("148 发出后 1 s 内无回包").isEqualTo(Duration.ofSeconds(1));
        assertThat(MatchSupport.RATING_WAIT).as("收到 150 后最多等 10 s 查评分").isEqualTo(Duration.ofSeconds(10));
        assertThat(standard.ratingPoll()).isLessThan(MatchSupport.RATING_WAIT);
    }

    @Test
    void 排队请求只带模式与副本_玩家号照真实客户端填自己() {
        JoinQueueRequest request = MatchSupport.joinRequest(BIG, MatchMode.MATCH_MODE_PVE_SOLO, 1);
        assertThat(request.getPlayerId()).isEqualTo(BIG);
        assertThat(request.getModeValue()).isEqualTo(4);
        assertThat(request.getBattleConfigId()).isEqualTo(1);
        assertThat(request.getZoneId()).isZero();
        assertThat(request.getMapConfigId()).isZero();
    }

    // ---------------------------------------------------------------- 过渡态重试

    @Test
    void 过渡态重试的判据_受理从不重试_只重试集合里的码_到期限即停() {
        Set<Integer> transientCodes = Set.of(16000);
        long deadline = 1_000_000;
        assertThat(MatchSupport.shouldRetry(16000, transientCodes, deadline - 1, deadline)).isTrue();
        assertThat(MatchSupport.shouldRetry(0, transientCodes, deadline - 1, deadline)).as("受理").isFalse();
        assertThat(MatchSupport.shouldRetry(16001, transientCodes, deadline - 1, deadline)).as("不是过渡态的拒绝立即返回").isFalse();
        assertThat(MatchSupport.shouldRetry(16000, transientCodes, deadline, deadline)).as("到期限").isFalse();
        assertThat(MatchSupport.shouldRetry(16000, transientCodes, deadline + 5, deadline)).isFalse();
        assertThat(MatchSupport.shouldRetry(16000, Set.of(), deadline - 1, deadline)).as("没有过渡态：一次定结果").isFalse();
        assertThat(MatchSupport.shouldRetry(0, Set.of(0), deadline - 1, deadline)).as("0 即使被放进集合也不重试").isFalse();
        // 单调时钟回绕：按差值比较
        assertThat(MatchSupport.shouldRetry(16000, transientCodes, Long.MAX_VALUE - 5, Long.MAX_VALUE + 10)).isTrue();
    }

    @Test
    void 排队尝试序列_受理与否看最后一次_出现过的码含之前重试掉的() {
        JoinQueueResponse accepted = JoinQueueResponse.newBuilder().setQueueTicket("3f2b8c1e-7a4d-4e0b-9c55-0a1b2c3d4e5f").build();
        JoinAttempts retried = new JoinAttempts(42, accepted, List.of(16000, 16000));
        assertThat(retried.accepted()).isTrue();
        assertThat(retried.mark()).isEqualTo(42);
        assertThat(retried.saw(16000)).isTrue();
        assertThat(retried.saw(16001)).isFalse();
        assertThat(retried.describe()).contains("error_code=0", "此前按过渡态重试 [16000, 16000]");

        JoinQueueResponse stale = JoinQueueResponse.newBuilder().setErrorCode(16001).setQueueTicket("old")
                .setErrorMessage(TipInfoMessage.newBuilder().setId(16001).addParameters("已在匹配队列中")).build();
        JoinAttempts rejected = new JoinAttempts(7, stale, List.of(16000));
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.saw(16001)).as("最后一次的码也算").isTrue();
        assertThat(new JoinAttempts(0, accepted, List.of()).describe()).doesNotContain("重试");
    }

    // ---------------------------------------------------------------- 大厅公告

    private static Received lobby(int index, int messageId, long requestId, Message body) {
        return new Received(index, 0, MessageContent.newBuilder().setMessageId(messageId).setId(requestId)
                .setSerializedMessage(body.toByteString()).build());
    }

    private static BattleAssignedS2C ticket(long battleId, eBattleTicketRole role) {
        return BattleAssignedS2C.newBuilder().setBattleId(battleId).setHost("127.0.0.1").setPort(12000).setRole(role).build();
    }

    @Test
    void 参战票177_推送形状_role是参战者_battle_id非0_才算() {
        BattleAssignedS2C participant = ticket(BIG, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        assertThat(MatchSupport.participantTicket(lobby(0, battleIds.battleAssigned(), 0, participant), battleIds)).isEqualTo(participant);

        assertThat(MatchSupport.participantTicket(lobby(0, battleIds.battleAssigned(), 0, ticket(BIG, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER)),
                battleIds)).as("观众票").isNull();
        assertThat(MatchSupport.participantTicket(lobby(0, battleIds.battleAssigned(), 9, participant), battleIds)).as("带请求号的不是推送").isNull();
        assertThat(MatchSupport.participantTicket(lobby(0, battleIds.battleStart(), 0, participant), battleIds)).as("别的消息号").isNull();
        assertThat(MatchSupport.participantTicket(lobby(0, battleIds.battleAssigned(), 0, ticket(0, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT)),
                battleIds)).as("battle_id 为 0").isNull();
        Received garbage = new Received(0, 0, MessageContent.newBuilder().setMessageId(battleIds.battleAssigned())
                .setSerializedMessage(ByteString.copyFrom(new byte[] {(byte) 0xFF, (byte) 0xFF})).build());
        assertThat(MatchSupport.participantTicket(garbage, battleIds)).as("体解析失败").isNull();
    }

    @Test
    void 开局143_只认这一局的() {
        BattleStartS2C start = BattleStartS2C.newBuilder().setBattleId(BIG).build();
        assertThat(MatchSupport.startOf(lobby(3, battleIds.battleStart(), 0, start), battleIds, BIG)).isEqualTo(start);
        assertThat(MatchSupport.startOf(lobby(3, battleIds.battleStart(), 0, start), battleIds, BIG + 1)).as("上一局的 143").isNull();
        assertThat(MatchSupport.startOf(lobby(3, battleIds.battleStart(), 5, start), battleIds, BIG)).isNull();
        assertThat(MatchSupport.startOf(lobby(3, battleIds.battleEnd(), 0, start), battleIds, BIG)).isNull();
    }

    @Test
    void 先177后143按收件箱下标判_不按解析出来的内容() {
        BattleAssignedS2C assigned = ticket(BIG, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        BattleStartS2C start = BattleStartS2C.newBuilder().setBattleId(BIG).build();
        MatchSupport.Started inOrder = new MatchSupport.Started(lobby(4, battleIds.battleAssigned(), 0, assigned),
                lobby(5, battleIds.battleStart(), 0, start), assigned, start);
        assertThat(inOrder.assignedFirst()).isTrue();
        assertThat(inOrder.battleId()).isEqualTo(BIG);
        MatchSupport.Started reversed = new MatchSupport.Started(lobby(5, battleIds.battleAssigned(), 0, assigned),
                lobby(4, battleIds.battleStart(), 0, start), assigned, start);
        assertThat(reversed.assignedFirst()).isFalse();
    }

    @Test
    void 下行到达的墙钟时刻_按单调时钟的差值折算() {
        long nowNanos = TimeUnit.SECONDS.toNanos(100);
        long nowMillis = 1_760_000_000_000L;
        assertThat(MatchSupport.wallClockMillis(nowNanos - TimeUnit.MILLISECONDS.toNanos(250), nowNanos, nowMillis)).isEqualTo(nowMillis - 250);
        assertThat(MatchSupport.wallClockMillis(nowNanos, nowNanos, nowMillis)).isEqualTo(nowMillis);
    }

    // ---------------------------------------------------------------- 直连帧

    private static MessageContent push(int messageId, Message body) {
        return MessageContent.newBuilder().setMessageId(messageId).setSerializedMessage(body.toByteString()).build();
    }

    @Test
    void 直连帧里找这一局的150_数139的条数() {
        BattleInbox inbox = new BattleInbox();
        inbox.addVerify(BattleTokenVerifyResponse.newBuilder().setSuccess(true).setBattleId(BIG).build(), 1);
        inbox.addContent(MessageContent.newBuilder().setMessageId(battleIds.getBattleState()).setId(1).build(), 2);
        inbox.addContent(push(battleIds.turnResult(), TurnResultS2C.newBuilder().setBattleId(BIG).setRoundIndex(1).build()), 3);
        inbox.addContent(push(battleIds.turnResult(), TurnResultS2C.newBuilder().setBattleId(BIG).setRoundIndex(2).build()), 4);
        // 别的局的 150（不该出现在这条直连上，出现了也不认）
        inbox.addContent(push(battleIds.battleEnd(), BattleEndS2C.newBuilder().setBattleId(BIG + 1).build()), 5);
        List<BattleFrame> before = inbox.snapshot(0);
        assertThat(MatchSupport.endOf(before, battleIds, BIG)).isNull();
        assertThat(MatchSupport.turnCount(before, battleIds)).isEqualTo(2);

        BattleEndS2C end = BattleEndS2C.newBuilder().setBattleId(BIG).setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).build();
        inbox.addContent(push(battleIds.battleEnd(), end), 6);
        inbox.markClosed(BattleFrame.FIN, 7);
        List<BattleFrame> all = inbox.snapshot(0);
        assertThat(MatchSupport.endOf(all, battleIds, BIG)).isEqualTo(end);
        assertThat(MatchSupport.turnCount(all, battleIds)).isEqualTo(2);
        // 150 的应答形状（带请求号）不是终局推送
        BattleInbox replyOnly = new BattleInbox();
        replyOnly.addContent(MessageContent.newBuilder().setMessageId(battleIds.battleEnd()).setId(3).setSerializedMessage(end.toByteString()).build(), 1);
        assertThat(MatchSupport.endOf(replyOnly.snapshot(0), battleIds, BIG)).isNull();
    }

    @Test
    void 收尾的关闭方式_只有FIN才算契约() {
        BattleEndS2C end = BattleEndS2C.newBuilder().setBattleId(1).build();
        assertThat(new MatchSupport.Finished(end, 3, BattleFrame.FIN, List.of(), null).fin()).isTrue();
        assertThat(new MatchSupport.Finished(end, 3, BattleFrame.RESET, List.of(), null).fin()).isFalse();
        assertThat(new MatchSupport.Finished(end, 3, null, List.of(), null).fin()).as("超时没关").isFalse();
        MatchSupport.Finished refused = new MatchSupport.Finished(end, 3, BattleFrame.FIN, List.of(), "162 的应答带 error_message 1005[]");
        assertThat(refused.autoAccepted()).isFalse();
        assertThat(refused.autoNote()).isEqualTo("；162 的应答带 error_message 1005[]");
        assertThat(new MatchSupport.Finished(end, 3, BattleFrame.FIN, List.of(), null).autoAccepted()).isTrue();
        assertThat(new MatchSupport.Finished(end, 3, BattleFrame.FIN, List.of(), null).autoNote()).isEmpty();
    }

    // ---------------------------------------------------------------- 162 的应答（评审 ROBOT-2）

    private MessageContent autoReply(long requestId, int tip) {
        SetAutoBattleResponse.Builder body = SetAutoBattleResponse.newBuilder();
        if (tip != 0) {
            body.setErrorMessage(TipInfoMessage.newBuilder().setId(tip).addParameters("战斗不存在"));
        }
        return MessageContent.newBuilder().setMessageId(battleIds.setAutoBattle()).setId(requestId).setSerializedMessage(body.build().toByteString())
                .build();
    }

    private MessageContent end(long battleId) {
        return push(battleIds.battleEnd(), BattleEndS2C.newBuilder().setBattleId(battleId).build());
    }

    private MessageContent turn(long battleId) {
        return push(battleIds.turnResult(), TurnResultS2C.newBuilder().setBattleId(battleId).build());
    }

    private static List<BattleFrame> frames(MessageContent... contents) {
        BattleInbox inbox = new BattleInbox();
        for (MessageContent content : contents) {
            inbox.addContent(content, 1);
        }
        inbox.markClosed(BattleFrame.FIN, 2);
        return inbox.snapshot(0);
    }

    @Test
    void 开挂机被受理_应答在150之前或之后都算_自己促成终局时应答排在150后面() {
        MatchSupport.AutoRequest auto = new MatchSupport.AutoRequest(9, 0);
        // 多人局里先开挂机的人：应答立刻回来，之后才打完
        assertThat(MatchSupport.autoProblem(frames(autoReply(9, 0), turn(BIG), end(BIG)), battleIds, BIG, auto)).isNull();
        // 最后一个开挂机的人（单人 PVE 必然是他）：处理器里先推 139 / 150，应答随后，FIN 最后
        assertThat(MatchSupport.autoProblem(frames(turn(BIG), end(BIG), autoReply(9, 0)), battleIds, BIG, auto)).isNull();
    }

    @Test
    void 开挂机被拒而这一局照样打到150_是问题_业务错误与信封错误都认_并写明这一局是靠超时打完的() {
        MatchSupport.AutoRequest auto = new MatchSupport.AutoRequest(9, 0);

        String business = MatchSupport.autoProblem(frames(autoReply(9, 1005), turn(BIG), end(BIG)), battleIds, BIG, auto);
        assertThat(business).contains("162 的应答带 error_message 1005", "战斗不存在", "挂机没有开成", "回合超时的默认行动");

        MessageContent envelope = MessageContent.newBuilder().setMessageId(battleIds.setAutoBattle()).setId(9)
                .setErrorMessage(TipInfoMessage.newBuilder().setId(1008)).build();
        assertThat(MatchSupport.autoProblem(frames(envelope, turn(BIG), end(BIG)), battleIds, BIG, auto)).contains("信封错误 tip=1008", "挂机没有开成");

        MessageContent garbage = MessageContent.newBuilder().setMessageId(battleIds.setAutoBattle()).setId(9)
                .setSerializedMessage(ByteString.copyFrom(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff})).build();
        assertThat(MatchSupport.autoProblem(frames(garbage, end(BIG)), battleIds, BIG, auto)).contains("解析不了");

        // 别人的请求号、别的消息号的应答都不算数：本人这条 162 没有应答
        assertThat(MatchSupport.autoProblem(frames(autoReply(8, 0), turn(BIG), end(BIG)), battleIds, BIG, auto)).contains("没有收到 162 的应答", "id=9");
        MessageContent otherMessage = MessageContent.newBuilder().setMessageId(battleIds.getBattleState()).setId(9).build();
        assertThat(MatchSupport.autoProblem(frames(otherMessage, turn(BIG), end(BIG)), battleIds, BIG, auto)).contains("没有收到 162 的应答");
    }

    @Test
    void 别人先把这一局打完_本人的162没赶上_三种情形都豁免() {
        // 没有发出（发送时连接已被服务端 FIN）
        assertThat(MatchSupport.autoProblem(frames(turn(BIG), end(BIG)), battleIds, BIG, MatchSupport.AutoRequest.NOT_SENT)).isNull();
        // 发出时 150 已经在收件箱里（下标 1 < 发出时的长度 2），服务端已经关了这条连接：没有应答
        assertThat(MatchSupport.autoProblem(frames(turn(BIG), end(BIG)), battleIds, BIG, new MatchSupport.AutoRequest(9, 2))).isNull();
        // 服务端在房间没了之后才读到这条 162：拒绝排在 150 之后
        assertThat(MatchSupport.autoProblem(frames(turn(BIG), end(BIG), autoReply(9, 1005)), battleIds, BIG, new MatchSupport.AutoRequest(9, 0))).isNull();

        // 对照：发出时 150 还没到（下标 1 ≥ 发出时的长度 1）却始终没有应答——不豁免
        assertThat(MatchSupport.autoProblem(frames(turn(BIG), end(BIG)), battleIds, BIG, new MatchSupport.AutoRequest(9, 1)))
                .contains("没有收到 162 的应答");
        // 对照：别的局的 150 不能拿来豁免本局的拒绝
        assertThat(MatchSupport.autoProblem(frames(end(BIG + 1), autoReply(9, 1005), end(BIG)), battleIds, BIG, new MatchSupport.AutoRequest(9, 0)))
                .contains("error_message 1005");
    }
}
