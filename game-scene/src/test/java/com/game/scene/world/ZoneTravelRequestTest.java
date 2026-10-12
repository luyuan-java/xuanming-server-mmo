package com.game.scene.world;

import static com.game.scene.world.TravelFixture.LINK;
import static com.game.scene.world.TravelFixture.LOCAL_ZONE;
import static com.game.scene.world.TravelFixture.REDIRECT;
import static com.game.scene.world.TravelFixture.RESOLVE_TIMEOUT;
import static com.game.scene.world.TravelFixture.TARGET_MAP;
import static com.game.scene.world.TravelFixture.TARGET_ZONE;
import static com.game.scene.world.TravelFixture.TEAM_CHECK_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ZoneRedirect;
import com.game.player.store.PlayerStore.HandOffMode;
import com.game.proto.MessageContent;
import com.game.scene.battle.BattleFixture;
import com.game.scene.battle.BattleFreeze;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.FakeTeamChecks.PendingCheck;
import com.game.scene.testing.FakeTravelTargets.PendingTravel;
import com.game.scene.world.TravelTargets.TravelSelection;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 跨 zone 传送（批次 5.4，zone-travel-spec §5.5、§11.5）在冻结之前的部分：226 的注册、同步段四步与「没装配」、PRECHECK（延迟应答、
 * 在队检查、槽过期作废并补回 3027）、RESOLVING（选目标的四种结果、槽过期作废并推 23 {3027}、迟到结果丢弃）、与 63 / 5.2 在途 / 回合制战斗 /
 * 5.3 实例的交错，以及 {@code game.scene.travel.requests} 每条 226、{@code resolves} 每次受理恰好计一次。冻结之后的结局在
 * {@link ZoneTravelHandOffTest}。
 */
class ZoneTravelRequestTest {

    private static final long E = 5;
    private static final int ZONE_INVALID = 3024;
    private static final int MAP_INVALID = 3007;
    private static final int IN_BATTLE = 3025;
    private static final int IN_TEAM = 3026;
    private static final int TRAVEL_FAILED = 3027;
    private static final int TRANSFER_IN_PROGRESS = 13000;
    private static final int CHANGING_SCENE = 3014;

    private TravelFixture f;
    private ScenePlayer a;
    private ScenePlayer b;

    @BeforeEach
    void setUp() {
        f = new TravelFixture();
        a = f.enter(11, 1001, f.scene1, E);
        b = f.enter(12, 1002, f.scene1, 1);
    }

    // ------------------------------------------------------------------ 注册与同步段

    @Test
    void 注册_226冻结闸与战斗闸都放行_它自己判在途与战斗() {
        int travelToZone = Contracts.IDS.travelToZone();

        assertThat(f.handler.freezePolicy(travelToZone)).isEqualTo(FreezePolicy.ALLOW);
        assertThat(f.handler.battlePolicy(travelToZone)).isEqualTo(BattlePolicy.GATED);
    }

    @Test
    void 同步段_目标zone为0或就是本zone_回3024_不挂槽不冻结不碰存储() {
        long zero = f.travel(a, 0, 0);
        long self = f.travel(a, LOCAL_ZONE, 0);

        assertThat(tipFor(11, zero)).isEqualTo(ZONE_INVALID);
        assertThat(tipFor(11, self)).isEqualTo(ZONE_INVALID);
        assertUntouched(a);
        assertThat(f.requests("zone_invalid")).isEqualTo(2);
        assertThat(f.requestsTotal()).as("每条 226 恰好计一次").isEqualTo(2);
    }

    @Test
    void 同步段_地图不在World表回3007_副本地图与超出int的uint32同样拒() {
        long notWorld = f.travel(a, TARGET_ZONE, 3);
        long dungeon = f.travel(a, TARGET_ZONE, FakeSceneTables.DUNGEON_MAP);
        long huge = f.travel(a, TARGET_ZONE, (int) 4_000_000_000L);

        assertThat(List.of(tipFor(11, notWorld), tipFor(11, dungeon), tipFor(11, huge))).containsOnly(MAP_INVALID);
        assertUntouched(a);
        assertThat(f.requests("map_invalid")).isEqualTo(3);
        assertThat(f.requestsTotal()).isEqualTo(3);
    }

