package com.game.scene.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.common.combatai.StateMutexMatrix;
import com.game.player.store.state.PlayerState;
import com.game.player.store.state.SavedBuffEntryState;
import com.game.player.store.state.SavedBuffListState;
import com.game.player.store.state.SkillCooldownEntryState;
import com.game.player.store.state.SkillCooldownListState;
import com.game.proto.BattleBuffEntry;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.ReleaseSkillRequest;
import com.game.scene.battle.BattleSnapshots;
import com.game.scene.battle.SceneBattleTables;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.PlayerRealtimeBuffs;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.Scene;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.Vec3;
import com.game.scene.world.WorldTestAccess;
import com.game.table.BuffTable;
import com.game.table.SkillErrorTip;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 实时技能冷却与 Buff 跨重登保留（PARITY 2026-11-19 / 11-20 三条）单测：
 * <ul>
 *   <li>技能冷却全量按 UTC 毫秒跨实体重建保留、离线照常计时、未来开始时刻压回当前时刻、空列表未知字段保留；</li>
 *   <li>keep_on_logout=1 Buff 跨重登保留与恢复裁剪（查不到表行原样带回连未知字段、表改为 0 丢弃、过期丢弃、
 *       永久改有限从现刻起算、剩余时长夹回表时长、未超时零漂移、按 no_caster 叠层、子 Buff 不单存且恢复重挂）；</li>
 *   <li>再施加同一实时 Buff 刷新有限时长到表整时长，永久与瞬时不动；</li>
 *   <li>回合战斗快照按剩余时长换算回合 max(1, ceil(ms / 6000))，剔除已到期、控制类与瞬时 Buff。</li>
 * </ul>
 */
class RealtimeCooldownBuffPersistenceTest {

    private static final int COOLDOWN_NOT_READY = SkillErrorTip.skill_error.kSkillCooldownNotReady_VALUE;

    private static BuffTable buff(int id, int buffType, double durationSec, int maxLayer,
                                  int infiniteDuration, int keepOnLogout, int noCaster, int... subBuffs) {
        BuffTable.Builder b = BuffTable.newBuilder()
                .setId(id)
                .setBuffType(buffType)
                .setDuration(durationSec)
                .setMaxLayer(maxLayer)
                .setInfiniteDuration(infiniteDuration)
                .setKeepOnLogout(keepOnLogout)
                .setNoCaster(noCaster);
        for (int sub : subBuffs) {
            b.addSubBuff(sub);
        }
        return b.build();
    }

    private static SkillTables skillTables(Map<Integer, BuffTable> buffs) {
        SkillTables.SkillDef skill1 = new SkillTables.SkillDef(
                1, List.of(), List.of(0), false, 0L, 0L, 0L, 7);
        StateMutexMatrix action = new StateMutexMatrix();
        action.ensureAction(SkillService.ACTION_USE_SKILL);
        return new SkillTables(
                Map.of(1, skill1),
                Map.of(7, 10_000_000_000L), // 10 秒冷却
                action,
                new StateMutexMatrix(),
                Map.of(),
                buffs);
    }

    @Test
    void 技能冷却跨实体重建保留_离线计时_未来开始时刻压回当前且空列表未知字段往返不丢() throws Exception {
        ManualClock clock = new ManualClock();
        SkillTables tables = skillTables(Map.of());
        SkillService service = new SkillService(tables, clock, SceneMetrics.noop(), Contracts.IDS);
        FakePlayerRepository repo = new FakePlayerRepository();
        SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, new RecordingSink(),
                repo, new AtomicLong(5000)::incrementAndGet, clock,
                SceneMetrics.noop(), service::initializeOnLoad, PlayerSnapshots.NONE);
        Scene scene = world.createScene(1);

