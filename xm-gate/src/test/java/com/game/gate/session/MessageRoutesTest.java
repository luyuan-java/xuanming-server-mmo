package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.DubboGroups;
import com.game.common.killswitch.KillSwitch;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 用同步进来的真实契约核对路由：登录链路走 login，进场后的消息走 scene，内部服务消息客户端发不了。 */
class MessageRoutesTest {

    private static MessageIdRegistry registry;
    private static MessageRoutes routes;

    @BeforeAll
    static void load() {
        registry = MessageIdRegistry.loadFromClasspath();
        routes = MessageRoutes.of(registry);
    }

    @Test
    void 登录链路消息号路由到login域() {
        for (String method : new String[] {"Login", "CreatePlayer", "EnterGame", "LeaveGame", "Disconnect"}) {
            int id = registry.requireId("ClientPlayerLogin", method);
            assertThat(routes.clientRoute(id).domain()).as(method).isEqualTo(ClientDispatcher.DOMAIN_LOGIN);
        }
    }

    @Test
    void 是否回包由契约的应答类型决定_与应答是否为0字节无关() {
        // Login / CreatePlayer / EnterGame 有应答：新账号的 LoginResponse 全默认值、序列化为 0 字节，也必须回包。
        for (String method : new String[] {"Login", "CreatePlayer", "EnterGame"}) {
            int id = registry.requireId("ClientPlayerLogin", method);
            assertThat(routes.clientRoute(id).hasResponse()).as(method).isTrue();
        }
        // 应答类型为 Empty 的方法不回包。
        registry.all().stream()
                .filter(m -> m.clientService()
                        && m.responsePrototype().getDescriptorForType().getFullName().endsWith("Empty"))
                .forEach(m -> assertThat(routes.clientRoute(m.messageId()).hasResponse()).as(m.key()).isFalse());
    }

    @Test
    void 进场后的客户端消息路由到scene域() {
        int listSkills = registry.requireId("SceneSkillClientPlayer", "ListSkills");
        MessageRoute route = routes.clientRoute(listSkills);
        assertThat(route).isEqualTo(new MessageRoute(listSkills, ClientDispatcher.DOMAIN_SCENE, true,
                "SceneSkillClientPlayer.ListSkills", false, route.rpcPath()));
        assertThat(route.rpcPath()).endsWith("SceneSkillClientPlayer/ListSkills").startsWith("/");
    }

    @Test
    void Java版未接入的客户端服务路由到unsupported() {
        // 不钉具体服务名：哪个服务先接入由批次决定，这里取任意一个「既不是玩家服务、也不在后端表里」的客户端服务。
        MessageMethod unsupported = registry.all().stream()
                .filter(m -> m.clientService() && !m.playerService()
                        && !MessageRoutes.SERVICE_BACKENDS.containsKey(m.serviceName()))
                .findFirst().orElseThrow();
        MessageRoute route = routes.clientRoute(unsupported.messageId());
        assertThat(route.domain()).isEqualTo(MessageRoutes.BACKEND_UNSUPPORTED);
        assertThat(route.method()).isEqualTo(unsupported.serviceName() + "." + unsupported.methodName());
    }

    @Test
    void 组队服务路由到team域_三个推送占位不回包_其余都回包() {
        // team-spec §6.2 / §10.3：ClientPlayerTeam 的 15 个号全部转给 xm-team（推送占位也转，由后端回空体）；
        // 203 / 213 / 215 的应答类型是 Empty → gate 不回包（D13），12 个 C2S 的应答是 TeamResponse / ListMyInvitesResponse → 一律回包。
        List<String> pushes = List.of("NotifyTeamEvent", "NotifyTeamSnapshot", "NotifyTeamInvite");
        List<String> requests = List.of("CreateTeam", "GetMyTeam", "ApplyJoinTeam", "HandleApplication", "InviteToTeam",
                "RespondInvite", "ListMyInvites", "LeaveTeam", "KickMember", "TransferLeader", "DisbandTeam", "StartTeamMatch");
        List<MessageMethod> team = registry.all().stream()
                .filter(m -> m.serviceName().equals("ClientPlayerTeam")).toList();
        assertThat(team).as("契约里 ClientPlayerTeam 的方法").extracting(MessageMethod::methodName)
                .containsExactlyInAnyOrderElementsOf(Stream.concat(pushes.stream(), requests.stream()).toList());
        for (MessageMethod method : team) {
            MessageRoute route = routes.clientRoute(method.messageId());
            assertThat(route).as(method.key()).isNotNull();
            assertThat(route.domain()).as(method.key()).isEqualTo(DubboGroups.TEAM);
            assertThat(route.hasResponse()).as(method.key()).isEqualTo(!pushes.contains(method.methodName()));
            assertThat(route.method()).isEqualTo("ClientPlayerTeam." + method.methodName());
            assertThat(route.rpcPath()).isEqualTo("/teampb.ClientPlayerTeam/" + method.methodName());
        }
    }

