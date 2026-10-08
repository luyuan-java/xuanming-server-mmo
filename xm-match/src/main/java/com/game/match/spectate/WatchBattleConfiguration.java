package com.game.match.spectate;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.lifecycle.InflightWatches;
import com.game.match.proto.BattlePlacement;
import com.game.match.support.MatchTips;
import com.game.proto.AddObserverRequest;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.WatchBattleRequest;
import com.game.proto.match.WatchBattleResponse;
import com.game.table.CommonErrorTip;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 163 WatchBattle、观众 RPC 与 163 的在途执行器的装配（批次 6.5，工作包 W2 负责本文件）。
 *
 * <p><b>现在是先行件放的三个占位</b>（每个提交上契约的十个方法都得有处理器、{@code MatchLifecycle} 的依赖都得在，所以先占着；
 * 行为与 6.4 的临时件逐字节相同，M22 还开着）。W2 把它们逐个换成真实现——<b>bean 的类型不变</b>，别的包（W3 的开局钩子注入
 * {@link ObserverDialer}、{@code MatchConfiguration} 注入 {@link InflightWatches}）不用跟着改：
 * <ul>
 *   <li>{@link #watchBattleHandler()}：163 的处理器。占位是 6.4 那个当场回的临时应答（in-band {@code error_message{1006}}，不带 parameters）。
 *       换成真的 {@code WatchBattleHandler}：非 inline，{@code executor()} 返回 163 自己的执行器，{@code onOverload()} 回 in-band 16004。</li>
 *   <li>{@link #observerDialer()}：观众 RPC。占位一律回「没送达」（不发任何调用）。换成包着 {@code PlacementDialer} 的 {@code DefaultObserverDialer}。</li>
 *   <li>{@link #inflightWatches()}：停机时等在途 163 的口。占位恒为「没有在途」。换成 163 的执行器（{@code SpectateExecutor}）本身，
 *       并在这里把在途数接到 {@code MatchMetrics.bindSpectateInflight}。</li>
 * </ul>
 * 不带条件装配（理由见 {@code MatchConfiguration} 的类注释）。
 */
@Configuration(proxyBeanMethods = false)
public class WatchBattleConfiguration {

    /** 163 的处理器。<b>占位</b>（6.4 的临时应答原样搬来）：W2 换成真处理器。 */
    @Bean
    public MatchMethodHandler watchBattleHandler() {
        return new PlaceholderWatchBattleHandler();
    }

    /** 观众 RPC。<b>占位</b>：W2 换成 {@code DefaultObserverDialer}。 */
    @Bean
    public ObserverDialer observerDialer() {
        return new UnavailableObserverDialer();
    }

    /** 停机时等在途 163 的口。<b>占位</b>（占位处理器是 inline 的，没有在途可言）：W2 换成 163 的执行器。 */
    @Bean
    public InflightWatches inflightWatches() {
        return timeout -> true;
    }

    /**
     * 6.4 期间 163 的临时应答（M22）：in-band {@code error_message{1006}}「该功能当前不可用」，不带 parameters；不看会话有没有绑定玩家，
     * 但照样解析请求体（解析失败由派发器回信封 1003）。当场回、不涉及 I/O。
     */
    private static final class PlaceholderWatchBattleHandler implements MatchMethodHandler {

        private static final Reply UNAVAILABLE = Reply.body(WatchBattleResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(CommonErrorTip.common_error.kFeatureUnavailable_VALUE)).build());

        @Override
        public String method() {
            return MatchMethods.WATCH_BATTLE;
        }

        @Override
        public boolean inline() {
            return true;
        }

        @Override
        public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
            WatchBattleRequest.parseFrom(body);
            return UNAVAILABLE;
        }

        /** inline 的处理器不进执行器，派发器不会调到这里。 */
        @Override
        public Reply onOverload() {
            return Reply.envelope(MatchTips.SERVICE_UNAVAILABLE);
        }
    }

    /** 占位的观众 RPC：不发任何调用，一律「没送达」。无状态、线程安全。 */
    private static final class UnavailableObserverDialer implements ObserverDialer {

        private static final Outcome NOT_WIRED = new Outcome.NotDelivered("批次 6.5 施工中：观众 RPC 还没有接上");

        @Override
        public Outcome add(BattlePlacement placement, AddObserverRequest request, Duration timeout, Deadline hardStop) {
            return NOT_WIRED;
        }

        @Override
        public Outcome remove(BattlePlacement placement, long observerId, String reason, Duration timeout, Deadline hardStop) {
            return NOT_WIRED;
        }

        @Override
        public CompletableFuture<Outcome> removeAsync(BattlePlacement placement, long observerId, String reason) {
            return CompletableFuture.completedFuture(NOT_WIRED);
        }
    }
}
