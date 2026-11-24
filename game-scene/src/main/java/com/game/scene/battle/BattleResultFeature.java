package com.game.scene.battle;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.player.store.state.BattleResultEntryState;
import com.game.player.store.state.PlayerState;
import com.game.proto.AckBattleResultsRequest;
import com.game.proto.AckBattleResultsResponse;
import com.game.proto.GetRecentBattleResultsRequest;
import com.game.proto.GetRecentBattleResultsResponse;
import com.game.proto.PlayerBattleResult;
import com.game.scene.player.PlayerBattleResults;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.PlayerCall;
import com.game.scene.world.SceneFeature;
import com.game.scene.world.ScenePlayer;
import com.game.table.CommonErrorTip;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本人近期战斗结果查询与已读确认（{@code SceneBattleResultClientPlayer} 371 / 372，对齐 C++ {@code player_battle_result_handler}）。
 *
 * <p>仅随玩家存档确认提交的结果对客户端公开并允许确认；未落盘的局保持隐藏，防止崩溃回档前提前曝光。
 */
public final class BattleResultFeature implements SceneFeature {

    private static final String SERVICE = "SceneBattleResultClientPlayer";
    static final int OK = 0;
    static final int FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;
    static final int ARRAY_TOO_LARGE = CommonErrorTip.common_error.kArraySizeTooLargeInMessage_VALUE;

    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetRecentBattleResults", GetRecentBattleResultsRequest.class,
                FreezePolicy.READ_ONLY, BattlePolicy.ALLOW, this::getRecent);
        r.on(SERVICE, "AckBattleResults", AckBattleResultsRequest.class,
                FreezePolicy.GATED, BattlePolicy.ALLOW, this::ack);
    }

    private void getRecent(PlayerCall call, GetRecentBattleResultsRequest request) {
        ScenePlayer player = call.player();
        Set<Long> hidden = awaitingCommitBattleIds(player);
        List<PlayerBattleResult> visible = player.battleResults().visibleNewestFirst(hidden);
        call.reply(GetRecentBattleResultsResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .addAllResults(visible)
                .build());
    }

    private void ack(PlayerCall call, AckBattleResultsRequest request) {
        if (request.getBattleIdsCount() > PlayerBattleResults.MAX_ENTRIES) {
            call.reply(AckBattleResultsResponse.newBuilder().setErrorMessage(tip(ARRAY_TOO_LARGE)).build());
            return;
        }
        ScenePlayer player = call.player();
        if (player.frozen()) {
            call.reply(AckBattleResultsResponse.newBuilder().setErrorMessage(tip(FEATURE_UNAVAILABLE)).build());
            return;
        }
        player.battleResults().markSeen(request.getBattleIdsList(), awaitingCommitBattleIds(player));
        call.reply(AckBattleResultsResponse.newBuilder().setErrorMessage(tip(OK)).build());
    }

    /** 尚未出现在最近一次已确认落盘快照中的战果 battle_id 集合（不对外公开、不接受已读确认）。 */
    static Set<Long> awaitingCommitBattleIds(ScenePlayer player) {
        List<BattleResultEntryState> current = player.battleResults().entries();
        if (current.isEmpty()) {
            return Set.of();
        }
        PlayerState persisted = player.persistedState();
        Set<Long> durableIds = new HashSet<>();
        if (persisted != null && persisted.hasBattleResults()) {
            for (BattleResultEntryState saved : persisted.getBattleResults().getEntriesList()) {
                durableIds.add(saved.getBattleId());
            }
        }
        Set<Long> hidden = new HashSet<>();
        for (BattleResultEntryState entry : current) {
            if (!durableIds.contains(entry.getBattleId())) {
                hidden.add(entry.getBattleId());
            }
        }
        return hidden;
    }
}
