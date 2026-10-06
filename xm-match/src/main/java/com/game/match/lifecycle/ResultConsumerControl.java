package com.game.match.lifecycle;

/**
 * 对局结果消费（{@code match-rating-consumer}：topic 核对 + Kafka 消费 + 评分入账）的启停口：{@link MatchLifecycle} 只经它启停，不认识具体的消费类。
 * 评分包把自己的消费者包成这个接口的 bean（一个进程恰好一个；{@code xm.match.rating.enabled = false} 时也提供，{@link #start} 里什么都不做）；
 * 上下文里没有这个 bean 时 {@link MatchLifecycle} 按「评分消费尚未接入」启动并告警。
 *
 * <p><b>契约</b>（match-spec §9.8 第 9 步、§5.3）：
 * <ul>
 *   <li>实现<b>不得自己启停</b>（理由同 {@link MatcherControl}）：消费排在凑单之后启动、在途 gather 等完之后才停。</li>
 *   <li>{@link #start} 只调一次，在启动线程上，<b>不阻塞、不因 Kafka 不可达而抛</b>：topic 核对与建连都在自己的线程上做，不可达时告警、
 *       每 30 s 后台重试，进程照常启动（排队与开局不依赖评分消费）。只有本地配置错误（如代次非法）才抛 = 拒绝启动。</li>
 *   <li>{@link #stop} 停止拉取并等手上这一条入账结束（有界），未提交的位点留给下次启动重放（入账按 battle_id 幂等）。幂等；
 *       没 {@link #start} 过也能调；不抛异常。</li>
 * </ul>
 */
public interface ResultConsumerControl {

    /** 开始核对 topic 并消费。 */
    void start();

    /** 停止消费。 */
    void stop();
}
