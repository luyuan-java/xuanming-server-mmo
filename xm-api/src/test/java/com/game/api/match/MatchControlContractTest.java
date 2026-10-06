package com.game.api.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.DubboGroups;
import com.game.api.MatchInternalService;
import com.game.api.MatchTeamService;
import com.game.api.proto.TeamGatherReply;
import com.game.api.proto.TeamGatherRequest;
import com.game.api.proto.TeamMatchCheckReply;
import com.game.api.proto.TeamMatchCheckRequest;
import com.game.api.proto.TeamMatchCheckResult;
import com.game.api.proto.TeamTicketsRelease;
import com.game.api.proto.TeamTicketsReply;
import com.game.api.proto.TeamTicketsRequest;
import com.game.api.proto.TeamTicketsStatus;
import com.game.proto.Empty;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import com.google.protobuf.Descriptors.Descriptor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 整队开战 / 活动开战的 Java 自有契约（match-spec §7.2、§7.5；lead 裁决 3）：应答枚举的首值是 UNSPECIFIED——全默认值（字段缺失、旧版本、损坏）
 * 不得被读成成功 / OK；字段号钉住（滚动升级时两边版本不一也能互通）；接口全异步、方法集就是规格那四个加一个。
 */
class MatchControlContractTest {

    @Test
    void 预检应答的缺省值不是OK() {
        assertThat(TeamMatchCheckReply.getDefaultInstance().getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_UNSPECIFIED);
        assertThat(TeamMatchCheckResult.TEAM_MATCH_CHECK_UNSPECIFIED.getNumber()).isZero();
        assertThat(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK.getNumber()).isEqualTo(1);
    }

    @Test
    void 预检结论的数值钉住_七个业务值() {
        assertThat(TeamMatchCheckResult.TEAM_MATCH_CHECK_DUNGEON_NOT_OPEN.getNumber()).isEqualTo(2);
        assertThat(TeamMatchCheckResult.TEAM_MATCH_CHECK_SIZE_EXCEEDED.getNumber()).isEqualTo(3);
        assertThat(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_OFFLINE.getNumber()).isEqualTo(4);
        assertThat(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_IN_BATTLE.getNumber()).isEqualTo(5);
        assertThat(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_NOT_READY.getNumber()).isEqualTo(6);
        assertThat(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL.getNumber()).isEqualTo(7);
        assertThat(Arrays.stream(TeamMatchCheckResult.values()).filter(v -> v != TeamMatchCheckResult.UNRECOGNIZED)).hasSize(8);
    }

    @Test
    void 建票应答的缺省值不是全员建成() {
        assertThat(TeamTicketsReply.getDefaultInstance().getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_UNSPECIFIED);
        assertThat(TeamTicketsStatus.TEAM_TICKETS_UNSPECIFIED.getNumber()).isZero();
        assertThat(TeamTicketsStatus.TEAM_TICKETS_CREATED.getNumber()).isEqualTo(1);
        assertThat(TeamTicketsStatus.TEAM_TICKETS_FAILED.getNumber()).isEqualTo(2);
        assertThat(TeamTicketsStatus.TEAM_TICKETS_EXPIRED.getNumber()).isEqualTo(3);
        assertThat(Arrays.stream(TeamTicketsStatus.values()).filter(v -> v != TeamTicketsStatus.UNRECOGNIZED)).hasSize(4);
    }

    @Test
    void gather应答全默认值就是失败() {
        TeamGatherReply empty = TeamGatherReply.getDefaultInstance();
        assertThat(empty.getOk()).isFalse();
        assertThat(empty.getBattleId()).isZero();
        assertThat(empty.getOutcome()).isEmpty();
    }

    @Test
    void 没见过的枚举值读成UNRECOGNIZED_不会落进任何业务分支() throws Exception {
        // 字段 1 = varint 99：将来的新值、或损坏的数据
        TeamMatchCheckReply check = TeamMatchCheckReply.parseFrom(new byte[] {0x08, 99});
        assertThat(check.getResult()).isEqualTo(TeamMatchCheckResult.UNRECOGNIZED);
        assertThat(check.getResultValue()).isEqualTo(99);
        TeamTicketsReply tickets = TeamTicketsReply.parseFrom(new byte[] {0x08, 99});
        assertThat(tickets.getStatus()).isEqualTo(TeamTicketsStatus.UNRECOGNIZED);
    }

    @Test
    void 字段号钉住() {
        assertFields(TeamMatchCheckRequest.getDescriptor(), "battle_config_id", 1, "roster", 2);
        assertFields(TeamMatchCheckReply.getDescriptor(), "result", 1, "offender", 2, "zones", 3, "lock_ttl_seconds", 4);
        assertFields(TeamTicketsRequest.getDescriptor(), "battle_config_id", 1, "team_id", 2, "roster", 3, "zones", 4, "ticket_ids", 5);
        assertFields(TeamTicketsReply.getDescriptor(), "status", 1, "failed_player_id", 2);
        assertFields(TeamTicketsRelease.getDescriptor(), "ticket_ids", 1);
        assertFields(TeamGatherRequest.getDescriptor(), "battle_config_id", 1, "team_id", 2, "roster", 3, "ticket_ids", 4);
        assertFields(TeamGatherReply.getDescriptor(), "ok", 1, "outcome", 2, "battle_id", 3);
    }

