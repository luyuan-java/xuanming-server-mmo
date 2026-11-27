package com.game.scene.mail;

import com.game.player.store.state.MailOutboxCurrencyState;
import com.game.player.store.state.MailOutboxEntryState;
import com.game.player.store.state.PlayerState;
import com.game.proto.common.base.CurrencyType;
import com.game.proto.mail.MailAttachments;
import com.game.proto.mail.MailCurrency;
import com.game.proto.mail.SendMailRequest;
import com.game.proto.mail.SendMailResponse;
import com.game.scene.player.PlayerMailOutbox;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.SwitchPhase;
import com.game.table.MailErrorTip;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家待寄个人邮件出箱投递服务（场景逻辑线程单线程状态机，对齐 C++ {@code PlayerMailOutboxSystem}）：
 * 仅投递已确认落盘（出现在 {@code player.persistedState()} 中）的出箱条目，调用 {@code game-mail}
 * {@code MailAdminService.sendPersonalMail} 幂等入箱，收件人领奖后经 {@code mail_asset_op} 走
 * {@code ASSET_OP_STREAM_SYSTEM_CREDIT} 安全入账。
 */
public final class PlayerMailOutboxService {

    private static final Logger log = LoggerFactory.getLogger(PlayerMailOutboxService.class);

    /** sender_kind = 5（"battle"，见 MailStore.sender）。 */
    public static final int SENDER_KIND_BATTLE = 5;
    /** 战斗掉落溢出待寄邮件上限（主仓 32 条；攒满时回退为整笔结算延后，防出箱无限膨胀）。 */
    public static final int BATTLE_OVERFLOW_MAX_PENDING = 32;
    /** 单封战斗溢出邮件最多附件种数。 */
    public static final int BATTLE_OVERFLOW_MAX_TYPES = 16;
    /** 单封战斗溢出邮件合计最多附件件数。 */
    public static final int BATTLE_OVERFLOW_MAX_TOTAL_COUNT = 4096;
    private static final long MAIL_EXPIRE_MS = 30L * 86_400_000L;
    private static final long BASE_RETRY_BACKOFF_MS = 1_000L;
    private static final long MAX_RETRY_BACKOFF_MS = 60_000L;

    @FunctionalInterface
    public interface MailSender {
        CompletableFuture<SendMailResponse> sendPersonalMail(SendMailRequest request);
    }

    private static final class DispatchState {
        long inFlightRequestId;
        int failStreak;
        long nextRetryAtMs;
    }

    private final MailSender sender;
    private final LongSupplier requestIds;
    private final SceneClock clock;
    private final Executor logic;
    private final Map<Long, DispatchState> runtime = new HashMap<>();

    public PlayerMailOutboxService(MailSender sender, LongSupplier requestIds, SceneClock clock, Executor logic) {
        this.sender = sender;
        this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logic = Objects.requireNonNull(logic, "logic");
    }

    public boolean isSenderConfigured() {
        return sender != null;
    }

    public boolean canAccept(ScenePlayer owner) {
        return sender != null && owner != null && owner.mailOutbox().size() < PlayerMailOutbox.MAX_ENTRIES;
    }

    /** 构造并写入名匠代打 95% 净银两分成出箱条目（与买家扣款同事务落盘）。 */
    public boolean enqueueProxyFee(ScenePlayer buyer, long masterPlayerId, long netGold,
                                   long sourceId, String buyerDisplayName) {
        if (!canAccept(buyer) || masterPlayerId == 0L || netGold <= 0L) {
            return false;
        }
        long reqId = requestIds.getAsLong();
        if (reqId == 0L) {
            return false;
        }
        String buyerName = (buyerDisplayName == null || buyerDisplayName.isEmpty())
                ? ("侠士#" + Long.toUnsignedString(buyer.playerId())) : buyerDisplayName;
        MailOutboxEntryState entry = MailOutboxEntryState.newBuilder()
                .setRequestId(reqId)
                .setSource(PlayerMailOutbox.SOURCE_CRAFT_PROXY_FEE)
                .setSourceId(sourceId)
                .setCreatedMs(clock.epochMillis())
                .setRecipientPlayerId(masterPlayerId)
                .setTitle("名匠代打收益到账")
                .setBody("侠士【" + buyerName + "】使用您的名匠代打服务，扣除 5% 摆摊税后净收益 "
                        + netGold + " 银两已随邮件奉上，请及时查收。")
                .addCurrencies(MailOutboxCurrencyState.newBuilder()
                        .setCurrencyType(CurrencyType.CURRENCY_TYPE_GOLD_VALUE)
                        .setAmount(netGold))
                .build();
        return buyer.mailOutbox().append(entry);
    }

