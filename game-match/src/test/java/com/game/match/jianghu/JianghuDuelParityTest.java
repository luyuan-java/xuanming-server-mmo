package com.game.match.jianghu;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SessionContext;
import com.game.proto.common.base.CurrencyType;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.JianghuDuelBattleContext;
import com.game.proto.chat.PublishSystemWorldNoticeRequest;
import com.game.proto.chat.SystemWorldNoticeTemplate;
import com.game.proto.eBattleOutcome;
import com.game.proto.mail.SendMailRequest;
import com.game.proto.match.ConfirmJianghuDuelMemberRequest;
import com.game.proto.match.CreateJianghuDuelInviteRequest;
import com.game.proto.match.DismissJianghuGrudgeRequest;
import com.game.proto.match.JianghuDuelInviteState;
import com.game.proto.match.JianghuDuelInviteView;
import com.game.proto.match.JianghuWritTier;
import com.game.proto.match.MatchMode;
import com.game.proto.match.RespondJianghuDuelInviteRequest;
import com.game.table.JianghuErrorTip;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 江湖恩怨与天墉生死状一期 Java 双版对账单测：1V1/3V3 双阶段确认、冷却、拉黑、宿敌连胜/终结播报、一笔勾销重现与 VOID 单次退银两。 */
class JianghuDuelParityTest {

    private static SessionContext session(long playerId) {
        return SessionContext.newBuilder().setPlayerId(playerId).setAccount("acc_" + playerId).setOwnerRevision(1L).build();
    }

