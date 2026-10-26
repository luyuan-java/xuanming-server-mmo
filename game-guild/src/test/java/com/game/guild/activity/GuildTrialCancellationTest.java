package com.game.guild.activity;

import static org.junit.jupiter.api.Assertions.*;

import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.proto.BattleActivityContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.match.*;
import org.junit.jupiter.api.Test;

/** 签名仅用本例合成秘密；验证原Start绑定、可信终局和错误回执不能释放ACTIVE。 */
class GuildTrialCancellationTest {
    private static final String SECRET = "trial-cancellation-fixture-0000000000000000001";
    private static final long NOW = 123456789;
    private final GuildTrialCancellation cancellation = new GuildTrialCancellation(SECRET,() -> NOW);
    private StartActivityBattleRequest original() {
        return StartActivityBattleRequest.newBuilder().setRequestId(101).setBattleConfigId(7).addMemberPlayerIds(3).addMemberPlayerIds(9)
                .setActivityContext(BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL)
                        .setGuildId(11).setActivityId(5).setPeriodKey(8).setGuildPeriodKey(8).setInitiatorPlayerId(3).setLaunchRequestId(101)).build();
    }
    private CancelActivityBattleResponse reply(CancelActivityBattleRequest request,long battle,int state,boolean cancelled) {
        return ServiceCallSignatures.sign(GuildTrialCancellation.METHOD + "/reply",CancelActivityBattleResponse.newBuilder()
                .setBattleId(battle).setFormationState(state).setRequestCancelled(cancelled)
                .setRequestDigest(ServiceCallSignatures.digest(request)).build(),"match",SECRET,NOW);
    }
    @Test void 只有原请求墓碑或真实battle安全中止才是释放证明() {
        var request = cancellation.request(original());
        assertTrue(cancellation.verify(request,reply(request,0,0,true)).terminal());
        assertTrue(cancellation.verify(request,reply(request,99,5,false)).terminal());
        for (int state : new int[]{0,1,2,3,4})
            assertFalse(cancellation.verify(request,reply(request,99,state,false)).terminal());
        assertFalse(cancellation.verify(request,reply(request,0,0,false)).terminal());
        assertThrows(SecurityException.class, () -> cancellation.verify(request,reply(request,0,5,false)));
        assertThrows(SecurityException.class, () -> cancellation.verify(request,reply(request,99,5,true)));
    }
    @Test void 缺签篡改过期换原件与错误方法均拒绝() {
        var request = cancellation.request(original()); var response = reply(request,0,0,true);
        assertThrows(SecurityException.class, () -> cancellation.verify(request,response.toBuilder().clearAuth().build()));
        assertThrows(SecurityException.class, () -> cancellation.verify(request,response.toBuilder().setBattleId(99).build()));
        var changed = cancellation.request(original().toBuilder().setBattleConfigId(8).build());
        assertThrows(SecurityException.class, () -> cancellation.verify(changed,response));
        var wrong = ServiceCallSignatures.sign(GuildTrialCancellation.METHOD,response,"match",SECRET,NOW);
        assertThrows(SecurityException.class, () -> cancellation.verify(request,wrong));
        var expired = new GuildTrialCancellation(SECRET,() -> NOW+300001);
        assertThrows(SecurityException.class, () -> expired.verify(request,response));
        assertThrows(IllegalArgumentException.class, () -> new GuildTrialCancellation("short",() -> NOW));
    }
    @Test void 已签COMPLETE不能伪装安全中止_ABORT和发房前未知终局仍可释放() {
        var request = cancellation.request(original());
        for (var decision : BattleOutcomeDecision.values()) {
            if (decision == BattleOutcomeDecision.UNRECOGNIZED) continue;
            var response = ServiceCallSignatures.sign(GuildTrialCancellation.METHOD + "/reply",
                    reply(request,99,5,false).toBuilder().setTerminalDecision(decision).build(),"match",SECRET,NOW);
            assertEquals(decision != BattleOutcomeDecision.BATTLE_OUTCOME_DECISION_COMPLETE,
                    cancellation.verify(request,response).terminal(), decision.name());
        }
    }
}
