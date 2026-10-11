package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static com.game.scene.world.TravelFixture.LINK;
import static com.game.scene.world.TravelFixture.REDIRECT;
import static com.game.scene.world.TravelFixture.TARGET_MAP;
import static com.game.scene.world.TravelFixture.TARGET_ZONE;
import static com.game.scene.world.TravelFixture.TOMBSTONE_TTL;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.PlayerLeave;
import com.game.player.store.PlayerStore.HandOffMode;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.Rotation;
import com.game.scene.testing.FakePlayerRepository.PendingHandOff;
import com.game.scene.testing.FakePlayerRepository.PendingProbe;
import com.game.scene.testing.ManualExecutor;
import com.game.scene.testing.RecordingLocations.PendingPlacement;
import com.game.scene.testing.RecordingLocations.Write;
import com.game.scene.testing.RecordingSink.Kicked;
import com.game.scene.testing.RecordingSink.Redirect;
import com.game.scene.world.PlayerRepository.HandOffAttempts;
import com.game.scene.world.PlayerRepository.HandOffOutcome;
import com.game.scene.world.PlayerRepository.ProbeOutcome;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 跨 zone 传送（批次 5.4，zone-travel-spec §5.5、§5.9、§11.5）冻结之后：交出并释放的各结局（不释放 E+1、未提交与判定不了推 / 踢 3027、
 * 读到 ≥ E+2 踢 2017）、冻结中的离开 / 接管、待落点 (E, 序号+1) 与「写完再发帧」、帧没写出（TF7）、与 PlayerLeave 交叉的墓碑、
 * 回调线程与投递被拒（TF16）、{@code travelFramesPending}，以及冻结闸对传送的冻结同样生效。
 */
class ZoneTravelHandOffTest {

    private static final long E = 5;
    private static final int TRAVEL_FAILED = 3027;
    private static final int KICKED = 2017;

    private TravelFixture f;
    private ScenePlayer a;
    private ScenePlayer b;

    @BeforeEach
    void setUp() {
        f = new TravelFixture();
        a = f.enter(11, 1001, f.scene1, E);
        b = f.enter(12, 1002, f.scene1, 1);
    }

    // ------------------------------------------------------------------ 冻结与成功路径

    @Test
    void 冻结前停下_快照就是交出写的内容_交出模式是RELEASE() {
        f.world.applyMove(a, new MoveInput(a.position(), Rotation.getDefaultInstance(), new Vec3(2, 0, 0), 1));
        f.world.step();
        f.world.step();
        f.sink.clear();

        PendingHandOff handOff = f.freeze(a);

        assertThat(a.velocity().isOrigin()).isTrue();
        assertThat(handOff.mode()).isEqualTo(HandOffMode.RELEASE);
        assertThat(handOff.frozen()).isEqualTo(a.toSave());
        assertThat(handOff.frozen().ownerEpoch()).isEqualTo(E);
        f.world.step();
        f.world.step();
        assertThat(f.sink.messageIdsTo(LINK, 12)).as("冻结后不再外推，旁人收到速度 0 的 66").containsExactly(66);
        assertThat(f.inFlight()).isEqualTo(1);
        assertThat(f.resolves("chosen")).isEqualTo(1);
    }

