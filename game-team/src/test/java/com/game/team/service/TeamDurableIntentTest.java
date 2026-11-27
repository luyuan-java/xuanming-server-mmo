package com.game.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.game.team.match.MatchTeamBattle;
import com.game.proto.team.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** 默认 SQL 分支不可把 UNKNOWN 转成失败；旧 Redis 断言另由显式兼容夹具继续覆盖。 */
class TeamDurableIntentTest {
    @Test void queuedUnitReturnsOriginalIntentWithoutStartingLegacyGather(){
        var fx=new TeamMatchFixture(TeamMatchFixture.inMemory(),MatchTeamBattle.IntentMode.SQL_AUTHORITY);
        try{
            long[] team=fx.team(2);long caller=team[1],id=team[0];String request=UUID.randomUUID().toString();var state=new java.util.concurrent.atomic.AtomicInteger();
            fx.match.queryReply=r->CompletableFuture.completedFuture(TeamMatchIntent.newBuilder().setRequestId(r.getRequestId()).setState(state.get()).build());
            fx.match.ticketsReply=r->{state.set(2);return CompletableFuture.completedFuture(com.game.api.proto.TeamTicketsReply.newBuilder().setStatus(com.game.api.proto.TeamTicketsStatus.TEAM_TICKETS_QUEUED).build());};
            var reply=fx.service.startTeamMatch(actor(caller),StartTeamMatchRequest.newBuilder().setExpectedTeamId(id).setBattleConfigId(TeamMatchFixture.CONFIG).setTargetTeamSize(3).setRequestId(request).build(),TeamMatchFixture.deadline());
            assertThat(reply.hasErrorMessage()).isFalse();assertThat(reply.getMatchIntent().getRequestId()).isEqualTo(request);assertThat(reply.getMatchIntent().getState()).isEqualTo(2);assertThat(reply.getMatchIntent().getBattleId()).isZero();assertThat(fx.match.gathers).isEmpty();
        }finally{fx.cleanup();}
    }
    private static com.game.api.proto.SessionContext actor(long player){return com.game.api.proto.SessionContext.newBuilder().setPlayerId(player).setAccount("原账号 "+Long.toUnsignedString(player)).setOwnerRevision(7).build();}
    @Test void nilRequestCannotReachCreateAndPendingFormationDoesNotPushFailure(){
        var fx=new TeamMatchFixture(TeamMatchFixture.inMemory(),MatchTeamBattle.IntentMode.SQL_AUTHORITY);
        try{
            long[] team=fx.team(2);long caller=team[1],id=team[0];
            var invalid=fx.service.startTeamMatch(actor(caller),StartTeamMatchRequest.newBuilder().setExpectedTeamId(id).setBattleConfigId(TeamMatchFixture.CONFIG).build(),TeamMatchFixture.deadline());
            assertThat(invalid.getErrorMessage().getId()).isEqualTo(com.game.table.CommonErrorTip.common_error.kInvalidParameter_VALUE);assertThat(fx.match.tickets).isEmpty();
            String request=UUID.randomUUID().toString();var pending=TeamMatchIntent.newBuilder().setRequestId(request).setBattleId(700).setState(2).build();
            fx.match.queryReply=r->{assertThat(r.getOwnerAccount()).isEqualTo(actor(caller).getAccount());assertThat(r.getOwnerRevision()).isEqualTo(7);return CompletableFuture.completedFuture(pending);};
            var replay=fx.service.startTeamMatch(actor(caller),StartTeamMatchRequest.newBuilder().setExpectedTeamId(id).setBattleConfigId(TeamMatchFixture.CONFIG).setRequestId(request).build(),TeamMatchFixture.deadline());
            assertThat(replay.getMatchIntent()).isEqualTo(pending);assertThat(fx.match.tickets).isEmpty();assertThat(fx.match.gathers).isEmpty();
        }finally{fx.cleanup();}
    }
    @Test void durableGatherOnlyCleansBusinessLockAfterSqlTerminalIsKnown(){
        for(int terminal:new int[]{2,4,5}){
            var fx=new TeamMatchFixture(TeamMatchFixture.inMemory(),MatchTeamBattle.IntentMode.SQL_AUTHORITY);
            try{
                long[] team=fx.team(2);long caller=team[1],id=team[0];String request=UUID.randomUUID().toString();
                var state=new java.util.concurrent.atomic.AtomicInteger();var gathering=new CompletableFuture<com.game.api.proto.TeamGatherReply>();
                fx.match.queryReply=r->CompletableFuture.completedFuture(TeamMatchIntent.newBuilder().setRequestId(r.getRequestId()).setState(state.get()).setBattleId(state.get()==0?0:700).build());
                fx.match.ticketsReply=r->{assertThat(r.getRequestId()).isEqualTo(request);assertThat(r.getInitiatorPlayerId()).isEqualTo(caller);assertThat(r.getClientRequest()).isNotEmpty();assertThat(r.getOwnerAccount()).isEqualTo(actor(caller).getAccount());assertThat(r.getOwnerRevision()).isEqualTo(7);state.set(2);
                    return CompletableFuture.completedFuture(com.game.api.proto.TeamTicketsReply.newBuilder().setStatus(com.game.api.proto.TeamTicketsStatus.TEAM_TICKETS_CREATED).build());};
                fx.match.gatherReply=r->gathering;
                var reply=fx.service.startTeamMatch(actor(caller),StartTeamMatchRequest.newBuilder().setExpectedTeamId(id).setBattleConfigId(TeamMatchFixture.CONFIG).setRequestId(request).build(),TeamMatchFixture.deadline());
                assertThat(reply.hasErrorMessage()).isFalse();assertThat(reply.getMatchIntent().getBattleId()).isEqualTo(700);fx.takePushes();
                state.set(terminal);
                if(terminal==2)gathering.completeExceptionally(new TimeoutException("原SQL成局仍在恢复"));
                else gathering.complete(com.game.api.proto.TeamGatherReply.newBuilder().setOk(terminal==4).setBattleId(700).build());
                if(terminal==2){assertThat(fx.record(id).getMatchLockToken()).isNotEmpty();assertThat(fx.takePushes()).isEmpty();}
                else{assertThat(fx.record(id).getMatchLockToken()).isEmpty();assertThat(fx.takePushes()).isNotEmpty();}
                assertThat(fx.match.releases).isEmpty();
            }finally{fx.cleanup();}
        }
    }

