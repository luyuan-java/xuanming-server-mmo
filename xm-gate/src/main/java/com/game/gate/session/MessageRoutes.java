package com.game.gate.session;

import com.game.api.DubboGroups;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端消息号白名单与路由：只有标了 {@code OptionIsClientProtocolService} 的服务方法客户端可以发。
 *
 * <p>路由按服务语义决定，<b>不看 proto 文件所在目录</b>（mmorpg 会整理目录，见 architecture.md §1）：
 * <ul>
 *   <li>标了 {@code OptionIsPlayerService} 的服务由玩家所在的 scene 处理；</li>
 *   <li>其余客户端服务查 {@link #SERVICE_BACKENDS}（服务裸名 → 后端 Dubbo group）；</li>
 *   <li>表里没有的是 Java 版尚未实现的服务，路由到 {@link #BACKEND_UNSUPPORTED}，由 dispatcher 回「服务不可用」。</li>
 * </ul>
 *
 * <p>单独成接口是为了让路由测试不依赖某个具体消息号的发号结果（消息号由 mmorpg 生成器发，会漂移）。
 */
@FunctionalInterface
public interface MessageRoutes {

    /** Java 版尚未接入的后端。 */
    String BACKEND_UNSUPPORTED = "unsupported";

    /**
     * 非玩家服务的客户端服务 → 后端。新接入一个后端就在这里加一行。
     * 帮会服务名是 {@code GuildService}（{@code message_id.txt} 的前缀，如 {@code 8=GuildServiceUpdateGuildScore}）：
     * 28 个号整体转给 xm-guild，含只对内部开放的 8 与推送占位 220，由后端按方法回信封 1003（guild-spec §7.2、§7.3）。
     * 聚宝斋服务名是 {@code ClientPlayerJubaozhai}（{@code 196=ClientPlayerJubaozhaiBrowseListings}），4 个号（196 / 197 / 198 / 200）转给
     * xm-trade（trade-spec §5.2）；199 {@code TradeAdminSeedListing} 刻意没标客户端协议服务（trade_admin.proto:13-18），不进白名单，
     * 客户端发来按「不认识的号」丢弃、计非法包、不回包（同基线 C++ gate，trade_smoke_scenario.go:281-284）。
     *
     * <p><b>{@code BattleClientPlayer} 永远不许加进来</b>（inventory combat.md gate-battle-uplink-reject；battle-node-spec §3.1、§3.7）：
     * 战斗上行（140 / 149 / 162 / 165）与战斗帧只走客户端到 xm-battle 的直连，大厅连接上发这个服务的任何号（含 Notify 号）都走
     * {@link #BACKEND_UNSUPPORTED} → 推 23 {1003}、不计非法包、不断连（基线 {@code client_message_processor.cpp:937-949}）。
     * 把它接到某个后端会让大厅连接成为绕过直连票据（身份只来自票据）的第二条战斗通路。{@code MessageRoutesTest} 与
     * {@code BattleUplinkRejectedTest} 钉住这一点。
     */
    Map<String, String> SERVICE_BACKENDS = Map.of(
            "ClientPlayerLogin", DubboGroups.LOGIN,
            "ClientPlayerFriend", DubboGroups.FRIEND,
            "ClientPlayerChat", DubboGroups.CHAT,
            "ClientPlayerTeam", DubboGroups.TEAM,
            "GuildService", DubboGroups.GUILD,
            "ClientPlayerJubaozhai", DubboGroups.TRADE);

    /** 客户端可发的消息号的路由；消息号不存在或不属于客户端协议服务时返回 null。 */
    MessageRoute clientRoute(int messageId);

    /** 从消息号注册表构建（启动时一次，之后只读，线程安全）。 */
    static MessageRoutes of(MessageIdRegistry registry) {
        Map<Integer, MessageRoute> table = new HashMap<>();
        for (MessageMethod method : registry.all()) {
            if (method.clientService()) {
                table.put(method.messageId(), new MessageRoute(method.messageId(), backendOf(method), hasResponse(method),
                        method.serviceName() + "." + method.methodName(), method.gmCommand(),
                        "/" + method.method().getService().getFullName() + "/" + method.methodName()));
            }
        }
        Map<Integer, MessageRoute> frozen = Map.copyOf(table);
        return frozen::get;
    }

    /** 应答类型是契约里的 {@code Empty}（无 package）或 {@code google.protobuf.Empty} 的方法不回包，其余一律回。 */
    static boolean hasResponse(MessageMethod method) {
        String type = method.responsePrototype().getDescriptorForType().getFullName();
        return !type.equals("Empty") && !type.equals("google.protobuf.Empty");
    }

    static String backendOf(MessageMethod method) {
        if (method.playerService()) {
            return ClientDispatcher.DOMAIN_SCENE;
        }
        return SERVICE_BACKENDS.getOrDefault(method.serviceName(), BACKEND_UNSUPPORTED);
    }
}
