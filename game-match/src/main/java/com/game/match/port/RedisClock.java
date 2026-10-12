package com.game.match.port;

import com.game.common.deadline.Deadline;

/**
 * Redis 服务器时间（match-spec §9.4「时间」、M7）。匹配里一切跨实例比较的时刻都用它，不用本机时钟——多个 match 实例之间、match 与 scene 之间的
 * 墙钟偏差不该进入期限与等待时长：
 * <ul>
 *   <li>gather 的 {@code deadline_ms}（= 177 的 {@code expire_at_ms}）与 {@code prepare_deadline_ms}：取<b>同一次</b>读数，在任何备战之前；</li>
 *   <li>落点记录的 {@code created_at_ms}（全员备战之后、写落点之前再读一次）。</li>
 * </ul>
 * 票据与切磋记录里的时间由各自的脚本在 Redis 内部取（{@code TIME}），不经这个接口。
 *
 * <p><b>契约</b>：阻塞，至多等到 {@code d}；在工作线程 / 虚拟线程上调。读失败或超时抛 {@link Deadline.DependencyException}
 * （gather 按 {@code internal} 收尾——此时还没冻结任何人）。线程安全。
 */
public interface RedisClock {

    /** Redis {@code TIME}，Unix 毫秒。 */
    long nowMs(Deadline d);
}
