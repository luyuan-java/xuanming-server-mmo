package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssignSceneRequest;
import com.game.discovery.proto.PlayerLocation;
import com.game.login.handler.EnterRoutes.Decision;
import com.game.login.handler.EnterRoutes.Land;
import com.game.login.handler.EnterRoutes.LocationRead;
import com.game.login.handler.EnterRoutes.Redirect;
import com.game.login.metrics.LoginMetrics.EnterRoute;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 进游戏去向表（zone-travel-spec §5.8）的每一格：钉 / 未钉 × 没有 / 本 zone 节点 / 本 zone 待落点 / 他区节点 / 他区待落点 / 读失败。
 * 对照基线 {@code entergame_route_test.go} 的 {@code TestResolveEnterSceneRoute_Priority} 等（票据优先于 GO-5、等待落点不牵引）。
 *
 * <p>期望值逐格手写（不由被测函数的同一套规则推出来），断言的是给 scene-manager 的分配请求的 zone 与三个 preferred 字段，
 * 以及去向的指标标签；GO-5 断言目标 zone 与签票失败时的回落请求。
 */
class EnterRoutesTest {

    /** G：入口 zone。 */
    private static final int G = 7;
    /** 别的 zone。 */
    private static final int H = 8;
    private static final long PLAYER = 123456789L;
    /** 存档里上次所在的地图（只在读位置失败时用）。 */
    private static final int SAVED_CONF = 3;

    /** 这条连接握手时出示的票据（TP / TZ）。 */
    private record Ticket(String name, long holder, int targetZone) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** 钉住：重定向票据，持票者 = 请求的角色，目标 zone = G（226 的第二条腿、GO-5 之后的那一跳）。 */
    private static final Ticket PINNED = new Ticket("重定向票据钉在本zone", PLAYER, G);
    /** 未钉的几种：都必须按普通登录处理。 */
    private static final List<Ticket> UNPINNED = List.of(
            // X13 回归：Java 普通令牌 TP = 0、TZ = 签发 zone（= G）。判据若写成「TZ == G」，所有普通登录都会被钉在入口 zone、GO-5 永不触发。
            new Ticket("普通令牌TP0且TZ等于G", 0, G),
            // 不认识 8 / 9 号字段的旧 gate：两者都是 0。
            new Ticket("旧gate两字段都是0", 0, 0),
            // 目标 zone 不是本 zone 的票据（gate 只认本 zone 的票据，到这里是缺陷或伪造）：不算钉住。
            new Ticket("票据的目标zone不是G", PLAYER, H),
            new Ticket("票据的目标zone为0", PLAYER, 0));

    private static AssignSceneRequest.Builder assign() {
        return AssignSceneRequest.newBuilder().setZoneId(G).setPlayerId(PLAYER);
    }

    private static AssignSceneRequest first() {
        return assign().build();
    }

    private static LocationRead at(int zone, int node, long scene, int conf) {
        return new EnterRoutes.Found(PlayerLocation.newBuilder().setPlayerId(PLAYER).setZoneId(zone).setSceneNodeId(node)
                .setSceneId(scene).setSceneConfigId(conf).setOwnerEpoch(4).build());
    }

    /** 一行：位置记录 L，以及钉住 / 未钉两列各自的期望。 */
    private record Row(String name, LocationRead location, Decision whenPinned, Decision whenUnpinned) {
    }

    private static List<Row> table() {
        return List.of(
                new Row("没有记录", EnterRoutes.ABSENT,
                        new Land(EnterRoute.FIRST, first()),
                        new Land(EnterRoute.FIRST, first())),
                new Row("本zone节点", at(G, 4, 99_000_001L, 2),
                        new Land(EnterRoute.RESUME, assign().setPreferredSceneConfigId(2).setPreferredSceneNodeId(4)
                                .setPreferredSceneId(99_000_001L).build()),
                        new Land(EnterRoute.RESUME, assign().setPreferredSceneConfigId(2).setPreferredSceneNodeId(4)
                                .setPreferredSceneId(99_000_001L).build())),
                new Row("本zone待落点", at(G, 0, 0, 5),
                        new Land(EnterRoute.AWAITING, assign().setPreferredSceneConfigId(5).build()),
                        new Land(EnterRoute.AWAITING, assign().setPreferredSceneConfigId(5).build())),
                new Row("本zone待落点_地图0即默认主世界", at(G, 0, 0, 0),
                        new Land(EnterRoute.AWAITING, first()),
                        new Land(EnterRoute.AWAITING, first())),
                new Row("他区节点", at(H, 4, 99_000_001L, 2),
                        new Land(EnterRoute.PINNED, first()),
                        new Redirect(H, first())),
                new Row("他区待落点", at(H, 0, 0, 5),
                        new Land(EnterRoute.FIRST, first()),
                        new Land(EnterRoute.FIRST, first())),
                new Row("记录的zone为0_不往zone0送", at(0, 4, 99_000_001L, 2),
                        new Land(EnterRoute.FIRST, first()),
                        new Land(EnterRoute.FIRST, first())),
                new Row("读失败", EnterRoutes.UNREADABLE,
                        new Land(EnterRoute.LOCATION_ERROR, assign().setPreferredSceneConfigId(SAVED_CONF).build()),
                        new Land(EnterRoute.LOCATION_ERROR, assign().setPreferredSceneConfigId(SAVED_CONF).build())));
    }