    @Test
    void 帮会服务28个号都路由到guild域_220不回包_8回包_热关停键与基线同名() {
        // guild-spec §0.2 / §7.2 / §11.5：GuildService 标了 OptionIsClientProtocolService、没标 OptionIsPlayerService，
        // 全部 28 个号（含只对内部开放的 8 与推送占位 220）转给 xm-guild，由后端按方法回信封 1003；
        // 220 的应答类型是 Empty → tip 为 0 时 gate 不回包（tip ≠ 0 照样回信封，ClientDispatcher.replyToClient）。
        List<String> core = List.of("CreateGuild", "GetGuild", "GetPlayerGuild", "LeaveGuild", "DisbandGuild",
                "SetAnnouncement", "SetGuildMemberRole", "KickGuildMember", "TransferGuildLeader", "ApplyJoinGuild",
                "CancelGuildApplication", "ListMyGuildApplications", "ListGuildApplications", "ReviewGuildApplication",
                "GetGuildRank", "GetGuildRankByGuild");
        List<String> internalAndPush = List.of("UpdateGuildScore", "NotifyGuildChanged");
        List<String> economyAndActivity = List.of("GetGuildDonateOptions", "DonateToGuild", "UpgradeGuild", "GetGuildShop",
                "BuyGuildShopGoods", "GetGuildActivities", "LightGuildLantern", "ClaimGuildReunion", "StartGuildTrial",
                "RespondGuildTrialInvite");
        List<MessageMethod> guild = registry.all().stream().filter(m -> m.serviceName().equals("GuildService")).toList();
        assertThat(guild).as("契约里 GuildService 的方法").extracting(MessageMethod::methodName)
                .containsExactlyInAnyOrderElementsOf(Stream.of(core, internalAndPush, economyAndActivity)
                        .flatMap(List::stream).toList())
                .hasSize(28);
        for (MessageMethod method : guild) {
            MessageRoute route = routes.clientRoute(method.messageId());
            assertThat(route).as(method.key()).isNotNull();
            assertThat(route.domain()).as(method.key()).isEqualTo(DubboGroups.GUILD);
            assertThat(route.hasResponse()).as(method.key()).isEqualTo(!method.methodName().equals("NotifyGuildChanged"));
            assertThat(route.method()).isEqualTo("GuildService." + method.methodName());
            assertThat(route.gm()).as(method.key()).isFalse();
            // 热关停规则按 rpcPath 匹配：与基线 etcd 键 /mmorpg/killswitch/guildpb.GuildService/<Method> 同名（guild-spec §7.2、D17）
            assertThat(route.rpcPath()).isEqualTo("/guildpb.GuildService/" + method.methodName());
            assertThat(KillSwitch.matchKeys(route.rpcPath()))
                    .contains("guildpb.GuildService/" + method.methodName(), "guildpb.GuildService/*");
        }
        assertThat(routes.clientRoute(registry.requireId("GuildService", "NotifyGuildChanged")).hasResponse())
                .as("220 应答是 Empty").isFalse();
        assertThat(routes.clientRoute(registry.requireId("GuildService", "UpdateGuildScore")).hasResponse())
                .as("8 应答是 UpdateGuildScoreResponse").isTrue();
    }

