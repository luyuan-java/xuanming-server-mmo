package com.game.match.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.match.MatchBudgets;
import com.game.discovery.RedisProperties;
import com.game.match.lifecycle.MatchStartupChecks.Hops;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 启动门禁里对照配置表与预算的几项（match-spec §9.8 第 3、4 步，§9.6 末「启动断言」）：PVE 组队人数表的副本 id 必须在 Dungeon 表里；
 * Redis 单条命令最坏耗时 ≤ 6100 ms、各跳超时 ≤ 3 / 5 / 3 / 3 s；matched TTL 表仍是基线的 42 / 48 / 54 / 60 / 66 / 96。
 */
class MatchStartupChecksTest {

    private static final Set<Integer> DUNGEONS = Set.of(1, 2, 3);
    private static final Hops CURRENT = Hops.current();

    // ================================================================ PVE 组队人数表

    @Test
    void 人数表的副本id都在Dungeon表里_通过() {
        assertThat(MatchStartupChecks.teamSizeViolations(Map.of(1, 5, 3, 10), DUNGEONS::contains)).isEmpty();
        assertThat(MatchStartupChecks.teamSizeViolations(Map.of(), DUNGEONS::contains)).as("一个都不开放也合法").isEmpty();
    }

    @Test
    void 人数表里有Dungeon表没有的id_逐个列出_按id升序() {
        Map<Integer, Integer> sizes = new TreeMap<>(Map.of(1, 5, 999, 5, 42, 3));

        List<String> violations = MatchStartupChecks.teamSizeViolations(sizes, DUNGEONS::contains);

        assertThat(violations).hasSize(2);
        assertThat(violations.get(0)).contains("副本 id 42 ").contains("不在 Dungeon 表里");
        assertThat(violations.get(1)).contains("副本 id 999 ");
    }

    // ================================================================ 预算断言

    @Test
    void 代码里现行的各跳超时与缺省的Redis配置_通过() {
        long defaultWorst = new RedisProperties(null, null, null, null, null, null, null).worstCaseCommandMillis();

        assertThat(defaultWorst).as("缺省：2 × 2000 + 200").isEqualTo(4200);
        assertThat(MatchStartupChecks.budgetViolations(defaultWorst, CURRENT)).isEmpty();
        assertThat(CURRENT).as("各跳超时：3 / 3 / 5 / 3 / 3 / 3 s").isEqualTo(new Hops(3000, 3000, 5000, 3000, 3000, 3000));
    }

    @Test
    void Redis单条命令最坏耗时_恰好6100通过_多1毫秒拒绝() {
        assertThat(MatchStartupChecks.budgetViolations(MatchBudgets.PLACEMENT_WRITE_WORST_MS, CURRENT)).isEmpty();

        List<String> violations = MatchStartupChecks.budgetViolations(6101, CURRENT);

        assertThat(violations).singleElement().asString().contains("6101 ms").contains("6100 ms").contains("xm.redis.timeout-ms");
    }

    @Test
    void 常见的误配_把Redis响应超时调到3秒_最坏6200毫秒_拒绝() {
        long worst = new RedisProperties(null, null, null, null, 3000, null, null).worstCaseCommandMillis();

        assertThat(worst).isEqualTo(6200);
        assertThat(MatchStartupChecks.budgetViolations(worst, CURRENT)).hasSize(1);
        assertThat(MatchStartupChecks.budgetViolations(new RedisProperties(null, null, null, null, 3000, 0, null).worstCaseCommandMillis(), CURRENT))
                .as("同样的超时、不重试：最坏 3000 ms").isEmpty();
    }

