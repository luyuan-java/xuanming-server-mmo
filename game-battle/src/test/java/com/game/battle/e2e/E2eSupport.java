package com.game.battle.e2e;

import com.game.api.BattleNodeService;
import com.game.api.DubboGroups;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.BattleAdmission;
import com.game.api.proto.CreateBattleResult;
import com.game.battle.admin.BattleAdminAuthFilter;
import com.game.battle.admin.DevBattleController;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.dubbo.config.ReferenceConfig;

/** 端到端测试的公共件：Triple 调用方、dev 管理接口、快照、指标读数。 */
final class E2eSupport {

    /** 线上消息号（客户端契约，逐字节；message_id.txt 第 N+1 行是 N 号）。在这里写死就是钉住契约。 */
    static final int GET_BATTLE_STATE = 140;
    static final int SUBMIT_BATTLE_ACTION = 149;
    static final int SET_AUTO_BATTLE = 162;
    static final int STOP_WATCH_BATTLE = 165;
    static final int NOTIFY_TURN_RESULT = 139;
    static final int NOTIFY_BATTLE_START = 143;
    static final int NOTIFY_BATTLE_END = 150;
    static final int NOTIFY_SPECTATE_TURN_RESULT = 158;
    static final int NOTIFY_SPECTATE_STATE = 161;
    static final int NOTIFY_SPECTATE_END = 166;
    static final int NOTIFY_BATTLE_ASSIGNED = 177;

    /** tip（CommonErrorTip）。 */
    static final int TIP_SERVICE_UNAVAILABLE = 1003;
    static final int TIP_ENTITY_IS_NULL = 1004;
    static final int TIP_INVALID_PARAMETER = 1005;
    static final int TIP_RATE_LIMIT_EXCEEDED = 1008;
    static final int TIP_MESSAGE_SIZE_EXCEEDED = 1010;

    /** PVE 单人副本（Dungeon 1 = 怪物 1、2）。 */
    static final int MATCH_MODE_PVE = 4;
    static final int MATCH_MODE_PVP = 3;
    static final int DUNGEON_1 = 1;
    /** 回血药（正式表里能在战斗中用的道具）。 */
    static final int ITEM_HEAL = 10;

    private static final AtomicLong BATTLE_IDS = new AtomicLong(System.currentTimeMillis() * 1000);

    private E2eSupport() {
    }

    static long nextBattleId() {
        return BATTLE_IDS.incrementAndGet();
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- Triple 调用方（同 6.4 的 match：按节点直连、retries = 0）

    static BattleNodeService tripleClient(IsolatedDubboModule model, int rpcPort) {
        ReferenceConfig<BattleNodeService> reference = new ReferenceConfig<>(model.module());
        reference.setInterface(BattleNodeService.class);
        reference.setGroup(DubboGroups.BATTLE_NODE);
        reference.setUrl("tri://127.0.0.1:" + rpcPort);
        reference.setRetries(0);
        reference.setCheck(false);
        reference.setTimeout(5000);
        return reference.get();
    }

    /** 经 Triple 建房，断言受理且无业务错误。 */
    static CreateBattleResponse createAdmitted(BattleNodeService battle, CreateBattleRequest request) throws Exception {
        CreateBattleResult result = battle.createBattle(request).get(10, TimeUnit.SECONDS);
        return admitted(result);
    }

    static CreateBattleResponse admitted(CreateBattleResult result) throws InvalidProtocolBufferException {
        if (result.getAdmission() != BattleAdmission.BATTLE_ADMISSION_ADMITTED) {
            throw new AssertionError("建房没有被受理: " + result.getAdmission() + " reason=" + result.getReason());
        }
        CreateBattleResponse response = CreateBattleResponse.parseFrom(result.getResponse());
        if (response.hasErrorMessage()) {
            throw new AssertionError("建房业务失败: " + response.getErrorMessage());
        }
        return response;
    }

    static BattleAssignedS2C reissue(BattleNodeService battle, long battleId, long playerId) throws Exception {
        IssueBattleTicketResponse response = battle.issueBattleTicket(IssueBattleTicketRequest.newBuilder()
                .setBattleId(battleId).setPlayerId(playerId).build()).get(10, TimeUnit.SECONDS);
        if (response.hasErrorMessage()) {
            throw new AssertionError("补签失败: " + response.getErrorMessage());
        }
        return response.getAssignment();
    }

    // ---------------------------------------------------------------- dev 管理接口

    static HttpResponse<byte[]> admin(int managementPort, String token, String path, byte[] body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", DevBattleController.CONTENT_TYPE)
                .header(BattleAdminAuthFilter.TOKEN_HEADER, token)
                .header(BattleAdminAuthFilter.OPERATOR_HEADER, "battle-e2e")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    // ---------------------------------------------------------------- 快照

    static BattleRouting routing(GatePushInbox gate, int sessionId) {
        return BattleRouting.newBuilder()
                .setSessionId(sessionId)
                .setGateNodeId(gate.gateNodeId)
                .setGateInstanceId(gate.gateInstanceId)
                .setSceneNodeId(1)
                .setSceneInstanceId("e2e-scene")
                .setZoneId(gate.zoneId)
                .build();
    }

    /** PVE 打手：一刀秒怪、血厚到怪打不死，带 3 瓶回血药（self_items 用）。 */
    static BattlePlayerSnapshot hero(long playerId, BattleRouting routing) {
        return BattlePlayerSnapshot.newBuilder()
                .setPlayerId(playerId)
                .setPlayerName("e2e-hero-" + playerId)
                .setLevel(10)
                .setTeamIndex(0)
                .setMaxHealth(1_000_000)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(1_000_000).setStrength(1_000_000).setSpeed(1000))
                .setPhysicalAttack(1_000_000)
                .addItems(BattleItemEntry.newBuilder().setItemTableId(ITEM_HEAL).setCount(3))
                .setRouting(routing)
                .build();
    }

    /** PVP 沙包：血极厚、攻击极低，几十回合也分不出胜负。 */
    static BattlePlayerSnapshot tank(long playerId, int team, BattleRouting routing) {
        return BattlePlayerSnapshot.newBuilder()
                .setPlayerId(playerId)
                .setPlayerName("e2e-tank-" + playerId)
                .setLevel(10)
                .setTeamIndex(team)
                .setMaxHealth(100_000_000)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(100_000_000).setStrength(1).setSpeed(100 + team))
                .setRouting(routing)
                .build();
    }

    static CreateBattleRequest pve(long battleId, long deadlineMs, BattlePlayerSnapshot hero) {
        return CreateBattleRequest.newBuilder()
                .setBattleId(battleId)
                .setBattleConfigId(DUNGEON_1)
                .setMatchMode(MATCH_MODE_PVE)
                .setSeed(20261005)
                .setDeadlineMs(deadlineMs)
                .addPlayers(hero)
                .build();
    }

    static CreateBattleRequest pvp(long battleId, long deadlineMs, BattlePlayerSnapshot a, BattlePlayerSnapshot b) {
        return CreateBattleRequest.newBuilder()
                .setBattleId(battleId)
                .setMatchMode(MATCH_MODE_PVP)
                .setSeed(7)
                .setDeadlineMs(deadlineMs)
                .addPlayers(a)
                .addPlayers(b)
                .build();
    }

    // ---------------------------------------------------------------- 指标

    /** 名字为 {@code name}、带全部给定标签的计数器之和（没有就是 0）。 */
    static double count(MeterRegistry meters, String name, String... tags) {
        return meters.find(name).tags(tags).counters().stream().mapToDouble(Counter::count).sum();
    }
}
