package com.game.match.port;

import com.game.common.deadline.Deadline;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.proto.PlayerPresence;
import java.util.Optional;

/**
 * 匹配要读的三样「别人拥有的玩家状态」：战斗锁（scene 写）、在线目录（gate 写）、位置记录（scene 写）。排队入口、凑单的成员校验、成员预检、切磋
 * 都读它们，所以收成一个口——各包的组件测试共用同一个内存替身（{@code FakePlayerStatus}），不必各自去模拟 Redis。生产实现是
 * {@link RedisPlayerStatusReader}（包 xm-discovery 的 {@code BattleLockReader} / {@code PlayerPresenceDirectory} / {@code PlayerLocationDirectory}）。
 *
 * <p><b>契约</b>（对三个方法都成立）：
 * <ul>
 *   <li><b>阻塞</b>：在调用线程上等一次 Redis 读，至多等到 {@code d}；在工作线程 / 凑单线程 / 虚拟线程上调。只读、线程安全。</li>
 *   <li><b>失败一律抛 {@link Deadline.DependencyException}</b>（Redis 出错、超出 {@code d}、记录损坏），<b>绝不把故障折成「没有」</b>。
 *       读失败怎么对客户端交代，各入口口径不同、由调用方自己定：157 回 16004；切磋把读锁失败按「在战斗中」；预检回对应的
 *       {@code *_READ_FAILED}；凑单结束本轮、不做出局判定。</li>
 *   <li>都是<b>咨询性</b>的读（普通读路由，主从部署下可能读到从库的旧值）：只用来早拒。真正保证「一个人不会同时在两局里」的是 scene 的备战写锁——
 *       这里读到「没有锁」而实际有锁，后果只是他在 gather 的备战一步被拒、作为肇事者出局，不会串局。</li>
 * </ul>
 */
public interface PlayerStatusReader {

    /**
     * 这名玩家有没有战斗锁（EXISTS）。锁存在的时段比「在打」长：从备战起，到结算在 scene 落盘并销账为止——这期间 157 回 16000 是预期行为。
     */
    boolean inBattle(long playerId, Deadline d);

    /**
     * 在线目录的严格读：玩家此刻在哪个 gate 的哪个会话上；不在游戏里（没有条目）为空。目录只在「在游戏里」时存在，断线的重连租约期间不存在。
     * 条目损坏或与键不符算故障（抛异常），不算不在线。
     */
    Optional<PlayerPresence> presence(long playerId, Deadline d);

    /**
     * 位置记录的严格读。返回值的 {@code status} 只会是 ONLINE（{@code location} 非 null：zone、scene 节点号都取自它）、RECONNECT_LEASE（断线后的
     * 重连租约：此刻没有节点持有这名玩家）、LOGGED_OUT（登出墓碑）或 MISSING（没有记录）四种之一——<b>ERROR 不会返回，已转成异常</b>。
     * 调用方的口径：157 只认 ONLINE（其余回 16020）；预检要求 ONLINE 且节点号非 0；凑单对 RECONNECT_LEASE 是「本轮跳过、保留排队」，
     * 对 LOGGED_OUT / MISSING 是删票出局。
     */
    HolderRead location(long playerId, Deadline d);
}
