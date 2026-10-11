package com.game.login.handler;

import com.game.api.proto.AssignSceneRequest;
import com.game.discovery.proto.PlayerLocation;
import com.game.login.metrics.LoginMetrics.EnterRoute;
import java.util.Objects;

/**
 * 进游戏的去向（批次 5.4，zone-travel-spec §5.8 的去向表；对应基线 entergamelogic.go 的 resolveEnterSceneRoute 与
 * homezone.go 的 TicketPinsZone）。{@link #resolveEnterRoute} 是纯函数：不读库、不读 Redis、不打日志、不计指标，
 * 去向表的每一格都能表驱动地测（{@code EnterRoutesTest}）。
 *
 * <p>输入（G、TP、TZ、L 四样，另带两项只用来拼分配请求的值）：
 * <ul>
 *   <li><b>G</b>：入口 zone——这条连接所在 gate 的 zone（{@code SessionContext.zone_id}；会话没带时调用方用本 login 的 zone）。
 *       落地一律落在 G：Java 的 gate 只连本 zone 的 scene；</li>
 *   <li><b>TP / TZ</b>：这条连接握手时出示的 gate 票据里的持票者与目标 zone（{@code SessionContext.ticket_player_id /
 *       ticket_target_zone_id}，gate 只存不判）。<b>钉住</b> = {@code TP ≠ 0 ∧ TZ == G}：持重定向票据来到本 zone
 *       （226 的第二条腿、GO-5 之后的那一跳），不再按位置记录把它弹走——一次动作至多一跳。
 *       Java 的普通登录令牌 TP = 0、TZ = 签发 zone（普通令牌钉 zone 是已登记的有意差异，X13），所以判据看 TP、不看 TZ ≠ 0，
 *       普通登录永远不算钉住；TZ ≠ G 的票据也不算（gate 只认本 zone 的票据，到这里只可能是缺陷或伪造，按普通登录处理）；</li>
 *   <li><b>L</b>：位置记录（{@code PlayerLocationDirectory.find}：只有在线 / 重连租约有值，登出墓碑与过期都读成没有；
 *       <b>节点号 0 = 待落点</b>，跨 zone 传送的源节点写的「正在去 zone_id 的路上」），或读失败。</li>
 * </ul>
 *
 * <p>去向表（行 = L；落地都在 G，分配请求的 zone 恒为 G）：
 * <table>
 *   <caption>L × 钉住 / 未钉 → 去向（指标标签）与分配请求的 preferred 字段</caption>
 *   <tr><th>位置记录 L</th><th>钉住</th><th>未钉</th></tr>
 *   <tr><td>没有 / 墓碑</td><td colspan="2">本 zone 首登 {@code first}：不带 preferred（默认主世界）</td></tr>
 *   <tr><td>本 zone、节点号 ≠ 0（在线 / 断线租约）</td><td colspan="2">回原实例 {@code resume}：preferred = 记录的地图、节点、场景</td></tr>
 *   <tr><td>本 zone、节点号 = 0（待落点）</td><td colspan="2">{@code awaiting}：preferred 只带记录的地图，不带实例
 *       （同基线 zone 对上、请求没指定地图即采用 pending 地图）</td></tr>
 *   <tr><td>别的 zone、节点号 ≠ 0</td><td>{@code pinned}：本 zone 首登（票据优先、不再弹走）</td>
 *       <td><b>GO-5 重定向</b>：请 scene-manager 在记录的 zone 选 gate、签票（不夺权、不分配）；签票没成退化为本 zone 首登（X11）</td></tr>
 *   <tr><td>别的 zone、节点号 = 0（别处的待落点）</td><td colspan="2">本 zone 首登 {@code first}（等待落点不牵引，这次传送作废）</td></tr>
 *   <tr><td>读失败</td><td colspan="2">{@code location_error}：preferred 只带存档里上次所在的地图（不让 Redis 抖动把老玩家送回出生点）</td></tr>
 * </table>
 * 记录的 zone 为 0 不该出现（写者总写自己所在的 zone 或目标 zone），按别的 zone 处理但从不重定向到 zone 0：本 zone 首登。
 *
 * <p>与基线的差别：本 zone 有在线 / 租约记录时回原实例（基线落默认主世界，{@code PARITY.md} ⑥，跨 zone 重连同样适用，X18）；
 * GO-5 签票失败时落入口 zone（基线回 3023，X11）。基线 ShortReconnect / ReplaceLogin 取自会话登记，Java 没有会话登记，
 * 等价判定来自位置记录的在线 / 租约状态，客户端所见相同。
 */
final class EnterRoutes {

    private EnterRoutes() {
    }

    /** 位置记录读出来的样子（去向表的行）。 */
    sealed interface LocationRead permits Absent, Found, Unreadable {
    }

    /** 没有记录：从未写过、登出墓碑、租约 / 待落点过期（{@code find} 对这几种一律回空）。 */
    record Absent() implements LocationRead {
    }

