package com.game.scene.rewardedad;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.PlayerState;
import com.game.proto.AdClientPlatform;
import com.game.proto.BeginRewardedAdSessionRequest;
import com.game.proto.BeginRewardedAdSessionResponse;
import com.game.proto.ClaimRewardedAdRequest;
import com.game.proto.ClaimRewardedAdResponse;
import com.game.proto.GetRewardedAdInfoRequest;
import com.game.proto.GetRewardedAdInfoResponse;
import com.game.proto.MessageContent;
import com.game.scene.battle.BattleFixture;
import com.game.scene.player.PlayerClientRequestLedger;
import com.game.scene.player.Wallet;
import com.game.scene.testing.Contracts;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import com.game.table.RewardedAdSlotTable;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 零拒审激励视频广告系统全链路验收（对齐 C++ {@code player_rewarded_ad_test.cpp} 与 {@code docs/design/rewarded-ad-system.md}）。
 */
class RewardedAdFeatureAndServiceTest {

    private static final String SERVICE = RewardedAdFeature.SERVICE;
    private static final long BASE_DAY_MS = 1_760_000_000_000L; // 2025-10-09 UTC

    private static RewardedAdSlotTable enabledSlot(int slotId, boolean requireSsv) {
        return RewardedAdSlotTable.newBuilder()
                .setId(slotId)
                .setEnabled(1)
                .setMinLevel(10)
                .setDailyLimit(2)
                .setCooldownSec(60)
                .setMinWatchSec(15)
                .setSessionTtlSec(300)
                .setCurrencyType(Wallet.BOUND_GOLD)
                .setRewardAmount(300L)
                .setRequireSsv(requireSsv ? 1 : 0)
                .setPlatformMask(3) // 微信 + 抖音小游戏
                .build();
    }

    @Test
    void 真实配表默认关闭且非法币种被failClosed拒载() throws Exception {
        BattleFixture f = new BattleFixture();
        assertThat(f.rewardedAdTables.allRowsValid()).isTrue();
        ScenePlayer player = f.enter(11, 1001);
        player.setLevel(15);

        GetRewardedAdInfoResponse info = call(f, player, "GetRewardedAdInfo",
                GetRewardedAdInfoRequest.newBuilder()
                        .setClientPlatform(AdClientPlatform.AD_CLIENT_PLATFORM_WECHAT_MINIGAME_VALUE)
                        .build(),
                GetRewardedAdInfoResponse.parser());
        assertThat(info.getErrorMessage().getId()).isZero();
        assertThat(info.getSlotsList()).singleElement().satisfies(s -> {
            assertThat(s.getSlotId()).isEqualTo(1);
            assertThat(s.getEnabled()).isFalse();
            assertThat(s.getCurrencyType()).isEqualTo(Wallet.BOUND_GOLD);
            assertThat(s.getRewardAmount()).isEqualTo(300L);
        });

        BeginRewardedAdSessionResponse begin = call(f, player, "BeginRewardedAdSession",
                BeginRewardedAdSessionRequest.newBuilder()
                        .setSlotId(1)
                        .setClientPlatform(AdClientPlatform.AD_CLIENT_PLATFORM_WECHAT_MINIGAME_VALUE)
                        .build(),
                BeginRewardedAdSessionResponse.parser());
        assertThat(begin.getErrorMessage().getId()).isEqualTo(RewardedAdService.DISABLED);

        // 误配流通银两 (0) 必须 fail-closed 拦截
        RewardedAdSlotTable badGoldSlot = enabledSlot(99, false).toBuilder()
                .setCurrencyType(Wallet.GOLD).build();
        RewardedAdTables badTables = new RewardedAdTables(List.of(badGoldSlot));
        assertThat(badTables.allRowsValid()).isFalse();
        assertThat(badTables.slot(99)).isNull();
    }

    @Test
    void 完整观看发绑定银两_冷却_日限_幂等重放_抗回拨与重连存盘() {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 2001);
        RewardedAdTables tables = new RewardedAdTables(List.of(enabledSlot(1, false)));
        RewardedAdService svc = new RewardedAdService(tables, f.currency, f.clock, f.sceneMetrics);

        // 1. 等级 < 10 与未开放平台被拒
        player.setLevel(9);
        assertThat(svc.beginSession(f.world, player,
                BeginRewardedAdSessionRequest.newBuilder().setSlotId(1)
                        .setClientPlatform(AdClientPlatform.AD_CLIENT_PLATFORM_WECHAT_MINIGAME_VALUE).build(),
                BASE_DAY_MS).getErrorMessage().getId())
                .isEqualTo(RewardedAdService.LEVEL_NOT_ENOUGH);

