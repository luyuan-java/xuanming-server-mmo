package com.game.gate.session;

import com.game.api.DubboGroups;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户端消息号白名单与路由：只有标了 {@code OptionIsClientProtocolService} 的服务方法客户端可以发。
 *
 * <p>路由按服务语义决定，<b>不看 proto 文件所在目录</b>（mmorpg 会整理目录，见 architecture.md §1）：
 * <ul>
 *   <li>{@link #DIRECT_ONLY_SERVICES} 里的服务只走客户端直连，gate 永不中继：路由标 {@link MessageRoute#directOnly()}，
 *       dispatcher 在 GM 闸之后当场推 23 {1003}，先于下面各条的分派（域照常按下面的规则算，只用作指标标签）；</li>
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
     * 只走客户端直连、gate 永不中继的客户端服务（服务裸名）。现在只有战斗服务 {@code BattleClientPlayer}：它的 12 个号
     * （139 / 140 / 143 / 144 / 149 / 150 / 158 / 161 / 162 / 165 / 166 / 177，含各 Notify 号）在大厅连接上一律当场推
     * 23 {1003}，不计非法包、不断连（基线 {@code client_message_processor.cpp:937-950} 按 {@code targetNodeType == BattleNodeService}
     * 判定；scene-battle-spec §2.5、§7.19，D12）。
     *
     * <p>Java 按服务裸名判定，不照搬基线的判据。基线的 {@code targetNodeType} 由 protogen {@code NodeServiceForCpp} 的三级回落派生
     * （mmorpg {@code protogen/internal/model.go:167-183}）：服务名 + {@code NodeService} 命中 {@code eNodeType} → proto package 驼峰化后
     * 命中 → proto 所在目录名；{@code BattleClientPlayer} 没有 package，靠的是第三级的目录名 {@code battle}。它<b>不是</b>由 proto 文件的
     * {@code OptionFileDefaultNode} 派生的（login / friend / guild 三个文件没写这个 option，基线照样按目录判出节点）。
     * 目录名 Java 不许依赖（AGENTS.md §1），{@link MessageMethod} 也只暴露服务语义。
     *
     * <p>{@code MessageRoutesTest} 用「前两级照算 + 第三级以 {@code OptionFileDefaultNode = NODE_BATTLE} 近似」核对两边在现行契约上圈出
     * 同一组号，并钉住这个近似看不见的那部分——前两级不命中、文件又没写该 option 的客户端服务清单。mmorpg 以后新增可能被基线判成
     * 战斗节点的客户端服务时那里先失败，由人判断要不要加进这张表。
     */
    Set<String> DIRECT_ONLY_SERVICES = Set.of("BattleClientPlayer");

    /**
     * 非玩家服务的客户端服务 → 后端。新接入一个后端就在这里加一行。
     * 帮会服务名是 {@code GuildService}（{@code message_id.txt} 的前缀，如 {@code 8=GuildServiceUpdateGuildScore}）：
     * 28 个号整体转给 xm-guild，含只对内部开放的 8 与推送占位 220，由后端按方法回信封 1003（guild-spec §7.2、§7.3）。
     * 聚宝斋服务名是 {@code ClientPlayerJubaozhai}（{@code 196=ClientPlayerJubaozhaiBrowseListings}），4 个号（196 / 197 / 198 / 200）转给
     * xm-trade（trade-spec §5.2）；199 {@code TradeAdminSeedListing} 刻意没标客户端协议服务（trade_admin.proto:13-18），不进白名单，
     * 客户端发来按「不认识的号」丢弃、计非法包、不回包（同基线 C++ gate，trade_smoke_scenario.go:281-284）。
     *
     * <p><b>{@code BattleClientPlayer} 永远不许加进来</b>（inventory combat.md gate-battle-uplink-reject；battle-node-spec §3.1、§3.7）：
     * 战斗上行（140 / 149 / 162 / 165）与战斗帧只走客户端到 xm-battle 的直连，大厅连接上发这个服务的任何号（含 Notify 号）都由
     * {@link #DIRECT_ONLY_SERVICES} 当场拒绝 → 推 23 {1003}、不计非法包、不断连（基线 {@code client_message_processor.cpp:937-949}）。
     * 把它接到某个后端会让大厅连接成为绕过直连票据（身份只来自票据）的第二条战斗通路；即使误加，直连闸排在后端分派之前，
     * 请求也到不了后端（{@code ClientDispatcherTest} 钉住）。{@code MessageRoutesTest} 与 {@code BattleUplinkRejectedTest} 钉住这一点。
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

    /**
     * 全部客户端路由（按消息号升序，只读）。只在装配时用（预建指标）；手写的路由表（测试里的 lambda）不必枚举，缺省为空。
     */
    default List<MessageRoute> all() {
        return List.of();
    }

    /** 从消息号注册表构建（启动时一次，之后只读，线程安全）。 */
    static MessageRoutes of(MessageIdRegistry registry) {
        Map<Integer, MessageRoute> table = new HashMap<>();
        for (MessageMethod method : registry.all()) {
            if (method.clientService()) {
                table.put(method.messageId(), new MessageRoute(method.messageId(), backendOf(method), hasResponse(method),
                        method.serviceName() + "." + method.methodName(), method.gmCommand(),
                        "/" + method.method().getService().getFullName() + "/" + method.methodName(), directOnly(method)));
            }
        }
        Map<Integer, MessageRoute> frozen = Map.copyOf(table);
        List<MessageRoute> all = frozen.values().stream().sorted(Comparator.comparingInt(MessageRoute::messageId)).toList();
        return new MessageRoutes() {
            @Override
            public MessageRoute clientRoute(int messageId) {
                return frozen.get(messageId);
            }

            @Override
            public List<MessageRoute> all() {
                return all;
            }
        };
    }

    /** 应答类型是契约里的 {@code Empty}（无 package）或 {@code google.protobuf.Empty} 的方法不回包，其余一律回。 */
    static boolean hasResponse(MessageMethod method) {
        String type = method.responsePrototype().getDescriptorForType().getFullName();
        return !type.equals("Empty") && !type.equals("google.protobuf.Empty");
    }

    /** 这个方法是不是只走客户端直连（见 {@link #DIRECT_ONLY_SERVICES}）。 */
    static boolean directOnly(MessageMethod method) {
        return DIRECT_ONLY_SERVICES.contains(method.serviceName());
    }

    static String backendOf(MessageMethod method) {
        if (method.playerService()) {
            return ClientDispatcher.DOMAIN_SCENE;
        }
        return SERVICE_BACKENDS.getOrDefault(method.serviceName(), BACKEND_UNSUPPORTED);
    }
}