    @Test void invalidTargetTeamSizeRejectedBeforeLockingTeam(){
        var fx=new TeamMatchFixture(TeamMatchFixture.inMemory(),MatchTeamBattle.IntentMode.SQL_AUTHORITY);
        try{
            long[] team=fx.team(2);long caller=team[1],id=team[0];
            for(int badTarget:new int[]{1,99,-1}){
                var reply=fx.service.startTeamMatch(actor(caller),StartTeamMatchRequest.newBuilder()
                        .setExpectedTeamId(id).setBattleConfigId(TeamMatchFixture.CONFIG)
                        .setTargetTeamSize(badTarget).setRequestId(UUID.randomUUID().toString()).build(),
                        TeamMatchFixture.deadline());
                assertThat(reply.getErrorMessage().getId()).isEqualTo(com.game.team.rules.TeamTips.SIZE_EXCEEDED);
                assertThat(fx.record(id).getMatchLockToken()).isEmpty();
                assertThat(fx.match.tickets).isEmpty();
            }
        }finally{fx.cleanup();}
    }

    @Test void transferredOwnerIntentReturnsState6OnGetAndInvalidParamWithState6OnStart(){
        var fx=new TeamMatchFixture(TeamMatchFixture.inMemory(),MatchTeamBattle.IntentMode.SQL_AUTHORITY);
        try{
            long[] team=fx.team(2);long caller=team[1],id=team[0];String request=UUID.randomUUID().toString();
            var transferred=TeamMatchIntent.newBuilder().setRequestId(request).setState(6).build();
            fx.match.queryReply=r->CompletableFuture.completedFuture(transferred);

            var view=fx.service.getMyTeam(actor(caller),GetMyTeamRequest.newBuilder().setMatchRequestId(request).build(),TeamMatchFixture.deadline());
            assertThat(view.hasErrorMessage()).isFalse();
            assertThat(view.getMatchIntent()).isEqualTo(transferred);

            var start=fx.service.startTeamMatch(actor(caller),StartTeamMatchRequest.newBuilder()
                    .setExpectedTeamId(id).setBattleConfigId(TeamMatchFixture.CONFIG).setRequestId(request).build(),TeamMatchFixture.deadline());
            assertThat(start.getErrorMessage().getId()).isEqualTo(com.game.table.CommonErrorTip.common_error.kInvalidParameter_VALUE);
            assertThat(start.getMatchIntent()).isEqualTo(transferred);
            assertThat(fx.match.tickets).isEmpty();
            assertThat(fx.record(id).getMatchLockToken()).isEmpty();
        }finally{fx.cleanup();}
    }

