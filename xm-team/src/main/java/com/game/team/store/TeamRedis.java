package com.game.team.store;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * {@link TeamStore} 需要的 Redis 操作（生产实现 {@link RedissonTeamRedis}；单测可换成假实现制造故障或记录调用）。
 *
 * <p>实现必须：参数与回复走字节（pb 是任意字节，不能经 UTF-8）；{@link TeamScript#writes()} 为 true 的脚本以 READ_WRITE 执行；
 * 异步返回、不阻塞调用线程，失败以异常完成的 stage 表达（不同步抛出）。
 */
public interface TeamRedis {

    /**
     * 执行一段组队脚本。
     *
     * @param keys 键（{@code RedisKeys.team*} 生成的字符串）
     * @param args 参数（任意字节；数字一律 ASCII 十进制）
     * @return {@link TeamScript#multi()} 为 true 时是 {@code List<Object>}（整数 {@code Long}、字符串 {@code byte[]}），否则是 {@code Long}
     */
    CompletionStage<Object> eval(TeamScript script, List<Object> keys, List<byte[]> args);

    /** {@code HGET key field}；键或字段不存在为 null。 */
    CompletionStage<byte[]> hget(String key, String field);
}
