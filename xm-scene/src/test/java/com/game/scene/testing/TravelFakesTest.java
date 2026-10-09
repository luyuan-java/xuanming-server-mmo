package com.game.scene.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ZoneRedirect;
import com.game.player.store.PlayerStore.HandOffMode;
import com.game.scene.testing.FakePlayerRepository.PendingHandOff;
import com.game.scene.testing.RecordingLocations.PendingPlacement;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.TeamChecks.TeamCheck;
import com.game.scene.world.TravelTargets.TravelSelection;
import com.game.scene.world.Vec3;
import com.game.scene.world.WorldTestAccess;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 批次 5.4 先行件建的测试替身自己的行为（跨 zone 传送的状态机用例全建在它们上面，替身错了那些用例的绿就没有意义）：
 * 缺省挂起、手工完成；脚本自动回时不在调用栈内；待落点写取下一个位置序号、可在另一条线程上完成；重定向帧与交出模式照实记录。
 */
class TravelFakesTest {

    private static final ZoneRedirect REDIRECT = ZoneRedirect.newBuilder().setTargetZoneId(2).setGateNodeId(1)
            .setGateHost("127.0.0.1").setGatePort(11010).setTokenPayload(ByteString.copyFromUtf8("payload"))
            .setTokenSignature(ByteString.copyFromUtf8("signature")).setTokenDeadline(1_800_000_300L).build();

    // ------------------------------------------------------------------ FakeTravelTargets

