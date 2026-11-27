package com.game.match.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerSessionAuthority;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.script.BattleScriptCatalog;
import com.game.match.script.BattleScriptService;
import com.game.match.script.BattleScriptStore;
import com.game.match.script.BattleScriptValidator;
import com.game.proto.BattleReplayEnvelope;
import com.game.proto.match.GetBattleReportRequest;
import com.game.proto.match.GetBattleReportResponse;
import com.game.proto.match.ListBattleReportsRequest;
import com.game.proto.match.ListBattleReportsResponse;
import com.game.proto.match.ListBattleScriptsRequest;
import com.game.proto.match.ListBattleScriptsResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-12 只读方法核归属（战报列表/回放、作战脚本列表）：过户/删除后旧连接被拒，冻结角色仍可读。 */
class ReadOnlyOwnershipTest {

    @Test
    void 战报列表与回放和作战脚本列表核对角色归属_过户后旧会话拒读_现主正常读() throws Exception {
        BattleReportStore reportStore = mock(BattleReportStore.class);
        when(reportStore.listReports(1001L)).thenReturn(List.of());
        when(reportStore.getReport(1001L, 777L)).thenReturn(Optional.of(BattleReplayEnvelope.getDefaultInstance()));

        // 模拟角色 1001 已过户给 acc-new rev=2；旧会话 acc-old rev=1 失效
        PlayerSessionAuthority.ReadOnlyOwnerCheck check = (pid, acc, rev) ->
                pid == 1001L && "acc-new".equals(acc) && rev == 2L;

        MatchMethodHandler listHandler = BattleReportHandlers.listHandler(reportStore, check);
        MatchMethodHandler getHandler = BattleReportHandlers.getHandler(reportStore, check);

        SessionContext staleSession = SessionContext.newBuilder()
                .setPlayerId(1001L).setAccount("acc-old").setOwnerRevision(1L).build();
        SessionContext currentSession = SessionContext.newBuilder()
                .setPlayerId(1001L).setAccount("acc-new").setOwnerRevision(2L).build();

        // 过户后旧会话被拒，不触碰存储
        var staleList = ListBattleReportsResponse.parseFrom(((MatchMethodHandler.Reply.Body) listHandler.handle(
                staleSession, ListBattleReportsRequest.getDefaultInstance().toByteString(), Deadline.after(3000))).bytes());
        assertThat(staleList.getErrorMessage().getId()).isNotZero();
        var staleGet = GetBattleReportResponse.parseFrom(((MatchMethodHandler.Reply.Body) getHandler.handle(
                staleSession, GetBattleReportRequest.newBuilder().setBattleId(777L).build().toByteString(), Deadline.after(3000))).bytes());
        assertThat(staleGet.getErrorMessage().getId()).isNotZero();
        verify(reportStore, never()).listReports(anyLong());
        verify(reportStore, never()).getReport(anyLong(), anyLong());

        // 现主（含冻结期间）可读
        var okList = ListBattleReportsResponse.parseFrom(((MatchMethodHandler.Reply.Body) listHandler.handle(
                currentSession, ListBattleReportsRequest.getDefaultInstance().toByteString(), Deadline.after(3000))).bytes());
        assertThat(okList.getErrorMessage().getId()).isZero();
        var okGet = GetBattleReportResponse.parseFrom(((MatchMethodHandler.Reply.Body) getHandler.handle(
                currentSession, GetBattleReportRequest.newBuilder().setBattleId(777L).build().toByteString(), Deadline.after(3000))).bytes());
        assertThat(okGet.getErrorMessage().getId()).isZero();

        // 作战脚本列表核归属
        BattleScriptStore scriptStore = mock(BattleScriptStore.class);
        when(scriptStore.listScripts(1001L)).thenReturn(List.of());
        when(scriptStore.listLoadout(1001L)).thenReturn(List.of());
        BattleScriptCatalog catalog = mock(BattleScriptCatalog.class);
        when(catalog.officialScripts()).thenReturn(List.of());
        BattleScriptService scriptService = new BattleScriptService(
                scriptStore, catalog, mock(BattleScriptValidator.Tables.class), () -> 1L)
                .withReadOnlyOwnerCheck(check);
        MatchMethodHandler listScripts = scriptService.clientHandlers().getFirst();

        var staleScripts = ListBattleScriptsResponse.parseFrom(((MatchMethodHandler.Reply.Body) listScripts.handle(
                staleSession, ListBattleScriptsRequest.getDefaultInstance().toByteString(), Deadline.after(3000))).bytes());
        assertThat(staleScripts.getErrorMessage().getId()).isNotZero();
        verify(scriptStore, never()).listScripts(anyLong());

        var okScripts = ListBattleScriptsResponse.parseFrom(((MatchMethodHandler.Reply.Body) listScripts.handle(
                currentSession, ListBattleScriptsRequest.getDefaultInstance().toByteString(), Deadline.after(3000))).bytes());
        assertThat(okScripts.getErrorMessage().getId()).isZero();
    }
}
