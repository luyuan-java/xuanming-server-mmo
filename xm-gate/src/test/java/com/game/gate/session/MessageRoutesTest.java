package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.DubboGroups;
import com.game.common.killswitch.KillSwitch;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.net.limit.MessageLimit;
import com.game.net.limit.MessageLimits;
import com.game.net.limit.TableMessageLimits;
import com.game.proto.common.base.eNodeType;
import com.game.proto.match.MatchMatchInternalOuterClass;
import com.game.proto.match.MatchMatchServiceOuterClass;
import com.google.protobuf.Descriptors;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
    void 现行契约里的客户端服务都有去向_没有落到缺省分支的_后端表里的服务名契约里都有() {
        // 6.4 把 MatchService 接进来之后契约里已经没有「未接入」的客户端服务了：原来这里有一条取真样本的用例
        // （「Java版未接入的客户端服务路由到unsupported」），按它自己的说明删掉，缺省分支由下一条用例用假想的服务名钉住。
        // 这里钉住现状。「未接入」= 不是玩家服务、不在后端表里、也不是只走直连（战斗号的域同样是 unsupported，但它们在直连闸
        // 就被拒了、到不了 dispatcher 的缺省分支，不算）。
        List<String> notPorted = registry.all().stream()
                .filter(m -> m.clientService() && !m.playerService()
                        && !MessageRoutes.SERVICE_BACKENDS.containsKey(m.serviceName())
                        && !MessageRoutes.DIRECT_ONLY_SERVICES.contains(m.serviceName()))
                .map(MessageMethod::serviceName).distinct().toList();
        assertThat(notPorted)
                .as("契约里出现了 Java 版还没接入的客户端服务：它的号会走 dispatcher 的缺省分支（推 23 {1003}）。"
                        + "这一批就要接的，照 MatchService 的做法加进 SERVICE_BACKENDS 与 GateConfiguration；留到以后的，把这条断言改成"
                        + "钉住那份清单（containsExactlyInAnyOrder），并在 PARITY.md 登记")
                .isEmpty();
        // 落到 unsupported 域的客户端路由只剩只走直连的那 12 个战斗号
        assertThat(routes.all().stream().filter(r -> r.domain().equals(MessageRoutes.BACKEND_UNSUPPORTED)))
                .hasSize(12).allSatisfy(r -> assertThat(r.directOnly()).as(r.method()).isTrue());
        // 反过来：后端表里的每个服务名契约里都真有、是客户端协议服务且不是玩家服务（拼错的、契约里已删掉的服务名不会悄悄留在表里）
        for (Map.Entry<String, String> backend : MessageRoutes.SERVICE_BACKENDS.entrySet()) {
            List<MessageMethod> methods = registry.all().stream()
                    .filter(m -> m.serviceName().equals(backend.getKey())).toList();
            assertThat(methods).as("契约里 %s 的方法", backend.getKey()).isNotEmpty()
                    .allSatisfy(m -> {
                        assertThat(m.clientService()).as(m.key() + " 是客户端协议服务").isTrue();
                        assertThat(m.playerService()).as(m.key() + " 不是玩家服务（否则走 scene，后端表这一行是死的）").isFalse();
                        assertThat(routes.clientRoute(m.messageId()).domain()).as(m.key()).isEqualTo(backend.getValue());
                    });
        }
        assertThat(MessageRoutes.SERVICE_BACKENDS.values()).as("一个服务一个后端域，没有两个服务挤在同一个域里")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrder(DubboGroups.LOGIN, DubboGroups.FRIEND, DubboGroups.CHAT, DubboGroups.TEAM,
                        DubboGroups.GUILD, DubboGroups.TRADE, DubboGroups.MATCH);
    }

    @Test
    void 后端表里没有的非玩家服务缺省落到unsupported_与契约里还剩哪个服务没接无关() {
        // 契约里的客户端服务已经全部接完（上一条），缺省分支本身在这里用一个契约里不存在的服务名钉住。
        MessageMethod login = method("ClientPlayerLogin", "Login");
        MessageMethod notPorted = new MessageMethod(login.messageId(), "NotPortedYetService", "Foo", login.method(),
                login.requestPrototype(), login.responsePrototype(), true, false);
        assertThat(MessageRoutes.SERVICE_BACKENDS).doesNotContainKey(notPorted.serviceName());
        assertThat(MessageRoutes.backendOf(notPorted)).isEqualTo(MessageRoutes.BACKEND_UNSUPPORTED);
        assertThat(MessageRoutes.directOnly(notPorted)).isFalse();
        // 对照：同一个方法挂在已接入的服务名下就不是 unsupported——上面的结果来自查表落空，不是别的条件
        assertThat(MessageRoutes.backendOf(login)).isEqualTo(ClientDispatcher.DOMAIN_LOGIN);
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
    void 匹配服务10个号都路由到match域_148与两个推送占位不回包_热关停键与基线同名_内部服务不在白名单() {
        // match-spec §1.4 / §9.2 / §15.3：MatchService 标了 OptionIsClientProtocolService、没标 OptionIsPlayerService，10 个号整体转给
        // xm-match（Dubbo group match）。148 CancelQueue 与两个推送占位 154 / 156 的应答类型是 Empty → tip 为 0 时 gate 不回包（§8.1、M4；
        // tip ≠ 0 照样回信封）；其余 7 个都带 in-band 的 error_message / error_code → 一律回包（全默认值的应答是 0 字节也要回）。
        List<String> noReply = List.of("CancelQueue", "NotifyChallengeInvite", "NotifyChallengeResult");
        List<String> replied = List.of("JoinQueue", "GetQueueStatus", "ChallengePlayer", "RespondChallenge", "WatchBattle",
                "ListWatchableBattles", "RequestBattleTicket");
        List<MessageMethod> match = registry.all().stream().filter(m -> m.serviceName().equals("MatchService")).toList();
        assertThat(match).as("契约里 MatchService 的方法").extracting(MessageMethod::methodName)
                .containsExactlyInAnyOrderElementsOf(Stream.concat(noReply.stream(), replied.stream()).toList());
        // 10 个号逐个钉住（message_id.txt；match-spec §1.4 的表）：发号漂移或增删方法时这里先失败
        assertThat(match).extracting(MessageMethod::messageId)
                .containsExactlyInAnyOrder(148, 151, 152, 153, 154, 156, 157, 163, 164, 179);
        assertThat(Map.of("CancelQueue", 148, "RespondChallenge", 151, "ChallengePlayer", 152, "GetQueueStatus", 153,
                "NotifyChallengeResult", 154, "NotifyChallengeInvite", 156, "JoinQueue", 157, "WatchBattle", 163,
                "ListWatchableBattles", 164, "RequestBattleTicket", 179))
                .allSatisfy((name, id) -> assertThat(registry.requireId("MatchService", name)).as(name).isEqualTo(id));
        for (MessageMethod method : match) {
            assertThat(method.clientService()).as(method.key()).isTrue();
            assertThat(method.playerService()).as(method.key() + " 不是玩家服务：走后端表，不走 scene").isFalse();
            MessageRoute route = routes.clientRoute(method.messageId());
            assertThat(route).as(method.key()).isNotNull();
            assertThat(route.domain()).as(method.key()).isEqualTo(DubboGroups.MATCH).isEqualTo("match");
            assertThat(route.hasResponse()).as(method.key()).isEqualTo(replied.contains(method.methodName()));
            assertThat(route.directOnly()).as(method.key() + " 经 gate 中继，不是只走直连").isFalse();
            assertThat(route.method()).isEqualTo("MatchService." + method.methodName());
            assertThat(route.gm()).as(method.key()).isFalse();
            // 热关停规则按 rpcPath 匹配：/match.MatchService/<Method>（proto package match），与基线路由表里这些号的 gRPC 全名
            // 逐字相同（mmorpg go/client_rpc_router/generated/pb/game/route_table.go 的 FullMethod）
            assertThat(route.rpcPath()).isEqualTo("/match.MatchService/" + method.methodName());
            assertThat(KillSwitch.matchKeys(route.rpcPath()))
                    .contains("match.MatchService/" + method.methodName(), "match.MatchService/*");
        }
        assertThat(routes.clientRoute(148).hasResponse()).as("148 CancelQueue 应答是 Empty").isFalse();
        assertThat(routes.clientRoute(157).hasResponse()).as("157 JoinQueue 应答是 JoinQueueResponse").isTrue();
        assertThat(routes.clientRoute(179).hasResponse()).as("179 应答消息定义在 battle 包，照样回包").isTrue();
        // match 域里只有这 10 个号：别的服务（尤其是战斗服务）没有被带进来
        assertThat(routes.all().stream().filter(r -> r.domain().equals(DubboGroups.MATCH)).map(MessageRoute::messageId))
                .containsExactly(148, 151, 152, 153, 154, 156, 157, 163, 164, 179);
        assertThat(MessageRoutes.SERVICE_BACKENDS).containsEntry("MatchService", DubboGroups.MATCH);

        // MatchInternal（帮会活动开战，match_internal.proto）是内部服务：刻意没标客户端协议服务，也不在后端表里。
        // 现行契约没有给它发消息号（message_id.txt 里没有 MatchInternal*）；以后发了号，它也必须不可路由（同 199 TradeAdmin.SeedListing）
        Descriptors.ServiceDescriptor internal = MatchMatchInternalOuterClass.getDescriptor().findServiceByName("MatchInternal");
        assertThat(internal).as("match_internal.proto 里的 MatchInternal 服务").isNotNull();
        assertThat(internal.getMethods()).extracting(Descriptors.MethodDescriptor::getName).contains("StartActivityBattle");
        assertThat(internal.getOptions().getAllFields().keySet()).as("MatchInternal 没有任何服务级 option（客户端协议 / 玩家服务都没标）")
                .extracting(Descriptors.FieldDescriptor::getName).doesNotContain("OptionIsClientProtocolService", "OptionIsPlayerService");
        Descriptors.ServiceDescriptor client = MatchMatchServiceOuterClass.getDescriptor().findServiceByName("MatchService");
        assertThat(client.getOptions().getAllFields().keySet()).as("对照：MatchService 标了客户端协议服务（上面那条断言读得到这个 option）")
                .extracting(Descriptors.FieldDescriptor::getName).contains("OptionIsClientProtocolService");
        assertThat(registry.all().stream().filter(m -> m.serviceName().equals("MatchInternal")))
                .allSatisfy(m -> {
                    assertThat(m.clientService()).as(m.key()).isFalse();
                    assertThat(routes.clientRoute(m.messageId())).as(m.key() + " 不可路由").isNull();
                });
        assertThat(MessageRoutes.SERVICE_BACKENDS).doesNotContainKey("MatchInternal");
    }

    @Test
    void 匹配服务10个号都不在限频表里_按缺省每秒3条() {
        // match-spec §1.4：MessageLimiter 表里没有任何一个 match 消息号，gate 按缺省每会话每号每秒 3 条、超频回 1008 并计非法包。
        // 客户端可见：179 的退避重试、robot 连发同号请求（间隔 ≥ 350 ms，§15.5）都按这个节奏。表是同步来的契约，这里读真表。
        MessageLimits limits = TableMessageLimits.load(Path.of("..", "config-data", "tables"));
        for (int id : new int[] {148, 151, 152, 153, 154, 156, 157, 163, 164, 179}) {
            assertThat(limits.limitOf(id)).as("消息号 %d", id).isEqualTo(MessageLimit.DEFAULT)
                    .isEqualTo(new MessageLimit(3, Duration.ofSeconds(1)));
        }
        // 对照：表不是空的（否则上面的断言对任何号都成立）
        assertThat(registry.all().stream().filter(MessageMethod::clientService).map(m -> limits.limitOf(m.messageId())))
                .as("限频表里至少有一个客户端号不是缺省值").anyMatch(limit -> !limit.equals(MessageLimit.DEFAULT));
    }

    @Test
    void 战斗服务12个号全部标directOnly_域仍是unsupported_永不进后端表() {
        // combat.md gate-battle-uplink-reject；battle-node-spec §3.7 / §13.7；scene-battle-spec §2.5 / §7.19（D12）：战斗上行只走
        // xm-battle 直连，大厅连接上发 BattleClientPlayer 的任何号（含 Notify 号）都由直连闸当场回 23 {1003}。
        // 摘掉 directOnly 或把它接进任何后端都会让这里失败。
        List<String> methods = List.of("NotifyTurnResult", "GetBattleState", "NotifyBattleStart", "NotifyBattleReconnect",
                "SubmitBattleAction", "NotifyBattleEnd", "NotifySpectateTurnResult", "NotifySpectateState", "SetAutoBattle",
                "StopWatchBattle", "NotifySpectateEnd", "NotifyBattleAssigned");
        List<MessageMethod> battle = registry.all().stream()
                .filter(m -> m.serviceName().equals("BattleClientPlayer")).toList();
        assertThat(battle).as("契约里 BattleClientPlayer 的方法").extracting(MessageMethod::methodName)
                .containsExactlyInAnyOrderElementsOf(methods);
        for (MessageMethod method : battle) {
            assertThat(method.clientService()).as(method.key()).isTrue();
            assertThat(method.playerService()).as(method.key()).isFalse();
            MessageRoute route = routes.clientRoute(method.messageId());
            assertThat(route).as(method.key()).isNotNull();
            assertThat(route.directOnly()).as(method.key() + " 只走直连").isTrue();
            assertThat(route.domain()).as(method.key()).isEqualTo(MessageRoutes.BACKEND_UNSUPPORTED);
            assertThat(route.method()).isEqualTo("BattleClientPlayer." + method.methodName());
            assertThat(route.gm()).as(method.key() + " 不是 GM 指令（否则会先被 GM 闸按非法包拒）").isFalse();
            // player_battle.proto 没有 proto package：热关停键是 BattleClientPlayer/<Method>（直连闸排在热关停之前，这些键对 gate 无效）
            assertThat(route.rpcPath()).isEqualTo("/BattleClientPlayer/" + method.methodName());
        }
        // 12 个号逐个钉住（scene-battle-spec §2.5；message_id.txt）：发号漂移或增删方法时这里先失败
        assertThat(battle).extracting(MessageMethod::messageId)
                .containsExactlyInAnyOrder(139, 140, 143, 144, 149, 150, 158, 161, 162, 165, 166, 177);
        assertThat(MessageRoutes.DIRECT_ONLY_SERVICES).containsExactly("BattleClientPlayer");
        assertThat(MessageRoutes.SERVICE_BACKENDS).doesNotContainKey("BattleClientPlayer");
        assertThat(MessageRoutes.SERVICE_BACKENDS.keySet()).as("只走直连的服务不能同时有后端")
                .doesNotContainAnyElementsOf(MessageRoutes.DIRECT_ONLY_SERVICES);
    }

    @Test
    void directOnly恰好是这12个号_其余客户端路由都不是_与基线节点类型回落的近似判据圈出同一组号() {
        List<MessageMethod> clients = registry.all().stream().filter(MessageMethod::clientService).toList();
        List<Integer> direct = clients.stream().map(m -> routes.clientRoute(m.messageId()))
                .filter(MessageRoute::directOnly).map(MessageRoute::messageId).toList();
        assertThat(direct).containsExactlyInAnyOrder(139, 140, 143, 144, 149, 150, 158, 161, 162, 165, 166, 177);
        // 登录 / scene / 好友 / 聚宝斋 / 匹配 各取一个：没有被误标（179 补签的请求 / 应答消息定义在 battle 包，照样经 gate 中继）
        for (String[] m : new String[][] {{"ClientPlayerLogin", "Login"}, {"SceneSkillClientPlayer", "ListSkills"},
                {"ClientPlayerFriend", "AddFriend"}, {"ClientPlayerJubaozhai", "BrowseListings"},
                {"MatchService", "JoinQueue"}, {"MatchService", "RequestBattleTicket"}}) {
            assertThat(routes.clientRoute(registry.requireId(m[0], m[1])).directOnly()).as(m[0] + "." + m[1]).isFalse();
        }
        // 基线 gate 的判据是 targetNodeType == BattleNodeService（client_message_processor.cpp:944）。targetNodeType 由 protogen 的
        // NodeServiceForCpp 三级回落派生（mmorpg protogen/internal/model.go:167-183，cpp/service_register_info.go:319）：
        //   ① 服务名 + NodeService 是 eNodeType 的枚举名 → ② proto package 驼峰化 + NodeService 是枚举名 → ③ proto 所在目录名。
        // 它不是由文件的 OptionFileDefaultNode 派生的。Java 按服务裸名判，且不许依赖 proto 目录（AGENTS.md §1），所以这里
        // ①② 照算，③ 拿文件的 OptionFileDefaultNode 当目录名的近似。先钉住 ①② 算得对（与基线生成物 rpc_event_registry.cpp 的
        // targetNodeType 对过：聚宝斋 TradeNodeService、匹配 MatchNodeService 都来自 package；战斗与登录落到 ③）：
        assertThat(baselineNodeByName(method("ClientPlayerJubaozhai", "BrowseListings"))).as("package trade").isEqualTo("Trade");
        assertThat(baselineNodeByName(method("MatchService", "CancelQueue"))).as("package match").isEqualTo("Match");
        assertThat(baselineNodeByName(method("BattleClientPlayer", "SubmitBattleAction"))).as("没有 package，基线靠目录 battle").isNull();
        assertThat(baselineNodeByName(method("ClientPlayerLogin", "Login"))).as("loginpb 不是节点名，基线靠目录 login").isNull();
        // ① 现行契约里没有客户端服务命中，用一个假想的服务名钉住：裸名就叫 Battle 的服务不管放在哪、写没写 option 都是战斗节点
        MessageMethod login = method("ClientPlayerLogin", "Login");
        MessageMethod namedBattle = new MessageMethod(login.messageId(), "Battle", "Foo", login.method(),
                login.requestPrototype(), login.responsePrototype(), true, false);
        assertThat(fileDefaultNode(namedBattle)).as("login.proto 没写 OptionFileDefaultNode").isNull();
        assertThat(baselineBattleNode(namedBattle)).as("BattleNodeService 是 eNodeType 的枚举名，① 命中").isTrue();
        // 两个判据必须圈出同一组号：mmorpg 以后新增一个服务名 / package 解析成 Battle、或者放在 NODE_BATTLE 文件里的客户端服务，
        // 这里先失败，提醒把它加进 DIRECT_ONLY_SERVICES。
        List<Integer> byBaseline = clients.stream().filter(MessageRoutesTest::baselineBattleNode)
                .map(MessageMethod::messageId).toList();
        assertThat(byBaseline).containsExactlyInAnyOrderElementsOf(direct);
        // 近似看不见的情形之一：①② 都不命中、文件又没写 OptionFileDefaultNode——基线对它只看目录名，这里读不到。
        // 把这类服务的清单钉死（现在是 login.proto / friend.proto / guild.proto 三个文件，基线分别判成 Login / Friend / Guild 节点）。
        // 剩下测不出的只有「option 与目录自相矛盾」（写了别的节点却放在 proto/battle/ 下，或反过来），那是 mmorpg 自己的错配。
        assertThat(clients.stream().filter(m -> baselineNodeByName(m) == null && fileDefaultNode(m) == null)
                .map(MessageMethod::serviceName).distinct())
                .as("基线对这些客户端服务的节点类型只能回落到 proto 目录名，Java 看不到目录。清单变了先去 mmorpg 确认新服务是不是放在 "
                        + "proto/battle/ 下（生成物 rpc_event_registry.cpp 里它的 targetNodeType 是不是 BattleNodeService）："
                        + "是就加进 MessageRoutes.DIRECT_ONLY_SERVICES，不是就补进这份清单")
                .containsExactlyInAnyOrder("ClientPlayerLogin", "ClientPlayerFriend", "GuildService");
    }

    @Test
    void all按消息号升序列出全部客户端路由_与逐号查到的一致_手写路由表缺省为空() {
        List<MessageRoute> all = routes.all();
        List<Integer> clientIds = registry.all().stream().filter(MessageMethod::clientService)
                .map(MessageMethod::messageId).sorted().toList();
        assertThat(all).extracting(MessageRoute::messageId).containsExactlyElementsOf(clientIds);
        assertThat(all).allSatisfy(route -> assertThat(routes.clientRoute(route.messageId())).isSameAs(route));
        assertThat(all.stream().filter(MessageRoute::directOnly)).hasSize(12);
        MessageRoutes handwritten = id -> new MessageRoute(id, "scene");
        assertThat(handwritten.all()).isEmpty();
    }

    @Test
    void 手写路由的缺省构造都不是directOnly() {
        assertThat(new MessageRoute(1, "scene").directOnly()).isFalse();
        assertThat(new MessageRoute(1, "scene", true).directOnly()).isFalse();
        assertThat(new MessageRoute(1, "scene", true, "S.M").directOnly()).isFalse();
        assertThat(new MessageRoute(1, "scene", true, "S.M", true).directOnly()).isFalse();
        assertThat(new MessageRoute(1, "scene", true, "S.M", false, "/p.S/M").directOnly()).isFalse();
        assertThat(new MessageRoute(1, "scene", true, "S.M", false, "/p.S/M", true).directOnly()).isTrue();
    }

    private static MessageMethod method(String serviceName, String methodName) {
        return registry.byId(registry.requireId(serviceName, methodName)).orElseThrow();
    }

    /** 方法所在 proto 文件的 {@code OptionFileDefaultNode}（按扩展字段名读，不引用定义它的生成类）；没写为 null。 */
    private static String fileDefaultNode(MessageMethod method) {
        for (Map.Entry<Descriptors.FieldDescriptor, Object> e
                : method.method().getFile().getOptions().getAllFields().entrySet()) {
            if (e.getKey().isExtension() && e.getKey().getName().equals("OptionFileDefaultNode")) {
                return ((Descriptors.EnumValueDescriptor) e.getValue()).getName();
            }
        }
        return null;
    }

    /**
     * 基线 protogen {@code NodeServiceForCpp} 三级回落的前两级：{@code 服务裸名 + "NodeService"} 是 {@code eNodeType} 的枚举名就取服务裸名；
     * 否则 proto package 驼峰化（按 {@code _ . -} 分词、各词首字母大写）后加 {@code "NodeService"} 命中就取它。
     * 都不命中返回 null：基线此时回落到 proto 所在目录名，Java 不许看目录。
     */
    private static String baselineNodeByName(MessageMethod method) {
        Set<String> nodeTypes = eNodeType.getDescriptor().getValues().stream()
                .map(Descriptors.EnumValueDescriptor::getName).collect(Collectors.toSet());
        if (nodeTypes.contains(method.serviceName() + "NodeService")) {
            return method.serviceName();
        }
        StringBuilder camel = new StringBuilder();
        for (String word : method.method().getFile().getPackage().split("[_.\\-]+")) {
            if (!word.isEmpty()) {
                camel.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
            }
        }
        return camel.length() > 0 && nodeTypes.contains(camel + "NodeService") ? camel.toString() : null;
    }

    /** 基线会不会把这个方法判成 {@code BattleNodeService}：前两级照算，第三级（目录名）以文件的 {@code OptionFileDefaultNode} 近似。 */
    private static boolean baselineBattleNode(MessageMethod method) {
        String byName = baselineNodeByName(method);
        return byName != null ? byName.equals("Battle") : "NODE_BATTLE".equals(fileDefaultNode(method));
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
