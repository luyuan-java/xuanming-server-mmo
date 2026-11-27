package com.game.match.spectate;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethodHandler.Reply;
import com.game.match.dispatch.MatchMethods;
import com.game.match.placement.PlacementStore;
import com.game.match.proto.BattlePlacement;
import com.game.proto.TipInfoMessage;
import com.game.proto.content_guard.CheckTextResponse;
import com.game.proto.content_guard.TextScene;
import com.game.proto.content_guard.TextVerdict;
import com.game.proto.match.BattleSpectateChatRole;
import com.game.proto.match.PullBattleSpectateChatRequest;
import com.game.proto.match.PullBattleSpectateChatResponse;
import com.game.proto.match.SendBattleSpectateChatRequest;
import com.game.proto.match.SendBattleSpectateChatResponse;
import com.game.table.CommonErrorTip;
import com.game.table.MatchErrorTip;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * 局外人观战公开交流室业务服务（587 SendBattleSpectateChat / 588 PullBattleSpectateChat，与 Go {@code spectate_chat_logic.go} 同构）。
 *
 * <p>核心防作弊与合规保障：
 * <ul>
 *   <li>权威战局真源：每次发言/拉取均核验 {@link PlacementStore} 中冻结的 {@code spectate_visibility} 与 {@code spectate_chat_enabled}；</li>
 *   <li>全房单一公开频道：参战双方与局外观众共享同一条消息流，按权威 {@code player_ids} 自动标注角色；</li>
 *   <li>UGC 内容安全 fail-closed：自由文本必经 {@link TextGuard}（{@code TEXT_SCENE_CHAT_WORLD}），审核不可用一律拒收（16078）。</li>
 * </ul>
 */
public final class SpectateChatService {

    /** 内容安全审核端口（默认调用 {@code ContentGuardService.checkText}；null 仅限非 PROD 单测场景）。 */
    @FunctionalInterface
    public interface TextGuard {
        CheckTextResponse check(TextScene scene, long playerId, String text);
    }

    private static final int MAX_SENDER_NAME_CODEPOINTS = 16;

    private final PlacementStore placements;
    private final SpectateChatHub hub;
    private final TextGuard textGuard;
    private final BiFunction<Long, Deadline, String> publicNames;
    private final LongSupplier clockMs;

    public SpectateChatService(PlacementStore placements,
                               SpectateChatHub hub,
                               TextGuard textGuard,
                               BiFunction<Long, Deadline, String> publicNames,
                               LongSupplier clockMs) {
        this.placements = Objects.requireNonNull(placements, "placements");
        this.hub = Objects.requireNonNull(hub, "hub");
        this.textGuard = textGuard;
        this.publicNames = publicNames == null ? (p, d) -> "" : publicNames;
        this.clockMs = clockMs == null ? System::currentTimeMillis : clockMs;
    }

