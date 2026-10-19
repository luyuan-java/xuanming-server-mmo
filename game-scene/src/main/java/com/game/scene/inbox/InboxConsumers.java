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
 */
public final class InboxConsumers {

    /** 执行器的结局。 */
    public sealed interface ExecResult permits Applied, Rejected, Deferred {
    }

    /** 已生效：记 APPLIED。 */
    public record Applied() implements ExecResult {
    }

    /** 确定性拒绝（参数 / 状态不可能成功）：记 REJECTED(reason)。认识的类型但载荷解码失败按 REJECTED(kPlayerInboxPayloadInvalid)。 */
    public record Rejected(int reason) implements ExecResult {
    }

    /** 暂时做不了（本地缺陷，不能钉成终态）：在这一条停下，剩下的本地退避后续做。v1 的执行器不会返回它。 */
    public record Deferred(int reason) implements ExecResult {
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
