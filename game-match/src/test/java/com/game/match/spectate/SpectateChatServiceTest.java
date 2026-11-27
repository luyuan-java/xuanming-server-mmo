package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.match.placement.PlacementStore;
import com.game.match.proto.BattlePlacement;
import com.game.proto.content_guard.CheckTextResponse;
import com.game.proto.content_guard.TextVerdict;
import com.game.proto.eSpectateVisibility;
import com.game.proto.match.BattleSpectateChatRole;
import com.game.proto.match.PullBattleSpectateChatRequest;
import com.game.proto.match.SendBattleSpectateChatRequest;
import com.game.table.MatchErrorTip;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SpectateChatServiceTest {

    private static final class MapPlacements implements PlacementStore {
        final Map<Long, BattlePlacement> map = new HashMap<>();

        @Override
        public boolean write(BattlePlacement placement) {
            map.put(placement.getBattleId(), placement);
            return true;
        }

        @Override
        public void delete(long battleId) {
            map.remove(battleId);
        }

        @Override
        public Read read(long battleId, Deadline deadline) {
            BattlePlacement p = map.get(battleId);
            return p == null ? new Read.Absent() : new Read.Found(p);
        }
    }

    private static BattlePlacement publicBattle(long battleId, boolean chatEnabled) {
        return BattlePlacement.newBuilder()
                .setBattleId(battleId)
                .setSpectateVisibility(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC_VALUE)
                .setSpectateChatEnabled(chatEnabled)
                .addPlayerIds(101L).addPlayerNames("青冥剑客")
                .addPlayerIds(102L).addPlayerNames("玄水真人")
                .build();
    }

    @Test
    void 禁交流公开局允许观战但拒绝发言且拉取返回enabledFalse() {
        var placements = new MapPlacements();
        placements.write(publicBattle(8001L, false));
        var hub = new SpectateChatHub(SpectateChatHub.Config.DEFAULT);
        var service = new SpectateChatService(placements, hub, null, (id, d) -> "观战路人", () -> 10_000L);

        var sendResp = service.send(901L, SendBattleSpectateChatRequest.newBuilder()
                .setBattleId(8001L).setContent("加油").build(), Deadline.after(2000));
        assertThat(sendResp.getErrorMessage().getId())
                .isEqualTo(MatchErrorTip.match_error.kMatchSpectateChatDisabled_VALUE);
        assertThat(sendResp.getEnabled()).isFalse();

        var pullResp = service.pull(901L, PullBattleSpectateChatRequest.newBuilder()
                .setBattleId(8001L).build(), Deadline.after(2000));
        assertThat(pullResp.getErrorMessage().getId()).isZero();
        assertThat(pullResp.getEnabled()).isFalse();
        assertThat(pullResp.getMessagesList()).isEmpty();
    }

    @Test
    void 开放局按权威名单标参战与观战_审核脱敏冷却与终局封口完整生效() {
        var placements = new MapPlacements();
        placements.write(publicBattle(8002L, true));
        var hub = new SpectateChatHub(new SpectateChatHub.Config(180, 2_000L, 4_000L, 8));
        AtomicLong now = new AtomicLong(100_000L);
        SpectateChatService.TextGuard guard = (scene, pid, text) -> {
            if (text.contains("违禁")) {
                return CheckTextResponse.newBuilder().setVerdict(TextVerdict.TEXT_VERDICT_REJECT).build();
            }
            if (text.contains("坏词")) {
                return CheckTextResponse.newBuilder().setVerdict(TextVerdict.TEXT_VERDICT_MASKED)
                        .setMaskedText(text.replace("坏词", "**")).build();
            }
            if (text.contains("故障")) {
                throw new IllegalStateException("guard down");
            }
            return CheckTextResponse.newBuilder().setVerdict(TextVerdict.TEXT_VERDICT_PASS).build();
        };
        var service = new SpectateChatService(placements, hub, guard, (id, d) -> "看客甲", now::get);

        // 参战者发言（Pass）
        var pSend = service.send(101L, SendBattleSpectateChatRequest.newBuilder()
                .setBattleId(8002L).setContent("这回合拼一手").build(), Deadline.after(2000));
        assertThat(pSend.getErrorMessage().getId()).isZero();
        assertThat(pSend.getMessage().getRole()).isEqualTo(BattleSpectateChatRole.BATTLE_SPECTATE_CHAT_ROLE_PARTICIPANT);
        assertThat(pSend.getMessage().getSenderName()).isEqualTo("青冥剑客");

        // 冷却期内二次发言拒绝
        var cdSend = service.send(101L, SendBattleSpectateChatRequest.newBuilder()
                .setBattleId(8002L).setContent("连发").build(), Deadline.after(2000));
        assertThat(cdSend.getErrorMessage().getId())
                .isEqualTo(MatchErrorTip.match_error.kMatchSpectateChatCooldown_VALUE);

        // 观众脱敏放行 + 违禁拒收 + 审核故障 fail-closed
        var rejectSend = service.send(901L, SendBattleSpectateChatRequest.newBuilder()
                .setBattleId(8002L).setContent("违禁词").build(), Deadline.after(2000));
        assertThat(rejectSend.getErrorMessage().getId())
                .isEqualTo(MatchErrorTip.match_error.kMatchSpectateChatContentRejected_VALUE);

        var unavailableSend = service.send(901L, SendBattleSpectateChatRequest.newBuilder()
                .setBattleId(8002L).setContent("触发故障").build(), Deadline.after(2000));
        assertThat(unavailableSend.getErrorMessage().getId())
                .isEqualTo(MatchErrorTip.match_error.kMatchSpectateChatCheckUnavailable_VALUE);

        var maskedSend = service.send(901L, SendBattleSpectateChatRequest.newBuilder()
                .setBattleId(8002L).setContent("有个坏词提醒").build(), Deadline.after(2000));
        assertThat(maskedSend.getErrorMessage().getId()).isZero();
        assertThat(maskedSend.getMessage().getRole()).isEqualTo(BattleSpectateChatRole.BATTLE_SPECTATE_CHAT_ROLE_SPECTATOR);
        assertThat(maskedSend.getMessage().getSenderName()).isEqualTo("看客甲");
        assertThat(maskedSend.getMessage().getContent()).isEqualTo("有个**提醒");

        // 增量拉取
        var pullAll = service.pull(102L, PullBattleSpectateChatRequest.newBuilder()
                .setBattleId(8002L).setAfterSeq(0L).build(), Deadline.after(2000));
        assertThat(pullAll.getMessagesList()).hasSize(2);
        assertThat(pullAll.getLatestSeq()).isEqualTo(2L);

        var pullInc = service.pull(102L, PullBattleSpectateChatRequest.newBuilder()
                .setBattleId(8002L).setAfterSeq(1L).build(), Deadline.after(2000));
        assertThat(pullInc.getMessagesList()).hasSize(1);
        assertThat(pullInc.getMessages(0).getSeq()).isEqualTo(2L);

        // 战斗结束：禁止再发言，但可拉取历史并带 battle_ended=true
        hub.markEnded(8002L, now.addAndGet(5_000L));
        placements.delete(8002L);
        var afterEndSend = service.send(101L, SendBattleSpectateChatRequest.newBuilder()
                .setBattleId(8002L).setContent("打完了").build(), Deadline.after(2000));
        assertThat(afterEndSend.getErrorMessage().getId())
                .isEqualTo(MatchErrorTip.match_error.kMatchSpectateChatEnded_VALUE);
        assertThat(afterEndSend.getBattleEnded()).isTrue();

        var afterEndPull = service.pull(102L, PullBattleSpectateChatRequest.newBuilder()
                .setBattleId(8002L).build(), Deadline.after(2000));
        assertThat(afterEndPull.getErrorMessage().getId()).isZero();
        assertThat(afterEndPull.getBattleEnded()).isTrue();
        assertThat(afterEndPull.getMessagesList()).hasSize(2);
    }
}
