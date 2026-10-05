package com.game.battle.room;

import com.game.proto.BattleActorState;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleStateS2C;
import java.util.List;

/**
 * 视角裁剪（基线 {@code RedactStateForViewer} / {@code FillSelfItems}，{@code room.cpp:1250-1281}；battle-node-spec §5.6）。纯函数，
 * 对拷贝进行，不改引擎产出的对象。
 *
 * <ul>
 *   <li>先清 {@code self_items}（不依赖「引擎快照恰好不填它」这一外部事实）；</li>
 *   <li>「自己的单位」= {@code viewer ≠ 0} 且（{@code actor_id == viewer} 或 {@code owner_player_id == viewer}），不是自己的单位清
 *       {@code skill_cooldown_rounds}；<b>buff 不裁剪</b>；</li>
 *   <li>参战者再填本人的 {@code self_items}（引擎 {@code selfItems}，按 id 升序）；观众（viewer = 0）全清冷却、不填道具。</li>
 * </ul>
 * 开局 143、每回合 139、补拉 140、观战 161 / 158 都必须经这里（漏掉任何一处都会泄露对手的冷却和道具，{@code combat.md:231}）。
 */
final class BattleViews {

    private BattleViews() {
    }

    /**
     * 参战者视角：只保留本人与本人宝宝的冷却，填本人道具余量。
     *
     * @param full      引擎全知快照（已回填 battle_id / action_deadline_ms）
     * @param viewer    收信参战者的 player_id（0 时按观众处理：不留任何冷却、不填道具，同基线）
     * @param selfItems 引擎 {@code selfItems(viewer)}
     */
    static ViewerState forParticipant(BattleStateS2C full, long viewer, List<BattleItemEntry> selfItems) {
        BattleStateS2C.Builder state = redact(full, viewer);
        if (viewer != 0) {
            state.addAllSelfItems(selfItems);
        }
        return new ViewerState(state.build());
    }

    /** 观众视角：全员冷却清空，不带任何人的道具余量。 */
    static ViewerState forObserver(BattleStateS2C full) {
        return new ViewerState(redact(full, 0).build());
    }

    private static BattleStateS2C.Builder redact(BattleStateS2C full, long viewer) {
        BattleStateS2C.Builder state = full.toBuilder().clearSelfItems();
        for (BattleActorState.Builder actor : state.getActorsBuilderList()) {
            boolean own = viewer != 0 && (actor.getActorId() == viewer || actor.getOwnerPlayerId() == viewer);
            if (!own) {
                actor.clearSkillCooldownRounds();
            }
        }
        return state;
    }
}
