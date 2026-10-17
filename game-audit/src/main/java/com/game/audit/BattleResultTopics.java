package com.game.audit;

import com.game.common.KafkaNames;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 对局结果 topic 的规格（match-spec §5.4，落实 battle-node-spec Q12）：game-battle 生产、game-match 消费（评分），4.6 起 game-guild 用自己的消费组读活动局。
 * 基线是 {@code match-results}（3 分区、保留 7 天，{@code go/match/etc/match_service.yaml:106-107}）。规格件放在 game-audit 是因为 {@link TopicSpec} 与
 * {@link AuditTopicInitializer} 本来就是通用的；第三类 topic 出现时再抽独立模块（match-spec Q15）。
 *
 * <p>分区数是生产方与消费方共同的契约：双方启动时都核对，不符拒绝启用；改分区数只能升代次（{@value #GENERATION_ENV}，两个进程必须一致）。
 * 主人是 game-match（{@link AuditTopicInitializer.Mode#OWN}：校正保留期并读回），game-battle 只核对分区数
 * （{@link AuditTopicInitializer.Mode#CREATE_AND_VERIFY}）。两边都调 {@link #ensure}，分区数不符时的报错指向 {@value #GENERATION_ENV}
 * 而不是审计的代次变量。
 *
 * <p>基名随部署命名空间：{@code <ns>-battle-result}（缺省 {@code game-battle-result}），由注入的 {@link KafkaNames} 给出；game-battle 与 game-match
 * 各用自己进程的 {@code KafkaNames} bean 构造本类，两端必须是同一个命名空间。
 */
public final class BattleResultTopics {

    /** 分区数（同基线 match-results 的 3 分区）。key = battle_id，同一局保序即可。 */
    public static final int PARTITIONS = 3;
    /** 代次的环境变量名（缺省 1）：game-battle 与 game-match 读同一个，本机切片由 start-slice.sh 导出。 */
    public static final String GENERATION_ENV = "GAME_BATTLE_RESULT_TOPIC_GENERATION";
    /** 保留 7 天（逐 topic 显式声明，不继承 broker 默认）。评分入账标记要比它长：game-match 的 RatingCleanup 保留 30 天（保留期 + 滚段周期 + 余量），改这里的保留期或声明 segment.ms 时要重算那边。 */
    static final Map<String, String> RETENTION = Map.of(
            "retention.ms", "604800000",
            "retention.bytes", "-1",
            "cleanup.policy", "delete");

    private final String base;

    public BattleResultTopics(KafkaNames names) {
        this.base = Objects.requireNonNull(names, "names").battleResultTopicBase();
    }

    /** topic 基名 {@code <ns>-battle-result}（缺省 {@code game-battle-result}）；实际名字带代次后缀（{@link #name}）。 */
    public String base() {
        return base;
    }

    /** 代次后的 topic 名：{@code <ns>-battle-result-g<generation>}（缺省 {@code game-battle-result-g<generation>}）。 */
    public String name(int generation) {
        return AuditTopics.name(base, generation);
    }

    /** 这一代次的 topic 规格。 */
    public TopicSpec spec(int generation) {
        return new TopicSpec(name(generation), PARTITIONS, RETENTION);
    }

    /** 消息 key：battle_id 的无符号十进制（生产方写、消费方只拿来对日志；以 payload 里的 battle_id 为准）。 */
    public static String key(long battleId) {
        return Long.toUnsignedString(battleId);
    }

    /**
     * 启动期核对这一代次的对局结果 topic：不存在就按规格创建，存在就核对分区数；{@link AuditTopicInitializer.Mode#OWN} 另把保留期校正到规格并读回。
     * 分区数不符抛 {@link AuditTopicContractException}（报错提示升 {@value #GENERATION_ENV}）；连不上 broker 抛 {@link AuditBrokerUnavailableException}
     * （可恢复：调用方后台重试，核对通过之前生产方不得发送）。阻塞，最长 {@code timeout}；在调用方自己的启动 / 后台线程上调。
     */
    public void ensure(TopicAdmin admin, int generation, AuditTopicInitializer.Mode mode, short replicationFactor, Duration timeout) {
        AuditTopicInitializer.ensureLabeled(admin, List.of(spec(generation)), mode, replicationFactor, timeout, "对局结果 topic", GENERATION_ENV);
    }

    /** 全球结果日志：RF1 旧 topic 必须人工迁移副本；配置/元数据不明时拒绝启用。 */
    public void ensureDurable(TopicAdmin admin,int generation,AuditTopicInitializer.Mode mode,short replicationFactor,Duration timeout){
        if(replicationFactor<3)throw new AuditTopicContractException("全球结果日志必须显式配置至少3副本");
        long deadline=System.nanoTime()+timeout.toNanos();
        Map<String,String> configs=new java.util.HashMap<>(RETENTION);
        configs.put("min.insync.replicas","2");configs.put("unclean.leader.election.enable","false");
        var expected=new TopicSpec(name(generation),PARTITIONS,configs);
        AuditTopicInitializer.ensureLabeled(admin,List.of(expected),mode==AuditTopicInitializer.Mode.OWN?AuditTopicInitializer.Mode.CREATE_AND_VERIFY:mode,
                replicationFactor,remaining(deadline),"全球对局结果 topic",GENERATION_ENV);
        int minimum=admin.minimumReplicaCounts(List.of(expected.name()),remaining(deadline)).getOrDefault(expected.name(),0);
        if(minimum<3)throw new AuditTopicContractException("全球结果日志实际副本不足3，须人工迁移副本，禁止自动降级");
        var actual=admin.configs(expected.name(),configs.keySet(),remaining(deadline));
        if(mode==AuditTopicInitializer.Mode.OWN){
            try{if(Integer.parseInt(actual.getOrDefault("min.insync.replicas","0"))>2)
                configs.put("min.insync.replicas",actual.get("min.insync.replicas"));}catch(NumberFormatException ignored){/* 主人校正非法值。 */}
            admin.alterConfigs(expected.name(),configs,remaining(deadline));
            actual=admin.configs(expected.name(),configs.keySet(),remaining(deadline));
        }
        int minIsr;try{minIsr=Integer.parseInt(actual.getOrDefault("min.insync.replicas","0"));}
        catch(NumberFormatException invalid){throw new AuditTopicContractException("全球结果日志minISR配置无效");}
        if(minIsr<2||!"false".equals(actual.get("unclean.leader.election.enable")))
            throw new AuditTopicContractException("全球结果日志必须minISR至少2且禁止unclean leader election");
    }
    private static Duration remaining(long deadline){return Duration.ofNanos(Math.max(1,deadline-System.nanoTime()));}
}
