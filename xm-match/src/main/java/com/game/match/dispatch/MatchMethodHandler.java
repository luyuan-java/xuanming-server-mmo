package com.game.match.dispatch;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import java.util.concurrent.Executor;

/**
 * 一个客户端消息号的处理器（match-spec §8.1、§9.9）。各业务包（排队、切磋、补签……）把自己的处理器声明成 Spring bean，派发器启动时收集全部
 * {@code MatchMethodHandler} bean、按 {@link #method()} 经 {@code MessageIdRegistry} 换成消息号建表；同一个方法出现两个处理器、或方法名不在契约的
 * {@code MatchService} 里，启动失败。没有处理器的号回信封 1003。
 *
 * <p>派发器对一次调用做的事（处理器可以依赖）：
 * <ul>
 *   <li>{@link #inline()} 为 true 的处理器在 Dubbo 线程上当场调用；其余投到一个执行器上（可以阻塞等 Redis / MySQL / 别的节点）：
 *       缺省是有界的 {@code match-worker}，处理器也可以经 {@link #executor()} 给出自己的（163 观战：虚拟线程 + 在途上限）。</li>
 *   <li>{@code deadline} = 受理时刻 + {@code xm.match.request-budget}（缺省 4500 ms，含排队时间）。</li>
 *   <li>执行器拒收（{@code execute} 抛 {@code RejectedExecutionException}）、或轮到执行时预算已用完 → 不调 {@link #handle}，改调 {@link #onOverload()}。</li>
 *   <li>{@link #handle} 抛 {@link InvalidProtocolBufferException}（请求体解析失败）或任何 RuntimeException → 信封 1003（后者另记 ERROR）。</li>
 *   <li>同一会话的调用由 gate 串行发出；不同会话并发。处理器必须线程安全。</li>
 * </ul>
 *
 * <p>处理器自己的义务：<b>身份只取 {@code session.getPlayerId()}</b>，请求体里的 player_id 一律忽略（M3）；{@code player_id = 0}（会话没进游戏）
 * 按 §8.1「会话没绑定玩家」一列回，不是异常；依赖故障（{@link Deadline.DependencyException}）自己接住、按 §8.1「依赖故障」一列回——
 * 漏到派发器的异常一律变成信封 1003，对 157 / 152 / 151 / 179 来说那是错的应答形状。
 */
public interface MatchMethodHandler {

    /** 契约方法名（{@link MatchMethods} 的常量，如 {@code "JoinQueue"}）。 */
    String method();

    /**
     * 是否不涉及任何 I/O、可以在 Dubbo 线程上当场回（156 / 154 的上行空操作）。缺省 false：投到执行器上。
     * 返回 true 的处理器在 {@link #handle} 里不得阻塞；它的 {@link #executor()} 不会被用到。
     */
    default boolean inline() {
        return false;
    }

    /**
     * 这个处理器（非 {@link #inline()}）跑在哪个执行器上。缺省 {@code null} = 共用的 {@code match-worker}（固定线程、有界队列），
     * 九个号里除 163 之外都用它。163 观战要同步等 battle 的 RPC（每跳至多 3 s），占着工作线程会拖住排队与补签，所以它给出自己的执行器：
     * 每个请求一条虚拟线程、全局在途上限 {@code xm.match.spectate.max-inflight}（spectate-spec §4.9）。
     *
     * <p><b>对自带执行器的要求</b>（派发器照这几条用它）：
     * <ul>
     *   <li>本方法在 Dubbo 线程上、每次派发被调一次：不得阻塞、不得抛异常，每次都返回<b>同一个</b>执行器（抛了异常按「处理器异常」回信封 1003）。</li>
     *   <li>{@code execute(task)} <b>不阻塞</b>调用线程（Dubbo 线程）、不在调用线程上跑 {@code task}；受理不了（在途已满、已关闭）就抛
     *       {@code RejectedExecutionException}——派发器据此调 {@link #onOverload()}，与工作池满同一个口径。不排队的执行器（虚拟线程）
     *       不会出现「排队等过了预算」，但派发器照样在任务开头看一次截止。</li>
     *   <li>受理了的 {@code task} 必须恰好执行一次：它负责完成这次调用的应答，丢掉它会让 gate 一直等到 5 s 超时。许可之类的资源由执行器自己在
     *       任务结束时（正常返回、抛异常、抛 {@code Error}）归还——派发器交进来的任务已经兜住处理器的 RuntimeException，只有 {@code Error} 会原样抛出。</li>
     *   <li>停机：执行器的拥有者自己提供「等在途任务结束」的手段（163 是 {@code lifecycle.InflightWatches}），派发器与工作池的排空都不管它。</li>
     * </ul>
     */
    default Executor executor() {
        return null;
    }

    /** 处理器的应答（二选一）。 */
    sealed interface Reply {

        /**
         * 应答体：该方法应答消息的序列化字节，可以是 0 字节（全默认值的应答，如 148 成功的 Empty、拒绝邀请后的 151）。业务拒绝也走这里
         * （写在应答体自己的 {@code error_message} 里）。是否回包由 gate 按契约的应答类型决定。
         */
        record Body(ByteString bytes) implements Reply {

            public Body {
                bytes = bytes == null ? ByteString.EMPTY : bytes;
            }
        }

        /** 信封：{@code ClientReply.tip_id}，不带 parameters。只给<b>没有 in-band 错误字段</b>的方法在依赖故障 / 过载时用（148 / 153 回 1003）。 */
        record Envelope(int tipId) implements Reply {

            public Envelope {
                if (tipId <= 0) {
                    throw new IllegalArgumentException("信封必须带非 0 的 tip: " + tipId);
                }
            }
        }

        /** 应答体的便捷构造。 */
        static Reply body(MessageLite response) {
            return new Body(response.toByteString());
        }

        /** 0 字节的应答体（Empty、或全默认值的应答）。 */
        static Reply empty() {
            return new Body(ByteString.EMPTY);
        }

        static Reply envelope(int tipId) {
            return new Envelope(tipId);
        }
    }

    /**
     * 处理一次调用。
     *
     * @param session  gate 填的会话上下文（只信任它；{@code player_id = 0} = 没进游戏；{@code account} 是已认证的账号）
     * @param body     请求消息的序列化字节（处理器自己解析；解析失败直接让 {@link InvalidProtocolBufferException} 抛出）
     * @param deadline 本次请求的截止（含排队时间）；阻塞等依赖时一律用它
     * @return 非 null
     * @throws InvalidProtocolBufferException 请求体解析失败 → 派发器回信封 1003
     */
    Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException;

    /**
     * 过载（执行器拒收：工作池满 / 163 的在途已满；或排队超预算）时的应答（§8.1「过载」列，M29）：有 in-band 错误字段的
     * 157 / 152 / 151 / 179 / 163 回 in-band 16004「服务器繁忙,请稍后再试」；只能用信封的 148 / 153 / 164 回信封 1003。
     * 在 Dubbo 线程或执行器的线程上调用，<b>不得阻塞、不得抛异常</b>；
     * 这时请求体没有被解析过，应答不能依赖请求内容。{@link #inline()} 的处理器不会被调到这个方法（可以直接返回信封 1003）。
     * 派发器已经把这次调用计进 {@code xm_match_requests_seconds{result="overloaded"}}；功能自己的出口计数（如 163 的
     * {@code watch_battle_total{outcome="overloaded"}}）由处理器在这个方法里记。
     */
    Reply onOverload();
}
