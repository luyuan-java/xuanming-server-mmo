package com.game.team;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;

/**
 * xm-team 的停机标志：Spring 上下文<b>开始</b>关闭的那一刻置位，之后不再复位。
 *
 * <p>唯一的读者是整队开战的 gather 回调（{@code TeamService}）。gather 是一次长挂的 Dubbo 调用，本进程停机时 Dubbo 在
 * {@code ContextClosedEvent} 里销毁引用、断开到 xm-match 的连接，而三个线程池与 Redisson 要到单例销毁阶段才关——这段时间里
 * 若挂着的调用<b>异常完成</b>，回调分不清「xm-match 出事了」还是「我自己在退出」，会照「结果不明」清开战锁并给全员推 MATCH_FAILED，
 * 而 xm-match 那边的 gather 照常跑完、成员随后被 177 / 143 拉进战斗。规格要的是另一种行为（match-spec §7.5：
 * 「xm-team 进程退出：match 照样把 gather 跑完，锁自然过期」，同进程崩溃、同基线「进程退出不等 EndMatch」），所以回调在
 * 本标志置位后遇到异常完成时只计数、不清锁、不推送。
 *
 * <p>实测（2026-10-08，Dubbo 3.3.6，Spring 上下文 + 真 Triple + 另一个进程里的提供方）：停机时在途的调用<b>通常根本不会完成</b>——
 * 撤引用只是发起 HTTP/2 的优雅关闭（有在途流时最长等 10 s），连接要到事件循环关闭才真正断开，而 Dubbo 在那之前已经销毁了回调执行器，
 * 取消通知投不出去；但只要连接断开时执行器还活着（只销毁模块、应用还在时实测约 10.5 s 后；或撤引用与销毁执行器之间有超过 10 s 的慢步骤，
 * 如卡住的注册中心注销），它就以 {@code CANCELLED} 异常完成。行为取决于 Dubbo 内部的先后与版本，所以这里显式钉死，不靠它。
 *
 * <p>次序：{@link #LISTENER_ORDER} 排在 Dubbo 的 {@code DubboDeployApplicationListener}（{@code LOWEST_PRECEDENCE}）之前——
 * 标志必须先于引用销毁置位（先例 xm-match 的 {@code MatchLifecycle.LISTENER_ORDER}）。线程安全（一个 volatile 位）。
 */
public final class TeamShutdown implements ApplicationListener<ContextClosedEvent>, Ordered, ApplicationContextAware {

    /** 作为事件监听器的次序：只要求比 Dubbo 的监听器（{@code LOWEST_PRECEDENCE}）靠前。 */
    public static final int LISTENER_ORDER = Ordered.HIGHEST_PRECEDENCE + 1000;

    private volatile boolean stopping;
    private volatile ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.context = applicationContext;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        // 子上下文的关闭事件也会传到父上下文的监听器：只认自己所在的那个。没有上下文（单元测试直接驱动）时都认
        ApplicationContext mine = context;
        if (mine == null || mine == event.getApplicationContext()) {
            stopping = true;
        }
    }

    @Override
    public int getOrder() {
        return LISTENER_ORDER;
    }

    /** 本进程是否已经开始停机（上下文关闭事件已到）。 */
    public boolean stopping() {
        return stopping;
    }
}
