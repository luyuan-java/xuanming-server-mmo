package com.game.gateway;

import com.game.gateway.zone.Zone;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gateway 配置（{@code xm.gateway.*}）。
 *
 * <p>gate 令牌密钥不在这里：它只从环境变量 {@code XM_GATE_TOKEN_SECRET} 读（见 {@link GatewayConfiguration}），
 * 不给任何可以写进仓库的配置入口。
 *
 * @param zones 区服列表，顺序即区服列表展示顺序；校验见 {@link com.game.gateway.zone.ZoneCatalog}
 */
@ConfigurationProperties("xm.gateway")
public record GatewayProperties(List<Zone> zones) {
}
