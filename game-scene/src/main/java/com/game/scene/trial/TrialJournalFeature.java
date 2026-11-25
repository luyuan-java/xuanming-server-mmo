package com.game.scene.trial;

import com.game.proto.ClaimTrialJournalMilestoneRequest;
import com.game.proto.GetTrialJournalRequest;
import com.game.proto.RedeemBoundArtifactVoucherRequest;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.SceneFeature;

/**
 * 五行试炼志 V2 客户端入口（{@code SceneTrialJournalClientPlayer} 456–458）。
 */
public final class TrialJournalFeature implements SceneFeature {

    private static final String SERVICE = "SceneTrialJournalClientPlayer";

    private final TrialJournalService service;

    public TrialJournalFeature(TrialJournalService service) {
        this.service = service;
    }

    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetTrialJournal", GetTrialJournalRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> call.reply(service.getJournal(call.player(), service.nowMillis())));
        r.on(SERVICE, "ClaimTrialJournalMilestone", ClaimTrialJournalMilestoneRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED,
                (call, request) -> {
                    var outcome = service.claimMilestone(call.world(), call.player(), request, service.nowMillis());
                    if (outcome.changed()) {
                        call.world().requestKeyProgressSave(call.player());
                    }
                    call.reply(outcome.response());
                });
        r.on(SERVICE, "RedeemBoundArtifactVoucher", RedeemBoundArtifactVoucherRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED,
                (call, request) -> {
                    var outcome = service.redeemArtifactVoucher(call.world(), call.player(), request, service.nowMillis());
                    if (outcome.changed()) {
                        call.world().requestKeyProgressSave(call.player());
                    }
                    call.reply(outcome.response());
                });
    }
}