    @Test void noBattleTerminalIntentStatesShortCircuitStartWithoutSecondLaunch(){
        for(int state:new int[]{2,5}){
            var fx=new TeamMatchFixture(TeamMatchFixture.inMemory(),MatchTeamBattle.IntentMode.SQL_AUTHORITY);
            try{
                long[] team=fx.team(2);long caller=team[1],id=team[0];String request=UUID.randomUUID().toString();
                var intent=TeamMatchIntent.newBuilder().setRequestId(request).setState(state).build();
                fx.match.queryReply=r->CompletableFuture.completedFuture(intent);
                var reply=fx.service.startTeamMatch(actor(caller),StartTeamMatchRequest.newBuilder()
                        .setExpectedTeamId(id).setBattleConfigId(TeamMatchFixture.CONFIG).setRequestId(request).build(),TeamMatchFixture.deadline());
                assertThat(reply.hasErrorMessage()).isFalse();
                assertThat(reply.getMatchIntent()).isEqualTo(intent);
                assertThat(fx.match.tickets).isEmpty();
            }finally{fx.cleanup();}
        }
    }

    @Test void pvp5v5Rejects4PlayerPartyWhile4v4AndPveAllow4PlayerParty(){
        var fx=new TeamMatchFixture(TeamMatchFixture.inMemory(),MatchTeamBattle.IntentMode.SQL_AUTHORITY);
        try{
            long[] team=fx.team(4);long caller=team[1],id=team[0];
            fx.match.queryReply=r->CompletableFuture.completedFuture(TeamMatchIntent.newBuilder().setRequestId(r.getRequestId()).build());
            // 5v5 PVP_TEAM (target=5 and target=0) rejects 4-player party with 4031
            for(int target:new int[]{0,5}){
                var rejected=fx.service.startTeamMatch(actor(caller),StartTeamMatchRequest.newBuilder()
                        .setExpectedTeamId(id).setBattleConfigId(TeamMatchFixture.CONFIG)
                        .setMatchMode(com.game.proto.match.MatchMode.MATCH_MODE_PVP_TEAM)
                        .setTargetTeamSize(target).setRequestId(UUID.randomUUID().toString()).build(),TeamMatchFixture.deadline());
                assertThat(rejected.getErrorMessage().getId()).isEqualTo(com.game.team.rules.TeamTips.PVP_PARTY_SIZE_NOT_ALLOWED);
                assertThat(fx.match.tickets).isEmpty();
            }
            // 4v4 PVP_TEAM (target=4) is allowed past party-size gate
            fx.match.ticketsReply=r->CompletableFuture.completedFuture(com.game.api.proto.TeamTicketsReply.newBuilder().setStatus(com.game.api.proto.TeamTicketsStatus.TEAM_TICKETS_QUEUED).build());
            var ok4v4=fx.service.startTeamMatch(actor(caller),StartTeamMatchRequest.newBuilder()
                    .setExpectedTeamId(id).setBattleConfigId(TeamMatchFixture.CONFIG)
                    .setMatchMode(com.game.proto.match.MatchMode.MATCH_MODE_PVP_TEAM)
                    .setTargetTeamSize(4).setRequestId(UUID.randomUUID().toString()).build(),TeamMatchFixture.deadline());
            assertThat(ok4v4.hasErrorMessage()).isFalse();
        }finally{fx.cleanup();}
    }

}
