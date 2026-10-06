package com.game.match.rating;

import java.util.Collection;
import java.util.Map;

/**
 * 读玩家评分（match-spec §5.2「读接口」；基线 {@code rating.go:132-150}）：排队入口入队前读一次（写进票据与评分镜像），5V5 的 gather 在分队前
 * 重读一次。评分不下发客户端，可见的只有间接效果（凑单容差、5V5 分队）。
 *
 * <p><b>单位是 centi</b>：评分 × 100 的整数（1500.00 = 150000，下限 0）。票据的 {@code rating_centi}、评分镜像的分数、数据库列都是这个单位；
 * 容差曲线的配置（{@code xm.match.rating.tolerance.*}）是评分点，比较前要乘 100。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li><b>永不抛异常、永不拒绝</b>：读失败（库故障、超时、被中断）记 ERROR 并回落 {@link #DEFAULT_CENTI}——评分读不到不该挡住排队（同基线）。</li>
 *   <li><b>阻塞、有界</b>：在调用线程上等，至多约 1 s（语句超时）。实现把 JDBC 放在自己的有界平台线程池（{@code match-db}）上跑、调用线程只在
 *       future 上等——所以可以在虚拟线程上调（虚拟线程里直接跑 JDBC 会钉住载体线程）。不得在 Dubbo / Netty I/O 线程上调。</li>
 *   <li>只读、线程安全。没有行 = 新号 = {@link #DEFAULT_CENTI}。</li>
 * </ul>
 */
public interface RatingReader {

    /** 新号 / 读不到时的评分：1500.00。 */
    long DEFAULT_CENTI = 150_000;

    /** 一名玩家的评分（centi）；没有行或读失败为 {@link #DEFAULT_CENTI}。 */
    long loadCentiOrDefault(long playerId);

    /**
     * 一组玩家的评分（centi）：返回的 Map <b>覆盖入参里的每一个玩家号</b>（没有行或读失败的人是 {@link #DEFAULT_CENTI}），入参重复的玩家号只读一次。
     * 整批读失败时每个人都是缺省值。
     */
    Map<Long, Long> loadAllCentiOrDefault(Collection<Long> playerIds);
}
