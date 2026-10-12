package com.game.match.port;

/**
 * 能按「空闲多久」清掉自己缓存条目的东西（match 的几个直连客户端缓存）。{@link NodeClientSweeper} 收集全部这种 bean，定时各清一次。
 *
 * <p>契约：{@link #sweepIdle} 可以在任意线程上调、线程安全、<b>不抛异常</b>（出错自己记日志）；允许短暂阻塞（销毁连接），不得无限等待。
 */
public interface IdleSweep {

    /** 缓存的名字（日志用）。 */
    String name();

    /**
     * 清掉空闲超过阈值的条目。
     *
     * @return 这一次清掉的条目数
     */
    int sweepIdle();
}
