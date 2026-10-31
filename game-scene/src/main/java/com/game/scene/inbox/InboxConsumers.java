package com.game.scene.inbox;

import com.game.proto.player_inbox.GmCurrencyGainPayload;
import com.game.proto.player_inbox.PlayerInboxDomain;
import com.game.proto.player_inbox.PlayerInboxDomainSpec;
import com.game.proto.player_inbox.PlayerInboxMessageType;
import com.game.proto.player_inbox.PlayerInboxPlayerInboxOuterClass;
import com.game.proto.player_inbox.PlayerInboxTypeSpec;
import com.game.scene.currency.CurrencyService;
import com.game.scene.player.Wallet;
import com.game.scene.world.ScenePlayer;
import com.game.table.PlayerInboxErrorTip;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 本二进制消费哪些域、每种类型由谁执行（mmorpg C++ {@code player_inbox_registry} 与 {@code gm_sanction_inbox_executor}，设计 §3.2、§7.6）。
 * 域规格（是否要求非战斗）从同步来的契约 proto 枚举选项读；执行器表是一张按类型平铺的表（不用宏 / 反射生成），{@link #selfCheck} 双向核对。
 *
 * <p><b>v1 的执行器只有封锁 / 解封货币获取</b>。挂欠款（{@code GM_ATTACH_CURRENCY_DEBT}）在 v1 不开放（收件箱入箱启动即拒，PARITY 第 ⑪ 条），
 * Java 版也没有它的执行器：万一收到（版本错位），按「本二进制不认识的类型」在这一条停下、停用该域、不记 REJECTED，运维挂 VOID 即可解开——
 * 欠款的合并语义、撤销类型与单笔上限一起做完之后再补执行器（同 mmorpg 解禁顺序）。
 *
 * <p>战斗结算域（{@link #withBattle}）的执行器结局只有 APPLIED / DEFER / 版本错位三种，没有 REJECTED：永久终局只能由真实应用终结。
 */
public final class InboxConsumers {

    /** 执行器的结局。 */
    public sealed interface ExecResult permits Applied, Rejected, Deferred, VersionSkew {
    }

    /** 已生效：记 APPLIED。 */
    public record Applied() implements ExecResult {
    }

    /** 确定性拒绝（参数 / 状态不可能成功）：记 REJECTED(reason)。GM 域认识的类型但载荷解码失败按 REJECTED(kPlayerInboxPayloadInvalid)。 */
    public record Rejected(int reason) implements ExecResult {
    }

    /**
     * 暂时做不了（会随状态变化自愈，不能钉成终态）：在这一条停下，剩下的本地退避后续做（{@code inbox_deferred reason=executor_defer}）。
     * GM 域的两个执行器不会返回它；战斗域在交出冻结、金币溢出、持久组件暂不可写等情况下返回它。
     *
     * @param cause 低基数的原因标签，只进 WARN 日志（例如战斗结算的 {@code deferred_frozen} / {@code deferred_currency} / {@code deferred_ledger}）
     */
    public record Deferred(int reason, String cause) implements ExecResult {
        public Deferred {
            cause = cause == null || cause.isBlank() ? "unspecified" : cause;
        }

        public Deferred(int reason) {
            this(reason, "unspecified");
        }
    }

    /**
     * 版本错位（同 mmorpg C++ {@code PlayerInboxExecKind::kVersionSkew}）：载荷本二进制解不开或带本版本不认识的字段，本地重试永远不会成功。
     * 与「不认识的类型」同口径：在这一条停下、不记账、不确认这一条，停用该域（{@code inbox_halted reason=version_skew}），已定前缀照常确认；
     * 绝不能当成终态的 REJECTED，也不走 DEFER 的本地续做——这一行留在服务端 PENDING，实例在新版本节点上重建后重拉。
     */
    public record VersionSkew(int reason) implements ExecResult {
    }

    /** 执行一条新条目（场景逻辑线程上；效果与记账在同一个任务里）。 */
    @FunctionalInterface
    public interface Executor {
        ExecResult execute(ScenePlayer player, ByteString payload);
    }

    /** 一个消费域的规则。 */
    public record DomainRule(int domain, boolean requiresOutOfBattle) {
    }

    static final int PAYLOAD_INVALID = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxPayloadInvalid_VALUE;

    private final Map<PlayerInboxMessageType, Executor> executors;
    private final Map<Integer, DomainRule> domains;
    private final String brokenDetail;

    private InboxConsumers(Map<PlayerInboxMessageType, Executor> executors) {
        this.executors = Collections.unmodifiableMap(new EnumMap<>(executors));
        TreeMap<Integer, DomainRule> rules = new TreeMap<>(Integer::compareUnsigned);
        String broken = null;
        for (PlayerInboxMessageType type : executors.keySet()) {
            PlayerInboxTypeSpec spec = typeSpec(type);
            if (spec == null) {
                broken = "类型 " + type + " 有执行器却没有 (OptionPlayerInboxType) 规格";
                break;
            }
            PlayerInboxDomainSpec domainSpec = domainSpec(spec.getDomain());
            if (domainSpec == null) {
                broken = "类型 " + type + " 的域 " + spec.getDomain() + " 没有 (OptionPlayerInboxDomain) 规格";
                break;
            }
            rules.put(spec.getDomainValue(), new DomainRule(spec.getDomainValue(), domainSpec.getRequiresOutOfBattle()));
        }
        if (broken == null && rules.isEmpty()) {
            broken = "没有任何消费域";
        }
        this.domains = Collections.unmodifiableMap(rules);
        this.brokenDetail = broken;
    }

    /** 生产装配：GM 处置域（封锁 / 解封货币获取）。 */
    public static InboxConsumers gmSanction(CurrencyService currency) {
        Objects.requireNonNull(currency, "currency");
        Map<PlayerInboxMessageType, Executor> table = new EnumMap<>(PlayerInboxMessageType.class);
        table.put(PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_BLOCK_CURRENCY_GAIN, (player, payload) -> gain(currency, player, payload, true));
        table.put(PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_UNBLOCK_CURRENCY_GAIN, (player, payload) -> gain(currency, player, payload, false));
        return new InboxConsumers(table);
    }

    /** 战斗结算载荷的上限（同 game-inbox 规格与 game-match 的个人终局 16 KiB 上限，同 C++ {@code ApplyInboxSettlement}）。 */
    static final int BATTLE_PAYLOAD_MAX_BYTES = 16 * 1024;

    /**
     * 保留 GM 域并加入战斗域（同 mmorpg C++ {@code PlayerBattleSystem::ApplyInboxSettlement}）。战斗结局只能是 APPLIED，执行器绝不返回 REJECTED：
     * <ul>
     *   <li>载荷超限、解码失败、带（含嵌套的）未知字段、结局不是已结束的合法枚举 → {@link VersionSkew}：本二进制永远解不对，停用该域，留待新版本节点；</li>
     *   <li>应用返回非 APPLIED（交出冻结、金币溢出、持久组件暂不可写……）→ {@link Deferred}，cause 是结算结论的指标口径，退避后续做。</li>
     * </ul>
     */
    public static InboxConsumers withBattle(CurrencyService currency,com.game.scene.battle.PlayerBattleService battle) {
        var table=new EnumMap<PlayerInboxMessageType,Executor>(PlayerInboxMessageType.class);
        table.putAll(gmSanction(currency).executors);
        table.put(PlayerInboxMessageType.PLAYER_INBOX_MSG_BATTLE_SETTLEMENT,(player,payload)->battleSettlement(battle,player,payload));
        return new InboxConsumers(table);
    }

    static ExecResult battleSettlement(com.game.scene.battle.PlayerBattleService battle, ScenePlayer player, ByteString payload) {
        if (payload.size() > BATTLE_PAYLOAD_MAX_BYTES) {
            return new VersionSkew(PAYLOAD_INVALID);
        }
        com.game.proto.BattleSettlementData settlement;
        try {
            settlement = com.game.proto.BattleSettlementData.parseFrom(payload);
        } catch (InvalidProtocolBufferException invalid) {
            return new VersionSkew(PAYLOAD_INVALID);
        }
        com.game.proto.eBattleOutcome outcome = settlement.getOutcome();
        if (unknown(settlement) || outcome == com.game.proto.eBattleOutcome.UNRECOGNIZED
                || outcome == com.game.proto.eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            return new VersionSkew(PAYLOAD_INVALID);
        }
        var result = battle.applyInbox(player, settlement);
        if (result.outcome() == com.game.scene.battle.BattleSettlementService.Outcome.APPLIED) {
            return new Applied();
        }
        return new Deferred(0, result.reason().name().toLowerCase(Locale.ROOT));
    }
    private static boolean unknown(com.google.protobuf.Message message) {
        if(!message.getUnknownFields().asMap().isEmpty())return true;
        for(var entry:message.getAllFields().entrySet()) {
            if(entry.getKey().getJavaType()!=com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE)continue;
            if(entry.getKey().isRepeated()){
                for(Object child:(List<?>)entry.getValue())if(unknown((com.google.protobuf.Message)child))return true;
            }else if(unknown((com.google.protobuf.Message)entry.getValue()))return true;
        }return false;
    }

    /** 任意执行器表（单测用）。 */
    public static InboxConsumers of(Map<PlayerInboxMessageType, Executor> executors) {
        return new InboxConsumers(executors);
    }

    /**
     * 注册表自检：null = 通过；否则是 {@code metric=inbox_registry_broken} 的 detail，整个节点停用消费（不阻断启动：收件箱停用只是延迟处置）。
     */
    public String selfCheck() {
        return brokenDetail;
    }

    /** 本二进制消费的域（按域号升序）。 */
    public List<Integer> consumerDomains() {
        return brokenDetail != null ? List.of() : List.copyOf(new ArrayList<>(domains.keySet()));
    }

    /** 某个消费域的规则；不是消费域为 null。 */
    public DomainRule rule(int domain) {
        return domains.get(domain);
    }

    /** 执行器：本二进制不认识的类型、或类型不属于本域，返回 null（调用方停用该域，不记 REJECTED）。 */
    public Executor executor(int msgType, int domain) {
        PlayerInboxMessageType type = PlayerInboxMessageType.forNumber(msgType);
        if (type == null) {
            return null;
        }
        Executor executor = executors.get(type);
        if (executor == null) {
            return null;
        }
        PlayerInboxTypeSpec spec = typeSpec(type);
        return spec != null && spec.getDomainValue() == domain ? executor : null;
    }

    /** 类型名（日志用）。 */
    public static String typeName(int msgType) {
        PlayerInboxMessageType type = PlayerInboxMessageType.forNumber(msgType);
        return type == null ? Integer.toUnsignedString(msgType) : type.name();
    }

    static PlayerInboxTypeSpec typeSpec(PlayerInboxMessageType type) {
        EnumValueDescriptor value = type.getValueDescriptor();
        return value.getOptions().hasExtension(PlayerInboxPlayerInboxOuterClass.optionPlayerInboxType)
                ? value.getOptions().getExtension(PlayerInboxPlayerInboxOuterClass.optionPlayerInboxType) : null;
    }

    static PlayerInboxDomainSpec domainSpec(PlayerInboxDomain domain) {
        if (domain == null || domain == PlayerInboxDomain.UNRECOGNIZED || domain == PlayerInboxDomain.PLAYER_INBOX_DOMAIN_UNSPECIFIED) {
            return null;
        }
        EnumValueDescriptor value = domain.getValueDescriptor();
        return value.getOptions().hasExtension(PlayerInboxPlayerInboxOuterClass.optionPlayerInboxDomain)
                ? value.getOptions().getExtension(PlayerInboxPlayerInboxOuterClass.optionPlayerInboxDomain) : null;
    }

    /**
     * 封锁 / 解封货币获取（同 mmorpg {@code GmSanctionInboxExecutor::Block/UnblockCurrencyGain}）：载荷解码失败或币种越界 → REJECTED(PayloadInvalid)；
     * 否则经 {@link CurrencyService} 改封禁名单（已封锁 / 没封锁都幂等成功），原语的非 0 码一律 REJECTED。Java 的钱包随实例恒在，不会因组件缺失 DEFER。
     */
    static ExecResult gain(CurrencyService currency, ScenePlayer player, ByteString payload, boolean block) {
        GmCurrencyGainPayload decoded;
        try {
            decoded = GmCurrencyGainPayload.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            return new Rejected(PAYLOAD_INVALID);
        }
        int type = decoded.getCurrencyType();
        if (Integer.compareUnsigned(type, Wallet.TYPE_COUNT) >= 0) {
            return new Rejected(PAYLOAD_INVALID);
        }
        int tip = block ? currency.block(player, type) : currency.unblock(player, type);
        return tip == 0 ? new Applied() : new Rejected(tip);
    }
}
