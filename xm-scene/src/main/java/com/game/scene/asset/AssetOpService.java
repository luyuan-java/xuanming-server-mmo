package com.game.scene.asset;

import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetItem;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetStream;
import com.game.player.store.asset.AssetSeqState;
import com.game.player.store.asset.PersistedAssetLedger;
import com.game.player.store.state.PlayerState;
import com.game.scene.asset.AssetOpLedger.RecordKind;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagService;
import com.game.scene.currency.CurrencyService;
import com.game.scene.player.Bag;
import com.game.scene.player.BagType;
import com.game.scene.player.Wallet;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.table.AssetErrorTip;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用资产通道的 scene 侧（同 mmorpg {@code PlayerAssetOpSystem}）：别的服务（帮会 / 交易）给在线玩家扣货币、发货币与物品，
 * 每条请求带 (流, 纪元, seq)，这边在<b>场景逻辑线程</b>的同一次处理里做完「验签 → 找人 → 查账本 → 闸门 → 改资产 → 记账本 → 触发存盘」。
 * 账本与资产同在 {@code player_state} 一份记录里、同一次围栏写落盘，不存在「资产落了账本没落」的中间态。
 *
 * <p>契约（调用方必须知道的全部）：
 * <ul>
 *   <li>结局在 {@link AssetOpResponse#getOutcome()}：APPLIED（已应用，partial = 只发了一部分，转人工补偿）/ REJECTED（终局拒绝，已记账）/
 *       RETRY（暂时条件，未记账，同一 seq 稍后重投）/ NOT_HERE（玩家不在本节点，未记账）/ UNKNOWN（信封畸形、验签失败、纪元过期、跳号过远、
 *       seq 已滑出窗口；未记账，告警转人工，不得终结）；</li>
 *   <li>幂等：同一 (玩家, 流, 纪元, seq) 重复请求只读答复、绝不重办；</li>
 *   <li>不在调用里等落盘：记账后立刻请求存盘并如实回报 durable（结局出现在最近一次确认落库的存档里），调用方用同一 seq 重查直到
 *       durable = true 才终结；已见未 durable 的重查每 500 ms 至多再触发一次存盘。</li>
 * </ul>
 * 闸门只在这一层，<b>不下沉</b>到 {@link CurrencyService} / {@link BagService}（战斗结算、任务发奖等内部路径共用它们，基线 D48）。
 * 与基线的差异：冻结 / 归属交接在途（27003）随 5.x 跨节点、战斗中（27002）随 6.x 回合制战斗接入（Java 版目前没有这两种状态，
 * 相应的闸恒放行）；退出存盘在途在 Java 版不存在（离场当场移除实例，之后的请求是 NOT_HERE）；Java 的批量入包整批原子，不会「写了一半」，
 * 所以物品段不会产生部分发放；货币净入账溢出在任何改动之前预检、记 REJECTED 27004（基线 uint64 无检查）。
 */
public final class AssetOpService {

    private static final Logger log = LoggerFactory.getLogger(AssetOpService.class);

    static final int CURRENCY_INSUFFICIENT = AssetErrorTip.asset_error.kAssetCurrencyInsufficient_VALUE;
    static final int BAG_FULL = AssetErrorTip.asset_error.kAssetBagFull_VALUE;
    static final int FROZEN = AssetErrorTip.asset_error.kAssetFrozen_VALUE;
    static final int INVALID_BUNDLE = AssetErrorTip.asset_error.kAssetInvalidBundle_VALUE;
    static final int BLOCKED = AssetErrorTip.asset_error.kAssetBlocked_VALUE;
    static final int PLAYER_NOT_HERE = AssetErrorTip.asset_error.kAssetPlayerNotHere_VALUE;
    static final int PARTIAL_APPLIED = AssetErrorTip.asset_error.kAssetPartialApplied_VALUE;
    static final int AUTH_FAILED = AssetErrorTip.asset_error.kAssetAuthFailed_VALUE;

    /** 已见未 durable 的重查，两次触发存盘之间的最小间隔（基线 kAssetOpResaveMinIntervalMs）。 */
    static final long RESAVE_MIN_INTERVAL_MS = 500;
    /** 发放包上限：货币 4 条、物品 16 条（同基线）。 */
    static final int MAX_CREDIT_CURRENCIES = 4;
    static final int MAX_CREDIT_ITEMS = 16;

    // 三个入口是 xm-api 的 AssetRpc（wireName 同时是签名规范串第 3 行，调用方签名与这边验签共用那一个枚举）。

    private enum Direction { DEBIT, CREDIT }

    /** 流 ↔ 方向 ↔ 流水原因白名单（数值同基线 TransactionType）。「流 ↔ 调用方」由 {@link AssetOpAuth} 管。 */
    private record StreamRule(Direction direction, Map<Integer, AssetAudit.Reason> allowedTx) {
    }

    private static final Map<AssetStream, StreamRule> STREAM_RULES = Map.of(
            AssetStream.ASSET_STREAM_GUILD_DEBIT, new StreamRule(Direction.DEBIT,
                    Map.of(24, AssetAudit.Reason.GUILD_DONATE)),
            AssetStream.ASSET_STREAM_GUILD_CREDIT, new StreamRule(Direction.CREDIT,
                    Map.of(25, AssetAudit.Reason.GUILD_SHOP, 26, AssetAudit.Reason.GUILD_ACTIVITY_REWARD)),
            AssetStream.ASSET_STREAM_TRADE_DEBIT, new StreamRule(Direction.DEBIT,
                    Map.of(3, AssetAudit.Reason.AUCTION_SELL)),
            AssetStream.ASSET_STREAM_TRADE_CREDIT, new StreamRule(Direction.CREDIT,
                    Map.of(4, AssetAudit.Reason.AUCTION_BUY, 1, AssetAudit.Reason.TRADE)),
            // 预留：没有合法调用方（验签一律拒）
            AssetStream.ASSET_STREAM_SYSTEM_CREDIT, new StreamRule(Direction.CREDIT,
                    Map.of(8, AssetAudit.Reason.SYSTEM_GRANT, 2, AssetAudit.Reason.MAIL_ATTACHMENT,
                            9, AssetAudit.Reason.GM_GRANT)));

    private final SceneWorld world;
    private final CurrencyService currency;
    private final BagService bags;
    private final AssetOpAuth auth;
    private final SceneClock clock;

    public AssetOpService(SceneWorld world, CurrencyService currency, BagService bags, AssetOpAuth auth,
                          SceneClock clock) {
        this.world = world;
        this.currency = currency;
        this.bags = bags;
        this.auth = auth;
        this.clock = clock;
    }

    /** 进场景前（{@code PlayerInitializer}）：账本加载时判了损坏就大声报一次（该玩家的资产通道随之关闭，原数据不改写）。 */
    public static void checkLedgerOnLoad(ScenePlayer player) {
        String reason = player.assetLedger().invalidReason();
        if (reason != null) {
            log.error("[AssetOp] 账本损坏，关闭该玩家的资产通道（原样保留，等人工排查） player={} 原因={}",
                    Long.toUnsignedString(player.playerId()), reason);
        }
    }

    /** 处理一条请求（逻辑线程上调用）。每次结束打一行日志（日志可以带 player_id，指标不可以）。 */
    public AssetOpResponse handle(AssetRpc rpc, AssetOpRequest request) {
        AssetOpResponse response = decide(rpc, request);
        log.info("[AssetOp] rpc={} player={} stream={} epoch={} seq={} corr={} outcome={} reason={} durable={} partial={}",
                rpc.wireName(), Long.toUnsignedString(request.getPlayerId()), request.getStreamValue(),
                Long.toUnsignedString(request.getStreamEpoch()), Long.toUnsignedString(request.getSeq()),
                Long.toUnsignedString(request.getCorrelationId()), response.getOutcome(), response.getReason(),
                response.getDurable(), response.getPartial());
        return response;
    }

    // ------------------------------------------------------------------ 统一流程（顺序同基线 Decide）

    private AssetOpResponse decide(AssetRpc rpc, AssetOpRequest request) {
        boolean abort = rpc == AssetRpc.ABORT_DEBIT;
        // 1. 信封（不记账）
        if (request.getPlayerId() == 0 || request.getSeq() == 0 || request.getStreamEpoch() == 0) {
            log.warn("[AssetOp] 信封非法 rpc={} player={} seq={} epoch={}", rpc.wireName(),
                    Long.toUnsignedString(request.getPlayerId()), Long.toUnsignedString(request.getSeq()),
                    Long.toUnsignedString(request.getStreamEpoch()));
            return answer(AssetOutcome.ASSET_OUTCOME_UNKNOWN, INVALID_BUNDLE);
        }
        StreamRule rule = STREAM_RULES.get(request.getStream());
        if (rule == null) {
            log.warn("[AssetOp] 未知流 rpc={} stream={}", rpc.wireName(), request.getStreamValue());
            return answer(AssetOutcome.ASSET_OUTCOME_UNKNOWN, INVALID_BUNDLE);
        }
        // 中止收全部流（它只是给未见 seq 记一个拒绝占位），也不校验流水原因
        if (!abort) {
            Direction wanted = rpc == AssetRpc.DEBIT ? Direction.DEBIT : Direction.CREDIT;
            if (rule.direction() != wanted || !rule.allowedTx().containsKey(request.getTxType())) {
                log.warn("[AssetOp] 流方向或流水原因白名单不符 rpc={} stream={} tx_type={}", rpc.wireName(),
                        request.getStreamValue(), Integer.toUnsignedString(request.getTxType()));
                return answer(AssetOutcome.ASSET_OUTCOME_UNKNOWN, INVALID_BUNDLE);
            }
        }
        // 1b. 验签（不记账）：失败一律 fail-closed，不做开发放行
        long nowMs = clock.epochMillis();
        AssetOpAuth.Verdict verdict = auth.verify(rpc.wireName(), request, nowMs);
        if (verdict != AssetOpAuth.Verdict.OK) {
            log.warn("[AssetOp] 验签失败 rpc={} verdict={} caller={} stream={} seq={}", rpc.wireName(), verdict,
                    request.getAuth().getCaller(), request.getStreamValue(), Long.toUnsignedString(request.getSeq()));
            return answer(AssetOutcome.ASSET_OUTCOME_UNKNOWN, AUTH_FAILED);
        }
        // 2. 找人（不记账）
        ScenePlayer player = world.playerById(request.getPlayerId());
        if (player == null) {
            return answer(AssetOutcome.ASSET_OUTCOME_NOT_HERE, PLAYER_NOT_HERE);
        }
        // 3. 账本损坏（加载时判的）：fail-closed
        AssetOpLedger ledger = player.assetLedger();
        if (ledger.invalidReason() != null) {
            log.error("[AssetOp] 账本损坏，拒绝一切资产操作 player={} rpc={} seq={}",
                    Long.toUnsignedString(request.getPlayerId()), rpc.wireName(), Long.toUnsignedString(request.getSeq()));
            return answer(AssetOutcome.ASSET_OUTCOME_RETRY, BLOCKED);
        }
        // 4. 分类
        int stream = request.getStreamValue();
        AssetSeqState state = ledger.classify(stream, request.getStreamEpoch(), request.getSeq());
        switch (state) {
            case INVALID -> {
                return answer(AssetOutcome.ASSET_OUTCOME_UNKNOWN, INVALID_BUNDLE);
            }
            case STALE_EPOCH, BEHIND_WINDOW, JUMP_TOO_FAR -> {
                // 调用方的守卫失效、库被重置 / 恢复，或存档回退过：不记账、不猜结局，原因留 0，调用方告警转人工
                log.error("[AssetOp] seq 不可采信 state={} player={} rpc={} stream={} req_epoch={} ledger_epoch={} seq={}"
                                + " watermark={} max_seq={}", state, Long.toUnsignedString(request.getPlayerId()),
                        rpc.wireName(), stream, Long.toUnsignedString(request.getStreamEpoch()),
                        Long.toUnsignedString(ledger.epochOf(stream)), Long.toUnsignedString(request.getSeq()),
                        Long.toUnsignedString(ledger.watermarkOf(stream)), Long.toUnsignedString(ledger.maxSeqOf(stream)));
                return answer(AssetOutcome.ASSET_OUTCOME_UNKNOWN, 0);
            }
            case APPLIED, REJECTED -> {
                return answerSeen(player, request, state == AssetSeqState.APPLIED, nowMs);
            }
            case UNSEEN, AHEAD_OF_WINDOW -> {
                // 继续走闸门
            }
        }
        // 5. 改动闸门（未见 seq 才走到这里）。冻结 / 交接在途随 5.x 接入；退出存盘在途在 Java 版就是 NOT_HERE。
        if (!fenced(player)) {
            // 存盘没有归属围栏：记下的结局可能被旧节点晚到的写回抹掉。中止占位也是一笔账本改动，同样要挡
            log.warn("[AssetOp] blocked: owner_epoch unknown rpc={} player={} stream={} seq={}", rpc.wireName(),
                    Long.toUnsignedString(request.getPlayerId()), stream, Long.toUnsignedString(request.getSeq()));
            return answer(AssetOutcome.ASSET_OUTCOME_RETRY, FROZEN);
        }
        // 6. 中止占位：给未见 seq 记一个 REJECTED（原因 0），此后这条 seq 永远拒绝
        if (abort) {
            return rejectAndRecord(player, request, 0, nowMs);
        }
        // 7. 应用闸门：战斗中（27002）随 6.x 回合制战斗接入
        // 8. 包内容（确定性失败 → 记 REJECTED，免得调用方无限重投同一个坏包）
        String why = rpc == AssetRpc.DEBIT ? validateDebit(request.getBundle()) : validateCredit(request.getBundle());
        if (why != null) {
            log.warn("[AssetOp] 包内容非法 rpc={} player={} seq={} why={}", rpc.wireName(),
                    Long.toUnsignedString(request.getPlayerId()), Long.toUnsignedString(request.getSeq()), why);
            return rejectAndRecord(player, request, INVALID_BUNDLE, nowMs);
        }
        AssetAudit.Reason reason = rule.allowedTx().get(request.getTxType());
        return rpc == AssetRpc.DEBIT ? applyDebit(player, request, reason, nowMs)
                : applyCredit(player, request, reason, nowMs);
    }

    /** 本实例的存盘受 owner_epoch 围栏保护（epoch 0 = 没有围栏，fail-closed）。 */
    private static boolean fenced(ScenePlayer player) {
        return player.ownerEpoch() != 0;
    }

    // ------------------------------------------------------------------ 包内容校验

    private static boolean currencyValid(AssetCurrency c) {
        // 数额 ∈ [1, Long.MAX_VALUE]（uint64 放在 long 里，超过 Long.MAX_VALUE 即为负）
        return Integer.compareUnsigned(c.getCurrencyType(), Wallet.TYPE_COUNT) < 0 && c.getAmount() >= 1;
    }

    private static boolean hasInstanceFields(AssetBundle bundle) {
        return bundle.getItemUuidsCount() > 0 || bundle.getPetId() != 0;
    }

    /** 扣款包：恰好 1 条货币、0 件物品；按实例扣（交易二期）尚未支持，记账拒绝。返回 null = 合法。 */
    static String validateDebit(AssetBundle bundle) {
        if (hasInstanceFields(bundle)) {
            return "debit 不支持按实例扣物 / 扣宝宝（item_uuids / pet_id）";
        }
        if (bundle.getCurrenciesCount() != 1 || bundle.getItemsCount() != 0) {
            return "debit 只收恰好 1 条货币、0 件物品";
        }
        if (!currencyValid(bundle.getCurrencies(0))) {
            return "debit 货币类型或数额越界";
        }
        return null;
    }

    /** 发放包：非空、货币 ≤ 4、物品 ≤ 16、无重复、物品在表里、数量 ≥ 1。返回 null = 合法。 */
    String validateCredit(AssetBundle bundle) {
        if (hasInstanceFields(bundle)) {
            return "credit 不支持按实例发物 / 发宝宝（item_uuids / pet_id）";
        }
        if (bundle.getCurrenciesCount() + bundle.getItemsCount() < 1) {
            return "credit 包为空";
        }
        if (bundle.getCurrenciesCount() > MAX_CREDIT_CURRENCIES || bundle.getItemsCount() > MAX_CREDIT_ITEMS) {
            return "credit 条目超上限（货币 4 / 物品 16）";
        }
        Set<Integer> types = new HashSet<>();
        for (AssetCurrency c : bundle.getCurrenciesList()) {
            if (!currencyValid(c)) {
                return "credit 货币类型或数额越界";
            }
            if (!types.add(c.getCurrencyType())) {
                return "credit 同一货币类型重复";
            }
        }
        Set<Integer> configs = new HashSet<>();
        for (AssetItem item : bundle.getItemsList()) {
            if (item.getCount() == 0) {
                return "credit 物品数量为 0";
            }
            if (!configs.add(item.getConfigId())) {
                return "credit 同一物品 config_id 重复";
            }
            if (bags.tables().item(item.getConfigId()) == null) {
                return "credit 物品 config_id 不存在于物品表";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 应用

    private AssetOpResponse applyDebit(ScenePlayer player, AssetOpRequest request, AssetAudit.Reason reason,
                                       long nowMs) {
        AssetCurrency c = request.getBundle().getCurrencies(0);
        int type = c.getCurrencyType();
        long amount = c.getAmount();
        if (player.wallet().balance(type) < amount) {
            return rejectAndRecord(player, request, CURRENCY_INSUFFICIENT, nowMs);
        }
        Wallet.Change change = currency.deduct(player, type, amount, reason, request.getCorrelationId());
        if (!change.ok()) {
            // 闸门与余额都过了，不该失败。不记账、回 RETRY：记成 REJECTED 会把没看懂的失败变成终局
            log.error("[AssetOp] 扣款在闸门之后仍失败 player={} seq={} currency_type={} amount={} tip={}",
                    Long.toUnsignedString(request.getPlayerId()), Long.toUnsignedString(request.getSeq()), type, amount,
                    change.tipId());
            return answer(AssetOutcome.ASSET_OUTCOME_RETRY, change.tipId());
        }
        if (!record(player, request, RecordKind.APPLIED, 0)) {
            // 钱已扣却记不上账：重投会再扣一次。只能大声报错，按流水关联号人工对账补偿（正常路径不可达）
            log.error("[AssetOp] 扣款已生效但记账前置被破坏 player={} stream={} epoch={} seq={} corr={}",
                    Long.toUnsignedString(request.getPlayerId()), request.getStreamValue(),
                    Long.toUnsignedString(request.getStreamEpoch()), Long.toUnsignedString(request.getSeq()),
                    Long.toUnsignedString(request.getCorrelationId()));
            return answer(AssetOutcome.ASSET_OUTCOME_UNKNOWN, 0);
        }
        return applied(player, request, false, nowMs);
    }

    /**
     * 发放（同基线 ApplyCredit 的顺序）：货币预检（封禁 → 终局拒绝；净入账溢出 → 终局拒绝 27004，基线没有这一条）→ 物品（整批原子）→ 货币 → 结局。
     * 先物品后货币：只有物品会暂时失败（背包满），先做它就不会留下半截。已有改动后又失败记部分发放（APPLIED + partial）。
     */
    private AssetOpResponse applyCredit(ScenePlayer player, AssetOpRequest request, AssetAudit.Reason reason,
                                        long nowMs) {
        // 欠款到期按同一时刻判，预检的结论才对后面的加币成立
        long nowSeconds = nowMs / 1000;
        AssetBundle bundle = request.getBundle();
        // (a) 货币预检：封禁与溢出都必须在任何改动之前判掉
        for (AssetCurrency c : bundle.getCurrenciesList()) {
            int tip = currency.checkAdd(player, c.getCurrencyType(), c.getAmount(), nowSeconds);
            if (tip == Wallet.BLOCKED) {
                return rejectAndRecord(player, request, BLOCKED, nowMs);
            }
            if (tip != 0) {
                log.warn("[AssetOp] 发放会让余额溢出，按坏包拒绝 player={} seq={} currency_type={} amount={}",
                        Long.toUnsignedString(request.getPlayerId()), Long.toUnsignedString(request.getSeq()),
                        c.getCurrencyType(), Long.toUnsignedString(c.getAmount()));
                return rejectAndRecord(player, request, INVALID_BUNDLE, nowMs);
            }
        }
        boolean anyMutated = false;
        // (b) 物品：Java 的批量入包先规划后写入，要么全写要么一点不动
        if (bundle.getItemsCount() > 0) {
            Map<Integer, Long> counts = new LinkedHashMap<>();
            for (AssetItem item : bundle.getItemsList()) {
                counts.put(item.getConfigId(), Integer.toUnsignedLong(item.getCount()));
            }
            Bag.AddResult result = bags.addItems(player, BagType.INVENTORY, counts, reason, request.getCorrelationId(),
                    "");
            if (!result.ok()) {
                return itemsFailed(player, request, result.tip(), nowMs);
            }
            anyMutated = true;
        }
        // (c) 货币（补缴抵扣属于正常应用）。(a) 的预检在同一线程上、按同一时刻刚做过，这里不会失败；失败分支只作防御（结构同基线）
        String failedWhat = null;
        int failedTip = 0;
        for (AssetCurrency c : bundle.getCurrenciesList()) {
            Wallet.Change change = currency.add(player, c.getCurrencyType(), c.getAmount(), reason,
                    request.getCorrelationId(), nowSeconds);
            if (change.ok()) {
                anyMutated = true;
                continue;
            }
            if (!anyMutated) {
                // 一点没改：安全重投，不记账
                return answer(AssetOutcome.ASSET_OUTCOME_RETRY, change.tipId());
            }
            failedTip = change.tipId();
            failedWhat = "currency_type=" + Integer.toUnsignedString(c.getCurrencyType());
            break;
        }
        // (d) 结局
        boolean partial = failedWhat != null;
        if (!record(player, request, partial ? RecordKind.APPLIED_PARTIAL : RecordKind.APPLIED, 0)) {
            log.error("[AssetOp] 发放已生效但记账前置被破坏 player={} stream={} epoch={} seq={} corr={}",
                    Long.toUnsignedString(request.getPlayerId()), request.getStreamValue(),
                    Long.toUnsignedString(request.getStreamEpoch()), Long.toUnsignedString(request.getSeq()),
                    Long.toUnsignedString(request.getCorrelationId()));
            return answer(AssetOutcome.ASSET_OUTCOME_UNKNOWN, 0);
        }
        if (partial) {
            // 人工补偿的唯一依据：哪笔操作、卡在哪一条、什么错
            log.error("[AssetOp] partial player={} stream={} epoch={} seq={} corr={} failed={} tip={}",
                    Long.toUnsignedString(request.getPlayerId()), request.getStreamValue(),
                    Long.toUnsignedString(request.getStreamEpoch()), Long.toUnsignedString(request.getSeq()),
                    Long.toUnsignedString(request.getCorrelationId()), failedWhat, failedTip);
        }
        return applied(player, request, partial, nowMs);
    }

    /** 物品段零改动失败：按错误类别选结局（同基线）。 */
    private AssetOpResponse itemsFailed(ScenePlayer player, AssetOpRequest request, int tip, long nowMs) {
        if (tip == Bag.NO_SPACE) {
            // 空间不足：暂时条件，同一 seq 稍后重投（超过业务时限由调用方改发中止）
            return answer(AssetOutcome.ASSET_OUTCOME_RETRY, BAG_FULL);
        }
        if (tip == BagService.REFUSED) {
            // 入包的闸只有冻结与封禁两种，冻结在 Java 版还不存在：走到这里就是封禁——终局拒绝，记账
            return rejectAndRecord(player, request, BLOCKED, nowMs);
        }
        if (tip == Bag.INVALID_PARAM) {
            // 数量已校验过，剩下的只有物品 guid 发不出号（节点号租约丢失等）：暂时条件，不记账（同基线的号段余量预检回 RETRY 0）
            log.warn("[AssetOp] 物品 guid 发不出号，暂不发放 player={} seq={}", Long.toUnsignedString(request.getPlayerId()),
                    Long.toUnsignedString(request.getSeq()));
            return answer(AssetOutcome.ASSET_OUTCOME_RETRY, 0);
        }
        return answer(AssetOutcome.ASSET_OUTCOME_RETRY, tip);
    }

    // ------------------------------------------------------------------ 记账、答复、durable

    /**
     * 记一次结局。先复核前置再写（{@link AssetOpLedger#record} 自己保证前置失败什么都不改、不会留下纪元 0 的空流）。
     */
    private static boolean record(ScenePlayer player, AssetOpRequest request, RecordKind kind, int reasonTipId) {
        return player.assetLedger().record(request.getStreamValue(), request.getStreamEpoch(), request.getSeq(), kind,
                reasonTipId);
    }

    /** 记一次拒绝并答复（原因 0 = 中止占位）。 */
    private AssetOpResponse rejectAndRecord(ScenePlayer player, AssetOpRequest request, int reasonTipId, long nowMs) {
        if (!record(player, request, RecordKind.REJECTED, reasonTipId)) {
            log.error("[AssetOp] 记账前置被破坏（拒绝） player={} stream={} epoch={} seq={}",
                    Long.toUnsignedString(request.getPlayerId()), request.getStreamValue(),
                    Long.toUnsignedString(request.getStreamEpoch()), Long.toUnsignedString(request.getSeq()));
            return answer(AssetOutcome.ASSET_OUTCOME_UNKNOWN, 0);
        }
        return AssetOpResponse.newBuilder()
                .setOutcome(AssetOutcome.ASSET_OUTCOME_REJECTED)
                .setReason(reasonTipId)
                .setDurable(persistAndProbe(player, request, false, nowMs))
                .build();
    }

    private AssetOpResponse applied(ScenePlayer player, AssetOpRequest request, boolean partial, long nowMs) {
        return AssetOpResponse.newBuilder()
                .setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED)
                .setReason(partial ? PARTIAL_APPLIED : 0)
                .setPartial(partial)
                .setDurable(persistAndProbe(player, request, true, nowMs))
                .build();
    }

    /** 已见 seq 的只读答复：回原结局，允许时补一次存盘把 durable 追平。不经过任何闸门（结局早已固定）。 */
    private AssetOpResponse answerSeen(ScenePlayer player, AssetOpRequest request, boolean applied, long nowMs) {
        AssetOpLedger ledger = player.assetLedger();
        int stream = request.getStreamValue();
        AssetOpResponse.Builder response = AssetOpResponse.newBuilder();
        if (applied) {
            response.setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED);
            if (ledger.isPartial(stream, request.getSeq())) {
                response.setPartial(true).setReason(PARTIAL_APPLIED);
            }
        } else {
            // 原因环被挤掉时回 0；中止占位本来就是 0
            response.setOutcome(AssetOutcome.ASSET_OUTCOME_REJECTED)
                    .setReason(ledger.rejectionReason(stream, request.getSeq()));
        }
        boolean durable = durable(player, request, applied);
        if (!durable && fenced(player)) {
            // 限频：调用方按 100 / 200 / 400 ms 重查，不限频会把序列化 + 比对打成热路径
            if (!ledger.persistRequestedWithin(nowMs, RESAVE_MIN_INTERVAL_MS)) {
                durable = persistAndProbe(player, request, applied, nowMs);
            }
        }
        return response.setDurable(durable).build();
    }

    /** 记账之后统一走这里：立刻请求存盘，如实回报 durable，不在调用里等落盘。 */
    private boolean persistAndProbe(ScenePlayer player, AssetOpRequest request, boolean expectApplied, long nowMs) {
        player.assetLedger().markPersistRequested(nowMs);
        SceneWorld.SaveRequest save = world.requestSave(player);
        boolean durable = durable(player, request, expectApplied);
        if (save == SceneWorld.SaveRequest.UNCHANGED && !durable) {
            // 没写 = 内存与最近一次落库快照相同；结局既然已在内存里就必然也在快照里。走到这里说明比对或快照维护有 bug
            log.error("[AssetOp] 存盘因与落库快照相同被跳过，但结局不在快照里 player={} stream={} epoch={} seq={}",
                    Long.toUnsignedString(request.getPlayerId()), request.getStreamValue(),
                    Long.toUnsignedString(request.getStreamEpoch()), Long.toUnsignedString(request.getSeq()));
        }
        return durable;
    }

    /**
     * 结局已出现在最近一次确认落库的存档里（只看落库快照，不另建水位）。快照用只读视图 {@link PersistedAssetLedger} 判——与帮会离线读
     * 已落盘账本（guild-economy-spec §4.9）是同一份判定代码，「scene 说 durable」与「调用方离线读到结论」不会分叉。
     */
    private static boolean durable(ScenePlayer player, AssetOpRequest request, boolean expectApplied) {
        PlayerState persisted = player.persistedState();
        if (persisted == null || !persisted.hasAssetLedger()) {
            return false;
        }
        PersistedAssetLedger onDisk = PersistedAssetLedger.restore(persisted.getAssetLedger());
        int stream = request.getStreamValue();
        if (onDisk.invalidReason() != null || onDisk.epochOf(stream) != request.getStreamEpoch()) {
            return false;
        }
        AssetSeqState state = onDisk.classify(stream, request.getStreamEpoch(), request.getSeq());
        if (state != AssetSeqState.APPLIED && state != AssetSeqState.REJECTED) {
            return false;
        }
        if ((state == AssetSeqState.APPLIED) != expectApplied) {
            // 盘上结局与内存结局不同：结局固定被破坏，只可能是 bug。不敢报 durable，让调用方继续重查并告警
            log.error("[AssetOp] 落库快照结局与内存结局不一致 player={} stream={} epoch={} seq={} on_disk={} expect_applied={}",
                    Long.toUnsignedString(request.getPlayerId()), stream, Long.toUnsignedString(request.getStreamEpoch()),
                    Long.toUnsignedString(request.getSeq()), state, expectApplied);
            return false;
        }
        return true;
    }

    private static AssetOpResponse answer(AssetOutcome outcome, int reason) {
        return AssetOpResponse.newBuilder().setOutcome(outcome).setReason(reason).build();
    }

    /** 测试用：流 → 允许的流水原因（只读）。 */
    static Map<Integer, AssetAudit.Reason> allowedTx(AssetStream stream) {
        StreamRule rule = STREAM_RULES.get(stream);
        return rule == null ? Map.of() : rule.allowedTx();
    }

    /** 测试用：全部登记了规则的流。 */
    static List<AssetStream> ruledStreams() {
        return List.copyOf(STREAM_RULES.keySet());
    }
}
