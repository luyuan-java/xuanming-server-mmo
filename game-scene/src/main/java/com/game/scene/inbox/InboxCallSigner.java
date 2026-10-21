package com.game.scene.inbox;

import com.game.api.inbox.InboxCallContract;
import com.game.common.player.SceneAdmissionAuthority;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.proto.player_inbox.AckPlayerInboxRequest;
import com.game.proto.player_inbox.FetchPlayerInboxRequest;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 对已登记原准入的完整请求签名；隔离密钥与时间，运输层不能用空原件发请求。 */
final class InboxCallSigner {
    private final String secret;
    private final LongSupplier clock;

    InboxCallSigner(String secret, LongSupplier clock) {
        // 复用唯一签名器的配置校验，不记录或改写原密钥。
        new ServiceCallSignatures(Map.of(InboxCallContract.CALLER, Objects.requireNonNull(secret, "scene内部密钥未配置")), clock);
        this.secret = secret;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    FetchPlayerInboxRequest sign(FetchPlayerInboxRequest request) {
        SceneAdmissionAuthority.original(request.getAdmission());
        if (request.getPlayerId() != request.getAdmission().getPlayerId()
                || request.getOwnerEpoch() != request.getAdmission().getOwnerEpoch())
            throw new SecurityException("拉取请求与原准入不匹配");
        return ServiceCallSignatures.sign(InboxCallContract.FETCH, request, InboxCallContract.CALLER, secret, clock.getAsLong());
    }

    AckPlayerInboxRequest sign(AckPlayerInboxRequest request) {
        SceneAdmissionAuthority.original(request.getAdmission());
        if (request.getPlayerId() != request.getAdmission().getPlayerId()
                || request.getOwnerEpoch() != request.getAdmission().getOwnerEpoch())
            throw new SecurityException("确认请求与原准入不匹配");
        return ServiceCallSignatures.sign(InboxCallContract.ACK, request, InboxCallContract.CALLER, secret, clock.getAsLong());
    }
}
