package com.game.match.gather;

import com.game.api.BattleNodeService;
import com.game.api.SceneBattleService;
import com.game.api.proto.BattleNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.location.SceneAssetLocator;
import com.game.match.MatchProperties;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.placement.PlacementStore;
import com.game.match.port.NodeCalls;
import com.game.match.port.RedisClock;
import com.game.match.rating.RatingReader;
import com.game.match.ticket.TicketStore;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 开局管线的装配（match-spec §9.6）：battle 目录的读口、scene 备战 / 取消、补偿、管线本体与它的入口 {@link GatherLauncher}。
 * 依赖别的包经接口注入：票据存储（{@link TicketStore}）、评分读取（{@link RatingReader}）、落点记录（{@link PlacementStore}）、
 * 观战钩子（{@link GatherHooks}，6.4 是空实现）。
 *
 * <p>销毁顺序由依赖链钉住：{@link GatherLauncher} 依赖发号（进而依赖发号租约）与两个直连出站口，Spring 按依赖逆序销毁——
 * 先停用到它的入口（凑单、各提供方），之后才还租约、关直连客户端。在途 gather 的有界等待（{@link GatherLauncher#awaitIdle}）由进程的停机流程调。
 */
@Configuration(proxyBeanMethods = false)
public class GatherConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatherConfiguration.class);

    @Bean
    public BattleNodes battleNodes(NodeDirectory<BattleNodeInfo> battleNodeDirectory, MatchMetrics metrics) {
        return new RedisBattleNodes(battleNodeDirectory, metrics);
    }

    @Bean
    public ScenePreparer scenePreparer(SceneAssetLocator sceneLocator, NodeCalls<SceneBattleService> sceneBattleCalls) {
        return new ScenePreparer(sceneLocator, sceneBattleCalls);
    }

    @Bean
    public Compensation gatherCompensation(TicketStore tickets, ScenePreparer scenePreparer, PlacementStore placements, MatchMetrics metrics,
                                           MatchProperties props) {
        return new Compensation(tickets, scenePreparer, placements, metrics, props.ticketTtl().toMillis(), props.requeueBackoff().toMillis());
    }

    @Bean
    public GatherPipeline gatherPipeline(MatchIds ids, BattleNodes battleNodes, RedisClock clock, GatherHooks hooks, RatingReader ratings,
                                         ScenePreparer scenePreparer, NodeCalls<BattleNodeService> battleNodeCalls, PlacementStore placements,
                                         TicketStore tickets, Compensation gatherCompensation, MatchMetrics metrics, MatchProperties props) {
        SecureRandom seeds = new SecureRandom();
        return new GatherPipeline(new GatherPipeline.Parts(ids, battleNodes, clock, hooks, ratings, scenePreparer, battleNodeCalls, placements,
                tickets, gatherCompensation, metrics), props.tableFingerprintMode(), props.readyTicketTtl().toMillis(), seeds::nextLong);
    }

    @Bean
    public GatherLauncher gatherLauncher(GatherPipeline gatherPipeline, MatchMetrics metrics, MatchProperties props) {
        log.info("gather 在途上限={} 指纹闸={} 回队首退避={}", props.gatherMaxInflight(), props.tableFingerprintMode().label(), props.requeueBackoff());
        return new VirtualThreadGatherLauncher(gatherPipeline, metrics, props.gatherMaxInflight());
    }
}
