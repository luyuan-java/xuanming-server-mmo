package com.game.gateway;

import com.game.gateway.queue.QueueSettings;
import com.game.gateway.ratelimit.RateLimitSettings;
import com.game.gateway.zone.SeedZone;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gateway 配置（{@code xm.gateway.*}）。
 *
 * <p>gate 令牌密钥不在这里：它只从环境变量 {@code XM_GATE_TOKEN_SECRET} 读（见 {@link GatewayConfiguration}），
 * 不给任何可以写进仓库的配置入口。
 *
 * @param seedZones 启动时播种的区服（库里没有才插入）；区服目录本身在 MySQL {@code zone_config}，运维经 xm-data 改
 * @param queue     登录排队（缺省关闭）
 * @param rateLimit 开服限流（缺省关闭）
 */
@ConfigurationProperties("xm.gateway")
public record GatewayProperties(List<SeedZone> seedZones, QueueSettings queue, RateLimitSettings rateLimit) {

    public GatewayProperties {
        seedZones = seedZones == null ? List.of() : List.copyOf(seedZones);
        queue = queue == null ? QueueSettings.disabled() : queue;
        rateLimit = rateLimit == null ? RateLimitSettings.disabled() : rateLimit;
    }
}
