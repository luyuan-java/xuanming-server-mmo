package com.game.player.store.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.player.store.asset.PersistedAssetLedgerReader.Read;
import com.game.player.store.state.AssetOpLedgerState;
import com.game.player.store.state.AssetOpRejectionState;
import com.game.player.store.state.AssetOpStreamLedgerState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 读已落盘账本（缺省 H2 的 MySQL 兼容模式 + 生产建表脚本；{@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306} 时连真 MySQL，
 * 每个用例一个临时库、用例结束删库，不碰 xm_java）。guild-economy-spec §11.3「离线读账本」：absent / 坏 blob / 无 asset_ledger /
 * 已应用 / 已拒绝 / 部分发放 / 旧纪元，外加 ≥ 2^63 的 player_id 与超时。
 */
class PersistedAssetLedgerReaderTest {

    private static final String MYSQL_URL = System.getProperty("xm.it.mysql");
    private static final long EPOCH = 1_700_000_000_000L;
    private static final int GUILD_DEBIT = 1;

    private String database;
    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        database = "xm_it_ledger_" + UUID.randomUUID().toString().replace("-", "");
        dataSource = newDataSource();
        new ResourceDatabasePopulator(new ClassPathResource("db/xm-player-schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void tearDown() {
        if (MYSQL_URL != null) {
            jdbc.execute("DROP DATABASE IF EXISTS " + database);
        }
    }

    private DataSource newDataSource() {
        if (MYSQL_URL != null) {
            return new DriverManagerDataSource(url(), user(), password());
        }
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL(url());
        return h2;
    }

    private String url() {
        if (MYSQL_URL != null) {
            return MYSQL_URL + "/" + database + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true";
        }
        return "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private static String user() {
        return System.getProperty("xm.it.mysql.user", "root");
    }

    private static String password() {
        return System.getenv().getOrDefault("XM_MYSQL_PASSWORD", "");
    }

    private void putState(long playerId, byte[] data) {
        jdbc.update("INSERT INTO player_state (player_id, data, saved_epoch, updated_at) VALUES (?, ?, 1, 0)",
                PersistedAssetLedgerReader.unsigned(playerId), data);
    }

    private static AssetOpStreamLedgerState.Builder stream() {
        // seq 1 部分发放、seq 2 拒绝（27000）、seq 3 应用
        AssetOpStreamLedgerState.Builder b = AssetOpStreamLedgerState.newBuilder().setStream(GUILD_DEBIT)
                .setStreamEpoch(EPOCH).setMaxSeq(3);
        for (int i = 0; i < 16; i++) {
            b.addSeenBits(i == 0 ? 0b111 : 0).addAppliedBits(i == 0 ? 0b101 : 0);
        }
        return b.addRejections(AssetOpRejectionState.newBuilder().setSeq(2).setReasonTipId(27000)).addPartialSeqs(1);
    }

    private PersistedAssetLedger loaded(long playerId) {
        Read read = new PersistedAssetLedgerReader(dataSource).read(playerId);
        assertThat(read).isInstanceOf(Read.Loaded.class);
        return ((Read.Loaded) read).ledger();
    }

    @Test
    void 没有行是Absent() {
        assertThat(new PersistedAssetLedgerReader(dataSource).read(42)).isEqualTo(new Read.Absent());
    }

    @Test
    void 有行但没有账本段是空账本_不是Absent() {
        putState(42, PlayerState.newBuilder().setCurrency(CurrencyState.getDefaultInstance()).build().toByteArray());
        PersistedAssetLedger ledger = loaded(42);
        assertThat(ledger.invalidReason()).isNull();
        assertThat(ledger.outcomeOf(GUILD_DEBIT, EPOCH, 1).state()).isEqualTo(AssetSeqState.UNSEEN);
        assertThat(ledger.outcomeOf(GUILD_DEBIT, EPOCH, 1).conclusive()).isFalse();
    }

    @Test
    void 已应用_已拒绝_部分发放_旧纪元() {
        putState(42, PlayerState.newBuilder().setAssetLedger(AssetOpLedgerState.newBuilder().addStreams(stream()))
                .build().toByteArray());
        PersistedAssetLedger ledger = loaded(42);
        assertThat(ledger.outcomeOf(GUILD_DEBIT, EPOCH, 3)).isEqualTo(new PersistedAssetLedger.Outcome(AssetSeqState.APPLIED, false, 0));
        assertThat(ledger.outcomeOf(GUILD_DEBIT, EPOCH, 1)).isEqualTo(new PersistedAssetLedger.Outcome(AssetSeqState.APPLIED, true, 0));
        assertThat(ledger.outcomeOf(GUILD_DEBIT, EPOCH, 2))
                .isEqualTo(new PersistedAssetLedger.Outcome(AssetSeqState.REJECTED, false, 27000));
        assertThat(ledger.outcomeOf(GUILD_DEBIT, EPOCH - 1, 3).state()).isEqualTo(AssetSeqState.STALE_EPOCH);
        assertThat(ledger.outcomeOf(GUILD_DEBIT, EPOCH, 4).state()).isEqualTo(AssetSeqState.UNSEEN);
    }

    @Test
    void 账本自相矛盾_读到但判损坏() {
        putState(42, PlayerState.newBuilder().setAssetLedger(AssetOpLedgerState.newBuilder()
                .addStreams(stream().setAppliedBits(3, 1))).build().toByteArray());
        PersistedAssetLedger ledger = loaded(42);
        assertThat(ledger.invalidReason()).contains("applied 不是 seen 的子集");
        assertThat(ledger.outcomeOf(GUILD_DEBIT, EPOCH, 3).conclusive()).isFalse();
    }

    @Test
    void 坏blob是读失败_不是空账本() {
        putState(42, new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff});
        Read read = new PersistedAssetLedgerReader(dataSource).read(42);
        assertThat(read).isInstanceOf(Read.Failed.class);
        assertThat(((Read.Failed) read).reason()).contains("解析失败");
    }

