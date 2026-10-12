package com.game.battle.engine;

import static com.game.battle.engine.EngineTestSupport.hex;
import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.request;
import static com.game.battle.engine.TestTables.MONSTER_ID;
import static com.game.battle.engine.TestTables.PLAYER_A;
import static com.game.battle.engine.TestTables.SKILL_DAMAGE;
import static com.game.battle.engine.TestTables.standard;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_FLEE;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleEventItem;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleEventType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 事件线上字节（规格 §13.6）【复核】：protobuf-java 生成、按 proto3 编码规则手工逐字节核对过，C++ 会产出相同的字节。
 *
 * <ul>
 *   <li>M0 = {@code 0x8000000100000000} 编成 10 字节 varint（bit63 置位）；</li>
 *   <li>零值字段（hit_index = 0、is_critical = false、mana_after = 0）不上线；</li>
 *   <li>{@code BattleEventItem} 全是标量字段，同值事件跨语言字节完全相同（规格 §7.4）。</li>
 * </ul>
 * 第一个用例只钉住向量与 proto 编码本身（与引擎无关）；后两个用例要求引擎在 G-SS42 第 1 回合与 G-FLEE 里逐字节产出这些事件。
 */
class TurnBattleWireTest {

    private static final String SKILL_HEX = "0802108927188080808090808080800120655801";
    private static final String DAMAGE_HEX = "080310892718808080809080808080012065304548e7015801";
    private static final String MONSTER_ATTACK_HEX = "080110808080809080808080011889275802";
    private static final String MONSTER_DAMAGE_HEX = "08031080808080908080808001188927300f48d9075802";
    private static final String FLEE_HEX = "080b10892718892740015801";

    private static BattleEventItem.Builder event(eBattleEventType type, long source, long target, int group) {
        return BattleEventItem.newBuilder().setEventType(type).setSourceId(source).setTargetId(target).setGroupId(group);
    }

    @Test
    void 规格向量与proto3编码一致() {
        assertThat(hex(event(eBattleEventType.BATTLE_EVENT_SKILL, PLAYER_A, MONSTER_ID, 1)
                .setSkillTableId(SKILL_DAMAGE).build().toByteArray())).isEqualTo(SKILL_HEX);
        assertThat(hex(event(eBattleEventType.BATTLE_EVENT_DAMAGE, PLAYER_A, MONSTER_ID, 1)
                .setSkillTableId(SKILL_DAMAGE).setValue(69).setTargetHealthAfter(231).build().toByteArray())).isEqualTo(DAMAGE_HEX);
        assertThat(hex(event(eBattleEventType.BATTLE_EVENT_ATTACK, MONSTER_ID, PLAYER_A, 2).build().toByteArray()))
                .isEqualTo(MONSTER_ATTACK_HEX);
        assertThat(hex(event(eBattleEventType.BATTLE_EVENT_DAMAGE, MONSTER_ID, PLAYER_A, 2)
                .setValue(15).setTargetHealthAfter(985).build().toByteArray())).isEqualTo(MONSTER_DAMAGE_HEX);
        assertThat(hex(event(eBattleEventType.BATTLE_EVENT_FLEE, PLAYER_A, PLAYER_A, 1).setSuccess(true).build().toByteArray()))
                .isEqualTo(FLEE_HEX);
    }

    // G-SS42 第 1 回合（SameSeed，test.cpp:221-245，种子 42）
    @Test
    void 引擎产出的技能回合逐字节等于规格向量() {
        CreateBattleRequest.Builder request = request(9001, BattleConstants.MATCH_MODE_PVE_SOLO, 42);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 4, 100, 50, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE));
        TurnResultS2C result = engine.resolveCurrentRound();

        assertThat(result.getEventsList()).extracting(e -> hex(e.toByteArray()))
                .containsExactlyElementsOf(List.of(SKILL_HEX, DAMAGE_HEX, MONSTER_ATTACK_HEX, MONSTER_DAMAGE_HEX));
    }

    // G-FLEE（FleeIsDeterministic，test.cpp:607-640，种子 7）
    @Test
    void 引擎产出的逃跑事件逐字节等于规格向量() {
        CreateBattleRequest.Builder request = request(9016, BattleConstants.MATCH_MODE_PVE_SOLO, 7);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 600);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_FLEE));
        TurnResultS2C result = engine.resolveCurrentRound();

        assertThat(result.getEventsList()).extracting(e -> hex(e.toByteArray())).containsExactly(FLEE_HEX);
    }
}
