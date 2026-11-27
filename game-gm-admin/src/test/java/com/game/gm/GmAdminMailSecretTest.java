package com.game.gm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 核验 GM 后台优先读取与 game-mail 对齐的 MAIL_PRODUCER_SECRET_ADMIN，兼容回退旧名。 */
class GmAdminMailSecretTest {

    @Test
    void 优先读标准环境变量MAIL_PRODUCER_SECRET_ADMIN并兼容旧变量() {
        assertThat(GmAdminConfiguration.resolveMailAdminSecret(Map.of(
                "MAIL_PRODUCER_SECRET_ADMIN", " canonical-secret ",
                "MMORPG_MAIL_ADMIN_SECRET", "legacy-secret")::get))
                .isEqualTo("canonical-secret");
        assertThat(GmAdminConfiguration.resolveMailAdminSecret(Map.of(
                "MMORPG_MAIL_ADMIN_SECRET", " legacy-only ")::get))
                .isEqualTo("legacy-only");
        assertThat(GmAdminConfiguration.resolveMailAdminSecret(k -> null)).isEmpty();
    }

    @Test
    void 延迟自动配置提供Redis时仍正确装配在线状态与场景位置目录() {
        var cfg = new GmAdminConfiguration();
        var emptyRedis = mockProvider(org.redisson.api.RedissonClient.class, null);
        var emptyKeys = mockProvider(com.game.discovery.RedisKeys.class, null);
        assertThat(cfg.gmPlayerPresenceDirectory(emptyRedis, emptyKeys)).isNull();
        assertThat(cfg.gmPlayerLocationDirectory(emptyRedis, emptyKeys)).isNull();

        var redis = org.mockito.Mockito.mock(org.redisson.api.RedissonClient.class);
        var keys = new com.game.discovery.RedisKeys(com.game.common.GameNamespace.of("game"));
        assertThat(cfg.gmPlayerPresenceDirectory(
                mockProvider(org.redisson.api.RedissonClient.class, redis),
                mockProvider(com.game.discovery.RedisKeys.class, keys))).isNotNull();
        assertThat(cfg.gmPlayerLocationDirectory(
                mockProvider(org.redisson.api.RedissonClient.class, redis),
                mockProvider(com.game.discovery.RedisKeys.class, keys))).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> mockProvider(Class<T> type, T value) {
        org.springframework.beans.factory.ObjectProvider<T> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
