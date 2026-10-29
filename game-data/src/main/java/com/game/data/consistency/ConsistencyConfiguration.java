package com.game.data.consistency;

import com.game.common.GameNamespace;
import com.game.common.RunMode;
import com.game.discovery.RedisKeys;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 全部是现有game-data进程内组件；巡检不执行领域DDL或创建权威原件。 */
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(ConsistencyProperties.class)
public class ConsistencyConfiguration {
    @Bean(destroyMethod="close")
    JdbcConsistencyReader consistencySqlReader(ConsistencyProperties properties,GameNamespace namespace,
            @Value("${game.run-mode:prod}")String mode) {
        if(!properties.configured())return null;
        return new JdbcConsistencyReader(properties.getJdbcUrl(),properties.getUsername(),properties.getPassword(),
                namespace,RunMode.parse(mode)==RunMode.PROD);
    }
    @Bean
    ConsistencyService consistencyService(ObjectProvider<JdbcConsistencyReader> sql,
            ConfigurableListableBeanFactory beans,GameNamespace namespace,RedisKeys keys) {
        // 不促使现有懒Redis bean初始化（初始化可重试且不属于一次读的预算）。
        // 只使用业务已有的成功单例；未就绪时本项UNKNOWN，下次请求重新探测。
        var reader=new ConsistencyRedisReader() {
            private RedissonConsistencyReader ready() {
                for(String name:beans.getBeanNamesForType(RedissonClient.class,false,false)) {
                    if(beans.getSingleton(name) instanceof RedissonClient client)return new RedissonConsistencyReader(client,keys);
                }
                throw new ConsistencyBudget.Failure("redis_not_ready");
            }
            public boolean completeTopology(){return ready().completeTopology();}
            public Page scanRankKeys(String cursor,int count,ConsistencyBudget budget){budget.check();return ready().scanRankKeys(cursor,count,budget);}
            public Page scanRankZones(String cursor,int count,ConsistencyBudget budget){budget.check();return ready().scanRankZones(cursor,count,budget);}
            public String type(String key,ConsistencyBudget budget){budget.check();return ready().type(key,budget);}
        };
        return new ConsistencyService(sql.getIfAvailable(),reader,namespace,keys);
    }
}
