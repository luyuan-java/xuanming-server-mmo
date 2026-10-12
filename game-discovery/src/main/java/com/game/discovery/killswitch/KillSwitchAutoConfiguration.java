package com.game.discovery.killswitch;

import com.game.common.killswitch.KillSwitch;
import com.game.discovery.RedisKeys;
import java.time.Duration;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 热关停（按方法止血阀）：只在 {@code game.killswitch.enabled=true} 的进程里装配（gate / login / scene-manager / friend / chat……
 * 这些接客的进程）。装上后本进程的 {@link KillSwitch} 也登记成全局实例，Dubbo 提供方过滤器经它判定。
 *
 * <p>{@code game.killswitch.sync-interval}（缺省 1 s）：多久全量读一次规则；{@code game.killswitch.stale-after}（缺省 60 s）：
 * 规则源失联多久后本地快照作废、整体放行。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "game.killswitch.enabled", havingValue = "true")
public class KillSwitchAutoConfiguration {

    @Bean
    public KillSwitch killSwitch(@Value("${game.killswitch.stale-after:60s}") Duration staleAfter) {
        KillSwitch killSwitch = new KillSwitch(staleAfter.toNanos(), System::nanoTime);
        KillSwitch.installGlobal(killSwitch);
        return killSwitch;
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public RedisKillSwitchSync redisKillSwitchSync(RedissonClient redis, RedisKeys keys, KillSwitch killSwitch,
                                                   @Value("${game.killswitch.sync-interval:1s}") Duration interval) {
        return new RedisKillSwitchSync(redis, keys, killSwitch, interval);
    }
}
