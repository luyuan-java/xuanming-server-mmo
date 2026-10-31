package com.game.battle.port;

import com.game.proto.contracts.kafka.SpectateFrameEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 观战中继帧的出站端口（mmorpg battle-spectate-tiered-fanout.md §15.4、§15.7；房间只认这个接口，换传输只替换实现）。
 *
 * <p>契约：只发起异步操作、立即返回，<b>不阻塞、不抛异常</b>；至多一次投递，失败只计数（每帧都带全量状态，下一帧自愈），不重试、不影响回合推进。
 * 返回的 stage 在这一帧有结局时完成（成功或失败都算），<b>且一定会完成</b>：房间按它串行同一场的帧，上一帧没有结局下一帧不发。
 * 所以第一帧在逻辑线程上调，之后的帧可能在上一帧的回调线程上调，实现要线程安全。
 */
@FunctionalInterface
public interface SpectateFrameSink {

    /** 不发布（测试与不关心中继的装配）。 */
    SpectateFrameSink NONE = frame -> CompletableFuture.completedFuture(null);

    CompletionStage<Void> publish(SpectateFrameEvent frame);
}