    @Test
    void 往返_玩家号是64位无符号_名单顺序与映射都保得住() throws Exception {
        long big = Long.MIN_VALUE + 7; // ≥ 2^63
        TeamTicketsRequest request = TeamTicketsRequest.newBuilder().setBattleConfigId(1).setTeamId(big)
                .addRoster(big).addRoster(3).addRoster(2)
                .putZones(big, 9).putZones(3, 1)
                .putTicketIds(big, "6f1c2d3e-0000-4000-8000-000000000001").putTicketIds(3, "t3").putTicketIds(2, "t2").build();

        TeamTicketsRequest parsed = TeamTicketsRequest.parseFrom(request.toByteArray());

        assertThat(parsed.getRosterList()).as("名单顺序 = 站位顺序，不得被排序").containsExactly(big, 3L, 2L);
        assertThat(parsed.getZonesMap()).containsOnlyKeys(big, 3L).containsEntry(big, 9);
        assertThat(parsed.getZonesOrDefault(2, 0)).as("缺的人按 0").isZero();
        assertThat(parsed.getTicketIdsOrThrow(big)).isEqualTo("6f1c2d3e-0000-4000-8000-000000000001");
        assertThat(parsed.getTeamId()).isEqualTo(big);

        TeamMatchCheckReply ok = TeamMatchCheckReply.parseFrom(TeamMatchCheckReply.newBuilder()
                .setResult(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK).putZones(big, 2)
                .setLockTtlSeconds(MatchBudgets.teamMatchLockSeconds(5)).build().toByteArray());
        assertThat(ok.getLockTtlSeconds()).isEqualTo(101);
        assertThat(ok.getOffender()).isZero();
    }

    @Test
    void 整队端口是规格的四个异步方法() throws Exception {
        assertThat(Arrays.stream(MatchTeamService.class.getDeclaredMethods()).map(Method::getName))
                .containsExactlyInAnyOrder("checkTeamMatch", "createTeamTickets", "releaseTeamTickets", "runTeamGather");
        for (Method method : MatchTeamService.class.getDeclaredMethods()) {
            assertThat(method.getReturnType()).as(method.getName()).isEqualTo(CompletableFuture.class);
            assertThat(method.getParameterCount()).as(method.getName()).isEqualTo(1);
        }
        MatchTeamService service = new MatchTeamService() {
            @Override
            public CompletableFuture<TeamMatchCheckReply> checkTeamMatch(TeamMatchCheckRequest request) {
                return CompletableFuture.completedFuture(TeamMatchCheckReply.newBuilder()
                        .setResult(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_OFFLINE).setOffender(request.getRoster(1)).build());
            }

            @Override
            public CompletableFuture<TeamTicketsReply> createTeamTickets(TeamTicketsRequest request) {
                return CompletableFuture.completedFuture(TeamTicketsReply.newBuilder().setStatus(TeamTicketsStatus.TEAM_TICKETS_CREATED).build());
            }

            @Override
            public CompletableFuture<Empty> releaseTeamTickets(TeamTicketsRelease request) {
                return CompletableFuture.completedFuture(Empty.getDefaultInstance());
            }

            @Override
            public CompletableFuture<TeamGatherReply> runTeamGather(TeamGatherRequest request) {
                return CompletableFuture.completedFuture(TeamGatherReply.newBuilder().setOk(true).setBattleId(77).setOutcome("success").build());
            }
        };
        assertThat(service.checkTeamMatch(TeamMatchCheckRequest.newBuilder().addRoster(1).addRoster(2).build()).get(1, TimeUnit.SECONDS)
                .getOffender()).isEqualTo(2);
        assertThat(service.runTeamGather(TeamGatherRequest.getDefaultInstance()).get(1, TimeUnit.SECONDS).getBattleId()).isEqualTo(77);
    }

    @Test
    void 活动开战直接用同步来的契约类_拒绝原因首值NONE表示成功是契约既定的() throws Exception {
        // 这是两版共享的契约（match_internal.proto），不归 Java 改：NONE = 0 是「成功」。所以调用方必须同时看 battle_id ≠ 0，
        // 传输失败（future 异常完成）按 INTERNAL 映射——这条约束写在 MatchInternalService 的接口注释里，这里钉住它的形状。
        assertThat(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_NONE.getNumber()).isZero();
        assertThat(StartActivityBattleResponse.getDefaultInstance().getBattleId()).isZero();
        Method method = MatchInternalService.class.getDeclaredMethod("startActivityBattle", StartActivityBattleRequest.class);
        assertThat(method.getReturnType()).isEqualTo(CompletableFuture.class);
        assertThat(MatchInternalService.class.getDeclaredMethods()).hasSize(1);
        MatchInternalService service = request -> CompletableFuture.completedFuture(StartActivityBattleResponse.newBuilder()
                .setReject(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE).setOffenderPlayerId(request.getMemberPlayerIds(0)).build());
        assertThat(service.startActivityBattle(StartActivityBattleRequest.newBuilder().addMemberPlayerIds(5).build()).get(1, TimeUnit.SECONDS)
                .getOffenderPlayerId()).isEqualTo(5);
    }

    @Test
    void 三个接口共用group_match() {
        assertThat(DubboGroups.MATCH).isEqualTo("match");
    }

    private static void assertFields(Descriptor descriptor, Object... nameThenNumber) {
        assertThat(descriptor.getFields()).as(descriptor.getName() + " 的字段数").hasSize(nameThenNumber.length / 2);
        for (int i = 0; i < nameThenNumber.length; i += 2) {
            String name = (String) nameThenNumber[i];
            assertThat(descriptor.findFieldByName(name)).as(descriptor.getName() + "." + name).isNotNull();
            assertThat(descriptor.findFieldByName(name).getNumber()).as(descriptor.getName() + "." + name).isEqualTo(nameThenNumber[i + 1]);
        }
    }
}