    /** 预检战斗掉落溢出邮件是否未触达待寄上限（32 条），不修改玩家状态。 */
    public boolean canEnqueueBattleOverflow(ScenePlayer player, Map<Integer, Long> gained) {
        if (player == null || gained == null || gained.isEmpty()) {
            return false;
        }
        int needed = splitBattleOverflowItems(gained).size();
        if (needed == 0) {
            return true;
        }
        int existingBattleOverflow = 0;
        for (MailOutboxEntryState e : player.mailOutbox().entries()) {
            if (e.getSource() == PlayerMailOutbox.SOURCE_BATTLE_OVERFLOW) {
                existingBattleOverflow++;
            }
        }
        return existingBattleOverflow + needed <= BATTLE_OVERFLOW_MAX_PENDING
                && player.mailOutbox().size() + needed <= PlayerMailOutbox.MAX_ENTRIES;
    }

    /**
     * 战斗掉落两包（人物背包 + 临时格）都放不下时，按「一封至多 16 种、合计至多 4096 件」拆封写入玩家待寄邮件出箱
     * （与结算在同一次存档事务落盘，待寄达到 32 条上限返回 false 回落整笔延后，不丢不重，PARITY 2026-11-12）。
     */
    public boolean enqueueBattleOverflow(ScenePlayer player, long battleId, Map<Integer, Long> gained) {
        if (player == null || battleId == 0L || gained == null || gained.isEmpty()) {
            return false;
        }
        java.util.List<java.util.List<com.game.player.store.state.MailOutboxItemState>> chunks = splitBattleOverflowItems(gained);
        if (chunks.isEmpty()) {
            return true;
        }
        int existingBattleOverflow = 0;
        for (MailOutboxEntryState e : player.mailOutbox().entries()) {
            if (e.getSource() == PlayerMailOutbox.SOURCE_BATTLE_OVERFLOW) {
                existingBattleOverflow++;
            }
        }
        if (existingBattleOverflow + chunks.size() > BATTLE_OVERFLOW_MAX_PENDING
                || player.mailOutbox().size() + chunks.size() > PlayerMailOutbox.MAX_ENTRIES) {
            return false;
        }
        long[] ids = new long[chunks.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = requestIds.getAsLong();
            if (ids[i] == 0L) {
                return false;
            }
        }
        long nowMs = clock.epochMillis();
        for (int i = 0; i < chunks.size(); i++) {
            MailOutboxEntryState entry = MailOutboxEntryState.newBuilder()
                    .setRequestId(ids[i])
                    .setSource(PlayerMailOutbox.SOURCE_BATTLE_OVERFLOW)
                    .setSourceId(battleId)
                    .setCreatedMs(nowMs)
                    .setRecipientPlayerId(player.playerId())
                    .setTitle("战斗奖励")
                    .setBody("您的背包与临时格已满，本场战斗掉落奖励已随邮件发放，请腾出背包空位后及时领取。")
                    .addAllItems(chunks.get(i))
                    .build();
            if (!player.mailOutbox().append(entry)) {
                return false;
            }
        }
        return true;
    }

