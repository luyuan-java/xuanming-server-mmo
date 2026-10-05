package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleEventItem;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleEventType;

/**
 * 引擎单测共用的请求 / 行动 / 查询小工具（逐项移植基线 {@code turn_battle_engine_test.cpp:141-213} 与 {@code :1516-1549}）。
 *
 * <p>{@code addPlayer} / {@code addPet} 返回的是挂在父 builder 上的子 builder（{@code addXxxBuilder()}），之后对它的修改会反映到
 * 父请求里，等价于基线返回的可变指针。
 */
final class TestBattles {

    /** 省略 battle_id 的请求固定用 9900（基线两参 {@code MakeRequest}）。 */
    static final long DEFAULT_BATTLE_ID = 9900;

    private TestBattles() {
    }

    /** 基线 {@code MakeRequest(battleId, matchMode, seed)}：battle_config_id 固定为 {@link TestTables#DUNGEON_CONFIG}。 */
    static CreateBattleRequest.Builder request(long battleId, int matchMode, long seed) {
        return CreateBattleRequest.newBuilder()
                .setBattleId(battleId)
                .setBattleConfigId(TestTables.DUNGEON_CONFIG)
                .setMatchMode(matchMode)
                .setSeed(seed);
    }

    /** 基线两参 {@code MakeRequest(matchMode, seed)}：battle_id 固定 9900。 */
    static CreateBattleRequest.Builder request(int matchMode, long seed) {
        return request(DEFAULT_BATTLE_ID, matchMode, seed);
    }

    /**
     * 基线 {@code AddPlayer}：名字「测试玩家」、level 10，带技能 101、102、103、104；max_mana 与 mana 不设（为 0）。
     * 属性参数都是 uint64。
     */
    static BattlePlayerSnapshot.Builder addPlayer(CreateBattleRequest.Builder request, long playerId, int teamIndex,
                                                  long health, long maxHealth, long strength, long armor,
                                                  long critChance, long speed) {
        BattlePlayerSnapshot.Builder snapshot = request.addPlayersBuilder();
        snapshot.setPlayerId(playerId);
        snapshot.setPlayerName("测试玩家");
        snapshot.setLevel(10);
        snapshot.setTeamIndex(teamIndex);
        snapshot.setMaxHealth(maxHealth);
        BaseAttributesComp.Builder attributes = snapshot.getBaseAttributesBuilder();
        attributes.setHealth(health);
        attributes.setStrength(strength);
        attributes.setArmor(armor);
        attributes.setCritchance(critChance);
        attributes.setSpeed(speed);
        snapshot.addSkillTableIds(TestTables.SKILL_DAMAGE);
        snapshot.addSkillTableIds(TestTables.SKILL_POISON);
        snapshot.addSkillTableIds(TestTables.SKILL_NUKE);
        snapshot.addSkillTableIds(TestTables.SKILL_DISPEL);
        return snapshot;
    }

    /** 基线 {@code AddPet}（physical_attack 缺省 0）。 */
    static BattlePetSnapshot.Builder addPet(BattlePlayerSnapshot.Builder owner, long petId, long health, long maxHealth,
                                            long strength, long speed) {
        return addPet(owner, petId, health, maxHealth, strength, speed, 0);
    }

    /** 基线 {@code AddPet}：主人取 owner 当前的 player_id，名字「小灵狐」，pet_table_id 1，等级随主人当前等级。 */
    static BattlePetSnapshot.Builder addPet(BattlePlayerSnapshot.Builder owner, long petId, long health, long maxHealth,
                                            long strength, long speed, long physicalAttack) {
        BattlePetSnapshot.Builder pet = owner.addPetsBuilder();
        pet.setPetId(petId);
        pet.setOwnerPlayerId(owner.getPlayerId());
        pet.setPetName("小灵狐");
        pet.setPetTableId(1);
        pet.setLevel(owner.getLevel());
        pet.setMaxHealth(maxHealth);
        pet.setPhysicalAttack(physicalAttack);
        BaseAttributesComp.Builder attributes = pet.getBaseAttributesBuilder();
        attributes.setHealth(health);
        attributes.setStrength(strength);
        attributes.setSpeed(speed);
        return pet;
    }

    /** 基线 {@code MakeAction(type)}：目标 0。 */
    static BattleAction action(eBattleActionType actionType) {
        return action(actionType, 0, 0, 0);
    }

    /** 基线 {@code MakeAction(type, target)}。 */
    static BattleAction action(eBattleActionType actionType, long targetId) {
        return action(actionType, targetId, 0, 0);
    }

    /** 基线 {@code MakeAction(type, target, skill)}。 */
    static BattleAction action(eBattleActionType actionType, long targetId, int skillTableId) {
        return action(actionType, targetId, skillTableId, 0);
    }

    /** 基线 {@code MakeAction(type, target, skill, item)}。 */
    static BattleAction action(eBattleActionType actionType, long targetId, int skillTableId, int itemTableId) {
        return BattleAction.newBuilder()
                .setActionType(actionType)
                .setTargetId(targetId)
                .setSkillTableId(skillTableId)
                .setItemTableId(itemTableId)
                .build();
    }

    /** 开局并断言成功，返回引擎（基线 {@code ASSERT_TRUE(engine.Initialize(request))}）。 */
    static TurnBattleEngine start(CreateBattleRequest.Builder request, BattleData data) {
        BattleStart start = TurnBattleEngine.start(request.build(), data);
        assertThat(start).as("开局应当成功").isInstanceOf(BattleStart.Started.class);
        return ((BattleStart.Started) start).engine();
    }

    /** 基线 {@code CountEvents}。 */
    static int countEvents(TurnResultS2C result, eBattleEventType eventType) {
        int count = 0;
        for (BattleEventItem event : result.getEventsList()) {
            if (event.getEventType() == eventType) {
                count++;
            }
        }
        return count;
    }

    /** 基线 {@code FindFirstEvent}：没有时返回 null。 */
    static BattleEventItem firstEvent(TurnResultS2C result, eBattleEventType eventType) {
        for (BattleEventItem event : result.getEventsList()) {
            if (event.getEventType() == eventType) {
                return event;
            }
        }
        return null;
    }

    /** 基线 {@code FindStateActor}：按 actor_id 找快照里的单位，没有时返回 null。 */
    static BattleActorState stateActor(BattleStateS2C state, long actorId) {
        for (BattleActorState actor : state.getActorsList()) {
            if (actor.getActorId() == actorId) {
                return actor;
            }
        }
        return null;
    }

    /** 基线 {@code FindPetActor}：按真实 pet_id 找宝宝单位（宝宝的 actor_id 是引擎局内号），没有时返回 null。 */
    static BattleActorState petActor(BattleStateS2C state, long petId) {
        for (BattleActorState actor : state.getActorsList()) {
            if (actor.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET && actor.getPetId() == petId) {
                return actor;
            }
        }
        return null;
    }
}
