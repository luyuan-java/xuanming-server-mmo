package com.game.match.spectate;

import com.game.match.proto.BattlePlacement;
import com.game.proto.AddObserverRequest;
import com.game.proto.BattleRouting;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.RemoveObserverResponse;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 本地观战的一份不可变原件：固定原地址、实例、完整 Add；不从后来改写的落点拼凑取消请求。 */
public record LocalObserverOperation(BattlePlacement placement, AddObserverRequest add) {
    public static final long ADMISSION_MS = 25_000;
    private static final String PREFIX = "observer-v1:";
    private static final int MAX_MARK_CHARS = 65_536;

    public LocalObserverOperation {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(add, "add");
        UUID operation;
        try { operation = UUID.fromString(add.getSpectatorOperationId()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("观战原件缺少规范 UUID"); }
        if (!operation.toString().equals(add.getSpectatorOperationId()) || operation.equals(new UUID(0, 0))
                || placement.getBattleId() == 0 || placement.getBattleId() != add.getBattleId()
                || placement.getBattleNodeId() == 0 || placement.getRpcHost().isBlank()
                || placement.getRpcPort() <= 0 || placement.getRpcPort() > 65535
                || placement.getBattleInstanceId().isBlank() || !placement.getBattleInstanceId().equals(add.getTargetInstanceId())
                || add.getObserverPlayerId() == 0 || add.getAdmitBeforeMs() <= 0 || !add.hasRouting()
                || add.getRouting().getGateNodeId() == 0 || add.getRouting().getSessionId() == 0
                || add.getRouting().getGateInstanceId().isBlank() || add.getRouting().getSceneNodeId() != 0) {
            throw new IllegalArgumentException("观战原件身份、固定落点或观众路由不完整");
        }
    }

    public static LocalObserverOperation create(BattlePlacement placement, long player, BattleRouting routing,
                                                String publicName, String operation, long nowMs) {
        if (nowMs <= 0 || nowMs > Long.MAX_VALUE - ADMISSION_MS) throw new IllegalArgumentException("观战准入时钟非法");
        return new LocalObserverOperation(placement, AddObserverRequest.newBuilder().setBattleId(placement.getBattleId())
                .setObserverPlayerId(player).setRouting(routing).setObserverName(publicName)
                .setSpectatorOperationId(operation).setAdmitBeforeMs(nowMs + ADMISSION_MS)
                .setTargetInstanceId(placement.getBattleInstanceId()).build());
    }

    public long playerId() { return add.getObserverPlayerId(); }
    public long battleId() { return add.getBattleId(); }
    public RemoveObserverRequest remove(String reason) {
        return RemoveObserverRequest.newBuilder().setBattleId(battleId()).setObserverPlayerId(playerId())
                .setSpectatorOperationId(add.getSpectatorOperationId()).setAdmitBeforeMs(add.getAdmitBeforeMs())
                .setTargetInstanceId(add.getTargetInstanceId()).setReason(Objects.requireNonNull(reason)).build();
    }
    public boolean confirms(RemoveObserverResponse response) {
        return response != null && response.getCancelled() && response.getSpectatorOperationId().equals(add.getSpectatorOperationId())
                && response.getTargetInstanceId().equals(add.getTargetInstanceId());
    }
    public String mark() {
        String value = PREFIX + Base64.getEncoder().encodeToString(placement.toByteArray()) + ":"
                + Base64.getEncoder().encodeToString(add.toByteArray());
        if (value.length() > MAX_MARK_CHARS) throw new IllegalArgumentException("观战原件超过标记长度上限");
        return value;
    }
    public static Optional<LocalObserverOperation> decode(String value) {
        if (value == null || !value.startsWith(PREFIX) || value.length() > MAX_MARK_CHARS) return Optional.empty();
        int split = value.indexOf(':', PREFIX.length());
        if (split < 0 || split != value.lastIndexOf(':')) return Optional.empty();
        try {
            var operation = new LocalObserverOperation(BattlePlacement.parseFrom(Base64.getDecoder().decode(value.substring(PREFIX.length(), split))),
                    AddObserverRequest.parseFrom(Base64.getDecoder().decode(value.substring(split + 1))));
            return operation.mark().equals(value) ? Optional.of(operation) : Optional.empty();
        } catch (IllegalArgumentException | com.google.protobuf.InvalidProtocolBufferException invalid) {
            return Optional.empty();
        }
    }

    /** 未知版本或损坏的版本化原件均须保留，不能当成已失效的旧 nonce 标记删除。 */
    public static boolean isVersionedMark(String value) {
        return value != null && value.startsWith("observer-");
    }
}
