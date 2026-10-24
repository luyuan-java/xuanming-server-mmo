package com.game.inbox.store;

import static com.game.inbox.store.InboxSql.ITEM_COLUMNS;
import static com.game.inbox.store.InboxSql.ITEM_PK;
import static com.game.inbox.store.InboxSql.ITEM_TABLE;
import static com.game.inbox.store.InboxSql.PENDING;
import static com.game.inbox.store.InboxSql.STREAM_COLUMNS;
import static com.game.inbox.store.InboxSql.STREAM_PK;
import static com.game.inbox.store.InboxSql.STREAM_TABLE;
import static com.game.inbox.store.InboxSql.u32;
import static com.game.inbox.store.InboxSql.u64;

import com.game.common.deadline.Deadline;
import com.game.common.player.SceneAdmissionAuthority;
import com.game.api.inbox.InboxCallContract;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.discovery.sql.AuthorityJdbc.Session;
import com.game.inbox.gate.ConsumerGate;
import com.game.inbox.metrics.InboxMetrics;
import com.game.inbox.notify.Notifier;
import com.game.inbox.notify.NotifyResult;
import com.game.inbox.registry.InboxRegistry;
import com.game.inbox.rules.FenceRules;
import com.game.inbox.rules.InboxLabels;
import com.game.inbox.rules.InboxRules;
import com.game.inbox.rules.InboxShards;
import com.game.inbox.rules.InboxTips;
import com.game.proto.TipInfoMessage;
import com.game.proto.player_inbox.AckPlayerInboxRequest;
import com.game.proto.player_inbox.AckPlayerInboxResponse;
import com.game.proto.player_inbox.FetchPlayerInboxRequest;
import com.game.proto.player_inbox.FetchPlayerInboxResponse;
import com.game.proto.player_inbox.PlayerInboxAckItem;
import com.game.proto.player_inbox.PlayerInboxAuditAction;
import com.game.proto.player_inbox.PlayerInboxDecisionReport;
import com.game.proto.player_inbox.PlayerInboxDirective;
import com.game.proto.player_inbox.PlayerInboxDomain;
import com.game.proto.player_inbox.PlayerInboxDomainSpec;
import com.game.proto.player_inbox.PlayerInboxFenceVerdict;
import com.game.proto.player_inbox.PlayerInboxFinalizer;
import com.game.proto.player_inbox.PlayerInboxItem;
import com.game.proto.player_inbox.PlayerInboxItemState;
import com.game.proto.player_inbox.PlayerInboxMessageType;
import com.game.proto.player_inbox.PlayerInboxProducerKind;
import com.game.proto.player_inbox.PlayerInboxVoidReason;
import com.game.proto.player_inbox.player_inbox_item_row;
import com.game.proto.player_inbox.player_inbox_stream;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 收件箱的存储与协议核心：入箱、拉取、确认（mmorpg {@code store_enqueue.go} / {@code store_consume.go}，设计 §6.2、§7.8）。
 * 运维面在 {@link InboxAdminStore}，后台循环用的读写在 {@link InboxMaintenanceStore}。
 *
 * <p><b>并发与锁</b>（设计 §10）：
 * <ul>
 *   <li>所有事务都是 READ COMMITTED，经 {@link AuthorityJdbc#tx}（1213 / 1205 / 9007 有界重试，提交阶段的失败不重做）；
 *       事务体可能被重跑，必须可重跑——一律把结果作为返回值，不往外层变量里写；</li>
 *   <li>锁顺序固定：路由meta→完整zone围栏→角色P→流行（主键 FOR UPDATE）→ 条目行（只按主键，seq 升序）→ 审计行插入。条目行一律先普通读、再按主键条件写，不做二级索引上的加锁读；</li>
 *   <li>每个事务只碰一个 (player, domain)；同一条流内天然串行，不同流之间不存在环；</li>
 *   <li>写前门禁与在线提醒在事务外；消费方原准入、当前owner/E及强制证明在同P内复核，不以外部预读替代提交校验。</li>
 * </ul>
 * 业务拒绝走返回值里的 tip / 围栏结论；基础设施故障抛 {@link SQLException}（调用方回 kPlayerInboxStoreUnavailable）。线程安全（构造后只读）。
 */
public final class InboxStore {

    private static final Logger log = LoggerFactory.getLogger(InboxStore.class);

    /** 入箱提交后那次在线提醒的预算（设计 §6.2 第 8 步：尽力而为，最多 200ms）。 */
    public static final Duration NOTIFY_TIMEOUT = Duration.ofMillis(200);

    /**
     * 一次入箱的输入（设计 §6.2）。生产方契约：requestId 与 issuedAtMs 都按工单只生成一次，重试时原样带回；issuedAtMs 在内容摘要里，
     * 刷新它重试会得到 kPlayerInboxIdempotencyConflict。
     *
     * @param payload  按类型规格序列化之后的<b>最终</b>载荷（GM 的 gm_operator 已由服务端覆盖）
     * @param operator 写进条目 operator_name（必填，≤ 64B）
     * @param note     写进条目 note（≤ 256B）
     * @param audit    同事务写的审计（null = 不写）；seq、内容列在事务里补齐
     */
    public record EnqueueParams(long playerId, PlayerInboxMessageType type, PlayerInboxProducerKind producer, long requestId,
                                long issuedAtMs, byte[] payload, String operator, String note, Audit audit) {
        public EnqueueParams { payload=payload==null?null:payload.clone(); }
        @Override public byte[] payload(){return payload==null?null:payload.clone();}
    }

    /**
     * 审计行里「谁、为什么」的部分（评审 L9：「做了什么」——msg_type / request_id / issued_at_ms / content_sha256 / payload——一律从同一事务里的
     * 条目行复制，不收调用方的值）。
     */
    public record Audit(PlayerInboxAuditAction action, String operator, String peerAddr, String reason, PlayerInboxVoidReason voidReason) {
    }

    /** 入箱结局：tip = 0 时 created = true 是新建，false 是 DUPLICATE（同键同摘要，返回原 seq 与当前状态）。 */
    public record EnqueueResult(int tip, long seq, boolean created, PlayerInboxItemState state) {

        static EnqueueResult reject(int tip) {
            return new EnqueueResult(tip, 0, false, PlayerInboxItemState.PLAYER_INBOX_ITEM_UNSPECIFIED);
        }
    }

    /** 主事务插入撞上唯一键：有并发的同键入箱已提交。回滚后重读唯一键按判重规则返回。 */
    private static final class UniqueRace extends RuntimeException {
        UniqueRace() {
            super(null, null, false, false);
        }
    }

    private final AuthorityJdbc jdbc;
    private final InboxRegistry registry;
    private final Set<PlayerInboxMessageType> allowed;
    private final PlayerDirectory players;
    private final ConsumerGate gate;
    private final Notifier notifier;
    private final InboxMetrics metrics;
    private final LongSupplier nowMs;
    private final ServiceCallSignatures signatures;

    /**
     * @param allowed 入箱白名单（{@code game.inbox.enqueue-types} 解析后的结果；空 = 全部停用）
     * @param nowMs   墙钟（Unix 毫秒；seq 的时间下界、签发窗、行的时间列）
     */
    public InboxStore(AuthorityJdbc jdbc, InboxRegistry registry, Set<PlayerInboxMessageType> allowed, PlayerDirectory players,
                      ConsumerGate gate, Notifier notifier, InboxMetrics metrics, LongSupplier nowMs, ServiceCallSignatures signatures) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.registry = Objects.requireNonNull(registry, "registry");
        registry.checkAllowlist(allowed);
        this.allowed = Set.copyOf(allowed);
        this.players = Objects.requireNonNull(players, "players");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.nowMs = Objects.requireNonNull(nowMs, "nowMs");
        this.signatures = Objects.requireNonNull(signatures, "signatures");
    }

    public InboxRegistry registry() {
        return registry;
    }

    // ================================================================ 入箱（设计 §6.2）

    /**
     * 入箱。处理顺序（判重先于一切依赖当前时间与线上状态的校验；<b>权威判重在流行锁之内</b>）：
     * <ol>
     *   <li>注册表与字段校验（类型已登记、持久类、在白名单、载荷大小 / 解码 / 类型校验；文本字段）；</li>
     *   <li>玩家存在校验（玩家表）。不存在 → PlayerNotFound；查询失败 → 抛（fail-closed，不入箱）；</li>
     *   <li>算 shard 与内容摘要；</li>
     *   <li>判重快路径（事务外普通读唯一键）：同摘要 → DUPLICATE；不同 → CONFLICT。只为省一次加锁，结论以第 7.2 步为准；</li>
     *   <li>签发窗：issued_at_ms ∉ [now − 7d, now + 5min] → IssuedOutOfWindow；</li>
     *   <li>写前门禁（Java 版，见 {@link ConsumerGate}）：消费端没开 → Disabled；读不到 → StoreUnavailable；</li>
     *   <li>补建流行：先普通读探针，没有才在事务外 INSERT IGNORE（next_seq = 1）——有了探针，常态路径不再走「首个插入者回滚、后到者继承间隙锁」
     *       的死锁形状（mmorpg 死锁审计 #23）；</li>
     *   <li>主事务：7.1 流行 FOR UPDATE；7.2 锁内复读唯一键（排在满箱之前：重试与原请求赛跑时重试方一定拿到 DUPLICATE，不会是 FULL）；
     *       7.3 open_count ≥ max_pending → FULL；7.4 seq = max(next_seq, now_ms &lt;&lt; 12)，插入 PENDING 行；7.5 next_seq = seq + 1、open_count + 1、
     *       notify_due_ms = now；7.6 同事务写审计；7.7 提交。7.4 撞唯一键 → 回滚后重读，按 7.2 的规则返回；</li>
     *   <li>提交之后对新建条目发一次在线提醒：尽力而为，最多 200ms，结果不影响返回值；落定才按 next_seq 做 CAS 清 notify_due_ms。</li>
     * </ol>
     */
    public EnqueueResult enqueue(Deadline budget, EnqueueParams p) throws SQLException {
        String typeLabel = InboxLabels.type(p.type());
        EnqueueResult result;
        try {
            result = doEnqueue(budget, p);
        } catch (SQLException | RuntimeException e) {
            metrics.enqueue(typeLabel, "error");
            throw e;
        }
        metrics.enqueue(typeLabel, enqueueLabel(result));
        return result;
    }

    /** 专用Match边界，不借GM签发窗；签名仍有五分钟传输窗，业务issuedAt永久固定。 */
    public com.game.proto.player_inbox.BattleSettlementInboxResponse battleSettlement(Deadline budget,
            com.game.proto.player_inbox.BattleSettlementInboxRequest request, boolean enqueue) throws SQLException {
        var type = PlayerInboxMessageType.PLAYER_INBOX_MSG_BATTLE_SETTLEMENT;
        var producer = PlayerInboxProducerKind.PLAYER_INBOX_PRODUCER_MATCH;
        var domain = PlayerInboxDomain.PLAYER_INBOX_DOMAIN_SCENE_BATTLE_SETTLEMENT;
        byte[] payload = request.getSettlementPayload().toByteArray();
        var response = com.game.proto.player_inbox.BattleSettlementInboxResponse.newBuilder()
                .setPlayerId(request.getPlayerId()).setBattleId(request.getBattleId())
                .setRequestDigest(ServiceCallSignatures.digest(request));
        if (request.getPlayerId() == 0 || request.getBattleId() == 0 || request.getExpectedOwnerAccount().isBlank()
                || request.getExpectedOwnerRevision() == 0 || request.getIssuedAtMs() <= 0
                || request.getIssuedAtMs() > nowMs.getAsLong() + 300_000) throw new SecurityException("战斗来源原身份或时间无效");
        var validation = registry.validatePayload(type, Set.of(type), payload);
        if (validation.tip() != 0) throw new IllegalArgumentException("战斗来源载荷不符合已登记规格");
        com.game.proto.BattleSettlementData settlement;
        try { settlement = com.game.proto.BattleSettlementData.parseFrom(payload); }
        catch (com.google.protobuf.InvalidProtocolBufferException invalid) { throw new IllegalArgumentException("战斗来源载荷损坏", invalid); }
        if (settlement.getBattleId() != request.getBattleId() || settlement.getPlayerId() != request.getPlayerId())
            throw new SecurityException("战斗来源正文身份不符");
        byte[] digest = InboxRules.contentSha256(type, request.getIssuedAtMs(), payload);
        int shard = InboxShards.rowShard(request.getPlayerId());
        if (enqueue) {
            var gated = gate.check();
            if (gated.verdict() != ConsumerGate.Verdict.OPEN) throw new SQLException("战斗来源消费端尚未就绪");
            ensureStream(budget, shard, request.getPlayerId(), domain, nowMs.getAsLong());
        }
        return jdbc.tx(budget, s -> {
            // 所有新写与身份复核同一个P锁；旧收据也必须逐字匹配其原归属。
            InboxSql.Owner owner = InboxSql.lockPlayer(s, budget, request.getPlayerId());
            var existing = readItemByRequest(s, shard, request.getPlayerId(), producer, request.getBattleId());
            if (existing != null) {
                if (existing.getMsgType() != type.getNumber() || existing.getOwnerRevision() != request.getExpectedOwnerRevision()
                        || !existing.getOwnerAccount().equals(request.getExpectedOwnerAccount())
                        || !InboxRules.sameContent(existing.getContentSha256(), digest)
                        || existing.getState() != PENDING && existing.getState() != PlayerInboxItemState.PLAYER_INBOX_ITEM_APPLIED_VALUE)
                    throw new SecurityException("战斗永久收据原件或状态冲突");
                return response.setSeq(existing.getSeq()).setStateValue(existing.getState())
                        .setContentSha256(com.google.protobuf.ByteString.copyFrom(digest)).build();
            }
            if (!enqueue) return response.build();
            if (!owner.account().equals(request.getExpectedOwnerAccount()) || owner.revision() != request.getExpectedOwnerRevision()
                    || owner.released()) throw new SecurityException("战斗来源不能借当前目录替换原归属");
            var params = new EnqueueParams(request.getPlayerId(), type, producer, request.getBattleId(),
                    request.getIssuedAtMs(), payload, "match", "永久战斗终局", null);
            var result = enqueueLocked(s, shard, domain, registry.domain(domain).orElseThrow(), digest, params, owner);
            return response.setErrorMessage(tip(result.tip())).setSeq(result.seq()).setState(result.state())
                    .setContentSha256(com.google.protobuf.ByteString.copyFrom(digest)).build();
        });
    }

    private EnqueueResult doEnqueue(Deadline budget, EnqueueParams p) throws SQLException {
        // 1. 注册表与字段校验（与时间无关）
        if (p.playerId() == 0 || p.requestId() == 0 || p.issuedAtMs() == 0 || !validProducer(p.producer())
                || p.producer() != PlayerInboxProducerKind.PLAYER_INBOX_PRODUCER_GM || p.payload() == null) {
            return EnqueueResult.reject(InboxTips.INVALID_PARAMETER);
        }
        InboxRegistry.Validation validation = registry.validatePayload(p.type(), allowed, p.payload());
        if (validation.tip() != 0) {
            return EnqueueResult.reject(validation.tip());
        }
        int tip = InboxRules.validateText(p.operator(), InboxRules.MAX_OPERATOR_BYTES, true);
        if (tip == 0) {
            tip = InboxRules.validateText(p.note(), InboxRules.MAX_REASON_BYTES, false);
        }
        if (tip != 0) {
            return EnqueueResult.reject(tip);
        }
        PlayerInboxDomain domain = validation.entry().domain();
        PlayerInboxDomainSpec spec = registry.domain(domain)
                .orElseThrow(() -> new IllegalStateException("类型 " + p.type() + " 的域 " + domain + " 没有域规格"));

        // 3. shard 与内容摘要
        int shard = InboxShards.rowShard(p.playerId());
        byte[] digest = InboxRules.contentSha256(p.type(), p.issuedAtMs(), p.payload());

        // 4. 判重快路径
        player_inbox_item_row existing = jdbc.read(budget, s -> readItemByRequest(s, shard, p.playerId(), p.producer(), p.requestId()));
        if (existing != null) {
            return resultForExisting(existing, digest);
        }
        // 永久原收据优先于当前目录存在性；删除后的旧UUID也不能变成一次新业务。
        if (players.ownerEpoch(budget, p.playerId()).isEmpty()) {
            return EnqueueResult.reject(InboxTips.PLAYER_NOT_FOUND);
        }

        // 5. 签发窗
        long now = nowMs.getAsLong();
        if (!InboxRules.issuedWithinWindow(p.issuedAtMs(), now)) {
            return EnqueueResult.reject(InboxTips.ISSUED_OUT_OF_WINDOW);
        }

        // 6. 写前门禁
        ConsumerGate.Result gated = gate.check();
        metrics.consumerGate(gated.verdict().label());
        if (gated.verdict() != ConsumerGate.Verdict.OPEN) {
            log.error("[PlayerInbox] metric=inbox_consumer_gate result={} player={} request_id={} type={}：{}",
                    gated.verdict().label(), Long.toUnsignedString(p.playerId()), Long.toUnsignedString(p.requestId()),
                    InboxLabels.type(p.type()), gated.detail());
            return EnqueueResult.reject(gated.verdict() == ConsumerGate.Verdict.CLOSED ? InboxTips.DISABLED : InboxTips.STORE_UNAVAILABLE);
        }

        // 7. 补建流行
        ensureStream(budget, shard, p.playerId(), domain, now);

        // 8. 主事务
        EnqueueResult result;
        try {
            result = jdbc.tx(budget, s -> {
                InboxSql.Owner owner = InboxSql.lockPlayer(s, budget, p.playerId());
                return enqueueLocked(s, shard, domain, spec, digest, p, owner);
            });
        } catch (UniqueRace race) {
            player_inbox_item_row raced = jdbc.read(budget, s -> readItemByRequest(s, shard, p.playerId(), p.producer(), p.requestId()));
            if (raced == null) {
                throw new SQLException("插入撞上了主键而不是幂等键 player=" + Long.toUnsignedString(p.playerId()) + " request_id="
                        + Long.toUnsignedString(p.requestId()) + "（seq 在流行锁下分配，只可能是收件箱库被恢复成不一致的状态）");
            }
            return resultForExisting(raced, digest);
        }

        // 9. 提交之后发提醒（只对新建条目）
        if (result.created()) {
            NotifyResult notified = notifier.notify(p.playerId(), domain, NOTIFY_TIMEOUT);
            if (notified.settled()) {
                try {
                    clearNotifyDue(Deadline.after(InboxMaintenanceStore.STATEMENT_BUDGET.toMillis()), jdbc, p.playerId(), domain,
                            result.seq() + 1);
                } catch (SQLException e) {
                    log.warn("[PlayerInbox] 提醒已落定但清 notify_due 失败（补发循环会再提醒一次，无害）player={} domain={}: {}",
                            Long.toUnsignedString(p.playerId()), domain.getNumber(), e.toString());
                }
            } else {
                log.info("[PlayerInbox] 入箱后的提醒没落定 player={} domain={} seq={} result={}；留给补发循环", Long.toUnsignedString(p.playerId()),
                        domain.getNumber(), Long.toUnsignedString(result.seq()), notified.label());
            }
        }
        return result;
    }

    private EnqueueResult enqueueLocked(Session s, int shard, PlayerInboxDomain domain, PlayerInboxDomainSpec spec, byte[] digest,
                                        EnqueueParams p, InboxSql.Owner owner) throws SQLException {
        player_inbox_stream stream = lockStream(s, shard, p.playerId(), domain);
        if (stream == null) {
            throw new SQLException("流行在补建之后消失 player=" + Long.toUnsignedString(p.playerId()) + " domain=" + domain.getNumber());
        }
        // 7.2 锁内权威判重（RC 下普通读看得见前一个持锁者已提交的行），7.3 再判满箱
        player_inbox_item_row existing = readItemByRequest(s, shard, p.playerId(), p.producer(), p.requestId());
        if (existing != null) {
            return resultForExisting(existing, digest);
        }
        requireOpenOwners(s, shard, p.playerId(), owner);
        if (Integer.compareUnsigned(stream.getOpenCount(), spec.getMaxPending()) >= 0) {
            return EnqueueResult.reject(InboxTips.FULL);
        }
        // 7.4 时间每次重跑重取：seq 的时间下界按实际提交那一轮算
        long now = nowMs.getAsLong();
        long seq = InboxRules.nextSeq(stream.getNextSeq(), now);
        try {
            s.update("INSERT INTO " + ITEM_TABLE + " (" + ITEM_COLUMNS + ") VALUES (" + InboxSql.placeholders(25) + ")",
                    shard, u64(p.playerId()), u32(domain.getNumber()), u64(seq), u32(p.type().getNumber()), u32(p.producer().getNumber()),
                    u64(p.requestId()), digest, p.payload(), PENDING, PlayerInboxDirective.PLAYER_INBOX_DIRECTIVE_NONE_VALUE,
                    PlayerInboxVoidReason.PLAYER_INBOX_VOID_UNSPECIFIED_VALUE, 0, u64(0), u64(0), 0,
                    PlayerInboxFinalizer.PLAYER_INBOX_FINALIZER_UNSPECIFIED_VALUE, u64(0), u64(p.issuedAtMs()), u64(now), u64(now),
                    p.operator(), p.note() == null ? "" : p.note(), owner.account(), u64(owner.revision()));
        } catch (SQLException e) {
            if (InboxSql.duplicateKey(e)) {
                throw new UniqueRace();
            }
            throw e;
        }
        // 7.5 notify_due_ms = now：提交后那次提醒成功才以 next_seq 为令牌清零，否则留给补发循环
        requireOne(s.update("UPDATE " + STREAM_TABLE + " SET `next_seq` = ?, `open_count` = ?, `notify_due_ms` = ?, `updated_ms` = ? WHERE "
                        + STREAM_PK, u64(seq + 1), u32(stream.getOpenCount() + 1), u64(now), u64(now), shard, u64(p.playerId()),
                u32(domain.getNumber())), "推进流行");
        // 7.6 同事务审计
        if (p.audit() != null) {
            player_inbox_item_row item = readItem(s, shard, p.playerId(), domain.getNumber(), seq);
            if (item == null) {
                throw new SQLException("审计找不到刚插入的条目 player=" + Long.toUnsignedString(p.playerId()) + " seq=" + Long.toUnsignedString(seq));
            }
            insertAudit(s, item, p.audit(), now);
        }
        return new EnqueueResult(0, seq, true, PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING);
    }

    private static EnqueueResult resultForExisting(player_inbox_item_row row, byte[] digest) throws SQLException {
        if (row.getState() != PENDING && !InboxRules.terminal(row.getState()))
            throw new SQLException("收件箱原收据状态未知，不能伪装重复成功");
        if (InboxRules.sameContent(row.getContentSha256(), digest)) {
            return new EnqueueResult(0, row.getSeq(), false, InboxRules.stateOf(row.getState()));
        }
        return EnqueueResult.reject(InboxTips.IDEMPOTENCY_CONFLICT);
    }

    private static boolean validProducer(PlayerInboxProducerKind producer) {
        return producer != null && producer != PlayerInboxProducerKind.UNRECOGNIZED
                && producer != PlayerInboxProducerKind.PLAYER_INBOX_PRODUCER_UNSPECIFIED;
    }

    /** 入箱结局 → {@code game.inbox.enqueue{result}}（封闭集合，与 mmorpg 同口径）。 */
    static String enqueueLabel(EnqueueResult result) {
        int tip = result.tip();
        if (tip == 0) {
            return result.created() ? "created" : "duplicate";
        }
        if (tip == InboxTips.IDEMPOTENCY_CONFLICT) {
            return "conflict";
        } else if (tip == InboxTips.FULL) {
            return "full";
        } else if (tip == InboxTips.DISABLED) {
            return "disabled";
        } else if (tip == InboxTips.UNKNOWN_TYPE) {
            return "unknown_type";
        } else if (tip == InboxTips.PAYLOAD_TOO_LARGE) {
            return "payload_too_large";
        } else if (tip == InboxTips.PAYLOAD_INVALID) {
            return "payload_invalid";
        } else if (tip == InboxTips.TEXT_TOO_LONG) {
            return "text_too_long";
        } else if (tip == InboxTips.ISSUED_OUT_OF_WINDOW) {
            return "out_of_window";
        } else if (tip == InboxTips.PLAYER_NOT_FOUND) {
            return "player_not_found";
        } else if (tip == InboxTips.STORE_UNAVAILABLE) {
            return "error";
        }
        return "invalid";
    }

    /** 补建流行：普通读探针，没有才 INSERT IGNORE（独立短事务，1213 / 1205 / 9007 有界重试）。 */
    private void ensureStream(Deadline budget, int shard, long playerId, PlayerInboxDomain domain, long now) throws SQLException {
        player_inbox_stream existing = jdbc.read(budget, s -> readStream(s, shard, playerId, domain.getNumber()));
        if (existing != null) {
            return;
        }
        jdbc.tx(budget, s -> {
            InboxSql.lockPlayer(s, budget, playerId);
            return s.update("INSERT IGNORE INTO " + STREAM_TABLE + " (" + STREAM_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?)",
                shard, u64(playerId), u32(domain.getNumber()), u64(1), u64(0), 0, u64(0), u64(now), u64(now));
        });
    }

    // ================================================================ Fetch（设计 §7.8）

    /**
     * 按 seq 升序拉 PENDING 条目并推进围栏：
     * <ol start="0">
     *   <li>核scene完整请求签名，事务先持路由/P，重核SQL完整原准入与所有未终局条目原owner；空流和无流同样核验；</li>
     *   <li>流行 FOR UPDATE，判围栏；STALE / EPOCH_REQUIRED / EPOCH_AHEAD 不写直接返回；</li>
     *   <li>CursorAhead：consumer_decided_through ≥ next_seq → 告警并 next_seq = 水位 + 1，照常投递；</li>
     *   <li>普通读 PENDING 行：主键范围扫描、seq 升序、LIMIT max + 1；</li>
     *   <li>每一行按主键条件写 delivery_count + 1、last_delivered_epoch、last_delivered_ms（条件 state = PENDING）；</li>
     *   <li>FIRST / ADVANCE 写回 fence_epoch（与抬过的 next_seq 一起）；提交。</li>
     * </ol>
     *
     * @param peer 调用方地址（只进日志）
     */
    public FetchPlayerInboxResponse fetch(Deadline budget, FetchPlayerInboxRequest req, String peer) throws SQLException {
        if (!InboxCallContract.CALLER.equals(signatures.verify(InboxCallContract.FETCH, req)))
            throw new SecurityException("收件箱消费调用方无权访问");
        FetchPlayerInboxResponse.Builder resp = FetchPlayerInboxResponse.newBuilder()
                .setPlayerId(req.getPlayerId()).setDomainValue(req.getDomainValue()).setCorrelationId(req.getCorrelationId());
        PlayerInboxDomain domain = req.getDomain();
        String domainLabel = InboxLabels.domain(domain);
        if (registry.domain(domain).isEmpty() || req.getPlayerId() == 0) {
            return resp.setErrorMessage(tip(InboxTips.INVALID_PARAMETER)).build();
        }
        long playerId = req.getPlayerId();
        long reqEpoch = req.getOwnerEpoch();
        if (reqEpoch == 0) {
            PlayerInboxFenceVerdict verdict = PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_EPOCH_REQUIRED;
            reportFenceReject(budget, "Fetch", verdict, playerId, domain, reqEpoch, 0, req.getSceneNodeId(), peer);
            metrics.fetch(domainLabel, FenceRules.label(verdict), "fast");
            return resp.setFence(verdict).setErrorMessage(tip(InboxTips.EPOCH_REQUIRED)).build();
        }
        if (!req.hasAdmission() || req.getAdmission().getPlayerId() != playerId || req.getAdmission().getOwnerEpoch() != reqEpoch)
            throw new SecurityException("收件箱请求与原入场身份不一致");
        long decidedThrough = req.getConsumerDecidedThrough();
        int shard = InboxShards.rowShard(playerId);
        int maxItems = InboxRules.clampItems(req.getMaxItems(), InboxRules.FETCH_DEFAULT_ITEMS, InboxRules.FETCH_MAX_ITEMS);
        FetchLocked locked = jdbc.tx(budget, s -> {
            InboxSql.Owner owner = InboxSql.lockPlayer(s, budget, playerId);
            SceneAdmissionAuthority.require(s.connection(), req.getAdmission(), budget);
            requireOpenOwners(s, shard, playerId, owner);
            return fetchLocked(s, shard, playerId, domain, reqEpoch, owner.epoch(), decidedThrough, maxItems);
        });
        resp.setFence(locked.verdict());
        if (locked.verdict() == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_NO_STREAM) return resp.build();
        if (!FenceRules.allows(locked.verdict())) {
            resp.setErrorMessage(tip(FenceRules.rejectTip(locked.verdict())));
            reportFenceReject(budget, "Fetch", locked.verdict(), playerId, domain, reqEpoch, locked.lockedFence(), req.getSceneNodeId(), peer);
            metrics.fetch(domainLabel, FenceRules.label(locked.verdict()), "locked");
            return resp.build();
        }
        resp.addAllItems(locked.items()).setHasMore(locked.hasMore());
        metrics.fetch(domainLabel, FenceRules.label(locked.verdict()), "locked");
        // 终态永久保留后不能为指标COUNT全历史；只记录本次有限查询实际读回的候选数。
        metrics.fetchScannedRows(locked.items().size()+(locked.hasMore()?1:0));
        if (locked.cursorAhead()) {
            metrics.cursorAhead();
            log.error("[PlayerInbox] CursorAhead player={} domain={} consumer_decided_through={} 已把 next_seq 抬到 {}："
                            + "收件箱库被按备份恢复过；水位以下恢复回来的旧行由 IB-9 的回放核对处理", Long.toUnsignedString(playerId),
                    domain.getNumber(), Long.toUnsignedString(decidedThrough), Long.toUnsignedString(locked.raisedTo()));
        }
        for (PlayerInboxItem item : locked.items()) {
            if (Integer.compareUnsigned(item.getDeliveryCount(), InboxRules.DELIVERY_WARN_THRESHOLD) >= 0) {
                log.error("[PlayerInbox] 条目已投递 {} 次仍没确认 player={} domain={} seq={} msg_type={} scene_node_id={} peer={}："
                                + "疑似毒消息，考虑 VoidItem(QUARANTINED)", Integer.toUnsignedString(item.getDeliveryCount()),
                        Long.toUnsignedString(playerId), domain.getNumber(), Long.toUnsignedString(item.getSeq()), item.getMsgTypeValue(),
                        Integer.toUnsignedString(req.getSceneNodeId()), peer);
            }
        }
        return resp.build();
    }

    private record FetchLocked(PlayerInboxFenceVerdict verdict, long lockedFence, List<PlayerInboxItem> items, boolean hasMore,
                               boolean cursorAhead, long raisedTo) {

        static FetchLocked rejected(PlayerInboxFenceVerdict verdict, long lockedFence) {
            return new FetchLocked(verdict, lockedFence, List.of(), false, false, 0);
        }
    }

    private FetchLocked fetchLocked(Session s, int shard, long playerId, PlayerInboxDomain domain, long reqEpoch, long r,
                                    long decidedThrough, int maxItems) throws SQLException {
        player_inbox_stream stream = lockStream(s, shard, playerId, domain);
        if (stream == null) {
            return FetchLocked.rejected(PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_NO_STREAM, 0);
        }
        long lockedFence = stream.getFenceEpoch();
        PlayerInboxFenceVerdict verdict = FenceRules.decide(reqEpoch, lockedFence, r);
        if (!FenceRules.allows(verdict)) {
            return FetchLocked.rejected(verdict, lockedFence);
        }
        long now = nowMs.getAsLong();
        boolean cursorAhead = InboxRules.cursorAhead(stream.getNextSeq(), decidedThrough);
        long nextSeq = InboxRules.raiseNextSeq(stream.getNextSeq(), decidedThrough);
        List<player_inbox_item_row> pending = s.list("SELECT " + ITEM_COLUMNS + " FROM " + ITEM_TABLE
                        + " WHERE `shard` = ? AND `player_id` = ? AND `domain` = ? AND `state` = ? ORDER BY `seq` ASC LIMIT ?",
                InboxSql::item, shard, u64(playerId), u32(domain.getNumber()), PENDING, maxItems + 1);
        boolean hasMore = pending.size() > maxItems;
        if (hasMore) {
            pending = pending.subList(0, maxItems);
        }
        List<PlayerInboxItem> items = new ArrayList<>(pending.size());
        for (player_inbox_item_row row : pending) {
            requireOne(s.update("UPDATE " + ITEM_TABLE + " SET `delivery_count` = `delivery_count` + 1, `last_delivered_epoch` = ?,"
                            + " `last_delivered_ms` = ?, `updated_ms` = ? WHERE " + ITEM_PK + " AND `state` = ?", u64(reqEpoch), u64(now), u64(now),
                    shard, u64(playerId), u32(domain.getNumber()), u64(row.getSeq()), PENDING), "标记投递");
            items.add(InboxRules.deliveredItem(row));
        }
        if (FenceRules.writes(verdict) || cursorAhead) {
            long fence = FenceRules.writes(verdict) ? reqEpoch : lockedFence;
            requireOne(s.update("UPDATE " + STREAM_TABLE + " SET `fence_epoch` = ?, `next_seq` = ?, `updated_ms` = ? WHERE " + STREAM_PK,
                    u64(fence), u64(nextSeq), u64(now), shard, u64(playerId), u32(domain.getNumber())), "写围栏");
        }
        return new FetchLocked(verdict, lockedFence, items, hasMore, cursorAhead, nextSeq);
    }

    // ================================================================ Ack（设计 §7.7、§7.8）

    /**
     * 登记消费方<b>已落盘</b>的结局：锁流行、判围栏（同样的 R 规则），然后按 seq 升序逐条判定（{@link InboxRules#decideAck}），终结时用主键条件写；
     * 最后写回 open_count、需要时写回 fence_epoch。没有流就不可能有投递过的条目：逐条回 UNKNOWN_SEQ（协议异常，消费方停用并告警）。
     */
    public AckPlayerInboxResponse ack(Deadline budget, AckPlayerInboxRequest req, String peer) throws SQLException {
        if (!InboxCallContract.CALLER.equals(signatures.verify(InboxCallContract.ACK, req)))
            throw new SecurityException("收件箱消费调用方无权访问");
        AckPlayerInboxResponse.Builder resp = AckPlayerInboxResponse.newBuilder()
                .setPlayerId(req.getPlayerId()).setDomainValue(req.getDomainValue()).setCorrelationId(req.getCorrelationId());
        PlayerInboxDomain domain = req.getDomain();
        String domainLabel = InboxLabels.domain(domain);
        if (registry.domain(domain).isEmpty() || req.getPlayerId() == 0 || !InboxRules.validAckReports(req.getDecisionsList())) {
            return resp.setErrorMessage(tip(InboxTips.INVALID_PARAMETER)).build();
        }
        long playerId = req.getPlayerId();
        long reqEpoch = req.getOwnerEpoch();
        if (reqEpoch == 0) {
            PlayerInboxFenceVerdict verdict = PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_EPOCH_REQUIRED;
            reportFenceReject(budget, "Ack", verdict, playerId, domain, reqEpoch, 0, req.getSceneNodeId(), peer);
            metrics.ack(domainLabel, FenceRules.label(verdict));
            return resp.setFence(verdict).setErrorMessage(tip(InboxTips.EPOCH_REQUIRED)).build();
        }
        if (!req.hasAdmission() || req.getAdmission().getPlayerId() != playerId || req.getAdmission().getOwnerEpoch() != reqEpoch)
            throw new SecurityException("收件箱请求与原入场身份不一致");
        int shard = InboxShards.rowShard(playerId);
        AckLocked locked = jdbc.tx(budget, s -> {
            InboxSql.Owner owner = InboxSql.lockPlayer(s, budget, playerId);
            SceneAdmissionAuthority.require(s.connection(), req.getAdmission(), budget);
            requireOpenOwners(s, shard, playerId, owner);
            return ackLocked(s, shard, playerId, domain, req, reqEpoch, owner.epoch());
        });
        resp.setFence(locked.verdict());
        if (locked.verdict() == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_NO_STREAM)
            return resp.addAllResults(locked.results()).build();
        if (!FenceRules.allows(locked.verdict())) {
            resp.setErrorMessage(tip(FenceRules.rejectTip(locked.verdict())));
            reportFenceReject(budget, "Ack", locked.verdict(), playerId, domain, reqEpoch, locked.lockedFence(), req.getSceneNodeId(), peer);
            metrics.ack(domainLabel, FenceRules.label(locked.verdict()));
            return resp.build();
        }
        resp.addAllResults(locked.results());
        reportAckResults(playerId, domain, req, locked.results(), peer);
        return resp.build();
    }

    private record AckLocked(PlayerInboxFenceVerdict verdict, long lockedFence, List<PlayerInboxAckItem> results) {
    }

    private AckLocked ackLocked(Session s, int shard, long playerId, PlayerInboxDomain domain, AckPlayerInboxRequest req, long reqEpoch,
                                long r) throws SQLException {
        player_inbox_stream stream = lockStream(s, shard, playerId, domain);
        if (stream == null) {
            List<PlayerInboxAckItem> absent = req.getDecisionsList().stream().map(report -> PlayerInboxAckItem.newBuilder()
                    .setSeq(report.getSeq()).setResult(com.game.proto.player_inbox.PlayerInboxAckResult.PLAYER_INBOX_ACK_UNKNOWN_SEQ).build()).toList();
            return new AckLocked(PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_NO_STREAM, 0, absent);
        }
        long lockedFence = stream.getFenceEpoch();
        PlayerInboxFenceVerdict verdict = FenceRules.decide(reqEpoch, lockedFence, r);
        if (!FenceRules.allows(verdict)) {
            return new AckLocked(verdict, lockedFence, List.of());
        }
        Map<Long, player_inbox_item_row> rows = readItemsBySeq(s, shard, playerId, domain, req.getDecisionsList());
        long now = nowMs.getAsLong();
        int finalized = 0;
        List<PlayerInboxAckItem> results = new ArrayList<>(req.getDecisionsCount());
        for (PlayerInboxDecisionReport report : req.getDecisionsList()) {
            var original = rows.get(report.getSeq());
            if (original != null && original.getMsgType() == PlayerInboxMessageType.PLAYER_INBOX_MSG_BATTLE_SETTLEMENT_VALUE
                    && report.getOutcome() != com.game.proto.player_inbox.PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_APPLIED)
                throw new SQLException("战斗来源只能由真实SQL落盘的APPLIED终结");
            InboxRules.AckDecision decision = InboxRules.decideAck(original, report);
            if (decision.finalizes()) {
                requireOne(s.update("UPDATE " + ITEM_TABLE + " SET `state` = ?, `finalized_by` = ?, `decided_epoch` = ?, `outcome_reason` = ?,"
                                + " `updated_ms` = ? WHERE " + ITEM_PK + " AND `state` = ?", decision.state().getNumber(),
                        PlayerInboxFinalizer.PLAYER_INBOX_FINALIZER_CONSUMER_VALUE, u64(reqEpoch), u32(InboxRules.ackOutcomeReason(report)),
                        u64(now), shard, u64(playerId), u32(domain.getNumber()), u64(report.getSeq()), PENDING), "终结条目");
                finalized++;
            }
            results.add(PlayerInboxAckItem.newBuilder().setSeq(report.getSeq()).setResult(decision.result()).setState(decision.state()).build());
        }
        if (finalized > 0 || FenceRules.writes(verdict)) {
            int open = InboxRules.subtractOpenCount(stream.getOpenCount(), finalized);
            if (Integer.compareUnsigned(finalized, stream.getOpenCount()) > 0) {
                log.error("[PlayerInbox] open_count 下溢 player={} domain={} open_count={} finalized={}；钳到 0", Long.toUnsignedString(playerId),
                        domain.getNumber(), Integer.toUnsignedString(stream.getOpenCount()), finalized);
            }
            long fence = FenceRules.writes(verdict) ? reqEpoch : lockedFence;
            requireOne(s.update("UPDATE " + STREAM_TABLE + " SET `open_count` = ?, `fence_epoch` = ?, `updated_ms` = ? WHERE " + STREAM_PK,
                    u32(open), u64(fence), u64(now), shard, u64(playerId), u32(domain.getNumber())), "确认后更新流行");
        }
        return new AckLocked(verdict, lockedFence, results);
    }

    /** 逐条结果的指标；协议异常与 SETTLED_UNKNOWN 记 ERROR（带发送方身份 peer / scene_node_id，评审 L10）。 */
    private void reportAckResults(long playerId, PlayerInboxDomain domain, AckPlayerInboxRequest req, List<PlayerInboxAckItem> results,
                                  String peer) {
        String domainLabel = InboxLabels.domain(domain);
        for (int i = 0; i < results.size(); i++) {
            PlayerInboxAckItem result = results.get(i);
            PlayerInboxDecisionReport report = i < req.getDecisionsCount() ? req.getDecisions(i) : PlayerInboxDecisionReport.getDefaultInstance();
            String label = InboxRules.ackMetricLabel(result.getResult(), result.getState());
            metrics.ack(domainLabel, label);
            if (label.equals("finalized_settled_unknown")) {
                log.error("[PlayerInbox] 条目以 SETTLED_UNKNOWN 收尾 player={} domain={} seq={} request_id={} epoch={} peer={} scene_node_id={}："
                                + "消费方水位已过它、原结局已挤出最近结局环；按对账核对", Long.toUnsignedString(playerId), domain.getNumber(),
                        Long.toUnsignedString(result.getSeq()), Long.toUnsignedString(report.getRequestId()),
                        Long.toUnsignedString(req.getOwnerEpoch()), peer, Integer.toUnsignedString(req.getSceneNodeId()));
            } else if (InboxRules.ackAnomaly(result.getResult())) {
                log.error("[PlayerInbox] Ack 协议异常 result={} player={} domain={} seq={} reported_outcome={} reported_request_id={} row_state={}"
                                + " epoch={} peer={} scene_node_id={}：行不变；消费方会停用这个域", InboxLabels.ackResult(result.getResult()),
                        Long.toUnsignedString(playerId), domain.getNumber(), Long.toUnsignedString(result.getSeq()), report.getOutcomeValue(),
                        Long.toUnsignedString(report.getRequestId()), result.getStateValue(), Long.toUnsignedString(req.getOwnerEpoch()), peer,
                        Integer.toUnsignedString(req.getSceneNodeId()));
            }
        }
    }

    // ================================================================ 围栏辅助

    /** 事务外读一次 R（玩家表的 owner_epoch）；玩家不存在按 0（任何非 0 的请求都会判 EPOCH_AHEAD，fail-closed）。读失败抛。 */
    private long epochBound(Deadline budget, long playerId) throws SQLException {
        OptionalLong epoch = players.ownerEpoch(budget, playerId);
        return epoch.orElse(0);
    }

    /**
     * 围栏拒绝的日志与指标。STALE 返回前再读一次 R 做分类：R ≤ req 记 fence_regressed（critical），否则 zombie；分类读失败只记 unclassified。
     */
    private void reportFenceReject(Deadline budget, String rpc, PlayerInboxFenceVerdict verdict, long playerId, PlayerInboxDomain domain,
                                   long req, long fence, int sceneNodeId, String peer) {
        String who = "player=" + Long.toUnsignedString(playerId) + " domain=" + domain.getNumber() + " req_epoch=" + Long.toUnsignedString(req)
                + " fence=" + Long.toUnsignedString(fence) + " peer=" + peer + " scene_node_id=" + Integer.toUnsignedString(sceneNodeId);
        switch (verdict) {
            case PLAYER_INBOX_FENCE_STALE -> {
                FenceRules.StaleKind kind = FenceRules.StaleKind.UNCLASSIFIED;
                try {
                    kind = FenceRules.classifyStale(req, epochBound(budget, playerId));
                } catch (SQLException | RuntimeException e) {
                    log.debug("[PlayerInbox] STALE 分类读 R 失败: {}", e.toString());
                }
                if (kind == FenceRules.StaleKind.FENCE_REGRESSED) {
                    metrics.fenceRegressed();
                    log.error("[PlayerInbox] {} STALE 分类为 fence_regressed {}：玩家表的 owner_epoch 倒退了（库回档 / 人工改库）；"
                            + "先定真源、把 owner_epoch 抬过收件箱的 max_fence_epoch，不要归零围栏", rpc, who);
                } else {
                    log.info("[PlayerInbox] {} STALE ({}) {}", rpc, kind.label(), who);
                }
            }
            case PLAYER_INBOX_FENCE_EPOCH_AHEAD -> {
                metrics.epochAhead();
                log.error("[PlayerInbox] {} EPOCH_AHEAD {}：请求的 epoch 超过玩家表的 owner_epoch（缺陷或伪造）；没有改任何状态", rpc, who);
            }
            case PLAYER_INBOX_FENCE_EPOCH_REQUIRED ->
                    log.error("[PlayerInbox] {} EPOCH_REQUIRED {}：owner_epoch = 0 一律拒绝（fail-closed）", rpc, who);
            default -> {
            }
        }
    }

    // ================================================================ 共用的读写（同包的运维 / 维护存储也用）

    static player_inbox_stream readStream(Session s, int shard, long playerId, int domain) throws SQLException {
        return s.one("SELECT " + STREAM_COLUMNS + " FROM " + STREAM_TABLE + " WHERE " + STREAM_PK, InboxSql::stream, shard, u64(playerId),
                u32(domain));
    }

    static player_inbox_stream lockStream(Session s, int shard, long playerId, PlayerInboxDomain domain) throws SQLException {
        return s.one("SELECT " + STREAM_COLUMNS + " FROM " + STREAM_TABLE + " WHERE " + STREAM_PK + " FOR UPDATE", InboxSql::stream, shard,
                u64(playerId), u32(domain.getNumber()));
    }

    static player_inbox_item_row readItemByRequest(Session s, int shard, long playerId, PlayerInboxProducerKind producer, long requestId)
            throws SQLException {
        return s.one("SELECT " + ITEM_COLUMNS + " FROM " + ITEM_TABLE
                        + " WHERE `shard` = ? AND `player_id` = ? AND `producer_kind` = ? AND `request_id` = ?", InboxSql::item, shard,
                u64(playerId), u32(producer.getNumber()), u64(requestId));
    }

    static player_inbox_item_row readItem(Session s, int shard, long playerId, int domain, long seq) throws SQLException {
        return s.one("SELECT " + ITEM_COLUMNS + " FROM " + ITEM_TABLE + " WHERE " + ITEM_PK, InboxSql::item, shard, u64(playerId), u32(domain),
                u64(seq));
    }

    /** 多读一项即可发现损坏计数，未知状态也不能被PENDING筛选静默漏掉；上限取正规域注册表。 */
    private void requireOpenOwners(Session s, int shard, long playerId, InboxSql.Owner owner) throws SQLException {
        int maximum = 0;
        for (PlayerInboxDomain registered : registry.domains()) maximum = Math.addExact(maximum, registry.domain(registered).orElseThrow().getMaxPending());
        List<player_inbox_item_row> open = s.list("SELECT " + ITEM_COLUMNS + " FROM " + ITEM_TABLE
                + " WHERE `shard`=? AND `player_id`=? AND (`state` IS NULL OR `state` NOT IN " + InboxSql.TERMINAL_STATES + ") LIMIT ?",
                InboxSql::item, shard, u64(playerId), Math.addExact(maximum, 1));
        if (open.size() > maximum) throw new SQLException("收件箱未终局行超过注册域上限，拒绝继续");
        for (player_inbox_item_row row : open) {
            if (registry.domain(PlayerInboxDomain.forNumber(row.getDomain())).isEmpty()) throw new SQLException("收件箱待办域未登记，拒绝继续");
            InboxSql.requireOriginalOwner(row, owner);
        }
    }


    private static Map<Long, player_inbox_item_row> readItemsBySeq(Session s, int shard, long playerId, PlayerInboxDomain domain,
                                                                  List<PlayerInboxDecisionReport> reports) throws SQLException {
        List<Object> args = new ArrayList<>(3 + reports.size());
        args.add(shard);
        args.add(u64(playerId));
        args.add(u32(domain.getNumber()));
        for (PlayerInboxDecisionReport report : reports) {
            args.add(u64(report.getSeq()));
        }
        List<player_inbox_item_row> rows = s.list("SELECT " + ITEM_COLUMNS + " FROM " + ITEM_TABLE
                + " WHERE `shard` = ? AND `player_id` = ? AND `domain` = ? AND `seq` IN (" + InboxSql.placeholders(reports.size()) + ")",
                InboxSql::item, args.toArray());
        Map<Long, player_inbox_item_row> bySeq = new HashMap<>();
        for (player_inbox_item_row row : rows) {
            bySeq.put(row.getSeq(), row);
        }
        return bySeq;
    }

    /**
     * 在调用方事务里写一行审计（INSERT IGNORE：每条目每种动作一行，重复调用不报错）。内容列一律从 {@code item}（同一事务里读到的条目行）复制，
     * 调用方只给「谁、为什么」与 void_reason（评审 L9）；shard 按 player_id 重算。零字节载荷写空串而不是 NULL。
     */
    static void insertAudit(Session s, player_inbox_item_row item, Audit audit, long createdMs) throws SQLException {
        s.update("INSERT IGNORE INTO " + InboxSql.AUDIT_TABLE + " (" + InboxSql.AUDIT_COLUMNS + ") VALUES (" + InboxSql.placeholders(15) + ")",
                InboxShards.rowShard(item.getPlayerId()), u64(item.getPlayerId()), u32(item.getDomain()), u64(item.getSeq()),
                audit.action().getNumber(), nullToEmpty(audit.operator()), nullToEmpty(audit.peerAddr()), nullToEmpty(audit.reason()),
                u64(createdMs), u32(item.getMsgType()), u64(item.getRequestId()), u64(item.getIssuedAtMs()),
                item.getContentSha256().toByteArray(), item.getPayload().toByteArray(),
                audit.voidReason() == null ? 0 : audit.voidReason().getNumber());
    }

    /** 按 next_seq CAS 清 notify_due_ms（落定的提醒）：这期间又有新的入箱 → next_seq 变了，CAS 失败，留给补发循环（最多多发一次）。 */
    static boolean clearNotifyDue(Deadline budget, AuthorityJdbc jdbc, long playerId, PlayerInboxDomain domain, long expectNextSeq)
            throws SQLException {
        int shard = InboxShards.rowShard(playerId);
        return jdbc.tx(budget, s -> {
            InboxSql.lockPlayer(s, budget, playerId);
            return s.update("UPDATE " + STREAM_TABLE + " SET `notify_due_ms` = 0 WHERE " + STREAM_PK
                + " AND `next_seq` = ? AND `notify_due_ms` <> 0", shard, u64(playerId), u32(domain.getNumber()), u64(expectNextSeq));
        }) == 1;
    }

    /** 所有条件写都在流行锁下：命中数不对只可能是缺陷或数据已矛盾，抛出让整个事务回滚（fail-closed）。 */
    static void requireOne(int affected, String what) throws SQLException {
        if (affected != 1) {
            throw new SQLException(what + " 命中 " + affected + " 行，应为 1（流行锁下状态被改了？）");
        }
    }

    static TipInfoMessage tip(int id) {
        return TipInfoMessage.newBuilder().setId(id).build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
