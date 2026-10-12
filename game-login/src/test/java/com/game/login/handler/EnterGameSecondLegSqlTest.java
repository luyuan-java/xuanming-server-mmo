package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.game.api.SceneDirectoryService;
import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.SessionContext;
import com.game.discovery.proto.PlayerLocation;
import com.game.discovery.zone.ZoneMergeFence;
import com.game.login.dispatch.HandlerReply;
import com.game.login.metrics.LoginMetrics;
import com.game.login.ownership.OwnerTakeovers;
import com.game.player.store.OwnerState;
import com.game.player.store.PlayerMapper;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.ClaimResult;
import com.game.player.store.PlayerStore.HandOffMode;
import com.game.player.store.PlayerStore.HandOffResult;
import com.game.player.store.state.PlayerState;
import com.game.proto.login.EnterGameRequest;
import com.game.proto.login.EnterGameResponse;
import com.game.table.LoginErrorTip;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 跨 zone 传送的第二条腿对真实 SQL 的夺权（zone-travel-spec §5.8 第 5 步、§11.7「库里 released = 1 → 一次成功、不发让出请求」）：
 * 源 zone 的节点用 {@link HandOffMode#RELEASE} 交出（E→E+1 且已释放），目标 zone 的 EnterGame 持票、按待落点的地图落，
 * 夺权第一次就拿到 E+2。对照组用 5.2 的 {@link HandOffMode#HOLD}：E+1 有人持有，同一时刻的夺权撞 Held、要请持有者让出。
 *
 * <p>H2 的 MySQL 兼容模式 + 生产建表脚本 + 生产 Mapper + Spring 事务（与 game-player-store 的 {@code PlayerStoreSqlTest} 同一套装配要素），
 * 不连外部库；场景分配、位置记录、让出请求用替身。
 */
class EnterGameSecondLegSqlTest {

    private static final String ACCOUNT = "robot_0001";
    /** 归属区 A（建角时的会话 zone）。 */
    private static final int ZONE_A = 1;
    /** 目标 zone B（第二条腿的入口 zone）。 */
    private static final int ZONE_B = 2;
    /** 冻结快照里的地图，也是待落点记录的地图。 */
    private static final int TRAVEL_CONF = 5;
    private static final long MARGIN_MS = 15_000;
    private static final Duration TX_TIMEOUT = Duration.ofSeconds(5);
    private static final AtomicLong CLOCK = new AtomicLong();

    private static final AssignSceneResponse ASSIGNED = AssignSceneResponse.newBuilder()
            .setSceneNodeId(4).setSceneId(99_000_001L).setSceneConfigId(TRAVEL_CONF).build();

    private AnnotationConfigApplicationContext context;
    private PlayerStore store;
    private final SceneDirectoryService scenes = mock(SceneDirectoryService.class);
    private final OwnerTakeovers takeovers = mock(OwnerTakeovers.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        CLOCK.set(1_000_000L);
        context = new AnnotationConfigApplicationContext(Config.class);
        store = context.getBean(PlayerStore.class);
        store.ensureAccount(ACCOUNT);
        when(scenes.assign(any())).thenReturn(CompletableFuture.completedFuture(ASSIGNED));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private long newPlayer(long playerId, String name) {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(playerId);
        row.setAccount(ACCOUNT);
        row.setZoneId(ZONE_A);
        row.setClassId(1);
        row.setGender(1);
        assertThat(store.createPlayerWithinCap(row, 5, List.of(name)).status()).isEqualTo(PlayerStore.CreateStatus.CREATED);
        return playerId;
    }

    /** 源 zone 的节点持有 E = 1，冻结后按给定模式交出：提交后是 E+1 = 2。 */
    private void travelOut(long playerId, HandOffMode mode) {
        assertThat(store.claimOwnership(playerId)).isEqualTo(new ClaimResult.Claimed(1));
        PlayerRow frozen = new PlayerRow();
        frozen.setPlayerId(playerId);
        frozen.setOwnerEpoch(1);
        frozen.setLevel(3);
        frozen.setSceneConfigId(TRAVEL_CONF);
        frozen.setPosX(12.5);
        long now = CLOCK.get();
        HandOffResult result = store.handOffOwnership(frozen, PlayerState.getDefaultInstance(),
                now + PlayerStore.OWNER_LEASE.toMillis(), now + MARGIN_MS, TX_TIMEOUT, mode);
        assertThat(result).isEqualTo(new HandOffResult.HandedOff(2));
    }

    /** 目标 zone B 的入口：连接持着签给这名玩家、目标 zone = B 的重定向票据；位置记录是源节点写的待落点。 */
    private HandlerReply secondLeg(long playerId, LoginMetrics metrics, Duration claimWait) {
        EnterGameHandler handler = new EnterGameHandler(store, scenes, takeovers, Runnable::run, 1,
                Duration.ofSeconds(5), claimWait, metrics, delay -> Runnable::run, System::nanoTime, session -> null,
                id -> Optional.of(PlayerLocation.newBuilder().setPlayerId(id).setZoneId(ZONE_B).setSceneNodeId(0)
                        .setSceneId(0).setSceneConfigId(TRAVEL_CONF).setOwnerEpoch(1).build()),
                ZoneMergeFence.OPEN);
        SessionContext session = SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-z2")
                .setSessionId((1 << 17) | 3).setZoneId(ZONE_B).setAccount(ACCOUNT)
                .setTicketPlayerId(playerId).setTicketTargetZoneId(ZONE_B).build();
        return handler.handle(session, EnterGameRequest.newBuilder().setPlayerId(playerId).build())
                .orTimeout(10, TimeUnit.SECONDS).join();
    }

    @Test
    void 源节点交出并释放之后_第二条腿夺权一次成功拿到E加2_不发让出请求_落待落点的地图() throws Exception {
        long p = newPlayer(5401, "旅人甲");
        travelOut(p, HandOffMode.RELEASE);
        LoginMetrics metrics = new LoginMetrics(meters);

        // 时钟没走：E+1 的租约还有整整 30 s，夺权能成只因为已释放。
        HandlerReply reply = secondLeg(p, metrics, Duration.ofSeconds(3));

        EnterGameResponse response = EnterGameResponse.parseFrom(reply.body().orElseThrow().toByteString());
        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(reply.directives()).hasSize(1);
        assertThat(reply.directives().get(0).getEnterScene().getOwnerEpoch()).isEqualTo(3);
        verifyNoInteractions(takeovers);
        assertThat(meters.get("game.login.owner.claims").tag("outcome", "claimed").timer().count()).isEqualTo(1);
        assertThat(meters.get("game.login.owner.takeover.requests").counter().count()).isZero();
        assertThat(meters.get("game.login.enter.routes").tag("route", "awaiting").counter().count()).isEqualTo(1);

        ArgumentCaptor<AssignSceneRequest> request = ArgumentCaptor.forClass(AssignSceneRequest.class);
        verify(scenes).assign(request.capture());
        assertThat(request.getValue()).isEqualTo(AssignSceneRequest.newBuilder().setZoneId(ZONE_B).setPlayerId(p)
                .setPreferredSceneConfigId(TRAVEL_CONF).build());
        verify(scenes, never()).redirectToZone(any());

        assertThat(store.probeOwnership(p, TX_TIMEOUT)).get().extracting(OwnerState::ownerEpoch, OwnerState::released)
                .containsExactly(3L, false);
        PlayerRow loaded = store.findPlayer(p).orElseThrow();
        assertThat(loaded.getZoneId()).as("传送不改归属区").isEqualTo(ZONE_A);
        assertThat(loaded.getSceneConfigId()).as("新持有者加载到的是冻结快照").isEqualTo(TRAVEL_CONF);
        assertThat(loaded.getPosX()).isEqualTo(12.5);
    }

    @Test
    void 对照_5点2的HOLD交出_E加1有人持有_同一时刻的夺权撞Held并请持有者让出() throws Exception {
        long p = newPlayer(5402, "旅人乙");
        travelOut(p, HandOffMode.HOLD);
        LoginMetrics metrics = new LoginMetrics(meters);

        HandlerReply reply = secondLeg(p, metrics, Duration.ZERO);

        EnterGameResponse response = EnterGameResponse.parseFrom(reply.body().orElseThrow().toByteString());
        assertThat(response.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kLoginInProgress_VALUE);
        verify(takeovers).request(p, 2);
        assertThat(store.probeOwnership(p, TX_TIMEOUT)).get().extracting(OwnerState::ownerEpoch, OwnerState::released)
                .as("没夺到：仍是交出铸出的 E+1、有人持有").containsExactly(2L, false);
        assertThat(meters.get("game.login.owner.claims").tag("outcome", "timeout").timer().count()).isEqualTo(1);
    }

    /** 与生产相同的装配要素：建表脚本、Mapper、列名下划线转驼峰、Spring 事务（@Transactional 经代理生效）。 */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {

        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:game_login_" + UUID.randomUUID().toString().replace("-", "")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            new ResourceDatabasePopulator(new ClassPathResource("db/game-player-schema.sql")).execute(h2);
            return h2;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        @Bean
        MapperFactoryBean<PlayerMapper> playerMapper(SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<PlayerMapper> bean = new MapperFactoryBean<>(PlayerMapper.class);
            bean.setSqlSessionFactory(sqlSessionFactory);
            return bean;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        PlayerStore playerStore(PlayerMapper playerMapper, PlatformTransactionManager transactionManager) {
            return new PlayerStore(playerMapper, CLOCK::get, transactionManager);
        }
    }
}
