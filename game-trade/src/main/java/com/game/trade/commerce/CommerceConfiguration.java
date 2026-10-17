package com.game.trade.commerce;

import com.game.api.asset.SceneAssetOpClients;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.*;
import com.game.discovery.asset.ReliableAssetDelivery;
import com.game.discovery.location.*;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import com.game.player.store.asset.PersistedAssetLedgerReader;
import com.game.trade.TradeConfiguration.SchemaReady;
import com.game.trade.TradeProperties;
import com.game.trade.id.ListingIds;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** 一个全服SQL市场；独立客户端、计时器及恢复线程都由容器按依赖顺序排空。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "game.trade.commerce.enabled", havingValue = "true")
public class CommerceConfiguration {
    @Bean
    public static StrongTiDbConnectionPolicy tradeStrongSqlPolicy() {
        return new StrongTiDbConnectionPolicy("trade", true);
    }
    @Bean
    public CommerceStore commerceStore(DataSource source, SchemaReady ready, TradeProperties p)
        throws java.sql.SQLException {
        if (!"global".equals(p.market().scope()))
            throw new IllegalStateException("交易写面只支持全服市场");
        try (var c = source.getConnection()) {
            StrongTiDbConnectionPolicy.verify(c, true);
        }
        return new CommerceStore(source);
    }
    @Bean
    public CommerceEngine commerceEngine(CommerceStore store, ListingIds ids, CommerceProperties p) {
        return new CommerceEngine(store, ids::nextId, p.policy(), System::currentTimeMillis);
    }
    @Bean(destroyMethod = "close")
    public SceneAssetOpClients tradeAssetClients() {
        return new SceneAssetOpClients("game-trade-assets");
    }
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService tradeAssetTimer() {
        return Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("trade-asset-timer").factory());
    }
    @Bean
    public ReliableAssetDelivery tradeAssetDelivery(RedissonClient redis, RedisKeys keys, SceneAssetOpClients clients,
        @Qualifier("tradeAssetTimer") ScheduledExecutorService timer) {
        var scenes = new NodeDirectory<>(redis, keys, NodeTypes.SCENE, SceneNodeInfo.parser());
        var locator = new SceneAssetLocator(new PlayerLocationDirectory(redis, keys), scenes, (failure) -> {});
        String secret = System.getenv("GAME_ASSET_OP_SECRET_TRADE");
        if (secret == null || secret.isBlank())
            throw new IllegalStateException("GAME_ASSET_OP_SECRET_TRADE 未配置");
        return new ReliableAssetDelivery(locator, clients, "trade", secret, timer, System::currentTimeMillis);
    }
    @Bean
    public PersistedAssetLedgerReader tradeAssetLedger(DataSource source) {
        return new PersistedAssetLedgerReader(source);
    }
    @Bean
    @ConditionalOnProperty(name = "game.trade.mall.enabled", havingValue = "true")
    public OfficialMallClient officialMallClient(
        OfficialMallProperties p, CommerceEngine e, com.fasterxml.jackson.databind.ObjectMapper json) {
        return new OfficialMallClient(p, e, json);
    }
    @Bean(initMethod = "start", destroyMethod = "close")
    public CommerceRuntime commerceRuntime(CommerceEngine e, ReliableAssetDelivery delivery,
        PersistedAssetLedgerReader ledger, org.springframework.beans.factory.ObjectProvider<OfficialMallClient> mall) {
        return new CommerceRuntime(e, delivery, ledger, mall.getIfAvailable());
    }
}
