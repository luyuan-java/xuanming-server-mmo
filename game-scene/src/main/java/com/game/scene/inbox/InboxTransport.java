package com.game.scene.inbox;

import com.game.proto.player_inbox.AckPlayerInboxRequest;
import com.game.proto.player_inbox.AckPlayerInboxResponse;
import com.game.proto.player_inbox.FetchPlayerInboxRequest;
import com.game.proto.player_inbox.FetchPlayerInboxResponse;
import java.util.function.BiConsumer;

/**
 * scene → game-inbox 的拉取 / 确认（{@code PlayerInboxService}）。实现保证：{@code onDone} <b>恰好一次</b>、在场景逻辑线程上、
 * 不在调用栈内回调（应答或失败之一；失败 = 传输失败，结局未知）。逻辑线程已停时结果丢弃。
 */
public interface InboxTransport {

    void fetch(FetchPlayerInboxRequest request, BiConsumer<FetchPlayerInboxResponse, Throwable> onDone);

    void ack(AckPlayerInboxRequest request, BiConsumer<AckPlayerInboxResponse, Throwable> onDone);
}