        // 1. 进场放技能记下 UTC 毫秒冷却开始，退出落盘后重建玩家实例
        repo.putNewPlayer(1001L, 1L);
        world.onPlayerEnter(1L, PlayerEnter.newBuilder()
                .setSessionId(11).setPlayerId(1001L).setSceneId(scene.sceneId()).setOwnerEpoch(1L).build());
        repo.completeAll();
        ScenePlayer p1 = WorldTestAccess.player(world, 1L, 11);
        assertThat(service.release(world, p1, ReleaseSkillRequest.newBuilder().setSkillTableId(1).build())).isZero();
        long startedAt = clock.epochMillis();
        PlayerState savedBytes = PlayerState.parseFrom(WorldTestAccess.persistentState(p1).toByteArray());
        assertThat(savedBytes.hasSkillCooldowns()).isTrue();
        assertThat(savedBytes.getSkillCooldowns().getEntriesList())
                .containsExactly(SkillCooldownEntryState.newBuilder().setCooldownId(7).setStartMs(startedAt).build());
        world.onPlayerLeave(1L, PlayerLeave.newBuilder().setSessionId(11).setPlayerId(1001L).build());

        // 离线推进 4 秒（10 秒冷却还剩 6 秒），实体重建重进场后仍被冷却拦住
        clock.advanceMillis(4_000L);
        repo.put(new PlayerData(1001L, 2L, 3, 1, "look-1001", 10, 1, Vec3.ORIGIN, savedBytes));
        world.onPlayerEnter(1L, PlayerEnter.newBuilder()
                .setSessionId(12).setPlayerId(1001L).setSceneId(scene.sceneId()).setOwnerEpoch(2L).build());
        repo.completeAll();
        ScenePlayer p2 = WorldTestAccess.player(world, 1L, 12);
        assertThat(service.release(world, p2, ReleaseSkillRequest.newBuilder().setSkillTableId(1).build()))
                .isEqualTo(COOLDOWN_NOT_READY);

        // 再过 5999ms 仍在冷却，再过 1ms 到点放行
        clock.advanceMillis(5_999L);
        assertThat(service.release(world, p2, ReleaseSkillRequest.newBuilder().setSkillTableId(1).build()))
                .isEqualTo(COOLDOWN_NOT_READY);
        clock.advanceMillis(1L);
        assertThat(service.release(world, p2, ReleaseSkillRequest.newBuilder().setSkillTableId(1).build())).isZero();
        world.onPlayerLeave(1L, PlayerLeave.newBuilder().setSessionId(12).setPlayerId(1001L).build());

        // 2. 写盘节点时钟超前：存档 start_ms 晚于本机当前时刻，加载时压回当前时刻（最多剩 1 个整冷却）
        long now = clock.epochMillis();
        PlayerState futureState = PlayerState.newBuilder()
                .setSkillCooldowns(SkillCooldownListState.newBuilder()
                        .addEntries(SkillCooldownEntryState.newBuilder().setCooldownId(7).setStartMs(now + 60_000L)))
                .build();
        repo.put(new PlayerData(1001L, 3L, 3, 1, "look-1001", 10, 1, Vec3.ORIGIN, futureState));
        world.onPlayerEnter(1L, PlayerEnter.newBuilder()
                .setSessionId(13).setPlayerId(1001L).setSceneId(scene.sceneId()).setOwnerEpoch(3L).build());
        repo.completeAll();
        ScenePlayer p3 = WorldTestAccess.player(world, 1L, 13);
        assertThat(p3.skillState().cooldownStart(7)).isEqualTo(now);
        clock.advanceMillis(10_000L);
        assertThat(service.release(world, p3, ReleaseSkillRequest.newBuilder().setSkillTableId(1).build())).isZero();