    @Test
    void 选目标替身_缺省挂起_按先后取出_三种结局都能手工完成() {
        FakeTravelTargets targets = new FakeTravelTargets();
        List<TravelSelection> results = new ArrayList<>();

        targets.selectTravel(1001, 2, 5, results::add);
        targets.selectTravel(1002, 3, 0, results::add);
        targets.selectTravel(1003, 4, 0, results::add);

        assertThat(results).as("挂起不回").isEmpty();
        assertThat(targets.pendingCount()).isEqualTo(3);
        FakeTravelTargets.PendingTravel first = targets.take();
        assertThat(first.playerId()).isEqualTo(1001);
        assertThat(first.toZoneId()).isEqualTo(2);
        assertThat(first.wantSceneConfigId()).isEqualTo(5);
        first.chosen(5, REDIRECT);
        targets.take().refused(3000);
        targets.take().failed("超时");

        assertThat(results).containsExactly(new TravelSelection.Chosen(5, REDIRECT), new TravelSelection.Refused(3000),
                new TravelSelection.Failed("超时"));
        assertThat(((TravelSelection.Chosen) results.get(0)).redirect()).as("载荷是同一个对象，没有被重组").isSameAs(REDIRECT);
        assertThat(targets.pendingCount()).isZero();
        assertThat(targets.calls()).as("全部请求都留着记录").hasSize(3);
        assertThatThrownBy(targets::take).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 选目标替身_装了脚本_结果经执行器投递不在调用栈内_hang之后回到挂起() {
        FakeTravelTargets targets = new FakeTravelTargets();
        ManualExecutor logic = new ManualExecutor();
        List<TravelSelection> results = new ArrayList<>();
        targets.replyWith(logic, call -> new TravelSelection.Failed("目标 zone " + call.toZoneId()));

        targets.selectTravel(1001, 2, 0, results::add);

        assertThat(results).as("不在 selectTravel 的调用栈内").isEmpty();
        assertThat(targets.pendingCount()).as("自动回的不进挂起队列").isZero();
        logic.runAll();
        assertThat(results).containsExactly(new TravelSelection.Failed("目标 zone 2"));

        targets.hang();
        targets.selectTravel(1001, 2, 0, results::add);
        logic.runAll();
        assertThat(results).hasSize(1);
        assertThat(targets.pendingCount()).isEqualTo(1);
        assertThat(targets.calls()).hasSize(2);
    }

    @Test
    void 选中的结果不许带空的重定向载荷() {
        assertThatThrownBy(() -> new TravelSelection.Chosen(5, null)).isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------ FakeTeamChecks

    @Test
    void 在队检查替身_缺省挂起_三种结果都能手工完成_记下传进来的上限() {
        FakeTeamChecks checks = new FakeTeamChecks();
        List<TeamCheck> results = new ArrayList<>();

        checks.check(1001, Duration.ofSeconds(2), results::add);
        checks.check(1002, Duration.ofMillis(1500), results::add);
        checks.check(1003, Duration.ofSeconds(2), results::add);

        assertThat(results).isEmpty();
        assertThat(checks.pendingCount()).isEqualTo(3);
        assertThat(checks.calls()).extracting(FakeTeamChecks.PendingCheck::timeout)
                .containsExactly(Duration.ofSeconds(2), Duration.ofMillis(1500), Duration.ofSeconds(2));
        checks.take().notInTeam();
        checks.take().inTeam();
        checks.take().unknown();

        assertThat(results).containsExactly(TeamCheck.NOT_IN_TEAM, TeamCheck.IN_TEAM, TeamCheck.UNKNOWN);
        assertThatThrownBy(checks::take).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 在队检查替身_装了脚本_结果经执行器投递不在调用栈内() {
        FakeTeamChecks checks = new FakeTeamChecks();
        ManualExecutor logic = new ManualExecutor();
        List<TeamCheck> results = new ArrayList<>();
        checks.replyWith(logic, call -> call.playerId() == 1001 ? TeamCheck.NOT_IN_TEAM : TeamCheck.IN_TEAM);

        checks.check(1001, Duration.ofSeconds(2), results::add);
        checks.check(1002, Duration.ofSeconds(2), results::add);

        assertThat(results).isEmpty();
        assertThat(logic.pending()).isEqualTo(2);
        logic.runAll();
        assertThat(results).containsExactly(TeamCheck.NOT_IN_TEAM, TeamCheck.IN_TEAM);
        assertThat(checks.pendingCount()).isZero();

        checks.hang();
        checks.check(1003, Duration.ofSeconds(2), results::add);
        assertThat(checks.pendingCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ RecordingLocations

    @Test
    void 位置替身_待落点写取下一个位置序号_缺省挂起_手工完成恰好一次() {
        RecordingLocations locations = new RecordingLocations();
        ScenePlayer removed = WorldTestAccess.player(1001);
        long seq = removed.locationSeq();
        List<Boolean> results = new ArrayList<>();

        locations.awaitingPlacement(removed, 2, 5, Duration.ofSeconds(290), results::add);

        assertThat(results).as("挂起").isEmpty();
        assertThat(locations.pendingPlacements()).isEqualTo(1);
        assertThat(removed.locationSeq()).as("同 Redis 实现：调用当时取下一个序号").isEqualTo(seq + 1);
        PendingPlacement placement = locations.takePlacement();
        assertThat(placement.playerId()).isEqualTo(1001);
        assertThat(placement.ownerEpoch()).isEqualTo(removed.ownerEpoch());
        assertThat(placement.seq()).isEqualTo(seq + 1);
        assertThat(placement.targetZoneId()).isEqualTo(2);
        assertThat(placement.sceneConfigId()).isEqualTo(5);
        assertThat(placement.ttl()).isEqualTo(Duration.ofSeconds(290));
        assertThat(placement.completed()).isFalse();

        placement.complete(true);

        assertThat(results).containsExactly(true);
        assertThat(placement.completed()).isTrue();
        assertThatThrownBy(() -> placement.complete(false)).as("完成两次是测试的错误").isInstanceOf(IllegalStateException.class);
        assertThat(results).hasSize(1);
        assertThat(locations.placements()).containsExactly(placement);
        assertThat(locations.events()).containsExactly(placement);
        assertThatThrownBy(locations::takePlacement).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 位置替身_设成当场完成_回调就在调用栈内_之后的普通写序号接着往上走() {
        RecordingLocations locations = new RecordingLocations();
        ScenePlayer removed = WorldTestAccess.player(1001);
        long seq = removed.locationSeq();
        List<Boolean> results = new ArrayList<>();
        locations.completeInline(false);

        locations.awaitingPlacement(removed, 2, 5, Duration.ofSeconds(1), results::add);

        assertThat(results).as("在 awaitingPlacement 返回之前就回了").containsExactly(false);
        assertThat(locations.pendingPlacements()).isZero();
        assertThat(locations.placements()).singleElement().satisfies(p -> assertThat(p.completed()).isTrue());

        locations.loggedOutWhileLoading(1001, 7);
        assertThat(locations.writes()).containsExactly(new RecordingLocations.Write("loggedOutWhileLoading", 1001, 7, 1, 0));
        assertThat(locations.events()).as("待落点写与普通写按发生顺序排在同一条时间线上").hasSize(2);
        assertThat(locations.events().get(0)).isInstanceOf(PendingPlacement.class);
        assertThat(removed.locationSeq()).isEqualTo(seq + 1);

        locations.hangPlacements();
        locations.awaitingPlacement(removed, 2, 5, Duration.ofSeconds(1), results::add);
        assertThat(results).hasSize(1);
        assertThat(locations.takePlacement().seq()).isEqualTo(seq + 2);
    }

    /**
     * 「在另一条线程上写完」这条路：回调不在那条线程上跑，而是进了逻辑执行器；测试先带上限等到「已投递」再去排空执行器、读记录
     * （不先读线程名、不睡）。
     */
    @Test
    void 位置替身_在另一条线程上完成_回调进逻辑执行器_由逻辑线程跑() throws Exception {
        RecordingLocations locations = new RecordingLocations();
        ManualExecutor logic = new ManualExecutor();
        List<String> callbackThreads = new ArrayList<>();
        locations.awaitingPlacement(WorldTestAccess.player(1001), 2, 5, Duration.ofSeconds(300),
                written -> callbackThreads.add(Thread.currentThread().getName() + ":" + written));
        PendingPlacement placement = locations.takePlacement();

        Boolean posted = placement.completeOnAnotherThread(logic, true).get(5, TimeUnit.SECONDS);

        assertThat(posted).as("已投递给逻辑执行器").isTrue();
        assertThat(callbackThreads).as("另一条线程只投递、不执行回调").isEmpty();
        assertThat(logic.pending()).isEqualTo(1);
        logic.runAll();
        assertThat(callbackThreads).containsExactly(Thread.currentThread().getName() + ":true");
        assertThat(placement.completed()).isTrue();
        assertThatThrownBy(() -> placement.completeOnAnotherThread(logic, true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 位置替身_在另一条线程上完成时逻辑线程已停_结果丢弃不抛_回调不跑() throws Exception {
        RecordingLocations locations = new RecordingLocations();
        ManualExecutor logic = new ManualExecutor();
        logic.rejectNewTasks(true);
        List<Boolean> results = new ArrayList<>();
        locations.awaitingPlacement(WorldTestAccess.player(1001), 2, 5, Duration.ofSeconds(300), results::add);

        Boolean posted = locations.takePlacement().completeOnAnotherThread(logic, true).get(5, TimeUnit.SECONDS);

        assertThat(posted).as("被拒：丢弃").isFalse();
        assertThat(logic.pending()).isZero();
        assertThat(results).isEmpty();
    }

    // ------------------------------------------------------------------ RecordingSink 与 FakePlayerRepository

    @Test
    void 出站替身_重定向帧照实记录_载荷是同一个对象_可脚本化写不出与异步写失败() {
        RecordingSink sink = new RecordingSink();
        List<String> failures = new ArrayList<>();

        assertThat(sink.playerRedirect(1, 11, 1001, 4, 5, REDIRECT, () -> failures.add("a"))).isTrue();

        assertThat(sink.redirects()).hasSize(1);
        RecordingSink.Redirect sent = sink.redirects().get(0);
        assertThat(sent.linkId()).isEqualTo(1);
        assertThat(sent.sessionId()).isEqualTo(11);
        assertThat(sent.playerId()).isEqualTo(1001);
        assertThat(sent.fromEpoch()).isEqualTo(4);
        assertThat(sent.toEpoch()).isEqualTo(5);
        assertThat(sent.redirect()).isSameAs(REDIRECT);
        assertThat(sink.transfers()).as("重定向帧不记成改绑指令").isEmpty();
        assertThat(sink.events()).as("与其余出站在同一条时间线上").containsExactly(sent);
        assertThat(failures).isEmpty();
        sent.failWrite();
        assertThat(failures).containsExactly("a");

        sink.setRedirectWritable(false);
        assertThat(sink.playerRedirect(1, 11, 1001, 4, 5, REDIRECT, () -> failures.add("b"))).as("确定写不出").isFalse();
        assertThat(sink.redirects()).as("写不出的不记录").hasSize(1);
        assertThat(failures).as("同步写不出时不会回调").containsExactly("a");
        assertThat(sink.playerTransfer(1, 11, 1001, 4, 5, 7, 900_001, () -> { })).as("改绑指令的开关各管各的").isTrue();
    }

    @Test
    void 存储替身_记下交出的模式_两参数形式是HOLD() {
        FakePlayerRepository repo = new FakePlayerRepository();
        PlayerSave frozen = new PlayerSave(1001, 9, 4, 2, new Vec3(7, 8, 9), null);

        repo.handOff(frozen, HandOffMode.RELEASE, outcome -> { });
        repo.handOff(frozen, outcome -> { });

        PendingHandOff release = repo.takeHandOff();
        PendingHandOff hold = repo.takeHandOff();
        assertThat(release.mode()).isEqualTo(HandOffMode.RELEASE);
        assertThat(release.frozen()).isSameAs(frozen);
        assertThat(hold.mode()).isEqualTo(HandOffMode.HOLD);
        assertThatThrownBy(() -> repo.handOff(frozen, null, outcome -> { })).isInstanceOf(NullPointerException.class);
    }
}