    @Test
    void 聚宝斋4个号路由到trade域_都回包_热关停键与基线同名_199不在白名单() {
        // trade-spec §0.2 / §5.2 / §9.4：ClientPlayerJubaozhai 标了 OptionIsClientProtocolService、没标 OptionIsPlayerService，
        // 196 / 197 / 198 / 200 整体转给 xm-trade；应答都是带 error_message 的业务应答 → 一律回包
        List<String> methods = List.of("BrowseListings", "GetListingDetail", "SetFavorite", "GetMyShelf");
        List<MessageMethod> jubaozhai = registry.all().stream()
                .filter(m -> m.serviceName().equals("ClientPlayerJubaozhai")).toList();
        assertThat(jubaozhai).as("契约里 ClientPlayerJubaozhai 的方法").extracting(MessageMethod::methodName)
                .containsExactlyInAnyOrderElementsOf(methods);
        for (MessageMethod method : jubaozhai) {
            MessageRoute route = routes.clientRoute(method.messageId());
            assertThat(route).as(method.key()).isNotNull();
            assertThat(route.domain()).as(method.key()).isEqualTo(DubboGroups.TRADE);
            assertThat(route.hasResponse()).as(method.key()).isTrue();
            assertThat(route.method()).isEqualTo("ClientPlayerJubaozhai." + method.methodName());
            assertThat(route.gm()).as(method.key()).isFalse();
            // 热关停规则按 rpcPath 匹配：与基线 trade.yaml:39-44 的键 trade.ClientPlayerJubaozhai/<Method> 同名（T9）
            assertThat(route.rpcPath()).isEqualTo("/trade.ClientPlayerJubaozhai/" + method.methodName());
            assertThat(KillSwitch.matchKeys(route.rpcPath()))
                    .contains("trade.ClientPlayerJubaozhai/" + method.methodName(), "trade.ClientPlayerJubaozhai/*");
        }
        // 199 TradeAdmin.SeedListing 是内部服务（trade_admin.proto:13-18）：契约里有这个号，但客户端发不了（gate 按不认识的号丢弃）
        MessageMethod seed = registry.all().stream()
                .filter(m -> m.serviceName().equals("TradeAdmin") && m.methodName().equals("SeedListing"))
                .findFirst().orElseThrow();
        assertThat(seed.clientService()).isFalse();
        assertThat(routes.clientRoute(seed.messageId())).as("199 不可路由").isNull();
        assertThat(MessageRoutes.SERVICE_BACKENDS).doesNotContainKey("TradeAdmin");
    }

    @Test
    void 好友服务路由到friend域_推送方法也在白名单里由后端拒() {
        int addFriend = registry.requireId("ClientPlayerFriend", "AddFriend");
        assertThat(routes.clientRoute(addFriend)).isEqualTo(new MessageRoute(addFriend, DubboGroups.FRIEND, true,
                "ClientPlayerFriend.AddFriend", false, "/friendpb.ClientPlayerFriend/AddFriend"));
        // 235 NotifyFriendEvent 是 S2C 推送，与 C2S 同处一个服务：gate 照样转给 friend 后端，由后端按方法拒（回 1003）
        int notify = registry.requireId("ClientPlayerFriend", "NotifyFriendEvent");
        assertThat(routes.clientRoute(notify).domain()).isEqualTo(DubboGroups.FRIEND);
        assertThat(routes.clientRoute(notify).hasResponse()).as("应答类型是 Empty").isFalse();
    }

    @Test
    void 聊天服务路由到chat域() {
        int send = registry.requireId("ClientPlayerChat", "SendChat");
        assertThat(routes.clientRoute(send)).isEqualTo(new MessageRoute(send, DubboGroups.CHAT, true, "ClientPlayerChat.SendChat",
                false, "/chatpb.ClientPlayerChat/SendChat"));
    }

    @Test
    void 指标用的方法名是服务点方法_只来自客户端白名单且互不相同() {
        // method 是 xm.gate.client.requests 的标签：取值集合 = 客户端白名单，基数有界、不随公网流量增长。
        List<String> methods = registry.all().stream()
                .filter(MessageMethod::clientService)
                .map(m -> routes.clientRoute(m.messageId()).method())
                .toList();
        assertThat(methods).doesNotHaveDuplicates().allMatch(name -> name.matches("\\w+\\.\\w+"));
        assertThat(routes.clientRoute(registry.requireId("ClientPlayerLogin", "Login")).method())
                .isEqualTo("ClientPlayerLogin.Login");
    }

    @Test
    void 非客户端协议服务与不存在的消息号不可路由() {
        MessageMethod internal = registry.all().stream().filter(m -> !m.clientService()).findFirst().orElseThrow();
        assertThat(routes.clientRoute(internal.messageId())).isNull();
        assertThat(routes.clientRoute(Integer.MAX_VALUE)).isNull();
    }

    @Test
    void 推tip用的消息号是23() {
        assertThat(registry.requireId("SceneClientPlayerCommon", "SendTipToClient")).isEqualTo(23);
    }
}