        // 3. 冷却列表里没有记录、只带更新版本列表级未知字段时，加载再存盘原样保留
        UnknownFieldSet listUnknown = UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(12345L).build())
                .build();
        PlayerState emptyWithUnknown = PlayerState.parseFrom(PlayerState.newBuilder()
                .setSkillCooldowns(SkillCooldownListState.newBuilder().setUnknownFields(listUnknown))
                .build()
                .toByteArray());
        ScenePlayer p4 = WorldTestAccess.player(1001L, 1, 0, "", 10, List.of(1), "甲", emptyWithUnknown);
        service.initializeOnLoad(p4);
        PlayerState resaved = PlayerState.parseFrom(WorldTestAccess.persistentState(p4).toByteArray());
        assertThat(resaved.hasSkillCooldowns()).isTrue();
        assertThat(resaved.getSkillCooldowns().getEntriesList()).isEmpty();
        assertThat(resaved.getSkillCooldowns().getUnknownFields().getField(99).getVarintList())
                .containsExactly(12345L);
    }

    @Test
    void 保留Buff按表裁剪_查不到行原样带回_零漂移_子Buff重挂与按无施法者叠层() throws Exception {
        ManualClock clock = new ManualClock();
        long t0 = clock.epochMillis();
        BuffTable keptParent = buff(101, 10, 600.0, 3, 0, 1, 0, 102); // 10 分钟、保留、带子 Buff 102
        BuffTable subBuff = buff(102, 11, 600.0, 1, 0, 1, 0);        // 子 Buff 本身不单独落盘
        BuffTable transientBuff = buff(103, 10, 30.0, 2, 0, 0, 0);   // keep_on_logout = 0，重建即清
        BuffTable permNowFinite = buff(104, 12, 120.0, 5, 0, 1, 0);  // 原存档永久，现表改为 120s 有限
        BuffTable droppedNowZero = buff(105, 10, 600.0, 1, 0, 0, 0); // 表里改成了 keep_on_logout = 0
        Map<Integer, BuffTable> tableMap = Map.of(
                101, keptParent, 102, subBuff, 103, transientBuff, 104, permNowFinite, 105, droppedNowZero);
        SkillService service = new SkillService(skillTables(tableMap), clock, SceneMetrics.noop(), Contracts.IDS);

        // 在线施加：keep_on_logout=1 按 no_caster=1 叠层（不同施法者合并为 1 条、layer=2），并挂子 Buff 102
        ScenePlayer p1 = WorldTestAccess.player(2001L);
        service.applyRealtimeBuff(p1, 101, 9001L);
        clock.advanceMillis(10_000L);
        service.applyRealtimeBuff(p1, 101, 9002L);
        service.applyRealtimeBuff(p1, 103, 9001L);
        assertThat(p1.realtimeBuffs().activeBuffs()).hasSize(3); // 101 + 子 102 + 短效 103
        PlayerRealtimeBuffs.ActiveBuff parent = p1.realtimeBuffs().activeBuffs().stream()
                .filter(b -> b.buffTableId() == 101).findFirst().orElseThrow();
        assertThat(parent.casterId()).isZero();
        assertThat(parent.layer()).isEqualTo(2);
        long expectedExpire = t0 + 10_000L + 600_000L;
        assertThat(parent.expireAtMs()).isEqualTo(expectedExpire);

        // 存盘只含 101，不含子 Buff 102 与短效 103
        PlayerState saved1 = WorldTestAccess.persistentState(p1);
        assertThat(saved1.getSavedBuffs().getEntriesList()).hasSize(1);
        assertThat(saved1.getSavedBuffs().getEntries(0).getBuffTableId()).isEqualTo(101);
        assertThat(saved1.getSavedBuffs().getEntries(0).getExpireAtMs()).isEqualTo(expectedExpire);

        // 构造含混版本未知字段、未识别表行（999 未到期、998 已到期）、表改为 0（105）、永久改有限（104）、层数超上限与剩余超时长裁剪的存档
        UnknownFieldSet listUnknown = UnknownFieldSet.newBuilder()
                .addField(88, UnknownFieldSet.Field.newBuilder().addVarint(77L).build()).build();
        UnknownFieldSet entryUnknown101 = UnknownFieldSet.newBuilder()
                .addField(77, UnknownFieldSet.Field.newBuilder().addVarint(55L).build()).build();
        UnknownFieldSet entryUnknown999 = UnknownFieldSet.newBuilder()
                .addField(76, UnknownFieldSet.Field.newBuilder().addVarint(66L).build()).build();

        clock.advanceMillis(20_000L); // 当前 now = t0 + 30_000
        long now = clock.epochMillis();
        PlayerState rawState = PlayerState.parseFrom(PlayerState.newBuilder()
                .setSavedBuffs(SavedBuffListState.newBuilder()
                        .setUnknownFields(listUnknown)
                        .addEntries(SavedBuffEntryState.newBuilder()
                                .setBuffTableId(101).setLayer(99).setExpireAtMs(expectedExpire)
                                .setUnknownFields(entryUnknown101))
                        .addEntries(SavedBuffEntryState.newBuilder()
                                .setBuffTableId(104).setLayer(2).setExpireAtMs(0L))
                        .addEntries(SavedBuffEntryState.newBuilder()
                                .setBuffTableId(105).setLayer(1).setExpireAtMs(now + 100_000L))
                        .addEntries(SavedBuffEntryState.newBuilder()
                                .setBuffTableId(999).setLayer(4).setExpireAtMs(now + 50_000L)
                                .setUnknownFields(entryUnknown999))
                        .addEntries(SavedBuffEntryState.newBuilder()
                                .setBuffTableId(998).setLayer(1).setExpireAtMs(now - 1L)))
                .build().toByteArray());

        ScenePlayer p2 = WorldTestAccess.player(2001L, 1, rawState);
        service.initializeOnLoad(p2);

        // 校验活跃列表：101（layer 夹到 3，expire 零漂移 = expectedExpire）+ 重挂子 Buff 102 + 104（永久改有限 = now + 120s）
        // 105 已丢弃；999 不生效但原样暂存；998 已过期丢弃
        assertThat(p2.realtimeBuffs().activeBuffs())
                .extracting(PlayerRealtimeBuffs.ActiveBuff::buffTableId)
                .containsExactlyInAnyOrder(101, 102, 104);
        PlayerRealtimeBuffs.ActiveBuff r101 = p2.realtimeBuffs().activeBuffs().stream()
                .filter(b -> b.buffTableId() == 101).findFirst().orElseThrow();
        assertThat(r101.layer()).isEqualTo(3);
        assertThat(r101.expireAtMs()).isEqualTo(expectedExpire);
        assertThat(r101.casterId()).isZero();
        PlayerRealtimeBuffs.ActiveBuff r104 = p2.realtimeBuffs().activeBuffs().stream()
                .filter(b -> b.buffTableId() == 104).findFirst().orElseThrow();
        assertThat(r104.expireAtMs()).isEqualTo(now + 120_000L);
        assertThat(p2.realtimeBuffs().unresolvedEntries()).hasSize(1);
        assertThat(p2.realtimeBuffs().unresolvedEntries().getFirst().getBuffTableId()).isEqualTo(999);

        // 连续多次重建/存盘：101 到期时刻一字不差（零漂移），999 与各级未知字段完整保留
        PlayerState round1 = PlayerState.parseFrom(WorldTestAccess.persistentState(p2).toByteArray());
        clock.advanceMillis(5_000L);
        ScenePlayer p3 = WorldTestAccess.player(2001L, 1, round1);
        service.initializeOnLoad(p3);
        PlayerState round2 = PlayerState.parseFrom(WorldTestAccess.persistentState(p3).toByteArray());
        assertThat(round2.getSavedBuffs().getEntriesList())
                .extracting(SavedBuffEntryState::getBuffTableId)
                .containsExactly(101, 104, 999);
        assertThat(round2.getSavedBuffs().getEntries(0).getExpireAtMs()).isEqualTo(expectedExpire);
        assertThat(round2.getSavedBuffs().getEntries(0).getUnknownFields().getField(77).getVarintList())
                .containsExactly(55L);
        assertThat(round2.getSavedBuffs().getEntries(2).getUnknownFields().getField(76).getVarintList())
                .containsExactly(66L);
        assertThat(round2.getSavedBuffs().getUnknownFields().getField(88).getVarintList())
                .containsExactly(77L);

        // 超过 999 自己的到期时刻再重建：999 自动清理
        clock.advanceMillis(50_000L);
        ScenePlayer p4 = WorldTestAccess.player(2001L, 1, round2);
        service.initializeOnLoad(p4);
        assertThat(p4.realtimeBuffs().unresolvedEntries()).isEmpty();
        assertThat(WorldTestAccess.persistentState(p4).getSavedBuffs().getEntriesList())
                .extracting(SavedBuffEntryState::getBuffTableId)
                .containsExactly(101, 104);
    }

    @Test
    void 再施加刷新有限Buff整时长且战斗快照按剩余时长换算回合并过滤控制瞬时与过期() {
        ManualClock clock = new ManualClock();
        BuffTable finiteBuff = buff(201, 10, 18.0, 3, 0, 0, 0);   // 18 秒（3 回合），非保留
        BuffTable permBuff = buff(202, 11, 0.0, 2, 1, 1, 0);      // 永久 Buff
        BuffTable instantBuff = buff(203, 10, 0.0, 2, 0, 0, 0);   // 瞬时 Buff（不带进战斗）
        BuffTable stunBuff = buff(204, 30, 12.0, 1, 0, 0, 0);     // 控制 Buff（STUN=30，不带进战斗）
        BuffTable expiredBuff = buff(205, 12, 5.0, 1, 0, 0, 0);   // 5 秒后过期，不带进战斗
        Map<Integer, BuffTable> buffs = Map.of(
                201, finiteBuff, 202, permBuff, 203, instantBuff, 204, stunBuff, 205, expiredBuff);
        SkillService service = new SkillService(skillTables(buffs), clock, SceneMetrics.noop(), Contracts.IDS);

        ScenePlayer player = WorldTestAccess.player(3001L);
        long selfEntity = player.entity();
        service.applyRealtimeBuff(player, 201, selfEntity);
        service.applyRealtimeBuff(player, 202, 0L);
        service.applyRealtimeBuff(player, 203, selfEntity);
        service.applyRealtimeBuff(player, 204, 9999L);
        service.applyRealtimeBuff(player, 205, 9999L);

        // 过 10 秒后重施 201：层数 1→2，到期时刻刷新为 now + 18_000ms；永久 202 与瞬时 203 到期时刻不变
        clock.advanceMillis(10_000L);
        long refreshNow = clock.epochMillis();
        PlayerRealtimeBuffs.ActiveBuff refreshed = service.applyRealtimeBuff(player, 201, selfEntity);
        assertThat(refreshed.layer()).isEqualTo(2);
        assertThat(refreshed.expireAtMs()).isEqualTo(refreshNow + 18_000L);
        PlayerRealtimeBuffs.ActiveBuff permReapplied = service.applyRealtimeBuff(player, 202, 0L);
        assertThat(permReapplied.layer()).isEqualTo(2);
        assertThat(permReapplied.expireAtMs()).isZero();
        PlayerRealtimeBuffs.ActiveBuff instantReapplied = service.applyRealtimeBuff(player, 203, selfEntity);
        assertThat(instantReapplied.layer()).isEqualTo(2);
        assertThat(instantReapplied.expireAtMs()).isZero();

        // 再过 11.9 秒：201 剩余 6100ms → ceil(6100 / 6000) = 2 回合；205 早已过期被剔除；204 控制 / 203 瞬时被剔除
        clock.advanceMillis(11_900L);
        SceneBattleTables battleTables = new SceneBattleTables(
                "00000000000000000000000000000000", Set.of(), Set.of(), Set.of(), Map.of(), null, Map.of(), Map.of(), buffs);
        BattlePlayerSnapshot snap1 = BattleSnapshots.build(player, battleTables, List.of(),
                BattleRouting.getDefaultInstance(), 0, clock.epochMillis());
        assertThat(snap1.getBuffsList()).extracting(BattleBuffEntry::getBuffTableId).containsExactly(201, 202);
        BattleBuffEntry b201 = snap1.getBuffs(0);
        assertThat(b201.getLayer()).isEqualTo(2);
        assertThat(b201.getRemainRounds()).isEqualTo(2);
        assertThat(b201.getCasterId()).isEqualTo(3001L); // 本人实体号映射为 playerId
        BattleBuffEntry b202 = snap1.getBuffs(1);
        assertThat(b202.getRemainRounds()).isZero();
        assertThat(b202.getCasterId()).isZero();

        // 再过 6000ms：201 剩余 100ms → max(1, ceil(100 / 6000)) = 1 回合
        clock.advanceMillis(6_000L);
        BattlePlayerSnapshot snap2 = BattleSnapshots.build(player, battleTables, List.of(),
                BattleRouting.getDefaultInstance(), 0, clock.epochMillis());
        assertThat(snap2.getBuffs(0).getRemainRounds()).isEqualTo(1);

        // 再过 100ms：201 刚好到点（expireAtMs == nowMs），不再带进战斗，只剩永久 202
        clock.advanceMillis(100L);
        BattlePlayerSnapshot snap3 = BattleSnapshots.build(player, battleTables, List.of(),
                BattleRouting.getDefaultInstance(), 0, clock.epochMillis());
        assertThat(snap3.getBuffsList()).extracting(BattleBuffEntry::getBuffTableId).containsExactly(202);
    }
}
