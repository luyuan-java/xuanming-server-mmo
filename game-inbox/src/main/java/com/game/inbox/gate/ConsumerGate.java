package com.game.inbox.gate;

/**
 * 入箱的写前门禁（mmorpg 设计 §3.3「写前门禁」、§8 #24；PARITY 收件箱行第 ⑫ 条的 Java 对应）：不让条目落进一个没人消费的箱子。
 *
 * <p>mmorpg 的门禁在 K8s 部署脚本里：渲染消费关、白名单非空时拒绝部署，并只读另一侧线上已部署的值。Java 版没有分 zone 的部署脚本
 * （本机切片与 compose 从同一个检出起全部进程），配置随 jar 进 git（{@code game.scene.inbox.consumer-enabled} 在 game-scene 的
 * application.yaml，{@code game.inbox.enqueue-types} 在本模块的 application.yaml），所以门禁放在<b>写的那一刻</b>：每次入箱前只读线上
 * scene 节点自己发布的开关（节点目录 {@code SceneNodeInfo.inbox_consumer_enabled}）——读的是进程而不是配置文件，比读 ConfigMap 更直接：
 * <ul>
 *   <li>任一在线 scene 节点没开消费（含不认识这个字段的旧版本节点）→ {@link Verdict#CLOSED}，入箱回 kPlayerInboxDisabled；</li>
 *   <li>一个在线 scene 节点都没有 → CLOSED（无从确认消费端已开）；</li>
 *   <li>读不到（Redis 故障）→ {@link Verdict#ERROR}，入箱回 kPlayerInboxStoreUnavailable（读不到即拒绝）。</li>
 * </ul>
 * 只拦新入箱；已入箱的条目照常等消费（scene 关消费时每 5 分钟打 {@code metric=inbox_consumer_disabled}）。
 */
@FunctionalInterface
public interface ConsumerGate {

    enum Verdict {
        OPEN("open"),
        CLOSED("closed"),
        ERROR("error");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** @param detail CLOSED / ERROR 时可进日志的原因 */
    record Result(Verdict verdict, String detail) {

        public static final Result OPEN = new Result(Verdict.OPEN, null);
    }

    /** 判一次（阻塞，至多几次 Redis 往返）。从不抛异常。 */
    Result check();

    /** 恒放行（单测与只测存储的装配）。 */
    ConsumerGate ALWAYS_OPEN = () -> Result.OPEN;
}
