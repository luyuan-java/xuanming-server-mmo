package com.game.scene.mission;

import static com.game.scene.mission.MissionFixtures.condition;
import static com.game.scene.mission.MissionFixtures.edit;
import static com.game.scene.mission.MissionFixtures.mission;
import static com.game.scene.mission.MissionFixtures.newCondition;
import static com.game.scene.mission.MissionFixtures.newMission;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.ActiveMission;
import com.game.player.store.state.BagState;
import com.game.player.store.state.MissionState;
import com.game.player.store.state.PlayerState;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.bag.BagService;
import com.game.scene.bag.BagTables;
import com.game.scene.gainblock.GlobalGainBlocks;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerMissions;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.testing.RecordingAssetAudit.Item;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 任务服务（基线 player_mission_system_test + missions_test 的可移植部分）：接取闸、回填、推进、完成连锁、领奖。
 * 配表用正式表，规则层的用例照基线的做法临时改表（{@link MissionFixtures.Editor}）。
 */
class MissionServiceTest {

    private static final long PLAYER = 1001;
    private static final long U32 = 0xFFFF_FFFFL;

    private final RecordingAssetAudit audit = new RecordingAssetAudit();
    private final ManualClock clock = new ManualClock();
    private final AtomicLong guidSeq = new AtomicLong(1L << 60);
    /** 还能发的物品 guid 数（铸不齐整批返回 null，同基线 CanMintGuids）。 */
    private long guidBudget = Long.MAX_VALUE;
    private final BagService bags = new BagService(BagTables.from(MissionFixtures.SHIPPED), count -> {
        if (count > guidBudget) {
            return null;
        }
        guidBudget -= count;
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            out[i] = guidSeq.incrementAndGet();
        }
        return out;
    }, audit, GainAnomalyDetector.off(), SceneMetrics.noop());
    private MissionService service;
    private ScenePlayer player;

    @BeforeEach
    void setUp() {
        use(MissionFixtures.shippedTables());
        player = load(PlayerState.getDefaultInstance(), 1);
    }

    private void use(MissionTables tables) {
        service = new MissionService(tables, bags, clock);
    }

    private ScenePlayer load(PlayerState state, int level) {
        ScenePlayer loaded = WorldTestAccess.player(PLAYER, level, state);
        bags.initializeOnLoad(loaded);
        service.initializeOnLoad(loaded);
        return loaded;
    }

    /** 背包容量 {@code capacity} 的玩家（只放得下 capacity 个不可叠加的物品）。 */
    private ScenePlayer withInventory(int capacity, MissionState missions) {
        PlayerState.Builder state = PlayerState.newBuilder().setBag(BagState.newBuilder().addCapacities(capacity));
        if (missions != null) {
            state.setMission(missions);
        }
        return load(state.build(), 1);
    }

    private ScenePlayer withMissions(MissionState missions) {
        return load(PlayerState.newBuilder().setMission(missions).build(), 1);
    }

    private static MissionState.Builder state() {
        return MissionState.newBuilder();
    }

    private static ActiveMission active(int id, int... progress) {
        ActiveMission.Builder entry = ActiveMission.newBuilder().setMissionId(id);
        for (int value : progress) {
            entry.addProgress(value);
        }
        return entry.build();
    }

    private long items() {
        return player.bags().bag(BagType.INVENTORY).total(1);
    }

    private PlayerMissions missions() {
        return player.missions();
    }

    private long progress(int missionId, int slot) {
        return missions().active(missionId).progress(slot);
    }

    private void kill(int monster, int count) {
        service.onMonsterKilled(player, monster, count);
    }

    private void fact(int category, int id, long amount) {
        service.dispatch(player, MissionFact.of(category, id, amount));
    }

    private List<Item> questRewards() {
        return audit.items.stream().filter(item -> item.reason() == Reason.QUEST_REWARD).toList();
    }

    // ------------------------------------------------------------------ 接取闸

    @Test
    void scope非0回1005_未知任务1001_无条件1002_玩法未开放1003_都不留状态() {
        assertThat(service.accept(player, 1, 4)).isEqualTo(1005);
        assertThat(service.accept(player, 0, 999_999)).isEqualTo(1001);
        assertThat(service.accept(player, 0, 3)).isEqualTo(1002);
        assertThat(service.accept(player, 0, 6)).isEqualTo(1003);
        assertThat(missions().isPristine()).isTrue();
        assertThat(missions().typeOccupied(1, 1)).isFalse();
        assertThat(items()).isZero();
    }

    @Test
    void 正式表_可接的任务与不可接的错误码同基线_只读闸不留状态() {
        for (int id : new int[] {1, 2, 10, 11}) {
            assertThat(service.accept(player, 0, id)).as("mission %d", id).isEqualTo(1003);
        }
        for (int id : new int[] {4, 7, 8, 9, 12, 13, 14}) {
            assertThat(service.checkAccept(player, 0, id, clock.epochMillis())).as("mission %d", id).isZero();
        }
        for (int id : new int[] {15, 16, 17}) {
            assertThat(service.accept(player, 0, id)).as("mission %d", id).isEqualTo(1006);
        }
        assertThat(missions().isPristine()).isTrue();
    }

    @Test
    void 接取记下接取时间_重复接5004_同类型5000_完成后再接5001() {
        clock.advanceMillis(1234);
        assertThat(service.accept(player, 0, 4)).isZero();
        assertThat(missions().active(4).acceptedAtMs()).isEqualTo(ManualClock.EPOCH_MILLIS_START + 1234);
        assertThat(missions().active(4).slots()).isEqualTo(1);
        assertThat(service.accept(player, 0, 4)).isEqualTo(5004);
        assertThat(service.accept(player, 0, 7)).isEqualTo(5000);
        assertThat(service.checkAccept(player, 0, 1, clock.epochMillis())).as("类型闸先于逐格闸").isEqualTo(5000);
        assertThat(service.checkAccept(player, 0, 5, clock.epochMillis())).as("无条件先于类型闸").isEqualTo(1002);

        kill(1, 1);

        assertThat(missions().isComplete(4)).isTrue();
        assertThat(service.accept(player, 0, 4)).isEqualTo(5001);
        assertThat(missions().typeOccupied(1, 1)).as("完成腾出类型").isFalse();
        assertThat(service.accept(player, 0, 12)).isZero();
    }

    @Test
    void 待领但未完成的存档也算已完成_接取回5001() {
        player = withMissions(state().addClaimableIds(4).build());
        assertThat(service.accept(player, 0, 4)).isEqualTo(5001);
    }

    // ------------------------------------------------------------------ 回填

    @Test
    void 接取回填已完成的前置任务_只推进新接的任务_每个历史完成只算一次() {
        use(edit().mission(mission(13).clearConditionId().addConditionId(27).clearTargetCount().addTargetCount(2))
                .build());
        player = withMissions(state().addCompletedIds(15).build());

        assertThat(service.accept(player, 0, 13)).isZero();
        assertThat(progress(13, 0)).isEqualTo(1);
        assertThat(service.accept(player, 0, 14)).isZero();
        assertThat(progress(14, 0)).isEqualTo(1);
        assertThat(progress(14, 1)).isZero();
        assertThat(progress(13, 0)).as("不重放给已在进行中的任务").isEqualTo(1);
        assertThat(service.accept(player, 0, 14)).isEqualTo(5004);
        assertThat(progress(13, 0)).isEqualTo(1);
        assertThat(items()).isZero();
    }

    @Test
    void 前置都已完成时接取当场完成_仍回成功_自动领奖到账() {
        player = withMissions(state().addCompletedIds(15).addCompletedIds(16).build());

        assertThat(service.accept(player, 0, 14)).isZero();

        assertThat(missions().isAccepted(14)).isFalse();
        assertThat(missions().isComplete(14)).isTrue();
        assertThat(missions().isClaimable(14)).as("自动领奖成功清待领").isFalse();
        assertThat(items()).isEqualTo(4);
        assertThat(service.accept(player, 0, 14)).isEqualTo(5001);
    }

    @Test
    void 当场完成但背包满_自动领奖失败保留待领() {
        player = withInventory(3, state().addCompletedIds(15).addCompletedIds(16).build());

        assertThat(service.accept(player, 0, 14)).isZero();

        assertThat(missions().isComplete(14)).isTrue();
        assertThat(missions().isClaimable(14)).isTrue();
        assertThat(items()).isZero();
        assertThat(questRewards()).isEmpty();
    }

    @Test
    void 接取回填等级_低于阈值不推进_达到阈值按原始等级覆盖_只推进新接的任务() {
        use(edit().condition(condition(24).setTargetCount(40))
                .mission(mission(4).clearConditionId().addConditionId(24).clearTargetCount().setRewardId(0))
                .mission(newMission(60, 6, 1, 0, 24))
                .build());
        player = load(PlayerState.getDefaultInstance(), 5);
        assertThat(service.accept(player, 0, 4)).isZero();
        assertThat(progress(4, 0)).as("等级 5 不到阈值 10").isZero();

        player.setLevel(12);
        assertThat(service.accept(player, 0, 60)).isZero();
        assertThat(progress(60, 0)).isEqualTo(12);
        assertThat(progress(4, 0)).as("回填只推进新接的任务").isZero();

        player.setLevel(40);
        service.onLevelChanged(player);
        assertThat(missions().isComplete(4)).isTrue();
        assertThat(missions().isComplete(60)).isTrue();
        assertThat(missions().isClaimable(4)).as("没有奖励的任务只记完成").isFalse();
        assertThat(service.claim(player, 0, 4)).isEqualTo(12000);
    }

    // ------------------------------------------------------------------ 推进

    @Test
    void 等级事实覆盖进度_两次20还是20_到40完成() {
        use(edit().condition(condition(24).setTargetCount(40))
                .mission(mission(4).clearConditionId().addConditionId(24).clearTargetCount().setRewardId(0))
                .build());
        player = load(PlayerState.getDefaultInstance(), 1);
        assertThat(service.accept(player, 0, 4)).isZero();
        fact(6, 20, 20);
        fact(6, 20, 20);
        assertThat(progress(4, 0)).isEqualTo(20);
        fact(6, 40, 40);
        assertThat(missions().isComplete(4)).isTrue();
    }

    @Test
    void 并行任务一条事实推进所有命中的格子() {
        use(edit().reachAllMonsters().build());
        assertThat(service.accept(player, 0, 1)).isZero();
        kill(1, 1);
        kill(2, 1);
        kill(3, 1);
        assertThat(missions().isAccepted(1)).isTrue();
        kill(4, 1);
        assertThat(missions().isAccepted(1)).isFalse();
        assertThat(missions().isComplete(1)).isTrue();
        assertThat(missions().typeOccupied(1, 1)).isFalse();
        assertThat(missions().watchers(1)).isEmpty();
    }

    @Test
    void 顺序任务只推进第一个未达成的格子_不跳格() {
        use(edit().reachAllMonsters().build());
        assertThat(service.accept(player, 0, 2)).isZero();
        kill(2, 2);
        assertThat(progress(2, 1)).isZero();
        kill(1, 1);
        kill(2, 2);
        kill(3, 1);
        kill(4, 2);
        assertThat(progress(2, 3)).isEqualTo(2);
        assertThat(progress(2, 4)).isZero();
        kill(4, 2);
        assertThat(missions().isAccepted(2)).isTrue();
        kill(4, 2);
        assertThat(missions().isComplete(2)).isTrue();
        assertThat(missions().isClaimable(2)).as("手动领奖任务完成后待领").isTrue();
    }

    @Test
    void 顺序任务一条事实只推进一格_多出的量不顺延() {
        use(edit().reachAllMonsters().build());
        assertThat(service.accept(player, 0, 1)).isZero();
        assertThat(service.accept(player, 0, 2)).isZero();
        for (int monster = 1; monster <= 4; monster++) {
            fact(1, monster, 4);
        }
        assertThat(missions().isComplete(1)).isTrue();
        PlayerMissions.Active two = missions().active(2);
        assertThat(new long[] {two.progress(0), two.progress(1), two.progress(2), two.progress(3), two.progress(4),
                two.progress(5)}).containsExactly(1, 2, 1, 2, 0, 0);
        fact(1, 4, 4);
        assertThat(missions().isAccepted(2)).isTrue();
        fact(1, 4, 4);
        assertThat(missions().isComplete(2)).isTrue();
    }

    @Test
    void 累计进度封顶到目标() {
        assertThat(service.accept(player, 0, 13)).isZero();
        kill(1, 1);
        assertThat(missions().isAccepted(13)).isTrue();
        assertThat(progress(13, 0)).isEqualTo(1);
        kill(1, 1);
        assertThat(missions().isComplete(13)).isTrue();

        assertThat(service.accept(player, 0, 7)).isZero();
        fact(1, 1, U32);
        assertThat(missions().isComplete(7)).isTrue();
    }

    @Test
    void 存档格数与条件数不符_永不推进永不完成() {
        use(edit().reachAllMonsters().build());
        player = withMissions(state().addActive(active(1, 0, 0, 0, 0, 0)).build());
        for (int monster = 1; monster <= 4; monster++) {
            fact(1, monster, U32);
        }
        assertThat(missions().isAccepted(1)).isTrue();
        assertThat(missions().active(1).progress(0)).isZero();
    }

    @Test
    void 一条击杀事实推进所有关注它的进行中任务() {
        assertThat(service.accept(player, 0, 4)).isZero();
        assertThat(service.accept(player, 0, 13)).isZero();
        kill(1, 1);
        assertThat(missions().isComplete(4)).isTrue();
        assertThat(progress(13, 0)).isEqualTo(1);
    }

    @Test
    void 定向事实只推进指定任务_不影响正常扇出() {
        player = withMissions(state().addActive(active(7, 0)).addActive(active(8, 0)).build());
        service.dispatch(player, MissionFact.of(1, 1, 1).onlyFor(7));
        assertThat(progress(7, 0)).isEqualTo(1);
        assertThat(progress(8, 0)).isZero();
        kill(1, 1);
        assertThat(progress(7, 0)).isEqualTo(2);
        assertThat(progress(8, 0)).isEqualTo(1);
    }

    @Test
    void 没有参数的事实与怪物号0什么都不推进() {
        // 条件 25 = 击杀任意怪（condition1 为空，参数匹配对任何事件都命中），只有两道闸能拦住
        use(edit().mission(mission(4).clearConditionId().addConditionId(25).clearTargetCount()).build());
        player = load(PlayerState.getDefaultInstance(), 1);
        assertThat(service.accept(player, 0, 4)).isZero();
        service.dispatch(player, new MissionFact(1, List.of(), 1, 0));
        kill(0, 5);
        assertThat(missions().isAccepted(4)).isTrue();
        assertThat(progress(4, 0)).isZero();
        kill(77, 1);
        assertThat(missions().isComplete(4)).as("任意怪的条件对真实怪物号照常推进").isTrue();
    }

    @Test
    void 没有参数的等级事实不推进() {
        // 等级类别按量匹配、不看参数：只有空参数闸能拦住
        use(edit().condition(condition(24).setTargetCount(40))
                .mission(mission(4).clearConditionId().addConditionId(24).clearTargetCount().setRewardId(0))
                .build());
        player = load(PlayerState.getDefaultInstance(), 1);
        assertThat(service.accept(player, 0, 4)).isZero();
        service.dispatch(player, new MissionFact(6, List.of(), 99, 0));
        assertThat(missions().isAccepted(4)).isTrue();
        assertThat(progress(4, 0)).isZero();
    }

    // ------------------------------------------------------------------ 完成连锁

    @Test
    void 自动领奖任务完成即发奖_链式接后续任务_后续不可接时只记日志() {
        assertThat(service.accept(player, 0, 7)).isZero();
        kill(1, 8);
        assertThat(missions().isComplete(7)).isTrue();
        assertThat(missions().isClaimable(7)).isFalse();
        assertThat(items()).isEqualTo(4);
        assertThat(missions().isAccepted(8)).as("7 → 8 自动接").isTrue();
        assertThat(progress(8, 0)).as("完成 7 的那次击杀不算给 8").isZero();

        kill(1, 9);
        assertThat(missions().isAccepted(9)).isTrue();
        kill(2, 10);
        assertThat(missions().isComplete(9)).isTrue();
        assertThat(missions().isAccepted(10)).as("10 的怪打不到（1003），链断").isFalse();
        assertThat(items()).isEqualTo(12);
        assertThat(questRewards()).extracting(Item::extra)
                .containsExactly("{\"mission_id\":7}", "{\"mission_id\":8}", "{\"mission_id\":9}");
        assertThat(questRewards()).allSatisfy(item -> {
            assertThat(item.quantity()).isEqualTo(4);
            assertThat(item.correlationId()).isZero();
            assertThat(item.configId()).isEqualTo(1);
        });
    }

    @Test
    void 完成任务事实推进已在进行中的任务_同一事实完成的任务按任务号升序处理() {
        use(edit().openSchedule(15).openSchedule(16).build());
        assertThat(service.accept(player, 0, 14)).isZero();
        assertThat(service.accept(player, 0, 15)).isZero();
        assertThat(service.accept(player, 0, 16)).isZero();

        kill(1, 1);

        assertThat(missions().isComplete(15)).isTrue();
        assertThat(missions().isComplete(16)).isTrue();
        assertThat(missions().isComplete(14)).isTrue();
        assertThat(questRewards()).extracting(Item::extra)
                .containsExactly("{\"mission_id\":15}", "{\"mission_id\":16}", "{\"mission_id\":14}");
    }

    @Test
    void 后续任务关注前置完成_先送完成事实再接后续_不重复计数() {
        use(edit().condition(newCondition(900, 8, 2, 7))
                .mission(newMission(50, 5, 1, 0, 900))
                .mission(mission(7).clearNextMissionId().addNextMissionId(50))
                .build());
        assertThat(service.accept(player, 0, 7)).isZero();
        kill(1, 8);
        assertThat(missions().isAccepted(50)).isTrue();
        assertThat(progress(50, 0)).isEqualTo(1);
    }

    @Test
    void 没有奖励的任务完成只记完成() {
        use(edit().mission(mission(4).setRewardId(0)).build());
        assertThat(service.accept(player, 0, 4)).isZero();
        kill(1, 1);
        assertThat(missions().isComplete(4)).isTrue();
        assertThat(missions().isClaimable(4)).isFalse();
        assertThat(service.claim(player, 0, 4)).isEqualTo(12000);
        assertThat(items()).isZero();
    }

    // ------------------------------------------------------------------ 领奖

    @Test
    void 领奖整批发一次_清待领保留完成_重复领12000_scope非0回1005() {
        assertThat(service.claim(player, 0, 12)).as("还没完成").isEqualTo(5002);
        assertThat(service.accept(player, 0, 12)).isZero();
        assertThat(service.claim(player, 0, 12)).isEqualTo(5002);
        kill(1, 1);
        assertThat(missions().isClaimable(12)).isTrue();
        assertThat(service.claim(player, 1, 12)).isEqualTo(1005);
        assertThat(service.checkClaim(player, 0, 12)).isZero();

        assertThat(service.claim(player, 0, 12)).isZero();

        assertThat(items()).isEqualTo(4);
        assertThat(missions().isClaimable(12)).isFalse();
        assertThat(missions().isComplete(12)).isTrue();
        assertThat(questRewards()).hasSize(1);
        assertThat(questRewards().get(0).extra()).isEqualTo("{\"mission_id\":12}");
        assertThat(service.claim(player, 0, 12)).isEqualTo(12000);
        assertThat(items()).isEqualTo(4);
    }

    @Test
    void 待领却未完成1002_未知任务1002_都保留领奖资格() {
        player = withMissions(state().addClaimableIds(12).addCompletedIds(999_999).addClaimableIds(999_999).build());
        assertThat(service.claim(player, 0, 12)).isEqualTo(1002);
        assertThat(service.claim(player, 0, 999_999)).isEqualTo(1002);
        assertThat(missions().isClaimable(12)).isTrue();
        assertThat(missions().isClaimable(999_999)).isTrue();
        assertThat(items()).isZero();
    }

    @Test
    void 奖励配置缺失或奖励号0_领奖1002() {
        use(edit().removeReward(1).build());
        player = withMissions(state().addCompletedIds(12).addClaimableIds(12).build());
        assertThat(service.claim(player, 0, 12)).isEqualTo(1002);
        use(edit().mission(mission(12).setRewardId(0)).build());
        assertThat(service.claim(player, 0, 12)).isEqualTo(1002);
        assertThat(missions().isClaimable(12)).isTrue();
    }

    @Test
    void 满包6006保留领奖资格_领奖闸不看空间_扩容后重领成功() {
        MissionState ready = state().addCompletedIds(12).addClaimableIds(12).build();
        player = withInventory(3, ready);
        assertThat(service.checkClaim(player, 0, 12)).isZero();
        assertThat(service.checkClaim(player, 0, 12)).isZero();
        assertThat(service.claim(player, 0, 12)).isEqualTo(6006);
        assertThat(missions().isClaimable(12)).isTrue();
        assertThat(items()).isZero();

        player = withInventory(4, missions().toState());
        assertThat(service.claim(player, 0, 12)).isZero();
        assertThat(items()).isEqualTo(4);
    }

    @Test
    void 物品guid不够整批_6004_零写入保留领奖资格() {
        player = withMissions(state().addCompletedIds(12).addClaimableIds(12).build());
        guidBudget = 2;
        assertThat(service.claim(player, 0, 12)).isEqualTo(6004);
        assertThat(guidBudget).isEqualTo(2);
        assertThat(items()).isZero();
        assertThat(missions().isClaimable(12)).isTrue();
        guidBudget = 4;
        assertThat(service.claim(player, 0, 12)).isZero();
        assertThat(guidBudget).isZero();
    }

    @Test
    void 奖励物品全服禁发_1005保留领奖资格() {
        bags.applyGlobalBlocks(new GlobalGainBlocks(Set.of(), Set.of(1)));
        player = withMissions(state().addCompletedIds(12).addClaimableIds(12).build());
        assertThat(service.checkClaim(player, 0, 12)).isZero();
        assertThat(service.claim(player, 0, 12)).isEqualTo(1005);
        assertThat(missions().isClaimable(12)).isTrue();
        assertThat(items()).isZero();
    }

    @Test
    void 自动领奖失败保留待领_手动领成功后不会再发() {
        player = withInventory(3, null);
        assertThat(service.accept(player, 0, 4)).isZero();
        kill(1, 1);
        assertThat(missions().isClaimable(4)).isTrue();
        assertThat(items()).isZero();

        player = withInventory(4, missions().toState());
        assertThat(service.claim(player, 0, 4)).isZero();
        assertThat(items()).isEqualTo(4);
        assertThat(service.claim(player, 0, 4)).isEqualTo(12000);
        assertThat(items()).isEqualTo(4);
    }

    // ------------------------------------------------------------------ 加载

    @Test
    void 存档往返_进度接取时间类型占用都在_加载不触发任何连带() {
        clock.advanceMillis(77);
        assertThat(service.accept(player, 0, 7)).isZero();
        kill(1, 3);
        MissionState saved = missions().toState();
        long acceptedAt = missions().active(7).acceptedAtMs();

        player = withMissions(saved.toBuilder().addCompletedIds(12).addClaimableIds(12).build());

        assertThat(progress(7, 0)).isEqualTo(3);
        assertThat(missions().active(7).acceptedAtMs()).isEqualTo(acceptedAt);
        assertThat(missions().typeOccupied(1, 1)).isTrue();
        assertThat(service.accept(player, 0, 4)).isEqualTo(5000);
        assertThat(missions().isClaimable(12)).isTrue();
        assertThat(items()).isZero();
        assertThat(audit.items).isEmpty();

        kill(1, 5);
        assertThat(missions().isComplete(7)).isTrue();
    }

    @Test
    void 已经达成的存档进度加载时不完成_表里没有或已完成的进行中任务不进索引() {
        player = withMissions(state()
                .addActive(active(7, 8))
                .addActive(active(4, 0))
                .addCompletedIds(4)
                .addActive(active(999_998, 123))
                .build());
        assertThat(missions().isAccepted(7)).isTrue();
        assertThat(missions().isComplete(7)).isFalse();
        assertThat(missions().watchers(1)).containsExactly(7);
        assertThat(audit.items).isEmpty();
        assertThat(service.accept(player, 0, 999_998)).isEqualTo(1001);
    }
}
