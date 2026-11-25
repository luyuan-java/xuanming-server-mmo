package com.game.scene.festivalbeast;

import com.game.proto.ClaimFestivalBeastRewardRequest;
import com.game.proto.GetFestivalBeastDetailRequest;
import com.game.proto.StartFestivalBeastChallengeRequest;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.SceneFeature;
import java.util.Objects;

/**
 * 瑞兽迎春限时活动客户端入口（{@code SceneActivityClientPlayer} 476–478）。
 *
 * <p>477/478 注册为 {@code READ_ONLY + ALLOW} 并在服务内部优先处理同 UUID 幂等重放与已领回执收敛，
 * 未命中的新写请求再按冻结/战斗闸返回 {@code kFestivalBeastFrozen} / {@code kFestivalBeastInBattle}。
 */
public final class FestivalBeastFeature implements SceneFeature {

    private static final String SERVICE = "SceneActivityClientPlayer";

    private final FestivalBeastService service;

    public FestivalBeastFeature(FestivalBeastService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetFestivalBeastDetail", GetFestivalBeastDetailRequest.class,
                FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> call.reply(service.getDetail(call.player(), request, service.nowMillis())));
        r.on(SERVICE, "StartFestivalBeastChallenge", StartFestivalBeastChallengeRequest.class,
                FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> call.reply(service.startChallenge(call.world(), call.player(), request, service.nowMillis())));
        r.on(SERVICE, "ClaimFestivalBeastReward", ClaimFestivalBeastRewardRequest.class,
                FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> call.reply(service.claimReward(call.world(), call.player(), request, service.nowMillis())));
    }
}
