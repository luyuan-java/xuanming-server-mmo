package com.game.gateway.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** 用生产建表脚本与 Mapper 在内存 H2（MySQL 兼容模式）上跑真实 SQL：upsert / 播种 / 改 / 状态 / 删、白名单、公告的生效窗口。 */
class GatewayStoreSqlTest {

    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private GatewayStore store;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:gw" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        DataSource ds = h2;
        new ResourceDatabasePopulator(new ClassPathResource("db/xm-gateway-schema.sql")).execute(ds);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        SqlSessionFactory sessions = factory.getObject();
        sessions.getConfiguration().addMapper(GatewayStoreMapper.class);
        GatewayStoreMapper mapper = new SqlSessionTemplate(sessions).getMapper(GatewayStoreMapper.class);
        store = new GatewayStore(mapper, new TransactionTemplate(new DataSourceTransactionManager(ds)), clock::get);
    }

    private static ZoneRow zone(int id, String name, ZoneManualStatus status, int sortOrder) {
        return new ZoneRow(id, name, status.code(), 5000, "", null, false, sortOrder, 0, 0);
    }

    @Test
    void 区服_upsert覆盖业务列保留创建时刻_列表按sort_order() {
        ZoneRow created = store.upsertZone(zone(2, "二区", ZoneManualStatus.OPEN, 5));
        assertThat(created.createdAt()).isEqualTo(clock.get());
        store.upsertZone(zone(1, "一区", ZoneManualStatus.PREVIEW, 9));
        clock.addAndGet(1000);
        ZoneRow overwritten = store.upsertZone(new ZoneRow(2, "二区·新", ZoneManualStatus.MAINTENANCE.code(), 100,
                "维护中", 1_900_000_000L, true, 1, 0, 0));
        assertThat(overwritten.name()).isEqualTo("二区·新");
        assertThat(overwritten.status()).isEqualTo(ZoneManualStatus.MAINTENANCE);
        assertThat(overwritten.openTime()).isEqualTo(1_900_000_000L);
        assertThat(overwritten.recommended()).isTrue();
        assertThat(overwritten.createdAt()).as("保留").isEqualTo(created.createdAt());
        assertThat(overwritten.updatedAt()).isEqualTo(clock.get());
        assertThat(store.zones()).extracting(ZoneRow::zoneId).containsExactly(2, 1);
    }

    @Test
    void 播种只插不存在的() {
        assertThat(store.seedZone(zone(1, "一区", ZoneManualStatus.OPEN, 1))).isTrue();
        store.setZoneStatus(1, ZoneManualStatus.MAINTENANCE, "停服");
        assertThat(store.seedZone(zone(1, "一区", ZoneManualStatus.OPEN, 1))).isFalse();
        assertThat(store.zone(1).orElseThrow().status()).as("运维改过的不被播种盖掉").isEqualTo(ZoneManualStatus.MAINTENANCE);
    }

    @Test
    void 改_不存在为空且不插入_状态只改状态与给了的文案() {
        assertThat(store.updateZone(7, zone(0, "七区", ZoneManualStatus.OPEN, 0))).isEmpty();
        assertThat(store.zone(7)).isEmpty();
        store.upsertZone(new ZoneRow(3, "三区", 0, 5000, "旧文案", null, true, 2, 0, 0));
        assertThat(store.updateZone(3, zone(999, "三区·改", ZoneManualStatus.CLOSED, 4)).orElseThrow())
                .satisfies(z -> {
                    assertThat(z.zoneId()).as("以路径为准").isEqualTo(3);
                    assertThat(z.status()).isEqualTo(ZoneManualStatus.CLOSED);
                    assertThat(z.sortOrder()).isEqualTo(4);
                });
        store.setZoneStatus(3, ZoneManualStatus.MAINTENANCE, "新文案");
        assertThat(store.setZoneStatus(3, ZoneManualStatus.MAINTENANCE, null).orElseThrow().maintenanceMsg())
                .as("没给文案保留原文案").isEqualTo("新文案");
        assertThat(store.setZoneStatus(3, ZoneManualStatus.OPEN, "").orElseThrow().maintenanceMsg()).isEmpty();
        assertThat(store.setZoneStatus(8, ZoneManualStatus.OPEN, "")).isEmpty();
        assertThat(store.deleteZone(3)).isTrue();
        assertThat(store.deleteZone(3)).isFalse();
    }

    @Test
    void 非法输入在写库前拒绝() {
        assertThatThrownBy(() -> store.upsertZone(zone(0, "零区", ZoneManualStatus.OPEN, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.upsertZone(zone(1, " ", ZoneManualStatus.OPEN, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.upsertZone(zone(1, "名".repeat(65), ZoneManualStatus.OPEN, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.upsertZone(new ZoneRow(1, "一区", 9, 5000, "", null, false, 0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.setZoneStatus(1, ZoneManualStatus.OPEN, "x".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.zones()).isEmpty();
    }

    @Test
    void 读到不认识的状态值按关闭() {
        assertThat(new ZoneRow(1, "一区", 9, 0, "", null, false, 0, 0, 0).status()).isEqualTo(ZoneManualStatus.CLOSED);
    }

    @Test
    void 白名单_幂等加入备注以最后一次为准_移出不存在也成功() {
        store.addWhitelist(1, "robot_0001", "内测");
        assertThat(store.addWhitelist(1, "robot_0001", "内测二期").note()).isEqualTo("内测二期");
        store.addWhitelist(1, "robot_0002", null);
        store.addWhitelist(2, "robot_0001", "");
        assertThat(store.whitelist(1)).extracting(WhitelistRow::account).containsExactly("robot_0001", "robot_0002");
        store.removeWhitelist(1, "robot_0002");
        store.removeWhitelist(1, "nobody");
        assertThat(store.whitelist(1)).hasSize(1);
        assertThatThrownBy(() -> store.addWhitelist(1, "", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 公告_只返回生效中的_新的在前_请求里的id不覆盖已有() {
        AnnouncementRow always = store.createAnnouncement("常驻", "内容", null, null, null);
        assertThat(always.type()).isEqualTo("notice");
        clock.addAndGet(1);
        store.createAnnouncement("未开始", null, "update", 2_000_000_000L, null);
        clock.addAndGet(1);
        store.createAnnouncement("已过期", null, "maintenance", null, 1_000L);
        clock.addAndGet(1);
        AnnouncementRow window = store.createAnnouncement("窗口内", "c", "maintenance", 1_000L, 1_900_000_000L);

        assertThat(store.activeAnnouncements(1_800_000_000L)).extracting(AnnouncementRow::title)
                .containsExactly("窗口内", "常驻");
        assertThat(store.announcements()).hasSize(4);
        assertThat(window.id()).isNotEqualTo(always.id());
        store.deleteAnnouncement(always.id());
        store.deleteAnnouncement(always.id());
        assertThat(store.announcements()).hasSize(3);
        assertThatThrownBy(() -> store.createAnnouncement("倒挂", null, null, 10L, 5L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
