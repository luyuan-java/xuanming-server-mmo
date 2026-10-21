package com.game.match.global;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.api.BattleNodeService;
import com.game.api.proto.BattleNodeInfo;
import com.game.common.RunMode;
import com.game.discovery.NodeDirectory;
import com.game.match.MatchProperties;
import com.game.match.dispatch.*;
import com.game.match.gather.*;
import com.game.match.id.MatchIds;
import com.game.match.placement.PlacementStore;
import com.game.match.port.*;
import com.game.match.rating.RatingReader;
import com.game.proto.match.*;
import java.util.ArrayList;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({GlobalMatchProperties.class,GlobalRankProperties.class})
public class GlobalMatchConfiguration {
    // BPP 创建早于属性占位符处理器，直接从 Environment 读取，不能在这里注入 @Value。
    @Bean static BeanPostProcessor globalConnectionPolicy(Environment environment){return new ConnectionPolicy(
            environment.getProperty("game.match.global.enabled",Boolean.class,false)
                    &&environment.getProperty("game.match.global.require-ti-db",Boolean.class,true));}
    private static final class ConnectionPolicy implements BeanPostProcessor,Ordered {
        private final boolean required;ConnectionPolicy(boolean required){this.required=required;}
        public int getOrder(){return Ordered.LOWEST_PRECEDENCE;}
        public Object postProcessBeforeInitialization(Object bean,String name){
            if(!required||!(bean instanceof DataSource))return bean;
            if(!(bean instanceof DruidDataSource pool)||pool.isInited())throw new IllegalStateException("全球匹配强读策略必须先于 Druid 初始化");
            var statements=pool.getConnectionInitSqls()==null?new ArrayList<String>():new ArrayList<>(pool.getConnectionInitSqls());
            statements.add("SET SESSION tidb_snapshot='',tidb_read_staleness=0,tidb_low_resolution_tso=OFF,tidb_read_consistency='strict',tidb_replica_read='leader',tidb_txn_mode='pessimistic',transaction_isolation='READ-COMMITTED',sql_mode=CONCAT(@@SESSION.sql_mode,',STRICT_TRANS_TABLES'),innodb_lock_wait_timeout=3");
            pool.setConnectionInitSqls(statements);return bean;
        }
    }
    @Bean Object globalProductionGate(GlobalMatchProperties props,@Value("${game.run-mode:prod}")String mode){
        if(RunMode.parse(mode)==RunMode.PROD&&(!props.enabled()||!props.requireTiDb()))
            throw new IllegalStateException("生产匹配必须启用全球 SQL 权威与 TiDB 强读");return new Object();}
    @Bean @ConditionalOnProperty(name="game.match.global.enabled",havingValue="true")
    GlobalMatchStore globalMatchStore(DataSource source,GlobalMatchProperties config,MatchProperties properties)throws java.sql.SQLException{return new GlobalMatchStore(source,config,properties);}
    @Bean(initMethod="start",destroyMethod="close") @ConditionalOnProperty(name="game.match.global.enabled",havingValue="true")
    RatingRankOutbox ratingRankOutbox(DataSource source,GlobalRankProperties config,GlobalMatchStore schema){return new RatingRankOutbox(source,config);}
    @Bean(initMethod="start",destroyMethod="close") @ConditionalOnProperty(name="game.match.global.enabled",havingValue="true")
    GlobalMatchService globalMatchService(GlobalMatchStore store,GlobalMatchProperties config,MatchProperties props,NodeDirectory<BattleNodeInfo> directory,
            MatchIds ids,RatingReader ratings,ScenePreparer scenes,NodeCalls<BattleNodeService> calls,PlayerStatusReader players,PlacementStore projections,GatherHooks hooks,
            com.game.match.spectate.SqlSpectateStore spectators){
        return new GlobalMatchService(store,config,props,directory,ids,ratings,scenes,calls,players,projections,hooks).withSpectators(spectators);}
}