        player.setLevel(10);
        assertThat(svc.beginSession(f.world, player,
                BeginRewardedAdSessionRequest.newBuilder().setSlotId(1)
                        .setClientPlatform(AdClientPlatform.AD_CLIENT_PLATFORM_IOS_APP_VALUE).build(),
                BASE_DAY_MS).getErrorMessage().getId())
                .isEqualTo(RewardedAdService.PLATFORM_UNSUPPORTED);

        // 2. 签发会话 + 观看不足 15 秒拒绝 + 伪造 session 拒绝
        BeginRewardedAdSessionResponse s1 = svc.beginSession(f.world, player,
                BeginRewardedAdSessionRequest.newBuilder().setSlotId(1)
                        .setClientPlatform(AdClientPlatform.AD_CLIENT_PLATFORM_WECHAT_MINIGAME_VALUE).build(),
                BASE_DAY_MS);
        assertThat(s1.getErrorMessage().getId()).isZero();
        assertThat(s1.getAdSessionId()).isPositive();

        // 验证会话已落到 PlayerState（断线重连后仍可补领）
        PlayerState persistedMidWatch = WorldTestAccess.persistentState(player);
        assertThat(persistedMidWatch.getClientRequestLedger().getRewardedAd().getActiveSessionId())
                .isEqualTo(s1.getAdSessionId());

        assertThat(svc.claim(f.world, player, ClaimRewardedAdRequest.newBuilder()
                .setClientRequestId(5001L).setSlotId(1).setAdSessionId(s1.getAdSessionId()).build(),
                BASE_DAY_MS + 14_000L).getErrorMessage().getId())
                .isEqualTo(RewardedAdService.WATCH_TOO_SHORT);

        assertThat(svc.claim(f.world, player, ClaimRewardedAdRequest.newBuilder()
                .setClientRequestId(5002L).setSlotId(1).setAdSessionId(s1.getAdSessionId() + 1L).build(),
                BASE_DAY_MS + 20_000L).getErrorMessage().getId())
                .isEqualTo(RewardedAdService.SESSION_INVALID);

        // 3. 满 15 秒成功领奖：仅增加绑定银两，流通银两恒为 0
        ClaimRewardedAdRequest claim1 = ClaimRewardedAdRequest.newBuilder()
                .setClientRequestId(5003L).setSlotId(1).setAdSessionId(s1.getAdSessionId()).build();
        ClaimRewardedAdResponse c1 = svc.claim(f.world, player, claim1, BASE_DAY_MS + 20_000L);
        assertThat(c1.getErrorMessage().getId()).isZero();
        assertThat(c1.getReplayed()).isFalse();
        assertThat(c1.getCurrencyType()).isEqualTo(Wallet.BOUND_GOLD);
        assertThat(c1.getRewardAmount()).isEqualTo(300L);
        assertThat(c1.getCurrencyBalance()).isEqualTo(300L);
        assertThat(c1.getGoldBalance()).isZero();
        assertThat(c1.getSlot().getClaimedToday()).isEqualTo(1);
        assertThat(player.wallet().balance(Wallet.BOUND_GOLD)).isEqualTo(300L);
        assertThat(player.wallet().balance(Wallet.GOLD)).isZero();

        // 4. 幂等重放（即使在冷却中且会话已清零）返回成功 replayed=true；改参数回 1005
        ClaimRewardedAdResponse replay = svc.claim(f.world, player, claim1, BASE_DAY_MS + 21_000L);
        assertThat(replay.getErrorMessage().getId()).isZero();
        assertThat(replay.getReplayed()).isTrue();
        assertThat(replay.getCurrencyBalance()).isEqualTo(300L);
        assertThat(player.wallet().balance(Wallet.BOUND_GOLD)).isEqualTo(300L);

        assertThat(svc.claim(f.world, player, claim1.toBuilder().setAdSessionId(999L).build(),
                BASE_DAY_MS + 22_000L).getErrorMessage().getId())
                .isEqualTo(RewardedAdService.INVALID_PARAMETER);

        // 5. 60 秒冷却内开新会话拒 39005；满 60 秒可第 2 次领奖至 600 绑银
        assertThat(svc.beginSession(f.world, player,
                BeginRewardedAdSessionRequest.newBuilder().setSlotId(1)
                        .setClientPlatform(AdClientPlatform.AD_CLIENT_PLATFORM_DOUYIN_MINIGAME_VALUE).build(),
                BASE_DAY_MS + 79_000L).getErrorMessage().getId())
                .isEqualTo(RewardedAdService.COOLDOWN);