    /** 将战斗掉落按配置号无符号升序拆分为满足「每封 ≤ 16 种且合计 ≤ 4096 件」的邮件附件分块。 */
    public static java.util.List<java.util.List<com.game.player.store.state.MailOutboxItemState>> splitBattleOverflowItems(
            Map<Integer, Long> gained) {
        java.util.TreeMap<Integer, Long> ordered = new java.util.TreeMap<>(Integer::compareUnsigned);
        gained.forEach((cfg, cnt) -> {
            if (cfg != null && cfg != 0 && cnt != null && cnt > 0L) {
                ordered.merge(cfg, cnt, Long::sum);
            }
        });
        java.util.List<java.util.List<com.game.player.store.state.MailOutboxItemState>> chunks = new java.util.ArrayList<>();
        java.util.List<com.game.player.store.state.MailOutboxItemState> current = new java.util.ArrayList<>();
        long currentTotal = 0L;
        for (Map.Entry<Integer, Long> e : ordered.entrySet()) {
            int cfg = e.getKey();
            long remaining = e.getValue();
            while (remaining > 0L) {
                if (current.size() >= BATTLE_OVERFLOW_MAX_TYPES || currentTotal >= BATTLE_OVERFLOW_MAX_TOTAL_COUNT) {
                    chunks.add(java.util.List.copyOf(current));
                    current = new java.util.ArrayList<>();
                    currentTotal = 0L;
                }
                long take = Math.min(remaining, BATTLE_OVERFLOW_MAX_TOTAL_COUNT - currentTotal);
                current.add(com.game.player.store.state.MailOutboxItemState.newBuilder()
                        .setConfigId(cfg)
                        .setCount(take)
                        .build());
                currentTotal += take;
                remaining -= take;
            }
        }
        if (!current.isEmpty()) {
            chunks.add(java.util.List.copyOf(current));
        }
        return chunks;
    }

    /** 构造确定性个人邮件请求（同一 request_id 重投字节级一致，满足 MailStore 幂等校验）。 */
    public static SendMailRequest buildSendRequest(long ownerPlayerId, MailOutboxEntryState entry) {
        long recipient = entry.getRecipientPlayerId() != 0L ? entry.getRecipientPlayerId() : ownerPlayerId;
        MailAttachments.Builder attachments = MailAttachments.newBuilder();
        for (MailOutboxCurrencyState c : entry.getCurrenciesList()) {
            attachments.addCurrencies(MailCurrency.newBuilder()
                    .setCurrencyType(c.getCurrencyType())
                    .setAmount(c.getAmount()));
        }
        for (com.game.player.store.state.MailOutboxItemState item : entry.getItemsList()) {
            attachments.addItems(com.game.proto.mail.MailItem.newBuilder()
                    .setConfigId(item.getConfigId())
                    .setCount((int) Math.min(item.getCount(), 0xFFFF_FFFFL)));
        }
        long createdMs = entry.getCreatedMs();
        long expireMs = createdMs > Long.MAX_VALUE - MAIL_EXPIRE_MS ? Long.MAX_VALUE : createdMs + MAIL_EXPIRE_MS;
        return SendMailRequest.newBuilder()
                .setSenderKind(SENDER_KIND_BATTLE)
                .setRequestId(entry.getRequestId())
                .setRecipientPlayerId(recipient)
                .setTitle(entry.getTitle())
                .setBody(entry.getBody())
                .setExpireMs(expireMs)
                .setAttachments(attachments)
                .build();
    }

