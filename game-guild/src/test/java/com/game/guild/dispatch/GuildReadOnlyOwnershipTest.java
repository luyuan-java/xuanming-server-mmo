package com.game.guild.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.player.PlayerSessionAuthority;
import com.game.contract.MessageIdRegistry;
import com.game.guild.metrics.GuildMetrics;
import com.game.guild.rules.GuildTips;
import com.game.guild.service.GuildEconomyService;
import com.game.guild.service.GuildManageService;
import com.game.guild.service.GuildRankService;
import com.game.guild.service.GuildService;
import com.game.proto.guild.GetGuildRequest;
import com.game.proto.guild.GetGuildResponse;
import com.game.proto.guild.GetPlayerGuildRequest;
import com.game.proto.guild.GetPlayerGuildResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-12 帮会私有只读方法核归属，公开帮会信息不核。 */
class GuildReadOnlyOwnershipTest {

    @Test
    void 帮会私有只读接口拒绝过户旧会话_公开GetGuild照常放行() {
        MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
        GuildService guilds = mock(GuildService.class);
        when(guilds.forActor(any())).thenReturn(guilds);
        when(guilds.getGuild(anyLong(), any(), any())).thenReturn(GetGuildResponse.getDefaultInstance());
        when(guilds.getPlayerGuild(anyLong(), any(), any())).thenReturn(GetPlayerGuildResponse.getDefaultInstance());

        PlayerSessionAuthority.ReadOnlyOwnerCheck check = (pid, acc, rev) ->
                pid == 1001L && "acc-new".equals(acc) && rev == 2L;

        GuildDispatcher dispatcher = new GuildDispatcher(
                registry, guilds, mock(GuildManageService.class), mock(GuildRankService.class),
                mock(GuildEconomyService.class), Runnable::run, new GuildMetrics(new SimpleMeterRegistry()), 3500L)
                .withReadOnlyOwnerCheck(check);

        SessionContext stale = SessionContext.newBuilder().setPlayerId(1001L).setAccount("acc-old").setOwnerRevision(1L).build();
        SessionContext current = SessionContext.newBuilder().setPlayerId(1001L).setAccount("acc-new").setOwnerRevision(2L).build();

        // 公开 GetGuild 不核归属
        int getGuildId = registry.requireId(GuildMethods.SERVICE, GuildMethods.GET_GUILD);
        ClientReply pubReply = dispatcher.dispatch(ClientCall.newBuilder()
                .setSession(stale).setMessageId(getGuildId)
                .setBody(GetGuildRequest.newBuilder().setGuildId(10L).build().toByteString()).build()).join();
        assertThat(pubReply.getTipId()).isZero();

        // 私有 GetPlayerGuild 拒绝旧会话、放行现主
        int getPlayerGuildId = registry.requireId(GuildMethods.SERVICE, GuildMethods.GET_PLAYER_GUILD);
        ClientReply staleReply = dispatcher.dispatch(ClientCall.newBuilder()
                .setSession(stale).setMessageId(getPlayerGuildId)
                .setBody(GetPlayerGuildRequest.getDefaultInstance().toByteString()).build()).join();
        assertThat(staleReply.getTipId()).isEqualTo(GuildTips.SERVICE_UNAVAILABLE);
        verify(guilds, never()).getPlayerGuild(anyLong(), any(), any());

        ClientReply okReply = dispatcher.dispatch(ClientCall.newBuilder()
                .setSession(current).setMessageId(getPlayerGuildId)
                .setBody(GetPlayerGuildRequest.getDefaultInstance().toByteString()).build()).join();
        assertThat(okReply.getTipId()).isZero();
    }
}
