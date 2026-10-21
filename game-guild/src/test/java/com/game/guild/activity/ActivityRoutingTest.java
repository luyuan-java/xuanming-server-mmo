package com.game.guild.activity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.game.api.proto.*;
import com.game.contract.MessageIdRegistry;
import com.game.guild.dispatch.GuildDispatcher;
import com.game.guild.metrics.GuildMetrics;
import com.game.guild.service.*;
import com.game.proto.guild.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActivityRoutingTest {
    @Test
    void 五个活动契约实际进入处理器而非功能占位() throws Exception {
        var activities = mock(GuildActivityService.class);
        when(activities.forActor(any())).thenReturn(activities); // 本例只测派发；SQL身份验证有独立回归。
        when(activities.list(eq(7L), any(), any())).thenReturn(GetGuildActivitiesResponse.getDefaultInstance());
        when(activities.lantern(eq(7L), any(), any())).thenReturn(LightGuildLanternResponse.getDefaultInstance());
        when(activities.reunion(eq(7L), any(), any())).thenReturn(ClaimGuildReunionResponse.getDefaultInstance());
        when(activities.start(eq(7L), any(), any())).thenReturn(StartGuildTrialResponse.getDefaultInstance());
        when(activities.respond(eq(7L), any(), any())).thenReturn(RespondGuildTrialInviteResponse.getDefaultInstance());
        var registry = MessageIdRegistry.loadFromClasspath();
        var dispatcher =
                new GuildDispatcher(registry, mock(GuildService.class), mock(GuildManageService.class), mock(GuildRankService.class),
                        mock(GuildEconomyService.class), activities, Runnable::run, new GuildMetrics(new SimpleMeterRegistry()), 3500);
        for (String method :
                List.of("GetGuildActivities", "LightGuildLantern", "ClaimGuildReunion", "StartGuildTrial", "RespondGuildTrialInvite")) {
            var contract = registry.byId(registry.requireId("GuildService", method)).orElseThrow();
            var reply = dispatcher
                                .dispatch(ClientCall.newBuilder()
                                                .setMessageId(contract.messageId())
                                                .setSession(SessionContext.newBuilder().setPlayerId(7))
                                                .setBody(contract.requestPrototype().toByteString())
                                                .build())
                                .get();
            assertEquals(0, reply.getTipId());
            var message = contract.responsePrototype().getParserForType().parseFrom(reply.getBody());
            var field = message.getDescriptorForType().findFieldByName("error_message");
            assertFalse(message.hasField(field), method);
        }
        verify(activities).list(eq(7L), any(), any());
        verify(activities).lantern(eq(7L), any(), any());
        verify(activities).reunion(eq(7L), any(), any());
        verify(activities).start(eq(7L), any(), any());
        verify(activities).respond(eq(7L), any(), any());
    }
}