    /** 每秒驱动：为所有在线且已有已落盘出箱条目的玩家发起至多一封在途投递。 */
    public void tick(SceneWorld world) {
        if (sender == null || world == null) {
            return;
        }
        long nowMs = clock.epochMillis();
        runtime.keySet().removeIf(id -> world.playerById(id) == null);
        for (ScenePlayer player : world.playersSnapshot()) {
            if (player.frozen() || player.switchPhase() != SwitchPhase.NONE || player.mailOutbox().isEmpty()) {
                continue;
            }
            MailOutboxEntryState head = player.mailOutbox().head();
            if (head == null) {
                continue;
            }
            if (!isEntryDurable(player, head.getRequestId())) {
                world.requestKeyProgressSave(player);
                continue;
            }
            DispatchState state = runtime.computeIfAbsent(player.playerId(), k -> new DispatchState());
            if (state.inFlightRequestId != 0L || nowMs < state.nextRetryAtMs) {
                continue;
            }
            long ownerId = player.playerId();
            long requestId = head.getRequestId();
            long recipientId = head.getRecipientPlayerId() != 0L ? head.getRecipientPlayerId() : ownerId;
            SendMailRequest request = buildSendRequest(ownerId, head);
            state.inFlightRequestId = requestId;
            try {
                sender.sendPersonalMail(request).whenComplete((resp, err) -> postCompletion(
                        world, ownerId, recipientId, requestId, resp, err));
            } catch (RuntimeException sendFailure) {
                onSendDone(world, ownerId, recipientId, requestId, null, sendFailure);
            }
        }
    }

    private static boolean isEntryDurable(ScenePlayer player, long requestId) {
        PlayerState persisted = player.persistedState();
        if (persisted == null || !persisted.hasMailOutbox()) {
            return false;
        }
        for (MailOutboxEntryState entry : persisted.getMailOutbox().getEntriesList()) {
            if (entry.getRequestId() == requestId) {
                return true;
            }
        }
        return false;
    }

    private void postCompletion(SceneWorld world, long ownerId, long recipientId, long requestId,
                                SendMailResponse response, Throwable error) {
        try {
            logic.execute(() -> onSendDone(world, ownerId, recipientId, requestId, response, error));
        } catch (RejectedExecutionException ignored) {
            // 节点停服后丢弃迟到回调
        }
    }

    private void onSendDone(SceneWorld world, long ownerId, long recipientId, long requestId,
                            SendMailResponse response, Throwable error) {
        DispatchState state = runtime.get(ownerId);
        if (state != null && state.inFlightRequestId == requestId) {
            state.inFlightRequestId = 0L;
        }
        ScenePlayer player = world.playerById(ownerId);
        if (player == null || player.frozen() || player.switchPhase() != SwitchPhase.NONE) {
            return;
        }
        int tipId = (error == null && response != null) ? response.getErrorMessage().getId() : -1;
        if (tipId == 0) {
            if (player.mailOutbox().remove(requestId)) {
                world.requestSave(player);
            }
            if (state != null) {
                state.failStreak = 0;
                state.nextRetryAtMs = 0L;
            }
            log.info("mail_outbox_delivered player_id={} recipient_player_id={} request_id={} mail_id={}",
                    Long.toUnsignedString(ownerId), Long.toUnsignedString(recipientId),
                    Long.toUnsignedString(requestId), response.getMailId());
            return;
        }
        if (tipId == MailErrorTip.mail_error.kMailRecipientDeleted_VALUE) {
            if (player.mailOutbox().remove(requestId)) {
                world.requestSave(player);
            }
            if (state != null) {
                state.failStreak = 0;
                state.nextRetryAtMs = 0L;
            }
            log.warn("mail_outbox_recipient_deleted player_id={} recipient_player_id={} request_id={}; dropped to prevent outbox head-of-line block",
                    Long.toUnsignedString(ownerId), Long.toUnsignedString(recipientId),
                    Long.toUnsignedString(requestId));
            return;
        }
        if (state != null) {
            state.failStreak++;
            long delay = Math.min(MAX_RETRY_BACKOFF_MS,
                    BASE_RETRY_BACKOFF_MS << Math.min(6, state.failStreak - 1));
            state.nextRetryAtMs = clock.epochMillis() + delay;
        }
        log.warn("mail_outbox_send_retry player_id={} recipient_player_id={} request_id={} tip_id={} err={}",
                Long.toUnsignedString(ownerId), Long.toUnsignedString(recipientId),
                Long.toUnsignedString(requestId), tipId, error == null ? "" : error.getMessage());
    }
}