    @Test
    void 交出并释放成功_旁人51_不写回不拍LOGOUT不释放_待落点E与序号加1_写完才发重定向帧() throws Exception {
        long entityA = a.entity();
        long seqBefore = a.locationSeq();
        PendingHandOff handOff = f.freeze(a);
        f.sink.clear();

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(f.sink.messageIdsTo(LINK, 12)).containsExactly(51);
        assertThat(ActorDestroyS2C.parseFrom(f.sink.to(LINK, 12).get(0).getSerializedMessage()).getEntity()).isEqualTo(entityA);
        assertThat(f.sink.to(LINK, 11)).as("源节点不给本人发任何下行").isEmpty();
        assertThat(f.sink.redirects()).as("待落点没写完，不发帧").isEmpty();
        PendingPlacement placement = f.locations.takePlacement();
        assertThat(placement.playerId()).isEqualTo(1001);
        assertThat(placement.ownerEpoch()).as("用交出前持有的 E 写").isEqualTo(E);
        assertThat(placement.seq()).isEqualTo(seqBefore + 1);
        assertThat(placement.targetZoneId()).isEqualTo(TARGET_ZONE);
        assertThat(placement.sceneConfigId()).as("scene-manager 解析后的地图").isEqualTo(TARGET_MAP);
        assertThat(placement.ttl()).as("票据剩余 300 s").isEqualTo(Duration.ofSeconds(300));
        assertThat(f.framesPending()).isEqualTo(1);
        assertThat(f.world.playerById(1001)).isNull();
        assertThat(f.world.ownedPlayers()).containsExactly(new OwnedPlayer(1002, 1));
        assertThat(f.repo.saves()).isEmpty();
        assertThat(f.repo.releases()).as("E+1 已在交出事务里释放，不再释放").isEmpty();
        assertThat(f.snapshots).as("不拍 LOGOUT").isEmpty();
        assertThat(f.transfers("travel", "handed_off")).isEqualTo(1);
        assertThat(f.transfers("player", "handed_off")).as("5.2 的序列不动").isZero();
        assertThat(f.inFlight()).isZero();
        assertThat(f.meters.get("xm.scene.transfer.freeze").timer().count()).isEqualTo(1);

        placement.complete(true);

        assertThat(f.sink.redirects()).singleElement().satisfies(r -> {
            assertThat(r.linkId()).isEqualTo(LINK);
            assertThat(r.sessionId()).isEqualTo(11);
            assertThat(r.playerId()).isEqualTo(1001);
            assertThat(r.fromEpoch()).isEqualTo(E);
            assertThat(r.toEpoch()).isEqualTo(E + 1);
            assertThat(r.redirect()).as("选目标的结果原样进帧").isSameAs(REDIRECT);
        });
        assertThat(f.sink.transfers()).as("不发改绑指令").isEmpty();
        assertThat(f.framesPending()).isZero();
        assertThat(f.placements("written")).isEqualTo(1);
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.locations.writes()).as("帧写出后不再写位置").isEmpty();
    }

    @Test
    void 待落点TTL_按票据剩余截到1秒与300秒() {
        ScenePlayer c = f.enter(13, 1003, f.scene1, 1);
        ScenePlayer d = f.enter(14, 1004, f.scene1, 1);
        f.clock.advanceMillis(60_000);
        long nowSeconds = f.clock.epochMillis() / 1000;

        handOff(a, REDIRECT);
        handOff(c, REDIRECT.toBuilder().setTokenDeadline(nowSeconds + 3_600).build());
        handOff(d, REDIRECT.toBuilder().setTokenDeadline(nowSeconds - 10).build());

        assertThat(f.locations.placements()).extracting(PendingPlacement::ttl)
                .containsExactly(Duration.ofSeconds(240), Duration.ofSeconds(300), Duration.ofSeconds(1));
    }

    @Test
    void 写待落点失败_照样发帧_计failed() {
        handOff(a, REDIRECT);

        f.locations.takePlacement().complete(false);

        assertThat(f.sink.redirects()).hasSize(1);
        assertThat(f.placements("failed")).isEqualTo(1);
        assertThat(f.placements("written")).isZero();
        assertThat(f.framesPending()).isZero();
    }

    @Test
    void 写口在调用栈内当场回调_墓碑与计数已先放好_照样发帧() {
        f.locations.completeInline(true);

        handOff(a, REDIRECT);

        assertThat(f.sink.redirects()).hasSize(1);
        assertThat(f.framesPending()).isZero();
        assertThat(f.placements("written")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 结局

    @Test
    void 租约不足_原地解冻_推3027_不释放_照常游玩() {
        PendingHandOff handOff = f.freeze(a);
        f.sink.clear();

        handOff.complete(new HandOffOutcome.LeaseTooShort());

        assertThat(f.pushedTips(11)).containsExactly(TRAVEL_FAILED);
        assertThat(a.frozen()).isFalse();
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.world.playerById(1001)).isSameAs(a);
        assertThat(f.sink.messageIdsTo(LINK, 12)).as("旁人看不到任何变化").isEmpty();
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.locations.placements()).isEmpty();
        assertThat(f.transfers("travel", "lease_too_short")).isEqualTo(1);
        assertThat(f.inFlight()).isZero();
        assertThat(a.wallet().add(0, 5).ok()).isTrue();
        assertThat(f.world.requestSave(a)).isEqualTo(SceneWorld.SaveRequest.WRITTEN);
    }

    @Test
    void 结局不明_探测按RELEASE判_没提交则原地解冻推3027() {
        PendingHandOff handOff = f.freeze(a);
        HandOffOutcome.Failed failed = new HandOffOutcome.Failed(1001, E, new HandOffAttempts(1, List.of(42L)), HandOffMode.RELEASE);
        handOff.complete(failed);
        assertThat(a.frozen()).as("探测期间仍冻结").isTrue();
        PendingProbe probe = f.repo.takeProbe();
        assertThat(probe.failed().mode()).isEqualTo(HandOffMode.RELEASE);
        f.sink.clear();

        probe.complete(new ProbeOutcome.NotCommitted());

        assertThat(f.pushedTips(11)).containsExactly(TRAVEL_FAILED);
        assertThat(a.frozen()).isFalse();
        assertThat(f.transfers("travel", "aborted_in_place")).isEqualTo(1);
    }

    @Test
    void 结局不明_探测认出已提交_照成功处理写待落点发帧() {
        PendingHandOff handOff = f.freeze(a);
        handOff.complete(new HandOffOutcome.Failed(1001, E, new HandOffAttempts(1, List.of(42L)), HandOffMode.RELEASE));

        f.repo.takeProbe().complete(new ProbeOutcome.HandedOff(E + 1));
        f.locations.takePlacement().complete(true);

        assertThat(f.sink.redirects()).singleElement().extracting(Redirect::toEpoch).isEqualTo(E + 1);
        assertThat(f.transfers("travel", "handed_off")).isEqualTo(1);
        assertThat(f.repo.releases()).isEmpty();
    }

    @Test
    void 交出被围栏拒_移除不写回_踢2017() {
        PendingHandOff handOff = f.freeze(a);

        handOff.complete(new HandOffOutcome.Fenced());

        assertThat(f.sink.kicks()).containsExactly(new Kicked(LINK, 11, 1001, E, KICKED));
        assertThat(f.repo.saves()).isEmpty();
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.world.playerById(1001)).isNull();
        assertThat(f.sink.messageIdsTo(LINK, 12)).containsExactly(51);
        assertThat(f.locations.placements()).isEmpty();
        assertThat(f.sink.redirects()).isEmpty();
        assertThat(f.transfers("travel", "fenced")).isEqualTo(1);
    }

    /** 读到 ≥ E+2（§0.6 裁决 14）：交出与探测两处，都与 Fenced 相同处理（踢 2017），不写待落点、不发帧，计 superseded。 */
    @Test
    void 读到E加2以后_交出与探测的Superseded都踢2017_不写待落点不发帧() {
        ScenePlayer c = f.enter(13, 1003, f.scene1, 7);
        PendingHandOff first = f.freeze(a);
        PendingHandOff second = f.freeze(c);

        first.complete(new HandOffOutcome.Superseded());
        second.complete(new HandOffOutcome.Failed(1003, 7, new HandOffAttempts(1, List.of(42L)), HandOffMode.RELEASE));
        f.repo.takeProbe().complete(new ProbeOutcome.Superseded());

        assertThat(f.sink.kicks()).containsExactly(new Kicked(LINK, 11, 1001, E, KICKED), new Kicked(LINK, 13, 1003, 7, KICKED));
        assertThat(f.locations.placements()).isEmpty();
        assertThat(f.sink.redirects()).isEmpty();
        assertThat(f.repo.saves()).isEmpty();
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.transfers("travel", "superseded")).isEqualTo(2);
        assertThat(f.transfers("travel", "handed_off")).isZero();
        assertThat(f.framesPending()).isZero();
    }

    @Test
    void 探测判定不了_failclosed_移除不写回不释放_踢3027() {
        PendingHandOff handOff = f.freeze(a);
        handOff.complete(new HandOffOutcome.Failed(1001, E, new HandOffAttempts(1, List.of(42L)), HandOffMode.RELEASE));

        f.repo.takeProbe().complete(new ProbeOutcome.Lost());

        assertThat(f.sink.kicks()).containsExactly(new Kicked(LINK, 11, 1001, E, TRAVEL_FAILED));
        assertThat(f.repo.saves()).isEmpty();
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.world.playerById(1001)).isNull();
        assertThat(f.transfers("travel", "lost_unknown")).isEqualTo(1);
    }

    @Test
    void 停服时冻结中_照常写回E_交出成功只计left_不释放不发帧() {
        PendingHandOff handOff = f.freeze(a);

        f.world.shutdown();
        assertThat(f.repo.saves()).extracting(PlayerSave::playerId).containsExactlyInAnyOrder(1001L, 1002L);

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(f.sink.redirects()).isEmpty();
        assertThat(f.locations.placements()).isEmpty();
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.transfers("travel", "left")).isEqualTo(1);
        assertThat(f.inFlight()).isZero();
        assertThat(f.framesPending()).isZero();
    }

    /** §2.4：提交后、帧发出前别的设备从源 zone 登录，夺到 E+2 进到本节点——冻结中的旧实例被接替、踢 2017；迟到的交出结局只计 left。 */
    @Test
    void 冻结中被本节点更高epoch的进场接替_旧会话踢2017_交出成功只计left() {
        PendingHandOff handOff = f.freeze(a);
        f.repo.putNewPlayer(1001, E + 2);
        f.world.onPlayerEnter(LINK, enterFrame(21, 1001, f.scene1.sceneId(), E + 2));
        f.repo.completeAll();
        assertThat(f.sink.kicks()).containsExactly(new Kicked(LINK, 11, 1001, E, KICKED));

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(f.sink.redirects()).isEmpty();
        assertThat(f.locations.placements()).isEmpty();
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.world.playerById(1001).ownerEpoch()).isEqualTo(E + 2);
        assertThat(f.transfers("travel", "left")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 冻结中的离开 / 接管

    @Test
    void 冻结中主动离开_交出成功不发帧不写待落点_写登出墓碑E与序号加1_不释放() {
        long seqBefore = a.locationSeq();
        PendingHandOff handOff = f.freeze(a);
        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 1001));
        assertThat(f.world.playerById(1001)).as("实例保持到结局回来").isSameAs(a);
        f.sink.clear();

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(f.sink.redirects()).isEmpty();
        assertThat(f.locations.placements()).isEmpty();
        assertThat(f.locations.writes()).containsExactly(new Write("loggedOut", 1001, E, seqBefore + 1, f.scene1.sceneId()));
        assertThat(f.sink.messageIdsTo(LINK, 12)).containsExactly(51);
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.repo.saves()).isEmpty();
        assertThat(f.transfers("travel", "left")).isEqualTo(1);
    }

    @Test
    void 冻结中所在链路断开_交出成功写重连租约_不发帧() {
        long seqBefore = a.locationSeq();
        PendingHandOff handOff = f.freeze(a);
        f.world.onLinkClosed(LINK);

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(f.sink.redirects()).isEmpty();
        assertThat(f.locations.writes()).filteredOn(w -> w.playerId() == 1001)
                .containsExactly(new Write("disconnected", 1001, E, seqBefore + 1, f.scene1.sceneId()));
        assertThat(f.repo.releases()).isEmpty();
    }

    @Test
    void 冻结中被请求让出_交出成功踢2017不释放_没提交则写回释放E并踢2017() {
        ScenePlayer c = f.enter(13, 1003, f.scene1, 7);
        PendingHandOff committed = f.freeze(a);
        PendingHandOff notCommitted = f.freeze(c);
        f.world.onTakeoverRequested(1001, E);
        f.world.onTakeoverRequested(1003, 7);
        assertThat(f.sink.kicks()).isEmpty();

        committed.complete(new HandOffOutcome.HandedOff(E + 1));
        notCommitted.complete(new HandOffOutcome.LeaseTooShort());

        assertThat(f.sink.kicks()).containsExactly(new Kicked(LINK, 11, 1001, E, KICKED), new Kicked(LINK, 13, 1003, 7, KICKED));
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.repo.saves()).extracting(PlayerSave::playerId, PlayerSave::ownerEpoch)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1003L, 7L));
        assertThat(f.pushedTips(13)).isEmpty();
        assertThat(f.sink.redirects()).isEmpty();
        assertThat(f.transfers("travel", "taken_over")).isEqualTo(1);
        assertThat(f.transfers("travel", "lease_too_short")).isEqualTo(1);
    }

    /** 冻结闸（§5.10）对传送的冻结同样生效：63 回 3014、226 回 13000、续约失去与在线存盘都交给交出结局。 */
    @Test
    void 传送冻结中_冻结闸照5点2生效() {
        f.freeze(a);

        long enter = f.enterScene(a, f.scene2.sceneId(), 2);
        long travel = f.travel(a, TARGET_ZONE, 0);
        f.world.onOwnershipLost(List.of(new OwnedPlayer(1001, E)));

        assertThat(enter).isNotZero();
        assertThat(f.enterSceneTips(11)).containsExactly(3014);
        assertThat(f.travelReplies(11).stream().filter(m -> m.getId() == travel).map(TravelFixture::travelTip).toList())
                .containsExactly(13000);
        assertThat(f.world.playerById(1001)).isSameAs(a);
        assertThat(f.sink.kicks()).isEmpty();
        assertThat(f.world.requestSave(a)).isEqualTo(SceneWorld.SaveRequest.IN_FLIGHT);
        assertThat(a.frozen()).isTrue();
    }

    // ------------------------------------------------------------------ 帧没写出（TF7）

    @Test
    void 帧写不出_位置以E和序号加2改回源场景重连租约_计link_gone_不释放() {
        long seqBefore = a.locationSeq();
        handOff(a, REDIRECT);
        f.sink.setRedirectWritable(false);

        f.locations.takePlacement().complete(true);

        assertThat(f.sink.redirects()).isEmpty();
        assertThat(f.locations.writes()).containsExactly(new Write("disconnected", 1001, E, seqBefore + 2, f.scene1.sceneId()));
        assertThat(f.transfers("travel", "link_gone")).isEqualTo(1);
        assertThat(f.transfers("travel", "handed_off")).as("已交出照计，link_gone 另计").isEqualTo(1);
        assertThat(f.framesWithheld()).as("墓碑还在、帧写不出：只计 link_gone").isZero();
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.framesPending()).isZero();
    }

    @Test
    void 帧交给链路后异步写失败_改回重连租约计link_gone_重复回调只处理一次() {
        long seqBefore = a.locationSeq();
        handOff(a, REDIRECT);
        f.locations.takePlacement().complete(true);
        Redirect sent = f.sink.redirects().getFirst();

        sent.failWrite();
        sent.failWrite();

        assertThat(f.locations.writes()).containsExactly(new Write("disconnected", 1001, E, seqBefore + 2, f.scene1.sceneId()));
        assertThat(f.transfers("travel", "link_gone")).isEqualTo(1);
        assertThat(f.repo.releases()).isEmpty();
        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 1001));
        assertThat(f.locations.writes()).as("墓碑已作废，交叉的离开不再写").hasSize(1);
    }

    /** 写待落点期间所在链路断开：墓碑不随链路作废，写完时照常尝试发帧——链路已断写不出，按 TF7 改回源场景重连租约。 */
    @Test
    void 写待落点期间所在链路断开_写完时帧写不出_按TF7收口() {
        long seqBefore = a.locationSeq();
        handOff(a, REDIRECT);
        f.world.onLinkClosed(LINK);
        f.sink.setRedirectWritable(false);

        f.locations.takePlacement().complete(true);

        assertThat(f.locations.writes()).filteredOn(w -> w.playerId() == 1001)
                .containsExactly(new Write("disconnected", 1001, E, seqBefore + 2, f.scene1.sceneId()));
        assertThat(f.transfers("travel", "link_gone")).isEqualTo(1);
        assertThat(f.framesPending()).isZero();
    }

    // ------------------------------------------------------------------ 墓碑

    @Test
    void 写待落点期间交叉到达的离开_按墓碑写E与序号加2_不再发帧() {
        long seqBefore = a.locationSeq();
        handOff(a, REDIRECT);
        PendingPlacement placement = f.locations.takePlacement();

        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 1001));
        placement.complete(true);

        assertThat(f.locations.writes()).containsExactly(new Write("loggedOut", 1001, E, seqBefore + 2, f.scene1.sceneId()));
        assertThat(f.sink.redirects()).as("墓碑已被消费：不发帧").isEmpty();
        assertThat(f.transfers("travel", "link_gone")).isZero();
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.framesPending()).isZero();
        assertThat(f.placements("written")).isEqualTo(1);
        // §7.3 第二条：gate 收不到这一帧、什么都不计，scene 必须自己计一次，否则勾稽里它就像丢了一帧 124
        assertThat(f.framesWithheld()).as("帧发出之前被交叉的离开消费：计 withheld").isEqualTo(1);
    }

    @Test
    void 帧发出后交叉到达的离开_按墓碑写一次位置_墓碑过期后不命中() {
        ScenePlayer c = f.enter(13, 1003, f.scene1, 7);
        long seqA = a.locationSeq();
        handOff(a, REDIRECT);
        f.locations.takePlacement().complete(true);
        handOff(c, REDIRECT);
        f.locations.takePlacement().complete(true);

        f.world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(11).setPlayerId(1001).build());
        f.world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(11).setPlayerId(1001).build());
        f.clock.advanceMillis(TOMBSTONE_TTL.toMillis());
        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(13, 1003));

        assertThat(f.locations.writes()).containsExactly(new Write("disconnected", 1001, E, seqA + 2, f.scene1.sceneId()));
        assertThat(f.repo.releases()).isEmpty();
        // 帧已发出：那一帧到 gate 时会话已离开，gate 计 stale / orphan；scene 再计就把同一次旅途在勾稽里算两遍
        assertThat(f.sink.redirects()).hasSize(2);
        assertThat(f.framesWithheld()).as("帧发出之后的交叉不计 withheld").isZero();
    }

    /** 帧还没定的墓碑不过期：待落点写得再久（远超墓碑存活时长）、期间每秒清过期墓碑，写完照样发帧。 */
    @Test
    void 帧还没定的墓碑不因过期被清_写完照样发帧() {
        handOff(a, REDIRECT);
        f.clock.advanceMillis(TOMBSTONE_TTL.toMillis() + 5_000);
        f.world.refreshDueLocations();

        f.locations.takePlacement().complete(true);

        assertThat(f.sink.redirects()).hasSize(1);
        assertThat(f.framesPending()).isZero();
    }

    @Test
    void 停服时帧还没定_墓碑留着_逻辑线程还在时写完照样发帧() {
        handOff(a, REDIRECT);

        f.world.shutdown();
        assertThat(f.framesPending()).isEqualTo(1);
        f.locations.takePlacement().complete(true);

        assertThat(f.sink.redirects()).hasSize(1);
        assertThat(f.framesPending()).isZero();
    }

    // ------------------------------------------------------------------ 线程（§5.11）

    /** 待落点在另一条线程上写完：那条线程只投递，墓碑检查与发帧在逻辑线程（逻辑执行器跑的那个任务）里做。 */
    @Test
    void 待落点在另一条线程上写完_墓碑检查与发帧在逻辑线程上() throws Exception {
        ManualExecutor logic = new ManualExecutor();
        handOff(a, REDIRECT);
        PendingPlacement placement = f.locations.takePlacement();

        Boolean posted = placement.completeOnAnotherThread(logic, true).get(5, TimeUnit.SECONDS);

        assertThat(posted).isTrue();
        assertThat(f.sink.redirects()).as("另一条线程上什么都没做").isEmpty();
        assertThat(f.framesPending()).isEqualTo(1);
        assertThat(logic.pending()).isEqualTo(1);
        long executed = logic.executed();
        logic.runAll();
        assertThat(logic.executed()).isEqualTo(executed + 1);
        assertThat(f.sink.redirects()).hasSize(1);
        assertThat(f.framesPending()).isZero();
    }

    /** 投递被拒（逻辑线程已停，TF16）：不抛、不发帧，计数停在 1——5.5 的收敛谓词据此等待。 */
    @Test
    void 待落点写完时逻辑线程已停_投递被拒_不抛不发帧_计数留着() throws Exception {
        ManualExecutor logic = new ManualExecutor();
        handOff(a, REDIRECT);
        logic.rejectNewTasks(true);

        Boolean posted = f.locations.takePlacement().completeOnAnotherThread(logic, true).get(5, TimeUnit.SECONDS);

        assertThat(posted).isFalse();
        assertThat(f.sink.redirects()).isEmpty();
        assertThat(f.framesPending()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ travelFramesPending

    @Test
    void 帧计数_提交加一_帧写出_TF7_墓碑消费各减一_最后回到0() {
        ScenePlayer c = f.enter(13, 1003, f.scene1, 7);
        ScenePlayer d = f.enter(14, 1004, f.scene1, 7);
        handOff(a, REDIRECT);
        handOff(c, REDIRECT);
        handOff(d, REDIRECT);
        assertThat(f.framesPending()).isEqualTo(3);
        PendingPlacement sent = f.locations.takePlacement();
        PendingPlacement linkGone = f.locations.takePlacement();
        PendingPlacement consumed = f.locations.takePlacement();

        sent.complete(true);
        assertThat(f.framesPending()).isEqualTo(2);
        f.sink.setRedirectWritable(false);
        linkGone.complete(true);
        assertThat(f.framesPending()).isEqualTo(1);
        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(14, 1004));
        assertThat(f.framesPending()).as("墓碑被消费时还没定：等写完的回调").isEqualTo(1);
        consumed.complete(false);

        assertThat(f.framesPending()).isZero();
        assertThat(f.sink.redirects()).hasSize(1);
        assertThat(f.transfers("travel", "handed_off")).isEqualTo(3);
        assertThat(f.transfers("travel", "link_gone")).isEqualTo(1);
        assertThat(f.framesWithheld()).isEqualTo(1);
        // §7.3 第二条的 scene 一侧：每次 handed_off 恰好落进「交给链路的帧（到 gate 计 sent / invalid / stale / orphan）」、link_gone、
        // withheld、pending 之一
        assertThat(f.sink.redirects().size() + f.transfers("travel", "link_gone") + f.framesWithheld() + f.framesPending())
                .isEqualTo(f.transfers("travel", "handed_off"));
    }

    // ------------------------------------------------------------------ 工具

    /** 让玩家走到「交出并释放已提交」（待落点在写）：选中给定的重定向载荷。 */
    private void handOff(ScenePlayer player, com.game.api.proto.ZoneRedirect redirect) {
        f.resolve(player).chosen(TARGET_MAP, redirect);
        f.repo.takeHandOff().complete(new HandOffOutcome.HandedOff(player.ownerEpoch() + 1));
    }
}
