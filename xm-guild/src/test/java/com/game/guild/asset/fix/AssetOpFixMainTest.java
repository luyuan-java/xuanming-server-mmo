package com.game.guild.asset.fix;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.guild.asset.InMemoryAssetStore;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * 人工终结 CLI（照基线 assetopfix 的行为：子命令、前置条件、-txlog-checked 核对清单、审计行、退出码 0/1/2/3；guild-economy-spec §2.12、§7.10）。
 * Store 用内存替身，不连库。
 */
class AssetOpFixMainTest {

    static final long NOW = 1_700_000_000_000L;
    static final long OLD = NOW - 31 * 60_000L;
    static final AssetBundle GOLD = AssetBundle.newBuilder()
            .addCurrencies(AssetCurrency.newBuilder().setCurrencyType(0).setAmount(100)).build();

    private final InMemoryAssetStore store = new InMemoryAssetStore();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final AtomicBoolean opened = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private int run(String... args) {
        out.reset();
        err.reset();
        return AssetOpFixMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), path -> {
                    opened.set(true);
                    return new AssetOpFixMain.OpenedStore(store, () -> closed.set(true));
                }, () -> NOW);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private GuildAssetOpRow stuck(long opId, int attempts, int lastOutcome, long createdMs) {
        return store.insert(InMemoryAssetStore.pending(opId, 0x8000_0000_0000_0001L, 3, GOLD).setAttempts(attempts)
                .setLastOutcome(lastOutcome).setCreatedMs(createdMs).setFundsDelta(12_000).setContributionDelta(120).build());
    }

    private String[] resolve(long op, String as, String... extra) {
        String[] base = {"resolve", "-op", Long.toUnsignedString(op), "-as", as, "-operator", "ops-li", "-reason",
                "工单 OPS-1 \"流水无记录\"", "-confirm", Long.toUnsignedString(op)};
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    @Test
    void 参数错误先于连库_退出码2() {
        assertThat(run()).isEqualTo(2);
        assertThat(run("bogus")).isEqualTo(2);
        assertThat(run("list", "-limit", "0")).isEqualTo(2);
        assertThat(run("list", "-limit", "501")).isEqualTo(2);
        assertThat(run("list", "-min-age-min", "-1")).isEqualTo(2);
        assertThat(run("list", "extra")).isEqualTo(2);
        assertThat(run("resolve", "-as", "applied")).isEqualTo(2);
        assertThat(err()).contains("缺 -op");
        assertThat(run("resolve", "-op", "5", "-as", "rejected", "-operator", "a", "-reason", "r", "-confirm", "5")).isEqualTo(2);
        assertThat(err()).contains("-as 只能是 applied 或 aborted");
        assertThat(run("resolve", "-op", "5", "-as", "applied", "-operator", "a\nb", "-reason", "r", "-confirm", "5"))
                .isEqualTo(2);
        assertThat(err()).contains("控制字符");
        assertThat(run("resolve", "-op", "5", "-as", "applied", "-operator", "a", "-reason", " ", "-confirm", "5")).isEqualTo(2);
        assertThat(run("resolve", "-op", "5", "-as", "applied", "-operator", "a", "-reason", "r", "-confirm", "6")).isEqualTo(2);
        assertThat(err()).contains("二次确认失败");
        assertThat(opened).isFalse();
        assertThat(run("-h")).isZero();
    }

    @Test
    void list_只列创建早于门槛的PENDING行_标出可否人工终结() {
        stuck(1, 12, 0, OLD - 60 * 60_000L);
        stuck(2, 3, 0, OLD - 60 * 60_000L);
        stuck(3, 12, 3, OLD - 60 * 60_000L);
        stuck(4, 12, 0, NOW - 5 * 60_000L);
        assertThat(run("list")).isZero();
        String table = out();
        assertThat(table).contains("op_id").contains("resolvable");
        assertThat(table.lines().filter(l -> l.startsWith("1 ")).findFirst().orElseThrow()).endsWith("yes");
        assertThat(table.lines().filter(l -> l.startsWith("2 ")).findFirst().orElseThrow()).endsWith("force");
        assertThat(table.lines().filter(l -> l.startsWith("3 ")).findFirst().orElseThrow()).endsWith("force");
        assertThat(table).doesNotContain("\n4 ");
        assertThat(table).contains("共 3 行");
        assertThat(closed).isTrue();

        store.rows.clear();
        assertThat(run("list", "-min-age-min", "10")).isZero();
        assertThat(out()).contains("没有创建早于 10 分钟前的 PENDING 行");
    }

    @Test
    void resolve_前置不满足退出码2_不改数据() {
        stuck(1, 12, 0, NOW - 5 * 60_000L);
        assertThat(run(resolve(1, "applied", "-txlog-checked"))).isEqualTo(2);
        assertThat(err()).contains("不足 30 分钟");
        stuck(2, 3, 0, OLD);
        assertThat(run(resolve(2, "applied", "-txlog-checked"))).isEqualTo(2);
        assertThat(err()).contains("只处理反复 UNKNOWN");
        assertThat(run(resolve(99, "applied", "-txlog-checked"))).isEqualTo(2);
        assertThat(err()).contains("不存在");
        assertThat(store.row(1).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        assertThat(store.row(2).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
    }

    @Test
    void resolve_不带txlog_checked只打印核对清单() {
        stuck(1, 12, 0, OLD);
        assertThat(run(resolve(1, "aborted"))).isEqualTo(2);
        assertThat(err()).contains("未带 -txlog-checked").contains("0. 先确认这是 scene 真回了 UNKNOWN")
                .contains("本行是捐献：applied = 帮会资金 +12000、捐献者帮贡 +120");
        assertThat(store.row(1).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
    }

    @Test
    void resolve_成功_审计行无论成败都打_写审计列不置durable() {
        stuck(1, 12, 0, OLD);
        assertThat(run(resolve(1, "aborted", "-txlog-checked"))).isZero();
        assertThat(out()).contains("[AssetOpManual] operator=ops-li op_id=1 player_id=9223372036854775809 stream=1 seq=3 kind=1"
                + " as=aborted reason=\"工单 OPS-1 \\\"流水无记录\\\"\" finalized=true force=false");
        GuildAssetOpRow row = store.row(1);
        assertThat(row.getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED);
        assertThat(row.getResolvedBy()).isEqualTo("ops-li");
        assertThat(row.getDurable()).isZero();
        assertThat(row.getNextAttemptMs()).as("终态行 next_attempt_ms = 终结时刻").isEqualTo(NOW);
        assertThat(store.finalized).extracting(f -> f.origin()).containsExactly(com.game.guild.asset.DeliveryOrigin.MANUAL);
    }

    @Test
    void resolve_force只跳过反复UNKNOWN一条() {
        stuck(1, 2, 3, OLD);
        assertThat(run(resolve(1, "applied", "-txlog-checked", "-force"))).isZero();
        assertThat(out()).contains("force=true");
        assertThat(store.row(1).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED);
    }

    @Test
    void resolve_前置检查之后被自动终结_CAS落空退出码3() {
        stuck(1, 12, 0, OLD);
        InMemoryAssetStore racing = new InMemoryAssetStore() {
            @Override
            public FinalizeResult resolveManually(com.game.guild.asset.ManualResolution resolution, long nowMs,
                                                  com.game.common.deadline.Deadline deadline) {
                return FinalizeResult.NOT_FINALIZED;
            }
        };
        racing.insert(store.row(1));
        int code = AssetOpFixMain.run(resolve(1, "applied", "-txlog-checked"), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                path -> new AssetOpFixMain.OpenedStore(racing, () -> { }), () -> NOW);
        assertThat(code).isEqualTo(3);
        assertThat(out()).contains("finalized=false").contains("不要再手工补账");
    }

    @Test
    void resolve_已终结的行拒绝_操作人超长执行出错退出码1() {
        store.insert(InMemoryAssetStore.pending(1, 7, 3, GOLD).setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED)
                .setCreatedMs(OLD).build());
        assertThat(run(resolve(1, "applied", "-txlog-checked"))).isEqualTo(2);
        assertThat(err()).contains("不是 PENDING");

        stuck(2, 12, 0, OLD);
        String longName = "x".repeat(65);
        assertThat(run("resolve", "-op", "2", "-as", "applied", "-operator", longName, "-reason", "r", "-confirm", "2",
                "-txlog-checked")).isEqualTo(1);
        assertThat(out()).contains("[AssetOpManual]").contains("finalized=false");
        assertThat(store.row(2).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
    }

    @Test
    void 连库失败退出码1() {
        int code = AssetOpFixMain.run(new String[] {"list"}, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), path -> {
                    throw new IllegalStateException("连接缓存 Redis 失败，拒绝执行");
                }, () -> NOW);
        assertThat(code).isEqualTo(1);
        assertThat(err()).contains("拒绝执行");
    }

    @Test
    void 审计行里的理由按Go的引号规则转义_伪造不出第二行() {
        assertThat(AssetOpFixMain.quote("a\"b\\c\nd\u0001")).isEqualTo("\"a\\\"b\\\\c\\nd\\x01\"");
        assertThat(AssetOpFixMain.hasControl("ok 名字")).isFalse();
        assertThat(AssetOpFixMain.hasControl("a\tb")).isTrue();
        assertThat(GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE_VALUE).isEqualTo(1);
    }

    @Test
    void 配置读取_缺省读jar里的application_yaml_环境变量占位符照常解析() throws Exception {
        var env = AssetOpFixEnvironment.load(null);
        assertThat(env.getProperty("spring.datasource.url")).contains("useAffectedRows=true")
                .contains("innodb_lock_wait_timeout=1");
        assertThat(env.getProperty("xm.redis.database", Integer.class)).isEqualTo(12);
        assertThat(env.getProperty("spring.datasource.username")).isNotBlank();
    }
}