    static Stream<Arguments> cells() {
        List<Arguments> cells = new ArrayList<>();
        for (Row row : table()) {
            cells.add(Arguments.of(row.name(), PINNED, row.location(), row.whenPinned()));
            for (Ticket ticket : UNPINNED) {
                cells.add(Arguments.of(row.name(), ticket, row.location(), row.whenUnpinned()));
            }
        }
        return cells.stream();
    }

    @ParameterizedTest(name = "{0} × {1}")
    @MethodSource("cells")
    void 去向表的每一格(String row, Ticket ticket, LocationRead location, Decision expected) {
        Decision actual = EnterRoutes.resolveEnterRoute(G, ticket.holder(), ticket.targetZone(), location, PLAYER, SAVED_CONF);

        assertThat(actual).isEqualTo(expected);
        // 不论哪一格，落地（含 GO-5 的回落）只落在入口 zone，分配请求带的是请求的角色。
        AssignSceneRequest landing = switch (actual) {
            case Land land -> land.assign();
            case Redirect redirect -> redirect.fallback();
        };
        assertThat(landing.getZoneId()).isEqualTo(G);
        assertThat(landing.getPlayerId()).isEqualTo(PLAYER);
    }

    @Test
    void 表覆盖了全部去向标签_除了只由处理器给出的两种() {
        Set<EnterRoute> seen = EnumSet.noneOf(EnterRoute.class);
        for (Row row : table()) {
            for (Decision decision : List.of(row.whenPinned(), row.whenUnpinned())) {
                if (decision instanceof Land land) {
                    seen.add(land.route());
                }
            }
        }
        // REDIRECT / REDIRECT_FALLBACK 是 GO-5 签票之后由处理器按结局计的；纯函数只给出 Redirect 这个去向。
        assertThat(seen).isEqualTo(EnumSet.complementOf(EnumSet.of(EnterRoute.REDIRECT, EnterRoute.REDIRECT_FALLBACK)));
    }

    @Test
    void 钉住的判据是持票者非0且目标zone等于入口zone() {
        assertThat(EnterRoutes.pinned(G, PLAYER, G)).isTrue();
        assertThat(EnterRoutes.pinned(G, 0, G)).as("普通令牌（X13）").isFalse();
        assertThat(EnterRoutes.pinned(G, 0, 0)).isFalse();
        assertThat(EnterRoutes.pinned(G, PLAYER, H)).isFalse();
        assertThat(EnterRoutes.pinned(G, PLAYER, 0)).isFalse();
        // 持票者是谁不在这里判（不符在选去向之前已回 2011），uint64 的大值同样算非 0。
        assertThat(EnterRoutes.pinned(G, -1L, G)).isTrue();
        // uint32 的大 zone（Java 里是负数）按位比较。
        assertThat(EnterRoutes.pinned(-2, PLAYER, -2)).isTrue();
        assertThat(EnterRoutes.pinned(-2, PLAYER, 2)).isFalse();
    }

    @Test
    void 普通登录遇到他区节点上的记录一定走GO5_不会被入口zone钉住() {
        // X13 回归的直述：普通令牌（gateway 签，TZ = 签发 zone = G）进来的顶号 / 重连，角色在 H 上在线 → 送去 H。
        Decision decision = EnterRoutes.resolveEnterRoute(G, 0, G, at(H, 4, 99_000_001L, 2), PLAYER, SAVED_CONF);
        assertThat(decision).isEqualTo(new Redirect(H, first()));
    }

    @Test
    void 一次动作至多一跳_持票到了目标zone的连接无论记录在哪都不再重定向() {
        for (Row row : table()) {
            Decision decision = EnterRoutes.resolveEnterRoute(G, PLAYER, G, row.location(), PLAYER, SAVED_CONF);
            assertThat(decision).as(row.name()).isInstanceOf(Land.class);
        }
    }

    @Test
    void 他区节点上的记录_重定向的目标是记录里的zone_不看记录的地图与实例() {
        Decision decision = EnterRoutes.resolveEnterRoute(G, 0, G, at(1000, 9, 5L, 77), PLAYER, SAVED_CONF);
        assertThat(decision).isEqualTo(new Redirect(1000, first()));
        // uint32 的大 zone 原样带过去。
        assertThat(EnterRoutes.resolveEnterRoute(G, 0, G, at(-5, 9, 5L, 77), PLAYER, SAVED_CONF))
                .isEqualTo(new Redirect(-5, first()));
    }

    @Test
    void 读失败时存档地图为0_就是默认主世界() {
        Decision decision = EnterRoutes.resolveEnterRoute(G, 0, G, EnterRoutes.UNREADABLE, PLAYER, 0);
        assertThat(decision).isEqualTo(new Land(EnterRoute.LOCATION_ERROR, first()));
    }
}
