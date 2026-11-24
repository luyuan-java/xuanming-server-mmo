package com.game.gm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.api.MailAdminService;
import com.game.api.MatchInternalService;
import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerHomeZones;
import com.game.common.player.PlayerProfiles;
import com.game.common.player.PlayerProfiles.Profile;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.proto.PlayerLocation;
import com.game.discovery.proto.PlayerPresence;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.GmBlockCurrencyRequest;
import com.game.proto.GmDeductCurrencyRequest;
import com.game.proto.GmGrantItemRequest;
import com.game.proto.GmGrantPetRequest;
import com.game.proto.GmSetPlayerLevelRequest;
import com.game.proto.GmUnblockCurrencyRequest;
import com.game.proto.mail.ImportMailRecipientsRequest;
import com.game.proto.mail.ImportMailRecipientsResponse;
import com.game.proto.mail.MailAttachments;
import com.game.proto.mail.MailCurrency;
import com.game.proto.mail.MailItem;
import com.game.proto.mail.SealMailCampaignRequest;
import com.game.proto.mail.SendMailRequest;
import com.game.proto.mail.SendMailResponse;
import com.game.proto.match.BattleScriptReviewReject;
import com.game.proto.match.ListPendingBattleScriptsRequest;
import com.game.proto.match.ListPendingBattleScriptsResponse;
import com.game.proto.match.PendingBattleScript;
import com.game.proto.match.ReviewBattleScriptRequest;
import com.game.proto.match.ReviewBattleScriptResponse;
import com.game.proto.scene_node.GmExecutePlayerCommandRequest;
import com.game.proto.scene_node.GmExecutePlayerCommandResponse;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 原生 Java 服务适配器：玩家只读查 {@link PlayerProfiles}/{@link PlayerHomeZones} 与 Redis 目录，
 * 发件经 {@link MailAdminService}，战斗剧本审核经 {@link MatchInternalService}，
 * 在线玩家 GM 命令按 {@code gm-player-command-v1} 签名后交给场景执行器。
 */
public final class DubboGmGamePort implements GmGamePort {

    public static final String PLAYER_COMMAND_METHOD = "/scene_node.SceneNodeGrpc/GmExecutePlayerCommand";
    private static final BigInteger MAX_UINT64 = new BigInteger("18446744073709551615");
    private static final Set<String> PLAYER_CMD_FIELDS = Set.of(
            "operation", "arguments", "requestId", "playerId", "operator", "reason", "issuedAtMs");

    @FunctionalInterface
    public interface PlayerCommandExecutor {
        CompletableFuture<GmExecutePlayerCommandResponse> execute(int targetNodeId, GmExecutePlayerCommandRequest signedRequest);
    }

    private final ObjectMapper mapper;
    private final PlayerProfiles profiles;
    private final PlayerHomeZones homeZones;
    private final PlayerPresenceDirectory presence;
    private final PlayerLocationDirectory locations;
    private final MailAdminService mailAdmin;
    private final String mailAdminSecret;
    private final MatchInternalService matchInternal;
    private final String matchOpsSecret;
    private final PlayerCommandExecutor playerExecutor;
    private final String playerCommandSecret;
    private final long budgetMillis;

