package com.game.match.spectate;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.gather.GatherHooks;
import com.game.match.lifecycle.SweeperControl;
import com.game.match.support.MatchTips;
import com.game.proto.match.ListWatchableBattlesRequest;
import com.game.proto.match.ListWatchableBattlesResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 164 ListWatchableBattles、开局钩子与观战清扫的装配（批次 6.5，工作包 W3 负责本文件）。
 *
 * <p><b>现在是先行件放的三个占位</b>（每个提交上契约的十个方法都得有处理器、开局管线与 {@code MatchLifecycle} 的依赖都得在，所以先占着；
 * 行为与 6.4 逐字节相同）。W3 把它们逐个换成真实现——<b>bean 的类型不变</b>：
 * <ul>
 *   <li>{@link #listWatchableBattlesHandler()}：164 的处理器。占位是 6.4 那个当场回的空列表。换成真的 {@code ListWatchableHandler}：
 *       非 inline、跑在 {@code match-worker} 上（不给 {@code executor()}），{@code onOverload()} 回信封 1003。</li>
 *   <li>{@link #gatherHooks()}：开局管线留给观战的两个接缝。占位是 {@link GatherHooks#NOOP}（6.4 时由 {@code MatchConfiguration} 给，
 *       现在挪到这里）。换成 {@code SpectateGatherHooks}。</li>
 *   <li>{@link #sweeperControl()}：观战清扫的启停口。占位什么都不做。换成 {@code SpectateSweeper}（不得自己启停，见 {@link SweeperControl}）。</li>
 * </ul>
 * 不带条件装配（理由见 {@code MatchConfiguration} 的类注释）：测试里要换钩子就把替身标 {@code @Primary}，或自己提供同名 bean。
 */
@Configuration(proxyBeanMethods = false)
public class WatchableConfiguration {

    /** 164 的处理器。<b>占位</b>（6.4 的临时应答原样搬来）：W3 换成真处理器。 */
    @Bean
    public MatchMethodHandler listWatchableBattlesHandler() {
        return new PlaceholderListWatchableHandler();
    }

    /** 开局钩子。<b>占位</b>：W3 换成 {@code SpectateGatherHooks}。 */
    @Bean
    public GatherHooks gatherHooks() {
        return GatherHooks.NOOP;
    }

    /** 观战清扫的启停口。<b>占位</b>：W3 换成 {@code SpectateSweeper}。 */
    @Bean
    public SweeperControl sweeperControl() {
        return SweeperControl.NOOP;
    }

    /**
     * 6.4 期间 164 的临时应答：空列表——全默认值的应答 = 0 字节；应答类型不是 Empty，gate 照常回包。不看会话有没有绑定玩家，
     * 但照样解析请求体（解析失败由派发器回信封 1003）。当场回、不涉及 I/O。
     */
    private static final class PlaceholderListWatchableHandler implements MatchMethodHandler {

        private static final Reply EMPTY_LIST = Reply.body(ListWatchableBattlesResponse.getDefaultInstance());

        @Override
        public String method() {
            return MatchMethods.LIST_WATCHABLE_BATTLES;
        }

        @Override
        public boolean inline() {
            return true;
        }

        @Override
        public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
            ListWatchableBattlesRequest.parseFrom(body);
            return EMPTY_LIST;
        }

        /** inline 的处理器不进执行器，派发器不会调到这里。 */
        @Override
        public Reply onOverload() {
            return Reply.envelope(MatchTips.SERVICE_UNAVAILABLE);
        }
    }
}