        long t2 = BASE_DAY_MS + 80_000L;
        BeginRewardedAdSessionResponse s2 = svc.beginSession(f.world, player,
                BeginRewardedAdSessionRequest.newBuilder().setSlotId(1)
                        .setClientPlatform(AdClientPlatform.AD_CLIENT_PLATFORM_DOUYIN_MINIGAME_VALUE).build(),
                t2);
        assertThat(s2.getErrorMessage().getId()).isZero();

        // 超过 300s TTL 过期拒 39007
        assertThat(svc.claim(f.world, player, ClaimRewardedAdRequest.newBuilder()
                .setClientRequestId(5004L).setSlotId(1).setAdSessionId(s2.getAdSessionId()).build(),
                t2 + 301_000L).getErrorMessage().getId())
                .isEqualTo(RewardedAdService.SESSION_EXPIRED);

        ClaimRewardedAdResponse c2 = svc.claim(f.world, player, ClaimRewardedAdRequest.newBuilder()
                .setClientRequestId(5005L).setSlotId(1).setAdSessionId(s2.getAdSessionId()).build(),
                t2 + 20_000L);
        assertThat(c2.getErrorMessage().getId()).isZero();
        assertThat(player.wallet().balance(Wallet.BOUND_GOLD)).isEqualTo(600L);
        assertThat(c2.getSlot().getClaimedToday()).isEqualTo(2);

        // 6. 今日已满 2/2 拒 39004；时钟回拨 1 天绝不清零；次日 05:00 后清零恢复
        assertThat(svc.beginSession(f.world, player,
                BeginRewardedAdSessionRequest.newBuilder().setSlotId(1)
                        .setClientPlatform(AdClientPlatform.AD_CLIENT_PLATFORM_WECHAT_MINIGAME_VALUE).build(),
                t2 + 90_000L).getErrorMessage().getId())
                .isEqualTo(RewardedAdService.DAILY_LIMIT_REACHED);

        assertThat(svc.beginSession(f.world, player,
                BeginRewardedAdSessionRequest.newBuilder().setSlotId(1)
                        .setClientPlatform(AdClientPlatform.AD_CLIENT_PLATFORM_WECHAT_MINIGAME_VALUE).build(),
                BASE_DAY_MS - RewardedAdRules.DAY_MS).getErrorMessage().getId())
                .isEqualTo(RewardedAdService.DAILY_LIMIT_REACHED);

        long nextDayMs = c2.getSlot().getNextResetMs();
        BeginRewardedAdSessionResponse s3 = svc.beginSession(f.world, player,
                BeginRewardedAdSessionRequest.newBuilder().setSlotId(1)
                        .setClientPlatform(AdClientPlatform.AD_CLIENT_PLATFORM_WECHAT_MINIGAME_VALUE).build(),
                nextDayMs);
        assertThat(s3.getErrorMessage().getId()).isZero();

        // 7. 持久化往返验证
        PlayerState saved = WorldTestAccess.persistentState(player);
        PlayerClientRequestLedger restored = PlayerClientRequestLedger.restore(saved.getClientRequestLedger());
        assertThat(restored.isValid()).isTrue();
        assertThat(restored.rewardedAd().getTotalClaimedLifetime()).isEqualTo(2L);
        assertThat(restored.rewardedAd().getActiveSessionId()).isEqualTo(s3.getAdSessionId());

        // 8. 货币封禁与冻结保护
        assertThat(f.currency.block(player, Wallet.BOUND_GOLD)).isZero();
        assertThat(svc.claim(f.world, player, ClaimRewardedAdRequest.newBuilder()
                .setClientRequestId(5006L).setSlotId(1).setAdSessionId(s3.getAdSessionId()).build(),
                nextDayMs + 20_000L).getErrorMessage().getId())
                .isEqualTo(RewardedAdService.CURRENCY_BLOCKED);
        assertThat(f.currency.unblock(player, Wallet.BOUND_GOLD)).isZero();

        WorldTestAccess.startFreezing(player);
        assertThat(svc.claim(f.world, player, ClaimRewardedAdRequest.newBuilder()
                .setClientRequestId(5007L).setSlotId(1).setAdSessionId(s3.getAdSessionId()).build(),
                nextDayMs + 20_000L).getErrorMessage().getId())
                .isEqualTo(RewardedAdService.FROZEN);
    }

    private static <T extends Message> T call(BattleFixture f, ScenePlayer player, String method,
                                              Message req, com.google.protobuf.Parser<T> parser)
            throws InvalidProtocolBufferException {
        int msgId = Contracts.REGISTRY.requireId(SERVICE, method);
        long reqId = f.request(player, msgId, req);
        MessageContent reply = f.pushes(player, msgId).stream()
                .filter(m -> m.getId() == reqId).findFirst().orElseThrow();
        return parser.parseFrom(reply.getSerializedMessage());
    }
}
