package com.game.battle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * xm-battle 进程入口（批次 6.2，docs/porting/battle-node-spec.md）：回合制战斗节点。
 *
 * <p>进程构成（§7.1）：
 * <ul>
 *   <li>客户端直连面（{@code com.game.battle.edge}，Netty，缺省端口 12000）：票据握手后承载四条战斗 RPC 与全部战斗帧；</li>
 *   <li>控制面（{@code com.game.battle.rpc}，Dubbo Triple {@code BattleNodeService}，group {@code battle-node}，{@code register = false}，
 *       缺省端口 21200）：建房 / 销毁 / 补签 / 观众登记，按节点目录直连；</li>
 *   <li>管理 Tomcat（缺省 18112，只绑本机）：actuator 与 dev / test 专用的 {@code /admin/battle/dev/*}。</li>
 * </ul>
 *
 * <p>线程（§7.3）：一条 {@code battle-logic} 线程同时是直连面唯一的 Netty I/O EventLoop，独占全部房间、直连会话与房间计时器；
 * Dubbo 线程只做第一道准入检查后投递任务，阻塞 I/O 一律不上逻辑线程。启停顺序见 {@code BattleNode}（§7.11）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BattleApplication {

    /**
     * 不让 Dubbo 往 JVM 注册它自己的关停钩子（同 xm-scene 的 {@code SceneApplication}、xm-guild 的 {@code GuildApplication}）：
     * 控制面 {@code BattleNodeService} 是 {@code IsolatedDubboModule} 里编程式导出的，Dubbo 3.3.6 在导出时无条件注册 {@code DubboShutdownHook}；
     * SIGTERM 时它与 Spring 的关停钩子并行，一开始就把提供方置只读并销毁整个模型——这时 {@code BattleNode.stop()} 可能还在摘目录、
     * 还没关准入闸、没作废房间，§7.11 第 4 步「关闸 + 作废 + 排空之后才反导出」被倒过来：在途 / 新到的 createBattle 拿到传输失败而不是
     * {@code NOT_ALLOCATABLE}（match 只能当「可能已建」去补偿，而不是无副作用地换节点重试），已排进逻辑线程的建房应答无处可回。
     * 关掉之后反导出只由 {@code BattleNode} 在停机顺序里做。
     *
     * <p>必须是 Spring 启动之前的系统属性：钩子在 {@code FrameworkModel.newApplication()} 构造部署器时就读这个开关，
     * {@code IsolatedDubboModule.create} 之后再写应用配置已经晚了。本进程只有这一个 Dubbo 应用，系统属性只影响它。
     */
    static final String DUBBO_SHUTDOWN_HOOK_IGNORE = "dubbo.shutdownHook.listenIgnore";

    public static void main(String[] args) {
        ignoreDubboShutdownHook();
        SpringApplication.run(BattleApplication.class, args);
    }

    /** 见 {@link #DUBBO_SHUTDOWN_HOOK_IGNORE}；{@link #main} 第一步调用（测试也调它，核对开关真的生效）。 */
    static void ignoreDubboShutdownHook() {
        System.setProperty(DUBBO_SHUTDOWN_HOOK_IGNORE, "true");
    }
}