    @Test
    void 同步段_World表为空时不拦地图_照常进在队检查() {
        TravelFixture empty = new TravelFixture(new NoWorldMaps(), true);
        ScenePlayer p = empty.enter(11, 1001, empty.scene1, E);

        empty.travel(p, TARGET_ZONE, 99);

        assertThat(p.switchPhase()).isEqualTo(SwitchPhase.PRECHECK);
        assertThat(empty.teamChecks.calls()).hasSize(1);
        empty.teamChecks.take().notInTeam();
        assertThat(empty.targets.take().wantSceneConfigId()).as("地图原样交给 scene-manager 判").isEqualTo(99);
    }

    @Test
    void 同步段_回合制战斗在途回3025_计zone_travel闸_解冻后照常受理() {
        BattleFixture.setFreeze(a, fighting());

        long id = f.travel(a, TARGET_ZONE, 0);

        assertThat(tipFor(11, id)).isEqualTo(IN_BATTLE);
        assertUntouched(a);
        assertThat(f.requests("in_battle")).isEqualTo(1);
        assertThat(f.battleGateRejects("zone_travel")).isEqualTo(1);
        assertThat(f.battleGateRejects("enter_scene")).as("不计在 63 的闸上").isZero();

        BattleFixture.setFreeze(a, null);
        f.travel(a, TARGET_ZONE, 0);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.PRECHECK);
    }

    /**
     * 第 4 步（§5.5 表）：任一阶段的跨 zone 传送、跨节点换图的冻结 → 13000；跨节点换图的选目标、镜像取号 → 3014。原有在途槽一概不变。
     */
    @Test
    void 同步段_在途_传送任一阶段与交出冻结回13000_63的选目标与镜像取号回3014() {
        ScenePlayer resolving = f.enter(13, 1003, f.scene1, 1);
        ScenePlayer travelFrozen = f.enter(14, 1004, f.scene1, 1);
        ScenePlayer switchFrozen = f.enter(15, 1005, f.scene1, 1);
        ScenePlayer switchResolving = f.enter(16, 1006, f.scene1, 1);
        ScenePlayer mirror = f.enter(17, 1007, f.scene1, 1);
        f.precheck(a);
        f.resolve(resolving);
        f.freeze(travelFrozen);
        f.resolveRemote(switchFrozen).chosen(4, 900_001, 2);
        f.resolveRemote(switchResolving);
        WorldTestAccess.startMirrorResolving(mirror);
        int teamChecks = f.teamChecks.calls().size();
        int handOffs = f.repo.pendingHandOffs();

        assertThat(tipFor(11, f.travel(a, TARGET_ZONE, 0))).as("PRECHECK").isEqualTo(TRANSFER_IN_PROGRESS);
        assertThat(tipFor(13, f.travel(resolving, TARGET_ZONE, 0))).as("RESOLVING").isEqualTo(TRANSFER_IN_PROGRESS);
        assertThat(tipFor(14, f.travel(travelFrozen, TARGET_ZONE, 0))).as("传送冻结中").isEqualTo(TRANSFER_IN_PROGRESS);
        assertThat(tipFor(15, f.travel(switchFrozen, TARGET_ZONE, 0))).as("跨节点换图冻结中").isEqualTo(TRANSFER_IN_PROGRESS);
        assertThat(tipFor(16, f.travel(switchResolving, TARGET_ZONE, 0))).as("63 选目标中").isEqualTo(CHANGING_SCENE);
        assertThat(tipFor(17, f.travel(mirror, TARGET_ZONE, 0))).as("镜像取号中").isEqualTo(CHANGING_SCENE);

        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.PRECHECK);
        assertThat(resolving.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        assertThat(travelFrozen.switchPhase()).isEqualTo(SwitchPhase.FREEZING);
        assertThat(switchFrozen.switchPhase()).isEqualTo(SwitchPhase.FREEZING);
        assertThat(switchResolving.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        assertThat(mirror.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        assertThat(f.teamChecks.calls()).as("被挡的不查在队").hasSize(teamChecks);
        assertThat(f.repo.pendingHandOffs()).as("被挡的不碰存储").isEqualTo(handOffs);
        assertThat(f.requests("busy_transfer")).isEqualTo(4);
        assertThat(f.requests("busy_switch")).isEqualTo(2);
    }

    @Test
    void 同步段_同时满足多条_回最先命中的那条() {
        f.precheck(a);
        BattleFixture.setFreeze(a, fighting());

        assertThat(tipFor(11, f.travel(a, 0, 3))).as("zone 先于地图、战斗、在途").isEqualTo(ZONE_INVALID);
        assertThat(tipFor(11, f.travel(a, TARGET_ZONE, 3))).as("地图先于战斗、在途").isEqualTo(MAP_INVALID);
        assertThat(tipFor(11, f.travel(a, TARGET_ZONE, 0))).as("战斗先于在途").isEqualTo(IN_BATTLE);
        assertThat(a.switchPhase()).as("原来的 PRECHECK 不动").isEqualTo(SwitchPhase.PRECHECK);
    }

    @Test
    void 没装配跨zone传送_同步段照判_都过了同步回3027() {
        TravelFixture disabled = new TravelFixture(new FakeSceneTables(), false);
        ScenePlayer p = disabled.enter(11, 1001, disabled.scene1, E);

        long zero = disabled.travel(p, 0, 0);
        long other = disabled.travel(p, TARGET_ZONE, 0);

        assertThat(tipFor(disabled, 11, zero)).isEqualTo(ZONE_INVALID);
        assertThat(tipFor(disabled, 11, other)).isEqualTo(TRAVEL_FAILED);
        assertThat(p.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(disabled.requests("unavailable")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ PRECHECK：延迟应答

    @Test
    void 受理应答_信封号226与请求号_error_message编码为0a00_在队检查读回来之后才到() {
        long id = f.travel(a, TARGET_ZONE, 0);

        assertThat(f.sink.to(LINK, 11)).as("处理器返回时没有应答，分发处也没补 1006").isEmpty();
        PendingCheck check = f.teamChecks.take();
        assertThat(check.playerId()).isEqualTo(1001);
        assertThat(check.timeout()).as("在队检查上限 = team-check-timeout").isEqualTo(TEAM_CHECK_TIMEOUT);

        check.notInTeam();

        MessageContent reply = f.travelReplies(11).getFirst();
        assertThat(reply.getMessageId()).isEqualTo(226);
        assertThat(reply.getId()).isEqualTo(id);
        assertThat(reply.hasErrorMessage()).isFalse();
        assertThat(reply.getSerializedMessage().toByteArray()).as("受理：error_message 是空子消息")
                .containsExactly((byte) 0x0a, (byte) 0x00);
        assertThat(f.requests("accepted")).isEqualTo(1);
        assertThat(f.requestsTotal()).isEqualTo(1);
    }

    @Test
    void 在队_回3026_槽清掉_不选目标_可再发() {
        long id = f.travel(a, TARGET_ZONE, 0);

        f.teamChecks.take().inTeam();

        assertThat(tipFor(11, id)).isEqualTo(IN_TEAM);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.targets.calls()).isEmpty();
        assertThat(f.requests("in_team")).isEqualTo(1);
        f.travel(a, TARGET_ZONE, 0);
        assertThat(a.switchPhase()).as("可再发").isEqualTo(SwitchPhase.PRECHECK);
    }

    @Test
    void 在队检查判定不了_读失败或超时_回3027_failclosed() {
        long id = f.travel(a, TARGET_ZONE, 0);

        f.teamChecks.take().unknown();

        assertThat(tipFor(11, id)).isEqualTo(TRAVEL_FAILED);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.targets.calls()).isEmpty();
        assertThat(f.requests("team_read_error")).isEqualTo(1);
    }

    /** 不在队：先回 {@code {0}}，再发起选目标（选目标被调用的那一刻应答已在出站里），之后的 23 恒在 {@code {0}} 之后。 */
    @Test
    void 不在队_先回0再发起选目标_带上玩家目标zone与地图() {
        List<Integer> repliesWhenSelecting = new ArrayList<>();
        f.targets.replyWith(task -> repliesWhenSelecting.add(f.travelReplies(11).size()), call -> new TravelSelection.Failed("不跑"));
        f.travel(a, TARGET_ZONE, TARGET_MAP);

        f.teamChecks.take().notInTeam();

        assertThat(repliesWhenSelecting).as("发起选目标时 {0} 已经发出").containsExactly(1);
        assertThat(f.travelTips(11)).containsExactly(0);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        assertThat(a.frozen()).as("RESOLVING 不冻结").isFalse();
        PendingTravel call = f.targets.calls().getFirst();
        assertThat(call.playerId()).isEqualTo(1001);
        assertThat(call.toZoneId()).isEqualTo(TARGET_ZONE);
        assertThat(call.wantSceneConfigId()).isEqualTo(TARGET_MAP);
    }

    @Test
    void 在队检查期间_63回3014_226回13000_不冻结照常游玩() {
        long first = f.travel(a, TARGET_ZONE, 0);
        PendingCheck check = f.teamChecks.take();

        long enter = f.enterScene(a, f.scene2.sceneId(), 2);
        long second = f.travel(a, TARGET_ZONE, 0);

        assertThat(f.enterSceneTips(11)).containsExactly(CHANGING_SCENE);
        assertThat(tipFor(11, second)).isEqualTo(TRANSFER_IN_PROGRESS);
        assertThat(enter).isNotEqualTo(first);
        assertThat(a.frozen()).as("PRECHECK 不冻结").isFalse();
        assertThat(f.world.requestSave(a)).as("照常在线存盘").isEqualTo(SceneWorld.SaveRequest.WRITTEN);

        check.notInTeam();
        assertThat(tipFor(11, first)).isZero();
    }

    /** X21：第一条的 {0} 是延迟应答，第二条的同步 13000 先到；客户端按信封 id 配对，两条各恰好一个应答。 */
    @Test
    void 两条226连发_第二条同步13000先到_按id各自配对() {
        long first = f.travel(a, TARGET_ZONE, 0);
        long second = f.travel(a, TARGET_ZONE, 0);

        assertThat(f.travelReplies(11)).extracting(MessageContent::getId).containsExactly(second);
        f.teamChecks.take().notInTeam();

        List<MessageContent> replies = f.travelReplies(11);
        assertThat(replies).extracting(MessageContent::getId).containsExactly(second, first);
        assertThat(replies).extracting(TravelFixture::travelTip).containsExactly(TRANSFER_IN_PROGRESS, 0);
        assertThat(f.teamChecks.calls()).as("第二条不查在队").hasSize(1);
        assertThat(f.requests("busy_transfer")).isEqualTo(1);
        assertThat(f.requests("accepted")).isEqualTo(1);
        assertThat(f.requestsTotal()).isEqualTo(2);
    }

    @Test
    void 在队检查读回来之前玩家离开_不回应答_不选目标_计team_read_error() {
        PendingCheck check = f.precheck(a);
        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 1001));
        f.sink.clear();

        check.notInTeam();

        assertThat(f.travelReplies(11)).as("会话已走：延迟应答作废、不回").isEmpty();
        assertThat(f.targets.calls()).isEmpty();
        assertThat(f.requests("team_read_error")).isEqualTo(1);
        assertThat(f.requestsTotal()).isEqualTo(1);
    }

    @Test
    void 在队检查期间同会话重新进场换了实例_迟到结果不回不选_新实例不受影响() {
        PendingCheck check = f.precheck(a);
        f.repo.putNewPlayer(1001, E + 1);
        f.world.onPlayerEnter(LINK, SceneWorldTest.enterFrame(11, 1001, f.scene1.sceneId(), E + 1));
        f.repo.completeAll();
        ScenePlayer reentered = f.world.playerById(1001);
        assertThat(reentered).isNotSameAs(a);
        f.sink.clear();

        check.notInTeam();

        assertThat(f.travelReplies(11)).isEmpty();
        assertThat(f.targets.calls()).isEmpty();
        assertThat(reentered.switchPhase()).isEqualTo(SwitchPhase.NONE);
    }

    @Test
    void 在队检查读回来时已有回合制战斗在途_回3025_不选目标() {
        long id = f.travel(a, TARGET_ZONE, 0);
        PendingCheck check = f.teamChecks.take();
        BattleFixture.setFreeze(a, fighting());

        check.notInTeam();

        assertThat(tipFor(11, id)).isEqualTo(IN_BATTLE);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.targets.calls()).isEmpty();
        assertThat(f.requests("in_battle")).isEqualTo(1);
        assertThat(f.battleGateRejects("zone_travel")).as("不是闸拒，闸只在同步段计").isZero();
    }

    // ------------------------------------------------------------------ PRECHECK：槽过期（TF17）

    /**
     * 在队检查的结果迟迟不回（回调丢了，正常不会）：槽在上限 + 1 s 后过期。下一条 63 经 {@code switchInFlight} 作废它——先回 226 的 3027、
     * 再清槽，63 自己照常办（不再是 3014）；之后迟到的在队结果不二次应答、不选目标。
     */
    @Test
    void PRECHECK槽过期_63作废方先补回3027再清槽_63照常办_迟到结果不二次应答() {
        long id = f.travel(a, TARGET_ZONE, 0);
        PendingCheck check = f.teamChecks.take();
        f.clock.advanceMillis(TEAM_CHECK_TIMEOUT.toMillis() + 999);
        f.enterScene(a, f.scene2.sceneId(), 2);
        assertThat(f.enterSceneTips(11)).as("期限前仍挡").containsExactly(CHANGING_SCENE);
        assertThat(f.travelReplies(11)).isEmpty();

        f.clock.advanceMillis(1);
        f.enterScene(a, f.scene2.sceneId(), 2);

        List<MessageContent> all = f.sink.to(LINK, 11);
        int travelReply = indexOf(all, 226, id);
        int enterReply = lastIndexOf(all, Contracts.IDS.enterScene());
        assertThat(travelReply).as("作废方先回 226 的 3027").isGreaterThanOrEqualTo(0);
        assertThat(TravelFixture.travelTip(all.get(travelReply))).isEqualTo(TRAVEL_FAILED);
        assertThat(travelReply).as("226 的 3027 在这条 63 的应答之前").isLessThan(enterReply);
        assertThat(f.enterSceneTips(11)).containsExactly(CHANGING_SCENE, 0);
        assertThat(a.scene()).isSameAs(f.scene2);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);

        check.notInTeam();

        assertThat(f.travelReplies(11)).as("迟到的结果不会造成第二个应答").hasSize(1);
        assertThat(f.targets.calls()).isEmpty();
        assertThat(f.requests("team_read_error")).isEqualTo(1);
        assertThat(f.requestsTotal()).isEqualTo(1);
    }

    @Test
    void PRECHECK槽过期_下一条226作废旧的回3027_新的照常受理() {
        long first = f.travel(a, TARGET_ZONE, 0);
        PendingCheck stale = f.teamChecks.take();
        f.clock.advanceMillis(TEAM_CHECK_TIMEOUT.toMillis() + 1000);

        long second = f.travel(a, TARGET_ZONE, 0);

        assertThat(f.travelReplies(11)).extracting(MessageContent::getId).containsExactly(first);
        assertThat(tipFor(11, first)).isEqualTo(TRAVEL_FAILED);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.PRECHECK);
        PendingCheck fresh = f.teamChecks.take();
        stale.notInTeam();
        assertThat(f.travelReplies(11)).as("旧的迟到结果丢弃").hasSize(1);
        assertThat(f.targets.calls()).isEmpty();

        fresh.notInTeam();

        assertThat(f.travelReplies(11)).extracting(MessageContent::getId).containsExactly(first, second);
        assertThat(tipFor(11, second)).isZero();
        assertThat(f.requests("team_read_error")).isEqualTo(1);
        assertThat(f.requests("accepted")).isEqualTo(1);
    }

    /**
     * 玩家之后什么都不发、也没有别的调用方碰它：每秒的维护（{@code refreshDueLocations}）在槽过期后作废它、补回 3027——应答一定到，
     * 期限之前不动。
     */
    @Test
    void PRECHECK槽过期_没人再碰这名玩家_每秒维护补回3027() {
        long id = f.travel(a, TARGET_ZONE, 0);
        PendingCheck check = f.teamChecks.take();
        f.clock.advanceMillis(TEAM_CHECK_TIMEOUT.toMillis() + 999);
        f.world.refreshDueLocations();
        assertThat(f.travelReplies(11)).as("期限前不动").isEmpty();
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.PRECHECK);

        f.clock.advanceMillis(1);
        f.world.refreshDueLocations();

        assertThat(tipFor(11, id)).isEqualTo(TRAVEL_FAILED);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        check.notInTeam();
        assertThat(f.travelReplies(11)).hasSize(1);
        assertThat(f.targets.calls()).isEmpty();
        assertThat(f.requests("team_read_error")).isEqualTo(1);
    }

    /** 备战闸、组队跟随也经 {@code switchInFlight}：它们作废过期的 PRECHECK 槽时同样先补回 3027。 */
    @Test
    void PRECHECK槽过期_任何经switchInFlight的调用方作废时都先补回3027() {
        long id = f.travel(a, TARGET_ZONE, 0);
        f.clock.advanceMillis(TEAM_CHECK_TIMEOUT.toMillis() + 1000);

        assertThat(f.world.switchInFlight(a)).isFalse();

        assertThat(tipFor(11, id)).isEqualTo(TRAVEL_FAILED);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.world.switchInFlight(a)).as("再问不再补").isFalse();
        assertThat(f.travelReplies(11)).hasSize(1);
    }

    // ------------------------------------------------------------------ RESOLVING：选目标

    @Test
    void 选目标调用失败或被拒_推3027留原地照常游玩_可再发226并成功冻结() {
        f.resolve(a).failed("超时");

        assertThat(f.pushedTips(11)).containsExactly(TRAVEL_FAILED);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.repo.pendingHandOffs()).isZero();
        assertThat(f.world.playerById(1001)).isSameAs(a);
        List<MessageContent> all = f.sink.to(LINK, 11);
        assertThat(all.getFirst().getMessageId()).as("23 恒在 {0} 之后").isEqualTo(226);
        assertThat(all.getLast().getMessageId()).isEqualTo(TravelFixture.TIP);

        f.resolve(a).refused(3000);
        assertThat(f.pushedTips(11)).containsExactly(TRAVEL_FAILED, TRAVEL_FAILED);

        f.enterScene(a, f.scene2.sceneId(), 2);
        assertThat(a.scene()).as("可继续游玩").isSameAs(f.scene2);

        f.resolve(a).chosen(TARGET_MAP, REDIRECT);
        assertThat(a.frozen()).as("可再发 226 并成功").isTrue();
        assertThat(f.repo.takeHandOff().mode()).isEqualTo(HandOffMode.RELEASE);
        assertThat(f.resolves("error")).isEqualTo(1);
        assertThat(f.resolves("rejected")).isEqualTo(1);
        assertThat(f.resolves("chosen")).isEqualTo(1);
    }

    @Test
    void 选目标应答残缺_推3027_计error_不冻结() {
        List<ZoneRedirect> broken = List.of(
                REDIRECT.toBuilder().setGateHost("").build(),
                REDIRECT.toBuilder().setGatePort(0).build(),
                REDIRECT.toBuilder().setGatePort(65536).build(),
                REDIRECT.toBuilder().setTokenPayload(ByteString.EMPTY).build(),
                REDIRECT.toBuilder().setTokenSignature(ByteString.EMPTY).build(),
                REDIRECT.toBuilder().setTokenDeadline(0).build(),
                REDIRECT.toBuilder().setTargetZoneId(TARGET_ZONE + 1).build());
        for (ZoneRedirect redirect : broken) {
            f.resolve(a).chosen(TARGET_MAP, redirect);
            assertThat(a.switchPhase()).as("%s", redirect).isEqualTo(SwitchPhase.NONE);
        }
        f.resolve(a).chosen(0, REDIRECT);

        assertThat(f.pushedTips(11)).hasSize(broken.size() + 1).containsOnly(TRAVEL_FAILED);
        assertThat(f.repo.pendingHandOffs()).isZero();
        assertThat(f.resolves("error")).isEqualTo(broken.size() + 1);
        assertThat(f.resolves("chosen")).isZero();
    }

    @Test
    void 选目标回来时已有回合制战斗在途_中止推3025() {
        PendingTravel resolve = f.resolve(a);
        BattleFixture.setFreeze(a, fighting());

        resolve.chosen(TARGET_MAP, REDIRECT);

        assertThat(f.pushedTips(11)).containsExactly(IN_BATTLE);
        assertThat(a.frozen()).isFalse();
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.repo.pendingHandOffs()).isZero();
        assertThat(f.resolves("in_battle")).isEqualTo(1);
    }

    @Test
    void 选目标中再发63回3014_226回13000() {
        f.resolve(a);

        f.enterScene(a, f.scene2.sceneId(), 2);
        long again = f.travel(a, TARGET_ZONE, 0);

        assertThat(f.enterSceneTips(11)).containsExactly(CHANGING_SCENE);
        assertThat(tipFor(11, again)).isEqualTo(TRANSFER_IN_PROGRESS);
    }

    /**
     * RESOLVING 槽过期（结果丢了或迟到，正常不会）：{@code {0}} 已回、客户端在「已受理」窗口里，作废方（这里是 63）先推 23 {3027}、
     * 计 {@code resolves{error}} 再清槽（§4.4：受理之后一定有 124 或失败 tip），63 自己照常办；迟到的选中结果不冻结、不再推、不再计数
     * （每次受理在 resolves 上恰好计一次）。
     */
    @Test
    void 选目标槽过期_作废方先推3027计error再清槽_迟到的选中结果不冻结不再推不再计() {
        PendingTravel resolve = f.resolve(a);
        f.clock.advanceMillis(RESOLVE_TIMEOUT.toMillis() + 999);
        f.enterScene(a, f.scene2.sceneId(), 2);
        assertThat(f.enterSceneTips(11)).as("期限前仍挡").containsExactly(CHANGING_SCENE);
        assertThat(f.pushedTips(11)).isEmpty();

        f.clock.advanceMillis(1);
        f.enterScene(a, f.scene2.sceneId(), 2);

        List<MessageContent> all = f.sink.to(LINK, 11);
        int tip = lastIndexOf(all, TravelFixture.TIP);
        assertThat(tip).as("作废方推了 23").isGreaterThanOrEqualTo(0);
        assertThat(tip).as("23 {3027} 在这条 63 的应答之前").isLessThan(lastIndexOf(all, Contracts.IDS.enterScene()));
        assertThat(f.pushedTips(11)).containsExactly(TRAVEL_FAILED);
        assertThat(f.enterSceneTips(11)).containsExactly(CHANGING_SCENE, 0);
        assertThat(a.scene()).as("过期槽不再挡 63").isSameAs(f.scene2);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.resolves("error")).isEqualTo(1);

        resolve.chosen(TARGET_MAP, REDIRECT);

        assertThat(a.frozen()).isFalse();
        assertThat(f.repo.pendingHandOffs()).isZero();
        assertThat(f.pushedTips(11)).as("迟到的结果不再推第二次").containsExactly(TRAVEL_FAILED);
        assertThat(f.resolves("stale")).isZero();
        assertThat(f.resolvesTotal()).as("这次受理恰好计一次").isEqualTo(1);
        assertThat(f.requests("accepted")).isEqualTo(1);
    }

    /** 下一条 226 作废过期的 RESOLVING 槽：旧的推 23 {3027}，新的照常进在队检查；旧的迟到结果（失败）不再推、不再计。 */
    @Test
    void 选目标槽过期_下一条226作废旧的推3027_新的照常进在队检查() {
        PendingTravel stale = f.resolve(a);
        f.clock.advanceMillis(RESOLVE_TIMEOUT.toMillis() + 1000);

        long second = f.travel(a, TARGET_ZONE, 0);

        assertThat(f.pushedTips(11)).containsExactly(TRAVEL_FAILED);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.PRECHECK);
        stale.failed("迟到的兜底超时");
        assertThat(f.pushedTips(11)).containsExactly(TRAVEL_FAILED);

        f.teamChecks.take().notInTeam();

        assertThat(tipFor(11, second)).isZero();
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        assertThat(f.resolves("error")).isEqualTo(1);
        assertThat(f.resolvesTotal()).isEqualTo(1);
    }

    /**
     * 选目标的结果回调丢了而这名玩家什么都不再发、也没有别的调用方碰它：每秒维护在槽过期后作废它、推 23 {3027}，期限之前不动。
     * 63 的 RESOLVING 槽不归它管（照 5.2：过期只在下一次碰到时清，玩家在原地游玩，没有等待中的结论）。
     */
    @Test
    void 选目标槽过期_没人再碰这名玩家_每秒维护推3027_63的槽不动() {
        PendingTravel resolve = f.resolve(a);
        f.resolveRemote(b);
        f.clock.advanceMillis(RESOLVE_TIMEOUT.toMillis() + 999);
        f.world.refreshDueLocations();
        assertThat(f.pushedTips(11)).as("期限前不动").isEmpty();
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);

        f.clock.advanceMillis(1);
        f.world.refreshDueLocations();

        assertThat(f.pushedTips(11)).containsExactly(TRAVEL_FAILED);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(b.switchPhase()).as("63 的槽每秒维护不碰").isEqualTo(SwitchPhase.RESOLVING);
        assertThat(f.pushedTips(12)).isEmpty();
        resolve.failed("迟到");
        assertThat(f.pushedTips(11)).hasSize(1);
        assertThat(f.resolves("error")).isEqualTo(1);
        assertThat(f.resolvesTotal()).isEqualTo(1);
    }

    /** 备战闸、组队跟随也经 {@code switchInFlight}：它们作废过期的 RESOLVING 槽时同样先推 23 {3027}，再问不再推。 */
    @Test
    void 选目标槽过期_任何经switchInFlight的调用方作废时都先推3027() {
        f.resolve(a);
        f.clock.advanceMillis(RESOLVE_TIMEOUT.toMillis() + 1000);

        assertThat(f.world.switchInFlight(a)).isFalse();

        assertThat(f.pushedTips(11)).containsExactly(TRAVEL_FAILED);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.world.switchInFlight(a)).as("再问不再推").isFalse();
        assertThat(f.pushedTips(11)).hasSize(1);
        assertThat(f.travelReplies(11)).as("226 的应答只有受理时那一个 {0}").hasSize(1);
    }

    @Test
    void 选目标中玩家离开或被请求让出_照现有流程写回释放_迟到结果计stale() {
        PendingTravel left = f.resolve(a);
        PendingTravel takenOver = f.resolve(b);

        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 1001));
        f.world.onTakeoverRequested(1002, 1);
        left.chosen(TARGET_MAP, REDIRECT);
        takenOver.failed("超时");

        assertThat(f.repo.saves()).extracting(PlayerSave::playerId).containsExactly(1001L, 1002L);
        assertThat(f.sink.kicks()).extracting(RecordingSinkKick::of).containsExactly(new RecordingSinkKick(12, 2017));
        assertThat(f.repo.pendingHandOffs()).isZero();
        assertThat(f.resolves("stale")).isEqualTo(2);
    }

    /** 5.3 的镜像 / 副本实例里发 226：放行（同基线不看场景类型，Q13），冻结快照就是实例里的状态。 */
    @Test
    void 从镜像实例里发226_放行() {
        Scene mirror = f.world.createInstance(InstanceSpec.mirror(777_001, 1, f.scene1.sceneId(), 1, 1001));
        f.world.switchScene(a, mirror);

        f.resolve(a).chosen(TARGET_MAP, REDIRECT);

        assertThat(a.frozen()).isTrue();
        assertThat(f.repo.takeHandOff().frozen().sceneConfigId()).isEqualTo(mirror.configId());
    }

    // ------------------------------------------------------------------ 工具

    private void assertUntouched(ScenePlayer player) {
        assertThat(player.switchPhase()).as("没有在途槽").isEqualTo(SwitchPhase.NONE);
        assertThat(player.frozen()).isFalse();
        assertThat(f.teamChecks.calls()).as("不查在队").isEmpty();
        assertThat(f.targets.calls()).as("不选目标").isEmpty();
        assertThat(f.repo.pendingHandOffs()).as("不碰存储").isZero();
        assertThat(f.repo.saves()).isEmpty();
    }

    private int tipFor(int sessionId, long requestId) {
        return tipFor(f, sessionId, requestId);
    }

    private static int tipFor(TravelFixture fixture, int sessionId, long requestId) {
        List<MessageContent> matched = fixture.travelReplies(sessionId).stream().filter(m -> m.getId() == requestId).toList();
        assertThat(matched).as("请求 %s 恰好一个应答", requestId).hasSize(1);
        return TravelFixture.travelTip(matched.getFirst());
    }

    private static int indexOf(List<MessageContent> all, int messageId, long requestId) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getMessageId() == messageId && all.get(i).getId() == requestId) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(List<MessageContent> all, int messageId) {
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).getMessageId() == messageId) {
                return i;
            }
        }
        return -1;
    }

    private BattleFreeze fighting() {
        long now = f.clock.epochMillis();
        return new BattleFreeze(7, 21, BattleFreeze.Phase.FIGHTING, now + 300_000, now + 60_000, true);
    }

    /** 只比会话与 tip 的踢出记录。 */
    private record RecordingSinkKick(int sessionId, int tipId) {

        static RecordingSinkKick of(com.game.scene.testing.RecordingSink.Kicked kicked) {
            return new RecordingSinkKick(kicked.sessionId(), kicked.tipId());
        }
    }

    /** World 表为空的配表（只换掉主世界地图列表）。 */
    private static final class NoWorldMaps implements SceneTables {

        private final FakeSceneTables delegate = new FakeSceneTables();

        @Override
        public List<Integer> worldSceneConfigIds() {
            return List.of();
        }

        @Override
        public Vec3 spawnPoint(int sceneConfigId) {
            return delegate.spawnPoint(sceneConfigId);
        }

        @Override
        public List<Integer> initialSkills() {
            return delegate.initialSkills();
        }

        @Override
        public boolean skillExists(int skillTableId) {
            return delegate.skillExists(skillTableId);
        }

        @Override
        public boolean mirrorExists(int mirrorConfigId) {
            return delegate.mirrorExists(mirrorConfigId);
        }

        @Override
        public OptionalInt dungeonSceneConfigId(int dungeonConfigId) {
            return delegate.dungeonSceneConfigId(dungeonConfigId);
        }
    }
}