    public DubboGmGamePort(ObjectMapper mapper,
                           PlayerProfiles profiles,
                           PlayerHomeZones homeZones,
                           PlayerPresenceDirectory presence,
                           PlayerLocationDirectory locations,
                           MailAdminService mailAdmin,
                           String mailAdminSecret,
                           MatchInternalService matchInternal,
                           String matchOpsSecret,
                           PlayerCommandExecutor playerExecutor,
                           String playerCommandSecret,
                           long budgetMillis) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.profiles = profiles;
        this.homeZones = homeZones;
        this.presence = presence;
        this.locations = locations;
        this.mailAdmin = mailAdmin;
        this.mailAdminSecret = mailAdminSecret == null ? "" : mailAdminSecret.strip();
        this.matchInternal = matchInternal;
        this.matchOpsSecret = matchOpsSecret == null ? "" : matchOpsSecret.strip();
        this.playerExecutor = playerExecutor;
        this.playerCommandSecret = playerCommandSecret == null ? "" : playerCommandSecret.strip();
        this.budgetMillis = Math.max(500L, Math.min(10_000L, budgetMillis));
    }

    @Override
    public boolean mailEnabled() {
        return mailAdmin != null && mailAdminSecret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    @Override
    public boolean playerCommandsEnabled() {
        return playerExecutor != null && presence != null && locations != null
                && playerCommandSecret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    @Override
    public boolean battleScriptReviewEnabled() {
        return matchInternal != null && matchOpsSecret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    @Override
    public ArrayNode currencyTypes() {
        return GmGamePort.defaultCurrencyTypes(mapper);
    }

    @Override
    public boolean supportsCurrency(int currencyType) {
        return GmGamePort.isKnownCurrency(currencyType);
    }

    @Override
    public ObjectNode player(String playerIdText) {
        long pid = parsePositiveUint64(playerIdText, "玩家 ID");
        if (profiles == null) {
            throw new CallFailure("NOT_CONFIGURED", "玩家资料服务尚未配置", false, 0);
        }
        Deadline deadline = Deadline.after(budgetMillis);
        Map<Long, Profile> loaded;
        try {
            loaded = profiles.loadStrict(List.of(pid), deadline);
        } catch (RuntimeException e) {
            throw new CallFailure("UNAVAILABLE", "玩家资料服务不可用", false, 0);
        }
        Profile profile = loaded.get(pid);
        ObjectNode result = mapper.createObjectNode().put("playerId", playerIdText).put("found", profile != null);
        if (profile == null) {
            return result;
        }
        ObjectNode display = result.putObject("display");
        display.put("name", profile.name());
        display.put("level", profile.level());
        display.put("classId", profile.classId());
        display.put("gender", profile.gender());
        display.put("appearanceId", profile.appearanceId());
        if (homeZones != null) {
            try {
                Map<Long, Integer> zones = homeZones.homeZones(List.of(pid), deadline);
                Integer homeZone = zones.get(pid);
                if (homeZone != null && homeZone > 0) {
                    result.put("homeZoneFound", true).put("homeZoneId", homeZone);
                } else {
                    result.put("homeZoneFound", false);
                }
            } catch (RuntimeException e) {
                throw new CallFailure("UNAVAILABLE", "归属区查询失败", false, 0);
            }
        }
        if (presence != null) {
            try {
                Optional<PlayerPresence> sess = presence.findStrictAsync(pid).toCompletableFuture()
                        .get(deadline.remainingMillis(), TimeUnit.MILLISECONDS);
                result.put("sessionFound", sess.isPresent());
                if (sess.isPresent()) {
                    PlayerPresence p = sess.get();
                    ObjectNode view = result.putObject("session");
                    view.put("state", "SESSION_STATE_ONLINE");
                    view.put("gateNodeId", p.getGateNodeId());
                    view.put("sessionId", p.getSessionId());
                    if (locations != null) {
                        PlayerLocationDirectory.HolderRead holder = locations.findHolderAsync(pid)
                                .get(deadline.remainingMillis(), TimeUnit.MILLISECONDS);
                        if (holder.status() == PlayerLocationDirectory.LocationStatus.ONLINE && holder.location() != null) {
                            PlayerLocation loc = holder.location();
                            view.put("sceneNodeId", Integer.toUnsignedString(loc.getSceneNodeId()));
                            view.put("sceneId", Long.toUnsignedString(loc.getSceneId()));
                            view.put("sceneZoneId", loc.getZoneId());
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CallFailure("CANCELLED", "玩家会话查询被中断", false, 0);
            } catch (Exception e) {
                throw new CallFailure("UNAVAILABLE", "玩家会话查询不可用", false, 0);
            }
        }
        return result;
    }

    @Override
    public ObjectNode sendMail(ObjectNode frozenRequest) {
        requireMailConfigured();
        if (frozenRequest == null || frozenRequest.has("auth")) {
            throw invalid("邮件请求非法，调用证明由服务端生成");
        }
        SendMailRequest req = buildMailRequest(frozenRequest, true, false);
        SendMailRequest signed = ServiceCallSignatures.sign(
                MailAdminService.PERSONAL_METHOD, req, "admin", mailAdminSecret, System.currentTimeMillis());
        return verifyMailResponse(awaitRpc(mailAdmin.sendPersonalMail(signed), true));
    }

    @Override
    public ObjectNode sendSystemMail(ObjectNode frozenRequest) {
        requireMailConfigured();
        if (frozenRequest == null || frozenRequest.has("auth")) {
            throw invalid("系统邮件请求非法，调用证明由服务端生成");
        }
        SendMailRequest req = buildMailRequest(frozenRequest, false, false);
        if (req.hasAttachments() && (req.getAttachments().getCurrenciesCount() > 0 || req.getAttachments().getItemsCount() > 0)) {
            throw invalid("携带附件的系统邮件必须走冻结名单活动流程");
        }
        SendMailRequest signed = ServiceCallSignatures.sign(
                MailAdminService.SYSTEM_METHOD, req, "admin", mailAdminSecret, System.currentTimeMillis());
        return verifyMailResponse(awaitRpc(mailAdmin.sendSystemMail(signed), true));
    }

    @Override
    public ObjectNode sendMailCampaign(ObjectNode frozenCampaign) {
        requireMailConfigured();
        if (frozenCampaign == null || !(frozenCampaign.get("mail") instanceof ObjectNode mailNode)
                || !(frozenCampaign.get("shards") instanceof ArrayNode shardsNode) || shardsNode.isEmpty()) {
            throw invalid("活动邮件草稿缺少冻结请求或名单分片");
        }
        if (mailNode.has("auth")) {
            throw invalid("活动邮件请求非法，调用证明由服务端生成");
        }
        List<List<String>> shards = new ArrayList<>(shardsNode.size());
        long totalRecipients = 0;
        for (JsonNode shardNode : shardsNode) {
            if (!shardNode.isArray() || shardNode.isEmpty() || shardNode.size() > MailCampaignManifest.SHARD_SIZE) {
                throw invalid("活动名单分片格式非法");
            }
            List<String> ids = new ArrayList<>(shardNode.size());
            for (JsonNode idNode : shardNode) {
                if (!idNode.isTextual()) {
                    throw invalid("活动收件人 ID 必须是字符串");
                }
                parsePositiveUint64(idNode.textValue(), "收件人 ID");
                ids.add(idNode.textValue());
            }
            shards.add(ids);
            totalRecipients += ids.size();
        }
        SendMailRequest createReq = buildMailRequest(mailNode, false, true);
        if (!createReq.hasAttachments()
                || (createReq.getAttachments().getCurrenciesCount() == 0 && createReq.getAttachments().getItemsCount() == 0)) {
            throw invalid("系统活动邮件必须包含至少一项货币或物品附件");
        }
        String computedHash;
        try {
            computedHash = MailCampaignManifest.manifestHashOf(shards);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        if (createReq.getExpectedShardCount() != shards.size()
                || createReq.getExpectedRecipientCount() != totalRecipients
                || !computedHash.equals(createReq.getManifestHash())) {
            throw invalid("活动名单分片与冻结摘要不一致");
        }
        SendMailRequest signedCreate = ServiceCallSignatures.sign(
                MailAdminService.SYSTEM_METHOD, createReq, "admin", mailAdminSecret, System.currentTimeMillis());
        verifyMailResponse(awaitRpc(mailAdmin.sendSystemMail(signedCreate), true));

        long importedTotal = 0;
        try {
            for (int i = 0; i < shards.size(); i++) {
                List<String> shard = shards.get(i);
                ImportMailRecipientsRequest.Builder imp = ImportMailRecipientsRequest.newBuilder()
                        .setSenderKind(1)
                        .setCampaignId(createReq.getCampaignId())
                        .setManifestGeneration(createReq.getManifestGeneration())
                        .setShardIndex(i);
                for (String id : shard) {
                    imp.addPlayerIds(Long.parseUnsignedLong(id));
                }
                ImportMailRecipientsRequest signedImp = ServiceCallSignatures.sign(
                        MailAdminService.IMPORT_METHOD, imp.build(), "admin", mailAdminSecret, System.currentTimeMillis());
                ImportMailRecipientsResponse impResp = awaitRpc(mailAdmin.importMailRecipients(signedImp), true);
                int tip = impResp.hasErrorMessage() ? impResp.getErrorMessage().getId() : 0;
                if (tip != 0) {
                    throw new CallFailure("SERVER_TIP", "导入活动名单分片未确认，请保留原单重试", true, tip);
                }
                if (impResp.getImportedCount() != shard.size()) {
                    throw new CallFailure("INVALID_RESPONSE", "活动名单分片导入数量不匹配", true, 0);
                }
                importedTotal += impResp.getImportedCount();
            }
            SealMailCampaignRequest sealReq = SealMailCampaignRequest.newBuilder()
                    .setSenderKind(1)
                    .setCampaignId(createReq.getCampaignId())
                    .setManifestGeneration(createReq.getManifestGeneration())
                    .build();
            SealMailCampaignRequest signedSeal = ServiceCallSignatures.sign(
                    MailAdminService.SEAL_METHOD, sealReq, "admin", mailAdminSecret, System.currentTimeMillis());
            ObjectNode result = verifyMailResponse(awaitRpc(mailAdmin.sealMailCampaign(signedSeal), true));
            result.put("campaignId", Long.toUnsignedString(createReq.getCampaignId()))
                    .put("manifestGeneration", Long.toUnsignedString(createReq.getManifestGeneration()))
                    .put("importedRecipients", importedTotal)
                    .put("importedShards", shards.size())
                    .put("sealed", true);
            return result;
        } catch (CallFailure e) {
            if (e.isOutcomeUnknown()) {
                throw e;
            }
            throw new CallFailure(e.getCode(), "活动邮件发布中途未完成，请沿用原命令重试：" + e.getCode(), true, e.getTipId());
        }
    }

    @Override
    public ObjectNode sendPlayerCommand(ObjectNode frozen) {
        if (!playerCommandsEnabled()) {
            throw new CallFailure("NOT_CONFIGURED", "在线玩家管理服务尚未配置", false, 0);
        }
        if (frozen == null || frozen.size() != PLAYER_CMD_FIELDS.size()) {
            throw invalid("玩家命令字段不完整");
        }
        frozen.fieldNames().forEachRemaining(name -> {
            if (!PLAYER_CMD_FIELDS.contains(name)) {
                throw invalid("玩家命令包含未允许字段");
            }
        });
        String operation = frozen.path("operation").asText("");
        long requestId = parsePositiveUint64(frozen.path("requestId").asText(""), "请求 ID");
        long playerId = parsePositiveUint64(frozen.path("playerId").asText(""), "玩家 ID");
        String operator = frozen.path("operator").asText("");
        String reason = frozen.path("reason").asText("");
        GmAdminAuth.validateUsername(operator);
        if (reason.isBlank() || reason.getBytes(StandardCharsets.UTF_8).length > 768
                || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("处置理由非法");
        }
        long issued = parsePositiveUint64(frozen.path("issuedAtMs").asText(""), "冻结时间");
        long now = System.currentTimeMillis();
        if (issued < now - 300_000L || issued > now + 300_000L) {
            throw invalid("玩家命令已过期，请重新预览");
        }
        if (!(frozen.get("arguments") instanceof ObjectNode args)) {
            throw invalid("操作参数必须是对象");
        }

        Deadline deadline = Deadline.after(budgetMillis);
        PlayerPresence sess;
        PlayerLocation loc;
        try {
            sess = presence.findStrictAsync(playerId).toCompletableFuture()
                    .get(deadline.remainingMillis(), TimeUnit.MILLISECONDS).orElse(null);
            if (sess == null) {
                throw new CallFailure("PLAYER_OFFLINE", "玩家当前不在线，不能执行在线命令", false, 0);
            }
            PlayerLocationDirectory.HolderRead holder = locations.findHolderAsync(playerId)
                    .get(deadline.remainingMillis(), TimeUnit.MILLISECONDS);
            if (holder.status() == PlayerLocationDirectory.LocationStatus.ERROR) {
                throw new CallFailure("UNAVAILABLE", "玩家定位记录不可用", false, 0);
            }
            loc = holder.status() == PlayerLocationDirectory.LocationStatus.ONLINE ? holder.location() : null;
            if (loc == null || loc.getSceneNodeId() == 0) {
                throw new CallFailure("PLAYER_NOT_IN_SCENE", "玩家尚未进入场景，不能执行在线命令", false, 0);
            }
        } catch (CallFailure e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CallFailure("CANCELLED", "玩家定位被中断", false, 0);
        } catch (Exception e) {
            throw new CallFailure("UNAVAILABLE", "玩家定位不可用", false, 0);
        }

        GmExecutePlayerCommandRequest.Builder builder = GmExecutePlayerCommandRequest.newBuilder()
                .setCommandId(requestId)
                .setPlayerId(playerId)
                .setOperator(operator)
                .setReason(reason)
                .setTargetNodeId(loc.getSceneNodeId())
                .setExpectedSessionId(sess.getSessionId())
                .setExpectedLoginGeneration(loc.getOwnerEpoch())
                .setIssuedAtMs(issued);
        applyPlayerCommand(builder, operation, args);
        GmExecutePlayerCommandRequest signed = signPlayerCommand(builder.build(), playerCommandSecret, issued / 1000L, Long.toUnsignedString(requestId));
        GmExecutePlayerCommandResponse resp = awaitRpc(playerExecutor.execute(loc.getSceneNodeId(), signed), true);
        int tip = resp.hasErrorMessage() ? resp.getErrorMessage().getId() : 0;
        if (tip != 0) {
            throw new CallFailure("SERVER_TIP", "服务器未确认命令完成，请保留原工单核对", true, tip);
        }
        if (resp.getCommandId() != requestId || !resp.getExecuted()) {
            throw new CallFailure("INVALID_RESPONSE", "玩家命令缺少匹配的完成凭据", true, 0);
        }
        return mapper.createObjectNode()
                .put("commandId", Long.toUnsignedString(resp.getCommandId()))
                .put("executed", true)
                .put("balanceAfter", Long.toUnsignedString(resp.getBalanceAfter()))
                .put("petId", Long.toUnsignedString(resp.getPetId()));
    }

    @Override
    public ObjectNode listPendingBattleScripts(int limit) {
        if (!battleScriptReviewEnabled()) {
            throw new CallFailure("NOT_CONFIGURED", "战斗剧本审核通道未配置", false, 0);
        }
        ListPendingBattleScriptsRequest req = ListPendingBattleScriptsRequest.newBuilder()
                .setLimit(Math.max(1, Math.min(50, limit)))
                .build();
        ListPendingBattleScriptsRequest signed = ServiceCallSignatures.sign(
                MatchInternalService.LIST_PENDING_SCRIPTS_METHOD, req, MatchInternalService.OPS_CALLER,
                matchOpsSecret, System.currentTimeMillis());
        ListPendingBattleScriptsResponse resp = awaitRpc(matchInternal.listPendingBattleScripts(signed), false);
        if (resp.getReject() != BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_NONE) {
            throw new CallFailure(resp.getReject().name(), "拉取待审战斗剧本失败", false, 0);
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode items = out.putArray("scripts");
        for (PendingBattleScript s : resp.getScriptsList()) {
            items.addObject()
                    .put("scriptId", Long.toUnsignedString(s.getScriptId()))
                    .put("ownerPlayerId", Long.toUnsignedString(s.getOwnerPlayerId()))
                    .put("revision", s.getRevision())
                    .put("approvedRevision", s.getApprovedRevision())
                    .put("name", s.getContent().getName())
                    .put("submittedMs", s.getSubmittedMs())
                    .put("roleCount", s.getContent().getRolesCount());
        }
        return out;
    }

    @Override
    public ObjectNode reviewBattleScript(long scriptId, int revision, boolean approve, String reviewer, String reason) {
        if (!battleScriptReviewEnabled()) {
            throw new CallFailure("NOT_CONFIGURED", "战斗剧本审核通道未配置", false, 0);
        }
        ReviewBattleScriptRequest req = ReviewBattleScriptRequest.newBuilder()
                .setScriptId(scriptId)
                .setRevision(revision)
                .setApprove(approve)
                .setReviewer(reviewer)
                .setReason(reason == null ? "" : reason)
                .build();
        ReviewBattleScriptRequest signed = ServiceCallSignatures.sign(
                MatchInternalService.REVIEW_SCRIPT_METHOD, req, MatchInternalService.OPS_CALLER,
                matchOpsSecret, System.currentTimeMillis());
        ReviewBattleScriptResponse resp = awaitRpc(matchInternal.reviewBattleScript(signed), false);
        if (resp.getReject() != BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_NONE) {
            throw new CallFailure(resp.getReject().name(), "审核战斗剧本未通过", false, 0);
        }
        return mapper.createObjectNode()
                .put("scriptId", Long.toUnsignedString(scriptId))
                .put("revision", revision)
                .put("approve", approve)
                .put("reject", resp.getReject().name());
    }

    /** 按 {@code gm-player-command-v1} 规范签发 {@code GmExecutePlayerCommandRequest.auth}。 */
    public static GmExecutePlayerCommandRequest signPlayerCommand(GmExecutePlayerCommandRequest request,
                                                                  String secret, long epochSec, String nonce) {
        GmExecutePlayerCommandRequest clean = request.toBuilder().clearAuth().build();
        byte[] wire = new byte[clean.getSerializedSize()];
        CodedOutputStream out = CodedOutputStream.newInstance(wire);
        out.useDeterministicSerialization();
        try {
            clean.writeTo(out);
            out.checkNoSpaceLeft();
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wire));
            String canonical = String.join("\n",
                    clean.getOperator(),
                    Long.toString(epochSec),
                    nonce,
                    PLAYER_COMMAND_METHOD,
                    Integer.toUnsignedString(clean.getTargetNodeId()),
                    "gm-player-command-v1\n" + bodyHash);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.strip().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            return clean.toBuilder().setAuth(clean.getOperator() + "|" + epochSec + "|" + nonce + "|" + sig).build();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("gm-player-command-v1 签名失败", e);
        }
    }

    private void applyPlayerCommand(GmExecutePlayerCommandRequest.Builder builder, String op, ObjectNode args) {
        switch (op) {
            case "currency.add" -> {
                int ct = requireCurrency(args);
                long amount = parsePositiveInt64(args.path("amount").asText(""), "金额");
                builder.setAddCurrency(GmAddCurrencyRequest.newBuilder().setCurrencyType(ct).setAmount(amount));
            }
            case "currency.deduct" -> {
                int ct = requireCurrency(args);
                long amount = parsePositiveInt64(args.path("amount").asText(""), "金额");
                builder.setDeductCurrency(GmDeductCurrencyRequest.newBuilder().setCurrencyType(ct).setAmount(amount));
            }
            case "currency.block" -> builder.setBlockCurrency(GmBlockCurrencyRequest.newBuilder().setCurrencyType(requireCurrency(args)));
            case "currency.unblock" -> builder.setUnblockCurrency(GmUnblockCurrencyRequest.newBuilder().setCurrencyType(requireCurrency(args)));
            case "player.level" -> {
                int level = requireIntRange(args, "level", 1, MAX_PLAYER_LEVEL);
                builder.setSetLevel(GmSetPlayerLevelRequest.newBuilder().setLevel(level));
            }
            case "pet.grant" -> {
                int petId = requireIntRange(args, "petTableId", 1, Integer.MAX_VALUE);
                builder.setGrantPet(GmGrantPetRequest.newBuilder().setPetTableId(petId));
            }
            case "item.grant" -> {
                int configId = requireIntRange(args, "configId", 1, Integer.MAX_VALUE);
                int count = requireIntRange(args, "count", 1, 99);
                builder.setGrantItem(GmGrantItemRequest.newBuilder().setConfigId(configId).setCount(count));
            }
            default -> throw invalid("玩家操作未开放");
        }
    }

    private int requireCurrency(ObjectNode args) {
        int ct = requireIntRange(args, "currencyType", 0, Integer.MAX_VALUE);
        if (!supportsCurrency(ct)) {
            throw invalid("币种不在游戏协议目录中");
        }
        return ct;
    }

    private static int requireIntRange(ObjectNode node, String field, int min, int max) {
        JsonNode v = node.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < min || v.intValue() > max) {
            throw invalid(field + " 超出允许范围");
        }
        return v.intValue();
    }

    private SendMailRequest buildMailRequest(ObjectNode node, boolean personal, boolean campaign) {
        SendMailRequest.Builder b = SendMailRequest.newBuilder()
                .setSenderKind(1)
                .setRequestId(parsePositiveUint64(node.path("requestId").asText(""), "requestId"))
                .setTitle(node.path("title").asText(""))
                .setBody(node.path("body").asText(""))
                .setExpireMs(parsePositiveUint64(node.path("expireMs").asText(""), "expireMs"));
        if (personal) {
            b.setRecipientPlayerId(parsePositiveUint64(node.path("recipientPlayerId").asText(""), "recipientPlayerId"));
        }
        if (campaign) {
            b.setCampaignId(parsePositiveUint64(node.path("campaignId").asText(""), "campaignId"))
                    .setManifestGeneration(parsePositiveUint64(node.path("manifestGeneration").asText(""), "manifestGeneration"))
                    .setExpectedRecipientCount(parsePositiveUint64(node.path("expectedRecipientCount").asText(""), "expectedRecipientCount"))
                    .setExpectedShardCount(node.path("expectedShardCount").asInt(0))
                    .setManifestHash(node.path("manifestHash").asText(""));
        }
        JsonNode attNode = node.get("attachments");
        if (attNode instanceof ObjectNode attObj) {
            MailAttachments.Builder att = MailAttachments.newBuilder();
            JsonNode currencies = attObj.get("currencies");
            if (currencies != null && currencies.isArray()) {
                for (JsonNode c : currencies) {
                    att.addCurrencies(MailCurrency.newBuilder()
                            .setCurrencyType(c.path("currencyType").asInt(-1))
                            .setAmount(parsePositiveUint64(c.path("amount").asText(""), "amount")));
                }
            }
            JsonNode items = attObj.get("items");
            if (items != null && items.isArray()) {
                for (JsonNode it : items) {
                    att.addItems(MailItem.newBuilder()
                            .setConfigId(it.path("configId").asInt(0))
                            .setCount(it.path("count").asInt(0)));
                }
            }
            if (att.getCurrenciesCount() > 0 || att.getItemsCount() > 0) {
                b.setAttachments(att);
            }
        }
        return b.build();
    }

    private ObjectNode verifyMailResponse(SendMailResponse resp) {
        int tip = resp.hasErrorMessage() ? resp.getErrorMessage().getId() : 0;
        if (tip != 0) {
            throw new CallFailure("SERVER_TIP", "服务器未确认邮件投递，请保留原请求核对", true, tip);
        }
        if (resp.getMailId() == 0L) {
            throw new CallFailure("INVALID_RESPONSE", "邮件响应缺少投递凭据", true, 0);
        }
        return mapper.createObjectNode()
                .put("mailId", Long.toUnsignedString(resp.getMailId()))
                .put("duplicated", resp.getDuplicated());
    }

    private <T> T awaitRpc(CompletableFuture<T> future, boolean writing) {
        try {
            return future.get(budgetMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CallFailure("CANCELLED", "游戏服务调用被中断", writing, 0);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof SecurityException) {
                throw new CallFailure("UNAUTHENTICATED", "内部签名认证被拒绝", false, 0);
            }
            if (cause instanceof IllegalArgumentException) {
                throw new CallFailure("INVALID_ARGUMENT", cause.getMessage(), false, 0);
            }
            throw new CallFailure("UNAVAILABLE", writing ? "写操作结果未知，请保留原请求核对" : "游戏服务调用失败", writing, 0);
        }
    }

    private void requireMailConfigured() {
        if (!mailEnabled()) {
            throw new CallFailure("NOT_CONFIGURED", "邮件管理通道未配置", false, 0);
        }
    }

    private static long parsePositiveUint64(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,19}") || new BigInteger(value).compareTo(MAX_UINT64) > 0) {
            throw invalid(field + "必须是非零 uint64 十进制字符串");
        }
        return Long.parseUnsignedLong(value);
    }

    private static long parsePositiveInt64(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}") || new BigInteger(value).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw invalid(field + "必须是正整数文本");
        }
        return Long.parseLong(value);
    }

    private static CallFailure invalid(String message) {
        return new CallFailure("INVALID_ARGUMENT", message, false, 0);
    }
}