    /** 有在线、重连租约或待落点记录（节点号 0 = 待落点）。 */
    record Found(PlayerLocation location) implements LocationRead {
        Found {
            Objects.requireNonNull(location, "location");
        }
    }

    /** 读失败（Redis 出错）。 */
    record Unreadable() implements LocationRead {
    }

    static final LocationRead ABSENT = new Absent();
    static final LocationRead UNREADABLE = new Unreadable();

    /** 去向：在入口 zone 落地，或登录期重定向。 */
    sealed interface Decision permits Land, Redirect {
    }

    /**
     * 在入口 zone 落地：合服围栏检查点 F2 → 按 {@code assign} 分配 → 夺权。
     *
     * @param route  指标标签（{@code xm.login.enter.routes{route}}）
     * @param assign 请 scene-manager 分配的请求（zone = G）
     */
    record Land(EnterRoute route, AssignSceneRequest assign) implements Decision {
        Land {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(assign, "assign");
        }
    }

    /**
     * 登录期重定向（GO-5）：角色此刻在别的 zone 的节点上（在线或断线重连租约内），这条连接又没被钉住——请 scene-manager 在
     * {@code toZoneId} 选一台 gate、签重定向票据，把连接送过去；不夺权、不分配、不查合服围栏（F3：只送连接）。
     *
     * @param toZoneId 位置记录所在的 zone（非 0、≠ G）
     * @param fallback 签票没成（调用失败 / 超时 / 拒绝 / 应答残缺）时的落地请求：本 zone 首登（X11）
     */
    record Redirect(int toZoneId, AssignSceneRequest fallback) implements Decision {
        Redirect {
            Objects.requireNonNull(fallback, "fallback");
        }
    }

    /**
     * 这条连接是否持票钉在入口 zone：{@code TP ≠ 0 ∧ TZ == G}（X13，见类注释）。
     *
     * @param gateZoneId         G
     * @param ticketPlayerId     TP（普通令牌为 0）
     * @param ticketTargetZoneId TZ
     */
    static boolean pinned(int gateZoneId, long ticketPlayerId, int ticketTargetZoneId) {
        return ticketPlayerId != 0 && ticketTargetZoneId == gateZoneId;
    }

    /**
     * 按去向表定去向（见类注释）。
     *
     * @param gateZoneId         G：入口 zone（非 0）
     * @param ticketPlayerId     TP：票据的持票者（普通令牌为 0）；持票者与请求的角色不符在这之前已回 2011
     * @param ticketTargetZoneId TZ：票据的目标 zone
     * @param location           L：位置记录
     * @param playerId           请求进游戏的角色（写进分配请求）
     * @param savedSceneConfigId 存档里上次所在的地图（{@code player.scene_config_id}），只在读位置失败时用
     */
    static Decision resolveEnterRoute(int gateZoneId, long ticketPlayerId, int ticketTargetZoneId, LocationRead location,
                                      long playerId, int savedSceneConfigId) {
        AssignSceneRequest first = AssignSceneRequest.newBuilder().setZoneId(gateZoneId).setPlayerId(playerId).build();
        return switch (location) {
            case Absent absent -> new Land(EnterRoute.FIRST, first);
            case Unreadable unreadable -> new Land(EnterRoute.LOCATION_ERROR,
                    first.toBuilder().setPreferredSceneConfigId(savedSceneConfigId).build());
            case Found found -> fromRecord(gateZoneId, pinned(gateZoneId, ticketPlayerId, ticketTargetZoneId),
                    found.location(), first);
        };
    }

    private static Decision fromRecord(int gateZoneId, boolean pinned, PlayerLocation at, AssignSceneRequest first) {
        boolean awaiting = at.getSceneNodeId() == 0;
        if (at.getZoneId() == gateZoneId) {
            if (awaiting) {
                // 本 zone 的待落点：跨 zone 传送的第二条腿（钉不钉住都一样，同基线 zone 对上即采用 pending 地图）。
                // 不带实例：落到这张图的哪个频道由 scene-manager 决定，这张图在本 zone 不可用时它回落默认主世界。
                return new Land(EnterRoute.AWAITING,
                        first.toBuilder().setPreferredSceneConfigId(at.getSceneConfigId()).build());
            }
            return new Land(EnterRoute.RESUME, first.toBuilder()
                    .setPreferredSceneConfigId(at.getSceneConfigId())
                    .setPreferredSceneNodeId(at.getSceneNodeId())
                    .setPreferredSceneId(at.getSceneId())
                    .build());
        }
        if (awaiting || at.getZoneId() == 0) {
            // 别处的待落点不牵引（这次传送作废，回到入口 zone）；zone 为 0 的记录不该出现，也不往 zone 0 送。
            return new Land(EnterRoute.FIRST, first);
        }
        if (pinned) {
            return new Land(EnterRoute.PINNED, first);
        }
        return new Redirect(at.getZoneId(), first);
    }
}
