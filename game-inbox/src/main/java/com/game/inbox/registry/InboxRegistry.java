package com.game.inbox.registry;

import com.game.inbox.rules.InboxRules;
import com.game.inbox.rules.InboxTips;
import com.game.proto.player_inbox.GmCurrencyDebtPayload;
import com.game.proto.player_inbox.GmCurrencyGainPayload;
import com.game.proto.player_inbox.PlayerInboxClass;
import com.game.proto.player_inbox.PlayerInboxDomain;
import com.game.proto.player_inbox.PlayerInboxDomainSpec;
import com.game.proto.player_inbox.PlayerInboxMessageType;
import com.game.proto.player_inbox.PlayerInboxPlayerInboxOuterClass;
import com.game.proto.player_inbox.PlayerInboxTypeSpec;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 类型注册表（mmorpg {@code registry.go}，设计 §3.2）。唯一事实源是同步来的契约 {@code player_inbox.proto} 枚举值上的选项
 * （{@code OptionPlayerInboxDomain = 420001}、{@code OptionPlayerInboxType = 420002}），启动时读成注册表并自检，任何一条不通过就拒绝启动：
 * 每个类型都有所属的域且该域登记了规格（max_pending &gt; 0）；分级只能是 DURABLE；载荷上限在 (0, 16KiB]；每个类型在 {@link #PAYLOAD_RULES}
 * 里都有校验函数，且校验函数认的消息名与规格一致（新增类型漏写校验函数 = 拒启，而不是「不校验就入箱」）。
 *
 * <p>入箱开关不在注册表里：只由配置 {@code game.inbox.enqueue-types} 决定（设计 §3.3），经 {@link #parseEnqueueTypes} 解析——v1 不开放的类型
 * （{@link #V1_DISABLED}：挂欠款）列进去即拒启（PARITY 第 ⑪ 条）。构造后只读，线程安全。
 */
public final class InboxRegistry {

    /**
     * 一个类型的 Java 侧载荷校验：{@code prototype} 的消息全名必须等于规格的 payload_message。
     *
     * @param validate 对解码后的载荷做业务校验；返回 null = 通过，否则是可进日志的原因
     */
    record PayloadRule(Message prototype, Function<Message, String> validate) {
    }

    /** 已登记类型解析之后的形态。 */
    public record TypeEntry(PlayerInboxMessageType type, PlayerInboxTypeSpec spec, PayloadRule rule) {

        public PlayerInboxDomain domain() {
            return spec.getDomain();
        }

        /** 按类型规格解码载荷；带未知字段的一律拒绝（消费方看不到的字段可能承载语义，fail-closed）。 */
        public Message decode(byte[] payload) throws InvalidProtocolBufferException {
            Message decoded = rule.prototype().getParserForType().parseFrom(payload);
            if (!decoded.getUnknownFields().asMap().isEmpty()) {
                throw new InvalidProtocolBufferException("载荷 " + spec.getPayloadMessage() + " 带了未知字段");
            }
            return decoded;
        }
    }

    /** 入箱第 1 步的结果：tip = 0 时 entry 非 null。 */
    public record Validation(TypeEntry entry, int tip) {
    }

    /** 欠款过期时刻（Unix 秒）的上限 9999-12-31T23:59:59Z：只挡「把毫秒当秒」这一类量级错误，不依赖当前时间。 */
    static final long MAX_DEBT_EXPIRES_AT_SECONDS = 253_402_300_799L;

    /** 按类型平铺的校验函数表（不用宏 / 反射生成；构造时双向核对，漏登记即拒启）。 */
    static final Map<PlayerInboxMessageType, PayloadRule> PAYLOAD_RULES = Map.of(
            PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_BLOCK_CURRENCY_GAIN,
            new PayloadRule(GmCurrencyGainPayload.getDefaultInstance(), InboxRegistry::validateGain),
            PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_UNBLOCK_CURRENCY_GAIN,
            new PayloadRule(GmCurrencyGainPayload.getDefaultInstance(), InboxRegistry::validateGain),
            PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_ATTACH_CURRENCY_DEBT,
            new PayloadRule(GmCurrencyDebtPayload.getDefaultInstance(), InboxRegistry::validateDebt));

    /**
     * v1 不许进入箱白名单的类型及原因（用户 2026-10-16 拍板，mmorpg 交接 §6 M2 / M3；PARITY 第 ⑪ 条）。挂欠款会把同币种的新单合并进旧欠款、
     * 并用后来那单覆盖到期时间 / 原因 / 操作人，挂错之后也没有撤销类型；合并语义、撤销类型与单笔上限一起做完之前，白名单里出现它就拒启。
     * GmSanctionPlayer 对它照常回 kPlayerInboxDisabled（不在白名单里）；类型注册不动，解禁时删掉这一项。
     */
    public static final Map<PlayerInboxMessageType, String> V1_DISABLED = Map.of(
            PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_ATTACH_CURRENCY_DEBT, "欠款合并语义与撤销路径未就绪（mmorpg 交接 §6 M2 / M3）");

    private final Map<PlayerInboxDomain, PlayerInboxDomainSpec> domains;
    private final Map<PlayerInboxMessageType, TypeEntry> types;

    private InboxRegistry(Map<PlayerInboxDomain, PlayerInboxDomainSpec> domains, Map<PlayerInboxMessageType, TypeEntry> types) {
        this.domains = domains;
        this.types = types;
    }

    /** 从生成的描述符读出全部规格并自检。自检不过抛 {@link IllegalStateException}（启动致命）。 */
    public static InboxRegistry load() {
        return load(PAYLOAD_RULES);
    }

    static InboxRegistry load(Map<PlayerInboxMessageType, PayloadRule> rules) {
        EnumMap<PlayerInboxDomain, PlayerInboxDomainSpec> domains = new EnumMap<>(PlayerInboxDomain.class);
        for (EnumValueDescriptor value : PlayerInboxDomain.getDescriptor().getValues()) {
            if (value.getNumber() == 0) {
                continue;
            }
            if (!value.getOptions().hasExtension(PlayerInboxPlayerInboxOuterClass.optionPlayerInboxDomain)) {
                throw new IllegalStateException("收件箱注册表：域 " + value.getName() + " 没有 (OptionPlayerInboxDomain) 规格");
            }
            PlayerInboxDomainSpec spec = value.getOptions().getExtension(PlayerInboxPlayerInboxOuterClass.optionPlayerInboxDomain);
            if (spec.getMaxPending() == 0) {
                throw new IllegalStateException("收件箱注册表：域 " + value.getName() + " 的 max_pending 必须 > 0");
            }
            domains.put(PlayerInboxDomain.forNumber(value.getNumber()), spec);
        }
        EnumMap<PlayerInboxMessageType, TypeEntry> types = new EnumMap<>(PlayerInboxMessageType.class);
        for (EnumValueDescriptor value : PlayerInboxMessageType.getDescriptor().getValues()) {
            if (value.getNumber() == 0) {
                continue;
            }
            PlayerInboxMessageType type = PlayerInboxMessageType.forNumber(value.getNumber());
            if (!value.getOptions().hasExtension(PlayerInboxPlayerInboxOuterClass.optionPlayerInboxType)) {
                throw new IllegalStateException("收件箱注册表：类型 " + value.getName() + " 没有 (OptionPlayerInboxType) 规格");
            }
            PlayerInboxTypeSpec spec = value.getOptions().getExtension(PlayerInboxPlayerInboxOuterClass.optionPlayerInboxType);
            if (spec.getDomain() == PlayerInboxDomain.PLAYER_INBOX_DOMAIN_UNSPECIFIED || !domains.containsKey(spec.getDomain())) {
                throw new IllegalStateException("收件箱注册表：类型 " + value.getName() + " 的域 " + spec.getDomain() + " 没有域规格");
            }
            if (spec.getKlass() != PlayerInboxClass.PLAYER_INBOX_CLASS_DURABLE) {
                throw new IllegalStateException("收件箱注册表：类型 " + value.getName() + " 的分级 " + spec.getKlass() + " 不是 DURABLE（v1 只登记持久类）");
            }
            if (spec.getMaxPayloadBytes() == 0 || Integer.compareUnsigned(spec.getMaxPayloadBytes(), InboxRules.MAX_PAYLOAD_BYTES_HARD_CAP) > 0) {
                throw new IllegalStateException("收件箱注册表：类型 " + value.getName() + " 的 max_payload_bytes="
                        + Integer.toUnsignedString(spec.getMaxPayloadBytes()) + " 不在 (0, " + InboxRules.MAX_PAYLOAD_BYTES_HARD_CAP + "] 内");
            }
            PayloadRule rule = rules.get(type);
            if (rule == null) {
                throw new IllegalStateException("收件箱注册表：类型 " + value.getName() + " 没有 Java 载荷校验函数（补进 PAYLOAD_RULES）");
            }
            String ruleMessage = rule.prototype().getDescriptorForType().getFullName();
            if (!ruleMessage.equals(spec.getPayloadMessage())) {
                throw new IllegalStateException("收件箱注册表：类型 " + value.getName() + " 的校验函数认 " + ruleMessage + "，规格载荷却是 "
                        + spec.getPayloadMessage());
            }
            types.put(type, new TypeEntry(type, spec, rule));
        }
        for (PlayerInboxMessageType type : rules.keySet()) {
            if (!types.containsKey(type)) {
                throw new IllegalStateException("收件箱注册表：PAYLOAD_RULES 有 " + type + "，枚举里却没有这个已登记类型");
            }
        }
        if (types.isEmpty()) {
            throw new IllegalStateException("收件箱注册表：没有登记任何类型");
        }
        return new InboxRegistry(Collections.unmodifiableMap(domains), Collections.unmodifiableMap(types));
    }

    /**
     * 把白名单里的枚举名解析成类型集合。未知名、UNSPECIFIED、重复、v1 未开放的类型都抛 {@link IllegalArgumentException}：写错的名字若被静默忽略，
     * 运维会以为打开了某个类型，实际一直回 Disabled。配置校验与装配层都经它解析，任何启动路径都拒绝 v1 未开放的类型。
     */
    public static Set<PlayerInboxMessageType> parseEnqueueTypes(List<String> names) {
        EnumSet<PlayerInboxMessageType> out = EnumSet.noneOf(PlayerInboxMessageType.class);
        if (names == null) {
            return out;
        }
        for (String name : names) {
            PlayerInboxMessageType type;
            try {
                type = PlayerInboxMessageType.valueOf(name == null ? "" : name.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("game.inbox.enqueue-types 含未知类型名 \"" + name + "\"（须是 PlayerInboxMessageType 的枚举名）");
            }
            if (type == PlayerInboxMessageType.UNRECOGNIZED || type == PlayerInboxMessageType.PLAYER_INBOX_MSG_UNSPECIFIED) {
                throw new IllegalArgumentException("game.inbox.enqueue-types 不能含 " + name);
            }
            String why = V1_DISABLED.get(type);
            if (why != null) {
                throw new IllegalArgumentException("game.inbox.enqueue-types 不能含 " + name + "：v1 不开放，" + why + "；v1 只放封锁 / 解封两种");
            }
            if (!out.add(type)) {
                throw new IllegalArgumentException("game.inbox.enqueue-types 重复列出 " + name);
            }
        }
        return out;
    }

    /** 白名单里的每个类型都已登记（装配时调用，不通过即拒启）。 */
    public void checkAllowlist(Set<PlayerInboxMessageType> allowed) {
        for (PlayerInboxMessageType type : allowed) {
            if (!types.containsKey(type)) {
                throw new IllegalStateException("game.inbox.enqueue-types 列了 " + type + "，它没有已登记的规格");
            }
        }
    }

    public Optional<TypeEntry> type(PlayerInboxMessageType type) {
        return Optional.ofNullable(type == null ? null : types.get(type));
    }

    public Optional<PlayerInboxDomainSpec> domain(PlayerInboxDomain domain) {
        return Optional.ofNullable(domain == null ? null : domains.get(domain));
    }

    /** 全部已登记域，按域号升序（对账输出顺序稳定）。 */
    public List<PlayerInboxDomain> domains() {
        List<PlayerInboxDomain> out = new ArrayList<>(domains.keySet());
        out.sort((a, b) -> Integer.compareUnsigned(a.getNumber(), b.getNumber()));
        return List.copyOf(out);
    }

    /**
     * 入箱第 1 步的注册表校验（设计 §6.2），全部与当前时间无关。顺序：类型已登记且为持久类 → 在白名单里 → 大小 → 解码 → 类型校验函数。
     */
    public Validation validatePayload(PlayerInboxMessageType type, Set<PlayerInboxMessageType> allowed, byte[] payload) {
        TypeEntry entry = type == null ? null : types.get(type);
        if (entry == null || entry.spec().getKlass() != PlayerInboxClass.PLAYER_INBOX_CLASS_DURABLE) {
            return new Validation(null, InboxTips.UNKNOWN_TYPE);
        }
        if (!allowed.contains(type)) {
            return new Validation(null, InboxTips.DISABLED);
        }
        if (Integer.compareUnsigned(payload.length, entry.spec().getMaxPayloadBytes()) > 0) {
            return new Validation(null, InboxTips.PAYLOAD_TOO_LARGE);
        }
        Message decoded;
        try {
            decoded = entry.decode(payload);
        } catch (InvalidProtocolBufferException e) {
            return new Validation(null, InboxTips.PAYLOAD_INVALID);
        }
        if (entry.rule().validate().apply(decoded) != null) {
            return new Validation(null, InboxTips.PAYLOAD_INVALID);
        }
        return new Validation(entry, 0);
    }

    /** 封锁 / 解封只有 currency_type。币种取值表在 scene（Wallet），这里不复制一份；越界由 scene 执行器给 REJECTED（设计 §7.6）。 */
    static String validateGain(Message message) {
        return message instanceof GmCurrencyGainPayload ? null : "want GmCurrencyGainPayload";
    }

    /** 欠款：owe_amount ∈ (0, INT64_MAX]；reason ≤ 256B；gm_operator 非空且 ≤ 64B（服务端用 operator 覆盖）；expires_at 是 Unix 秒。 */
    static String validateDebt(Message message) {
        if (!(message instanceof GmCurrencyDebtPayload debt)) {
            return "want GmCurrencyDebtPayload";
        }
        if (debt.getOweAmount() <= 0) {
            return "owe_amount 不在 (0, INT64_MAX] 内";
        }
        if (InboxRules.validateText(debt.getReason(), InboxRules.MAX_REASON_BYTES, false) != 0) {
            return "reason 超长或非法";
        }
        if (InboxRules.validateText(debt.getGmOperator(), InboxRules.MAX_OPERATOR_BYTES, true) != 0) {
            return "gm_operator 为空、超长或非法";
        }
        if (Long.compareUnsigned(debt.getExpiresAt(), MAX_DEBT_EXPIRES_AT_SECONDS) > 0) {
            return "expires_at 超过 Unix 秒上限（把毫秒当成秒了？）";
        }
        return null;
    }
}