    public SendBattleSpectateChatResponse send(long playerId, SendBattleSpectateChatRequest req, Deadline deadline) {
        long nowMs = clockMs.getAsLong();
        if (playerId == 0L || req == null || req.getBattleId() == 0L) {
            return SendBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(CommonErrorTip.common_error.kInvalidParameter_VALUE))
                    .setServerTimeMs(nowMs)
                    .build();
        }
        String sanitized = sanitizeContent(req.getContent());
        if (sanitized.isEmpty() || sanitized.getBytes(StandardCharsets.UTF_8).length > hub.config().maxContentBytes()) {
            return SendBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(CommonErrorTip.common_error.kInvalidParameter_VALUE))
                    .setBattleId(req.getBattleId())
                    .setServerTimeMs(nowMs)
                    .build();
        }

        long battleId = req.getBattleId();
        PlacementStore.Read read = placements.read(battleId, effectiveDeadline(deadline));
        if (read instanceof PlacementStore.Read.Failed) {
            return SendBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(MatchErrorTip.match_error.kMatchInternal_VALUE))
                    .setBattleId(battleId)
                    .setServerTimeMs(nowMs)
                    .build();
        }
        if (read instanceof PlacementStore.Read.Absent) {
            SpectateChatHub.PullResult probe = hub.pull(battleId, playerId, 0L, 1, nowMs);
            int code = probe.roomExists()
                    ? MatchErrorTip.match_error.kMatchSpectateChatEnded_VALUE
                    : MatchErrorTip.match_error.kMatchBattleNotWatchable_VALUE;
            return SendBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(code))
                    .setBattleId(battleId)
                    .setBattleEnded(true)
                    .setServerTimeMs(nowMs)
                    .build();
        }
        BattlePlacement placement = ((PlacementStore.Read.Found) read).placement();
        if (!SpectateVisibilityPolicy.isPublic(placement)) {
            return SendBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(MatchErrorTip.match_error.kMatchBattleNotWatchable_VALUE))
                    .setBattleId(battleId)
                    .setServerTimeMs(nowMs)
                    .build();
        }
        if (!placement.getSpectateChatEnabled()) {
            return SendBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(MatchErrorTip.match_error.kMatchSpectateChatDisabled_VALUE))
                    .setBattleId(battleId)
                    .setEnabled(false)
                    .setServerTimeMs(nowMs)
                    .build();
        }

        // UGC 内容安全审核（fail-closed）
        String moderated = sanitized;
        if (textGuard != null) {
            CheckTextResponse verdict;
            try {
                verdict = textGuard.check(TextScene.TEXT_SCENE_CHAT_WORLD, playerId, sanitized);
            } catch (RuntimeException e) {
                return SendBattleSpectateChatResponse.newBuilder()
                        .setErrorMessage(tip(MatchErrorTip.match_error.kMatchSpectateChatCheckUnavailable_VALUE))
                        .setBattleId(battleId)
                        .setEnabled(true)
                        .setServerTimeMs(nowMs)
                        .build();
            }
            if (verdict == null) {
                return SendBattleSpectateChatResponse.newBuilder()
                        .setErrorMessage(tip(MatchErrorTip.match_error.kMatchSpectateChatCheckUnavailable_VALUE))
                        .setBattleId(battleId)
                        .setEnabled(true)
                        .setServerTimeMs(nowMs)
                        .build();
            }
            if (verdict.getVerdict() == TextVerdict.TEXT_VERDICT_MASKED) {
                String masked = sanitizeContent(verdict.getMaskedText());
                if (masked.isEmpty()) {
                    return SendBattleSpectateChatResponse.newBuilder()
                            .setErrorMessage(tip(MatchErrorTip.match_error.kMatchSpectateChatContentRejected_VALUE))
                            .setBattleId(battleId)
                            .setEnabled(true)
                            .setServerTimeMs(nowMs)
                            .build();
                }
                moderated = masked;
            } else if (verdict.getVerdict() != TextVerdict.TEXT_VERDICT_PASS) {
                return SendBattleSpectateChatResponse.newBuilder()
                        .setErrorMessage(tip(MatchErrorTip.match_error.kMatchSpectateChatContentRejected_VALUE))
                        .setBattleId(battleId)
                        .setEnabled(true)
                        .setServerTimeMs(nowMs)
                        .build();
            }
        }

        int participantIdx = indexOfParticipant(placement, playerId);
        BattleSpectateChatRole role = participantIdx >= 0
                ? BattleSpectateChatRole.BATTLE_SPECTATE_CHAT_ROLE_PARTICIPANT
                : BattleSpectateChatRole.BATTLE_SPECTATE_CHAT_ROLE_SPECTATOR;
        String senderName = resolveSenderName(placement, participantIdx, playerId, role, effectiveDeadline(deadline));

        SpectateChatHub.SendResult sent = hub.send(battleId, playerId, senderName, role, moderated, nowMs);
        return switch (sent.status()) {
            case ENDED -> SendBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(MatchErrorTip.match_error.kMatchSpectateChatEnded_VALUE))
                    .setBattleId(battleId)
                    .setEnabled(true)
                    .setBattleEnded(true)
                    .setServerTimeMs(sent.serverTimeMs())
                    .build();
            case COOLDOWN -> SendBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(MatchErrorTip.match_error.kMatchSpectateChatCooldown_VALUE))
                    .setBattleId(battleId)
                    .setEnabled(true)
                    .setCooldownUntilMs(sent.cooldownUntilMs())
                    .setServerTimeMs(sent.serverTimeMs())
                    .build();
            case ROOM_BUSY -> SendBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(MatchErrorTip.match_error.kMatchSpectateChatRoomBusy_VALUE))
                    .setBattleId(battleId)
                    .setEnabled(true)
                    .setServerTimeMs(sent.serverTimeMs())
                    .build();
            case OK -> SendBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setBattleId(battleId)
                    .setEnabled(true)
                    .setMessage(sent.message())
                    .setCooldownUntilMs(sent.cooldownUntilMs())
                    .setServerTimeMs(sent.serverTimeMs())
                    .build();
        };
    }

    public PullBattleSpectateChatResponse pull(long playerId, PullBattleSpectateChatRequest req, Deadline deadline) {
        long nowMs = clockMs.getAsLong();
        if (playerId == 0L || req == null || req.getBattleId() == 0L) {
            return PullBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(CommonErrorTip.common_error.kInvalidParameter_VALUE))
                    .setServerTimeMs(nowMs)
                    .build();
        }
        long battleId = req.getBattleId();
        PlacementStore.Read read = placements.read(battleId, effectiveDeadline(deadline));
        if (read instanceof PlacementStore.Read.Failed) {
            return PullBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(MatchErrorTip.match_error.kMatchInternal_VALUE))
                    .setBattleId(battleId)
                    .setServerTimeMs(nowMs)
                    .build();
        }
        if (read instanceof PlacementStore.Read.Absent) {
            SpectateChatHub.PullResult hist = hub.pull(battleId, playerId, req.getAfterSeq(), req.getLimit(), nowMs);
            if (!hist.roomExists()) {
                return PullBattleSpectateChatResponse.newBuilder()
                        .setErrorMessage(tip(MatchErrorTip.match_error.kMatchBattleNotWatchable_VALUE))
                        .setBattleId(battleId)
                        .setBattleEnded(true)
                        .setServerTimeMs(nowMs)
                        .build();
            }
            return PullBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setBattleId(battleId)
                    .setEnabled(true)
                    .setBattleEnded(true)
                    .setLatestSeq(hist.latestSeq())
                    .setCooldownUntilMs(hist.cooldownUntilMs())
                    .setServerTimeMs(hist.serverTimeMs())
                    .addAllMessages(hist.messages())
                    .build();
        }
        BattlePlacement placement = ((PlacementStore.Read.Found) read).placement();
        if (!SpectateVisibilityPolicy.isPublic(placement)) {
            return PullBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(MatchErrorTip.match_error.kMatchBattleNotWatchable_VALUE))
                    .setBattleId(battleId)
                    .setServerTimeMs(nowMs)
                    .build();
        }
        if (!placement.getSpectateChatEnabled()) {
            return PullBattleSpectateChatResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setBattleId(battleId)
                    .setEnabled(false)
                    .setServerTimeMs(nowMs)
                    .build();
        }
        SpectateChatHub.PullResult pulled = hub.pull(battleId, playerId, req.getAfterSeq(), req.getLimit(), nowMs);
        return PullBattleSpectateChatResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setBattleId(battleId)
                .setEnabled(true)
                .setBattleEnded(pulled.battleEnded())
                .setLatestSeq(pulled.latestSeq())
                .setCooldownUntilMs(pulled.cooldownUntilMs())
                .setServerTimeMs(pulled.serverTimeMs())
                .addAllMessages(pulled.messages())
                .build();
    }

    public List<MatchMethodHandler> clientHandlers() {
        return List.of(
                new MatchMethodHandler() {
                    @Override
                    public String method() {
                        return MatchMethods.SEND_BATTLE_SPECTATE_CHAT;
                    }

                    @Override
                    public Reply handle(SessionContext session, ByteString body, Deadline deadline)
                            throws InvalidProtocolBufferException {
                        SendBattleSpectateChatRequest req = SendBattleSpectateChatRequest.parseFrom(body);
                        return Reply.body(send(session.getPlayerId(), req, deadline));
                    }

                    @Override
                    public Reply onOverload() {
                        return Reply.body(SendBattleSpectateChatResponse.newBuilder()
                                .setErrorMessage(tip(MatchErrorTip.match_error.kMatchInternal_VALUE))
                                .build());
                    }
                },
                new MatchMethodHandler() {
                    @Override
                    public String method() {
                        return MatchMethods.PULL_BATTLE_SPECTATE_CHAT;
                    }

                    @Override
                    public Reply handle(SessionContext session, ByteString body, Deadline deadline)
                            throws InvalidProtocolBufferException {
                        PullBattleSpectateChatRequest req = PullBattleSpectateChatRequest.parseFrom(body);
                        return Reply.body(pull(session.getPlayerId(), req, deadline));
                    }

                    @Override
                    public Reply onOverload() {
                        return Reply.body(PullBattleSpectateChatResponse.newBuilder()
                                .setErrorMessage(tip(MatchErrorTip.match_error.kMatchInternal_VALUE))
                                .build());
                    }
                });
    }

    private String resolveSenderName(BattlePlacement placement, int participantIdx, long playerId,
                                     BattleSpectateChatRole role, Deadline deadline) {
        String raw = "";
        if (participantIdx >= 0 && participantIdx < placement.getPlayerNamesCount()) {
            raw = placement.getPlayerNames(participantIdx);
        } else {
            try {
                String lookedUp = publicNames.apply(playerId, deadline);
                if (lookedUp != null) {
                    raw = lookedUp;
                }
            } catch (RuntimeException ignored) {
                raw = "";
            }
        }
        String sanitized = sanitizeSenderName(raw);
        if (!sanitized.isEmpty()) {
            return sanitized;
        }
        return role == BattleSpectateChatRole.BATTLE_SPECTATE_CHAT_ROLE_PARTICIPANT ? "参战道友" : "观战道友";
    }

    private static int indexOfParticipant(BattlePlacement placement, long playerId) {
        int count = placement.getPlayerIdsCount();
        for (int i = 0; i < count; i++) {
            if (placement.getPlayerIds(i) == playerId) {
                return i;
            }
        }
        return -1;
    }

    private static Deadline effectiveDeadline(Deadline deadline) {
        return deadline != null ? deadline : Deadline.after(2_000L);
    }

    private static TipInfoMessage tip(int id) {
        return TipInfoMessage.newBuilder().setId(id).build();
    }

    private static String sanitizeContent(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp == 0x7F) {
                sb.append(' ');
            } else {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString().strip();
    }

    private static String sanitizeSenderName(String raw) {
        String cleaned = sanitizeContent(raw);
        if (cleaned.isEmpty()) {
            return "";
        }
        int cp = cleaned.codePointCount(0, cleaned.length());
        if (cp <= MAX_SENDER_NAME_CODEPOINTS) {
            return cleaned;
        }
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_SENDER_NAME_CODEPOINTS));
    }
}