    @Test
    void fullFlow_1v1_3v3_StreakNotices_DismissAndVoidSingleRefund() {
        AtomicLong clock = new AtomicLong(1_730_000_000_000L);
        AtomicLong ids = new AtomicLong(100L);
        List<PublishSystemWorldNoticeRequest> notices = new ArrayList<>();
        List<SendMailRequest> refundMails = new ArrayList<>();
        Map<Long, List<Long>> teams = Map.of(
                1001L, List.of(1001L, 1002L, 1003L),
                2001L, List.of(2001L, 2002L, 2003L));
        boolean[] blocked = {false};

        JianghuDuelService svc = new JianghuDuelService(
                () -> null,
                new JianghuDuelService.Ports() {
                    @Override
                    public Optional<List<Long>> authoritativeTeamOf(long captainPlayerId) {
                        return Optional.ofNullable(teams.get(captainPlayerId));
                    }

                    @Override
                    public JianghuDuelService.SocialRelationCheck checkSocialRelations(List<Long> sideA, List<Long> sideB) {
                        return new JianghuDuelService.SocialRelationCheck(blocked[0], false);
                    }

                    @Override
                    public long launchBattle(JianghuDuelInviteView invite, JianghuDuelBattleContext duelContext) {
                        return 88000L + invite.getDuelInviteId();
                    }

                    @Override
                    public void publishWorldNotice(PublishSystemWorldNoticeRequest request) {
                        notices.add(request);
                    }

                    @Override
                    public void sendSilverRefundMail(SendMailRequest request) {
                        refundMails.add(request);
                    }
                },
                clock::get,
                ids::incrementAndGet);

        // 1. 拉黑直接拒绝
        blocked[0] = true;
        var blockedResp = svc.createInvite(session(1001L), CreateJianghuDuelInviteRequest.newBuilder()
                .setTargetPlayerId(2001L).setWritTier(JianghuWritTier.JIANGHU_WRIT_TIER_SILVER).build());
        assertThat(blockedResp.getErrorMessage().getId())
                .isEqualTo(JianghuErrorTip.jianghu_error.kJianghuBlockedByPeer_VALUE);
        blocked[0] = false;

        // 2. 3V3 两阶段确认，目标队 2002 拒绝 -> ABORTED（0 开局）
        var invResp = svc.createInvite(session(1001L), CreateJianghuDuelInviteRequest.newBuilder()
                .setTargetPlayerId(2001L).setWritTier(JianghuWritTier.JIANGHU_WRIT_TIER_GOLD_ITEM).build());
        assertThat(invResp.getErrorMessage().getId()).isZero();
        assertThat(invResp.getInvite().getState())
                .isEqualTo(JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_AWAITING_CHALLENGER_TEAM);
        long invId = invResp.getInvite().getDuelInviteId();

        // 同目标 5 分钟冷却拦截
        var cdResp = svc.createInvite(session(1001L), CreateJianghuDuelInviteRequest.newBuilder()
                .setTargetPlayerId(2001L).setWritTier(JianghuWritTier.JIANGHU_WRIT_TIER_SILVER).build());
        assertThat(cdResp.getErrorMessage().getId())
                .isEqualTo(JianghuErrorTip.jianghu_error.kJianghuSameTargetCooldown_VALUE);

        svc.confirmMember(session(1002L), ConfirmJianghuDuelMemberRequest.newBuilder().setDuelInviteId(invId).setAgree(true).build());
        var afterSideA = svc.confirmMember(session(1003L), ConfirmJianghuDuelMemberRequest.newBuilder().setDuelInviteId(invId).setAgree(true).build());
        assertThat(afterSideA.getInvite().getState())
                .isEqualTo(JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_PENDING_TARGET);

        var afterTargetCap = svc.respondInvite(session(2001L), RespondJianghuDuelInviteRequest.newBuilder().setDuelInviteId(invId).setAccept(true).build());
        assertThat(afterTargetCap.getInvite().getState())
                .isEqualTo(JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_AWAITING_TARGET_TEAM);

        var afterDecline = svc.confirmMember(session(2002L), ConfirmJianghuDuelMemberRequest.newBuilder().setDuelInviteId(invId).setAgree(false).build());
        assertThat(afterDecline.getInvite().getState())
                .isEqualTo(JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_ABORTED);

        // 3. 3 连胜触发高光播报 -> 一笔勾销 -> 第 4 场对手翻盘自动重现并触发终结连胜播报
        Map<Long, Long> revs = Map.of(1001L, 1L, 2001L, 1L);
        for (long b = 1; b <= 3; b++) {
            assertThat(svc.applyBattleResult(makeResult(b, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 6), revs)).isTrue();
        }
        assertThat(notices).anyMatch(n ->
                n.getTemplateId() == SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_STREAK_MILESTONE
                        && n.getStreakCount() == 3);

        var dismiss = svc.dismissGrudge(session(1001L), DismissJianghuGrudgeRequest.newBuilder().setPeerPlayerId(2001L).build());
        assertThat(dismiss.getErrorMessage().getId()).isZero();
        assertThat(svc.listGrudges(session(1001L)).getGrudgesList()).isEmpty();
        assertThat(svc.listGrudges(session(2001L)).getGrudgesList()).hasSize(1);

        assertThat(svc.applyBattleResult(makeResult(4L, eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, 9), revs)).isTrue();
        var revived = svc.listGrudges(session(1001L)).getGrudgesList();
        assertThat(revived).hasSize(1);
        assertThat(revived.get(0).getWins()).isEqualTo(3);
        assertThat(revived.get(0).getLosses()).isEqualTo(1);
        assertThat(notices).anyMatch(n ->
                n.getTemplateId() == SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_STREAK_BROKEN
                        && n.getStreakCount() == 3);

        // 4. VOID 异常局严格单次幂等退 500 银两（CURRENCY_TYPE_GOLD = 0）
        BattleResultEvent voidEv = makeResult(99L, eBattleOutcome.BATTLE_OUTCOME_VOID, 0);
        svc.applyBattleResult(voidEv, revs);
        svc.applyBattleResult(voidEv, revs);
        assertThat(refundMails).hasSize(1);
        assertThat(refundMails.get(0).getExpireMs()).isZero();
        assertThat(refundMails.get(0).getAttachments().getCurrencies(0).getCurrencyType())
                .isEqualTo(CurrencyType.CURRENCY_TYPE_GOLD_VALUE);
        assertThat(refundMails.get(0).getAttachments().getCurrencies(0).getAmount()).isEqualTo(500L);
    }

    private static BattleResultEvent makeResult(long battleId, eBattleOutcome outcome, int rounds) {
        return BattleResultEvent.newBuilder()
                .setBattleId(battleId)
                .setMatchMode(MatchMode.MATCH_MODE_JIANGHU_DUEL_VALUE)
                .setOutcome(outcome)
                .setTotalRounds(rounds)
                .setFinishedAtMs(1_730_000_000_000L + battleId)
                .setJianghuDuelContext(JianghuDuelBattleContext.newBuilder()
                        .setDuelInviteId(500L + battleId)
                        .setWritTier(JianghuWritTier.JIANGHU_WRIT_TIER_SILVER_VALUE)
                        .setChallengerCaptainId(1001L)
                        .setChallengerCaptainName("清风")
                        .setTargetCaptainId(2001L)
                        .setTargetCaptainName("墨渊")
                        .setTeamSize(1)
                        .build())
                .build();
    }
}
