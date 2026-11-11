package com.game.guild;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.common.deadline.Deadline;
import com.game.guild.dispatch.GuildDispatcher;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.TimeoutCountDown;
import org.junit.jupiter.api.Test;

/**
 * {@code handle} 入口的接线：gate 经 Dubbo 超时倒计时传来的截止在 Dubbo 线程上读出、交给派发器（mmorpg client-request-ordering.md §6.1）。
 * 派发器那一侧「调用方截止已尽 → 不碰库」由 {@code GuildDispatcherTest} 钉住；这里钉入口——入口不读 {@code CallerDeadline} 时本用例红。
 */
class GuildClientMessageServiceCallerDeadlineTest {

    private final GuildDispatcher dispatcher = mock(GuildDispatcher.class);
    private final GuildClientMessageService service = new GuildClientMessageService(dispatcher);

    @Test
    void handle在入口读gate传来的截止交给派发器_剩余已尽交过期截止_没有倒计时交空() {
        ClientCall call = ClientCall.newBuilder().setMessageId(1).build();
        when(dispatcher.dispatch(eq(call), any())).thenReturn(CompletableFuture.completedFuture(ClientReply.getDefaultInstance()));
        try {
            // 提供方 ContextFilter 把倒计时放在服务端附件上；剩余不超过传播余量 → 交下去的截止一开始就过期
            RpcContext.getServerAttachment().setObjectAttachment(CommonConstants.TIME_COUNTDOWN_KEY,
                    TimeoutCountDown.newCountDown(Deadline.CALLER_MARGIN_MILLIS, TimeUnit.MILLISECONDS));
            service.handle(call).join();
        } finally {
            RpcContext.removeServerAttachment();
        }
        verify(dispatcher).dispatch(eq(call), argThat(caller -> caller.isPresent() && caller.get().expired()));

        // 没有倒计时（旧 gate）：交空，派发器只看本地预算
        service.handle(call).join();
        verify(dispatcher).dispatch(call, Optional.empty());
    }
}