    @Test
    void player_id按无符号绑定() {
        // H2 的 MySQL 模式不支持 BIGINT UNSIGNED 的上半段，只在真 MySQL 上跑
        org.junit.jupiter.api.Assumptions.assumeTrue(MYSQL_URL != null, "需要 -Dxm.it.mysql");
        long big = Long.MIN_VALUE + 7;
        putState(big, PlayerState.newBuilder().setAssetLedger(AssetOpLedgerState.newBuilder().addStreams(stream()))
                .build().toByteArray());
        assertThat(loaded(big).outcomeOf(GUILD_DEBIT, EPOCH, 3).conclusive()).isTrue();
        assertThat(new PersistedAssetLedgerReader(dataSource).read(7)).as("低 63 位相同的另一个号").isEqualTo(new Read.Absent());
    }

    @Test
    void player_id为0与数据源故障都是读失败_从不抛异常() {
        assertThat(new PersistedAssetLedgerReader(dataSource).read(0)).isInstanceOf(Read.Failed.class);
        DataSource broken = new DriverManagerDataSource("jdbc:h2:mem:absent_" + UUID.randomUUID() + ";IFEXISTS=TRUE");
        Read read = new PersistedAssetLedgerReader(broken).read(42);
        assertThat(read).isInstanceOf(Read.Failed.class);
        assertThat(((Read.Failed) read).cause()).isInstanceOf(SQLException.class);
    }

    @Test
    void 连接池等不到连接_在预算内放弃() throws Exception {
        try (DruidDataSource pool = new DruidDataSource()) {
            pool.setUrl(url());
            if (MYSQL_URL != null) {
                pool.setUsername(user());
                pool.setPassword(password());
            }
            pool.setMaxActive(1);
            pool.setInitialSize(1);
            pool.setMaxWait(10_000);
            pool.init();
            PersistedAssetLedgerReader reader = new PersistedAssetLedgerReader(pool, Duration.ofMillis(200));
            assertThat(reader.read(42)).as("池有空闲连接时照常读").isEqualTo(new Read.Absent());
            try (Connection held = pool.getConnection()) {
                long started = System.nanoTime();
                Read read = reader.read(42);
                long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                assertThat(read).isInstanceOf(Read.Failed.class);
                assertThat(elapsedMs).as("按读账本的预算（200 ms）放弃，而不是池的 maxWait（10 s）").isLessThan(2_000);
                assertThat(held.isClosed()).isFalse();
            }
            assertThat(reader.read(42)).as("连接还回池后恢复").isEqualTo(new Read.Absent());
        }
    }
}
