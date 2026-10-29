package com.game.gateway.store;

import static org.assertj.core.api.Assertions.*;

import com.game.common.sql.testing.AuthoritySqlFixture;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** 正式三表DDL与真实owned TiDB；无H2替身、无跳过分支，资源与NORMAL权限沿用唯一夹具。 */
class GatewayStoreUnsignedZoneTiDbTest {
    @Test
    void 正式TiDB_UINT32三边界_目录白名单全CRUD与公告实际MyBatis往返() throws Exception {
        String root = System.getProperty("game.it.authority.jdbc", System.getProperty("game.it.mysql"));
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("缺少真实fixture配置：game.it.authority.jdbc");
        }
        List<Integer> ids = List.of(Integer.MAX_VALUE, Integer.MIN_VALUE, -1);
        var options = AuthoritySqlFixture.Options.fromEnvironment(root, System.getProperty("game.it.mysql.user", "root"),
                System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", ""), "jgw_u32", false, ids);
        try (var lease = AuthoritySqlFixture.beginUnpublishedWithPlayerBaseSchema(options)) {
            lease.withSchema((schema, principal, database) -> {
                new ResourceDatabasePopulator(new ClassPathResource("db/game-gateway-schema.sql")).populate(schema);
                return null;
            });
            try (var fixture = lease.publish()) {
                var source = fixture.normalDataSource("?connectTimeout=3000&socketTimeout=3000");
                var factory = new SqlSessionFactoryBean();
                factory.setDataSource(source);
                var sessions = factory.getObject();
                sessions.getConfiguration().setDefaultStatementTimeout(3);
                sessions.getConfiguration().addMapper(GatewayStoreMapper.class);
                var mapper = new SqlSessionTemplate(sessions).getMapper(GatewayStoreMapper.class);
                var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
                tx.setTimeout(3);
                AtomicLong clock = new AtomicLong(1_800_000_000_000L);
                var store = new GatewayStore(mapper, tx, clock::get, source);
                var jdbc = new JdbcTemplate(source);
                jdbc.setQueryTimeout(3);
                for (int bits : ids) {
                    assertThat(store.seedZone(row(bits, "初始", -7))).isTrue();
                    assertThat(store.seedZone(row(bits, "不覆盖", -8))).isFalse();
                    ZoneRow loaded = store.zone(bits).orElseThrow();
                    assertThat(loaded.zoneId()).isEqualTo(bits);
                    assertThat(loaded.sortOrder()).isEqualTo(-7);
                    assertThat(loaded.openTime()).isNull();
                    assertThat(loaded.createdAt()).isEqualTo(clock.get());
                    assertThat(jdbc.queryForObject("SELECT zone_id FROM zone_config WHERE zone_id=?", Long.class,
                            Integer.toUnsignedLong(bits))).isEqualTo(Integer.toUnsignedLong(bits));
                }
                assertThat(store.zones().stream().map(z -> Integer.toUnsignedLong(z.zoneId())).toList())
                        .containsExactly(2147483647L, 2147483648L, 4294967295L);
                for (int bits : ids) {
                    long created = store.zone(bits).orElseThrow().createdAt();
                    clock.incrementAndGet();
                    ZoneRow overwritten = store.upsertZone(row(bits, "覆盖", -6));
                    assertThat(overwritten.name()).isEqualTo("覆盖");
                    assertThat(overwritten.createdAt()).isEqualTo(created);
                    assertThat(overwritten.updatedAt()).isEqualTo(clock.get());
                    assertThat(store.updateZone(bits, row(1, "路径为准", -5)).orElseThrow().zoneId()).isEqualTo(bits);
                    assertThat(store.setZoneStatus(bits, ZoneManualStatus.MAINTENANCE, "维护").orElseThrow().status())
                            .isEqualTo(ZoneManualStatus.MAINTENANCE);
                    assertThat(store.addWhitelist(bits, "robot_u32", "初次").zoneId()).isEqualTo(bits);
                    assertThat(store.addWhitelist(bits, "robot_u32", "覆盖").note()).isEqualTo("覆盖");
                    assertThat(store.whitelist(bits)).containsExactly(new WhitelistRow(bits, "robot_u32", "覆盖"));
                    assertThat(jdbc.queryForObject("SELECT zone_id FROM zone_whitelist WHERE zone_id=?", Long.class,
                            Integer.toUnsignedLong(bits))).isEqualTo(Integer.toUnsignedLong(bits));
                    store.removeWhitelist(bits, "robot_u32");
                    store.removeWhitelist(bits, "robot_u32");
                    assertThat(store.whitelist(bits)).isEmpty();
                    assertThat(store.deleteZone(bits)).isTrue();
                    assertThat(store.deleteZone(bits)).isFalse();
                    assertThat(store.zone(bits)).isEmpty();
                }
                var announcement = store.createAnnouncement("标题", "正文", "notice", null, null);
                assertThat(store.announcements()).contains(announcement);
                assertThat(store.activeAnnouncements(clock.get() / 1000)).contains(announcement);
                store.deleteAnnouncement(announcement.id());
                assertThat(store.announcements()).isEmpty();
            }
        }
    }

    private static ZoneRow row(int bits, String name, int order) {
        return new ZoneRow(bits, name, ZoneManualStatus.OPEN.code(), 5000, "", null, false, order, 0, 0);
    }
}
