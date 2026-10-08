package com.game.match.lifecycle;

/**
 * 对局结果消费（{@code match-rating-consumer}：topic 核对 + Kafka 消费 + 评分入账）的启停口：{@link MatchLifecycle} 只经它启停，不认识具体的消费类。
 * 评分包把自己的消费者做成这个接口的 bean（一个进程恰好一个，生产实现是 {@code rating.BattleResultIngest}；
 * {@code xm.match.rating.enabled = false} 时也提供，{@link #start} 里什么都不做）。上下文里没有这个 bean 时进程拒绝启动。
 *
 * <p><b>契约</b>（match-spec §9.8 第 9 步、§5.3、§15.3「分区数不符时拒绝启动」）：
 * <ul>
 *   <li>实现<b>不得自己启停</b>（理由同 {@link MatcherControl}）：消费排在凑单之后启动、在途 gather 等完之后才停。</li>
 *   <li>{@link #start} 只调一次，在启动线程上（Dubbo 已导出、凑单已启动之后）。<b>有界阻塞</b>：对局结果 topic 的第一次核对同步做，至多等
 *       {@code xm.match.kafka.init-timeout}（缺省 10 s；Kafka 可达时是毫秒级）。
 *       <ul>
 *         <li>topic 与契约不符（分区数不对、保留期校正不过来）→ <b>抛异常 = 拒绝启动</b>（要升 topic 代次，xm-battle 与 xm-match 一起改）；</li>
 *         <li><b>Kafka 不可达不抛</b>：告警、之后在自己的线程上每 30 s 重试，进程照常启动（排队与开局不依赖评分消费）。Kafka 不可达时启动因此多等
 *             一个 {@code init-timeout}，这段时间 Dubbo 已经在服务。</li>
 *       </ul>
 *       消费本身（建连、拉取、入账）都在自己的线程上。</li>
 *   <li>{@link #stop} 停止拉取并等手上这一条入账结束（有界），未提交的位点留给下次启动重放（入账按 battle_id 幂等）。幂等；
 *       没 {@link #start} 过也能调；不抛异常。</li>
 * </ul>
 */
public interface ResultConsumerControl {

    /** 核对 topic（同步、有界）并开始消费。 */
    void start();

    /** 停止消费。 */
    void stop();
}