    @Test
    void 任何一跳超过上限_各自一条违规() {
        assertThat(MatchStartupChecks.budgetViolations(4200, new Hops(3001, 3000, 5000, 3000, 3000, 3000)))
                .singleElement().asString().contains("备战 prepareBattle").contains("3001 ms").contains("(0, 3000]");
        assertThat(MatchStartupChecks.budgetViolations(4200, new Hops(3000, 5000, 5000, 3000, 3000, 3000)))
                .singleElement().asString().contains("取消备战");
        assertThat(MatchStartupChecks.budgetViolations(4200, new Hops(3000, 3000, 5001, 3000, 3000, 3000)))
                .singleElement().asString().contains("建房 createBattle").contains("(0, 5000]");
        assertThat(MatchStartupChecks.budgetViolations(4200, new Hops(3000, 3000, 5000, 4000, 3000, 3000)))
                .singleElement().asString().contains("销毁 destroyBattle");
        assertThat(MatchStartupChecks.budgetViolations(4200, new Hops(3000, 3000, 5000, 3000, 3500, 3000)))
                .singleElement().asString().contains("补签 issueBattleTicket");
        assertThat(MatchStartupChecks.budgetViolations(4200, new Hops(3000, 3000, 5000, 3000, 3000, 9000)))
                .singleElement().asString().contains("清退观众");
    }

    @Test
    void 超时为0或负_也算违规_多处违规一次列全() {
        List<String> violations = MatchStartupChecks.budgetViolations(7000, new Hops(0, -1, 6000, 3000, 3000, 3000));

        assertThat(violations).hasSize(4);
        assertThat(violations.get(0)).contains("Redis");
        assertThat(violations.get(1)).contains("备战 prepareBattle").contains("0 ms");
        assertThat(violations.get(2)).contains("取消备战").contains("-1 ms");
        assertThat(violations.get(3)).contains("建房");
    }

    // ================================================================ 推出来的表

    @Test
    void 现行公式推出的matched_TTL表就是基线的值_开战锁不超过EndMatch截止() {
        assertThat(MatchStartupChecks.ttlTableViolations(MatchBudgets::matchedTicketTtlSeconds, MatchBudgets::teamMatchLockSeconds)).isEmpty();
        assertThat(MatchStartupChecks.BASELINE_MATCHED_TTL).as("规格 §3.4 的表").isDeepEqualTo(new int[][] {{1, 42}, {2, 48}, {3, 54}, {4, 60}, {5, 66}, {10, 96}});
    }

    @Test
    void 有人把某一跳改小了_各跳都没超上限_但TTL表不再是基线值_拒绝() {
        // 假设备战一跳改成 2 s：每人少 1 s
        List<String> violations = MatchStartupChecks.ttlTableViolations(n -> MatchBudgets.matchedTicketTtlSeconds(n) - n,
                MatchBudgets::teamMatchLockSeconds);

        assertThat(violations).hasSize(7);
        assertThat(violations.get(0)).isEqualTo("1 人的 matched TTL 是 41 s，基线是 42 s");
        assertThat(violations.get(5)).isEqualTo("10 人的 matched TTL 是 86 s，基线是 96 s");
        assertThat(violations.get(6)).contains("最长的 matched TTL 86 s").contains("MAX_MATCHED_TTL_SECONDS = 96");
    }

    @Test
    void 开战锁超过xm_team的EndMatch截止_拒绝() {
        List<String> violations = MatchStartupChecks.ttlTableViolations(MatchBudgets::matchedTicketTtlSeconds, n -> 111);

        assertThat(violations).singleElement().asString().contains("5 人的开战锁 111 s").contains("110 s");
        assertThat(MatchStartupChecks.ttlTableViolations(MatchBudgets::matchedTicketTtlSeconds, n -> 110)).as("恰好等于截止：通过").isEmpty();
    }

    // ================================================================ 合在一起

    @Test
    void 全部通过_返回凭据() {
        MatchStartupChecks.Passed passed = MatchStartupChecks.verify(Map.of(1, 5), DUNGEONS::contains, 4200);

        assertThat(passed).isEqualTo(new MatchStartupChecks.Passed(4200, 1));
    }

    @Test
    void 有违规_抛异常拒绝启动_消息里列全() {
        assertThatThrownBy(() -> MatchStartupChecks.verify(new TreeMap<>(Map.of(1, 5, 77, 5)), DUNGEONS::contains, 9000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("xm-match 启动门禁未通过，拒绝启动：")
                .hasMessageContaining("副本 id 77 ")
                .hasMessageContaining("9000 ms");
    }
}
