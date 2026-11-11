package com.game.api;

import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import java.util.concurrent.CompletableFuture;

/**
 * 客户端消息的后端入口（Dubbo 服务）。gate 按消息号所属的 proto 域（{@code proto/login/...} 为 {@code login}）
 * 选择 Dubbo group 转发；后端内部再按消息号派发到具体处理方法。
 *
 * <p>失败分三层（客户端契约见 {@code docs/reference/mmorpg-client-contract-login.md}「Java 必须做到」）：
 * <ul>
 *   <li><b>业务失败</b>（名字被占、角色不属于本账号……）写进应答体<b>自己的</b> {@code error_message} 字段，
 *       放在 {@link ClientReply#getBody()} 里，future 正常完成、{@code tip_id} 为 0。客户端只看应答体、不看信封，
 *       所以业务错误绝不能只放在 {@code tip_id}；成功应答体里也绝不能出现 {@code error_message}。</li>
 *   <li><b>传输层失败</b>（后端没能按方法处理这次调用：消息号不认识、请求体解析失败等）才用
 *       {@link ClientReply#getTipId()}，gate 放进 {@code MessageContent.error_message}。</li>
 *   <li><b>调用失败</b>（超时、后端不可用）：future 异常完成，gate 回带请求 id 的信封 1003（2026-10-15 起，原为推通用的「服务不可用」tip）。</li>
 * </ul>
 *
 * <p>是否回包由 gate 按契约里该方法的应答类型决定，不看 body 是否为空：应答类型不是 {@code Empty} 的方法一律回包，
 * 哪怕 body 是 0 字节（proto3 全默认值的应答序列化后就是空）；应答类型是 {@code Empty} 的方法只在 {@code tip_id} 非 0 时回包。
 * 会话指令（{@link ClientReply#getDirectivesList()}）无论回不回包都按顺序执行。
 *
 * <p>其他契约：
 * <ul>
 *   <li>同一会话的调用由 gate 串行发出：上一个<b>在 gate 看来</b>完成才发下一个。「完成」包括调用超时与调用以错误结束——gate 放行下一条时，
 *       后端的上一条可能还在跑（后端的处理不会因调用方放弃而停下）。所以后端不能假定同一会话没有并发，而要保证<b>截止之后不提交</b>：
 *       截止取 min(本地预算, 调用方剩余)（{@link com.game.common.deadline.Deadline#within}），调用方剩余在 {@code handle} 入口、Dubbo 线程上
 *       用 {@link CallerDeadline#fromRpcContext()} 读（gate 在 {@code handle} 上开 Dubbo 的超时倒计时，传的是这一次调用的超时、相对值），
 *       每个提交点之前检查它（mmorpg {@code docs/design/client-request-ordering.md} §5、§6.1）。截止前一刻已发出、晚落地的那一条写
 *       由业务栅栏兜底（版本 CAS、幂等键、owner_epoch）。跨会话的并发（同账号多连接）同样由后端自己控制。</li>
 *   <li>后端只信任 {@link ClientCall#getSession()} 里 gate 填的字段。</li>
 * </ul>
 */
public interface ClientMessageService {

    CompletableFuture<ClientReply> handle(ClientCall call);

    /** 会话结束通知（断线或主动离开）。幂等：同一会话重复通知不产生副作用。 */
    CompletableFuture<Ack> sessionClosed(SessionClosed event);

    /**
     * 一条 {@code EnterScene} 指令没能送到任何 scene（gate 确定 {@code PlayerEnter} 从未写上链路）：
     * 后端释放这次进游戏夺得的归属（带 owner_epoch 围栏，epoch 已变或已释放时什么也不做），
     * 玩家不必等归属租约过期就能再次进游戏。幂等；失败只影响「多等一个租约」，调用方不重试。
     */
    CompletableFuture<Ack> abandonEnter(AbandonedEnter event);
}
