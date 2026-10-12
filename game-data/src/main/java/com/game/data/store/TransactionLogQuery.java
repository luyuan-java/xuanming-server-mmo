package com.game.data.store;

import java.util.List;

/**
 * 流水筛选查询的参数（{@link TransactionLogMapper#query}）。等值条件为 null = 不过滤；时间窗半开 [since, until)；
 * 游标 (afterTimeMs, afterTxId) 之后的行（键集分页，不用 OFFSET）；按（时间、流水号）升序取 {@code fetch} 行。
 *
 * <p>本类不判断「能不能走索引」：那是 {@code TransactionLogQueryService} 的事（不给玩家时必须给 uuid / 物品 / 币种之一，
 * 或时间窗不超过上限）。不可变。
 */
public final class TransactionLogQuery {

    private final Long fromPlayer;
    private final Long toPlayer;
    private final Long itemUuid;
    private final Integer kind;
    private final Integer itemConfigId;
    private final Integer currencyType;
    private final boolean acquisitionsOnly;
    private final List<Integer> reasons;
    private final long since;
    private final long until;
    private final Long afterTimeMs;
    private final Long afterTxId;
    private final int fetch;

    private TransactionLogQuery(Builder b) {
        this.fromPlayer = b.fromPlayer;
        this.toPlayer = b.toPlayer;
        this.itemUuid = b.itemUuid;
        this.kind = b.kind;
        this.itemConfigId = b.itemConfigId;
        this.currencyType = b.currencyType;
        this.acquisitionsOnly = b.acquisitionsOnly;
        this.reasons = b.reasons == null ? List.of() : List.copyOf(b.reasons);
        this.since = b.since;
        this.until = b.until;
        this.afterTimeMs = b.afterTimeMs;
        this.afterTxId = b.afterTxId;
        this.fetch = b.fetch;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.fromPlayer = fromPlayer;
        b.toPlayer = toPlayer;
        b.itemUuid = itemUuid;
        b.kind = kind;
        b.itemConfigId = itemConfigId;
        b.currencyType = currencyType;
        b.acquisitionsOnly = acquisitionsOnly;
        b.reasons = reasons;
        b.since = since;
        b.until = until;
        b.afterTimeMs = afterTimeMs;
        b.afterTxId = afterTxId;
        b.fetch = fetch;
        return b;
    }

    public Long getFromPlayer() {
        return fromPlayer;
    }

    public Long getToPlayer() {
        return toPlayer;
    }

    public Long getItemUuid() {
        return itemUuid;
    }

    public Integer getKind() {
        return kind;
    }

    public Integer getItemConfigId() {
        return itemConfigId;
    }

    public Integer getCurrencyType() {
        return currencyType;
    }

    /** 只要获得方的行（回收匹配规则，data-ops-spec §5.2）：{@code to_player <> 0}，且货币行的 delta > 0。 */
    public boolean isAcquisitionsOnly() {
        return acquisitionsOnly;
    }

    public List<Integer> getReasons() {
        return reasons;
    }

    public long getSince() {
        return since;
    }

    public long getUntil() {
        return until;
    }

    public Long getAfterTimeMs() {
        return afterTimeMs;
    }

    public Long getAfterTxId() {
        return afterTxId;
    }

    public int getFetch() {
        return fetch;
    }

    public static final class Builder {
        private Long fromPlayer;
        private Long toPlayer;
        private Long itemUuid;
        private Integer kind;
        private Integer itemConfigId;
        private Integer currencyType;
        private boolean acquisitionsOnly;
        private List<Integer> reasons;
        private long since;
        private long until = Long.MAX_VALUE;
        private Long afterTimeMs;
        private Long afterTxId;
        private int fetch = 100;

        private Builder() {
        }

        public Builder fromPlayer(Long v) {
            fromPlayer = v;
            return this;
        }

        public Builder toPlayer(Long v) {
            toPlayer = v;
            return this;
        }

        public Builder itemUuid(Long v) {
            itemUuid = v;
            return this;
        }

        public Builder kind(Integer v) {
            kind = v;
            return this;
        }

        public Builder itemConfigId(Integer v) {
            itemConfigId = v;
            return this;
        }

        public Builder currencyType(Integer v) {
            currencyType = v;
            return this;
        }

        public Builder acquisitionsOnly(boolean v) {
            acquisitionsOnly = v;
            return this;
        }

        public Builder reasons(List<Integer> v) {
            reasons = v;
            return this;
        }

        public Builder window(long sinceMs, long untilMs) {
            since = sinceMs;
            until = untilMs;
            return this;
        }

        /** 游标：只要（时间、流水号）严格大于它的行；null = 从头。 */
        public Builder after(Long timeMs, Long txId) {
            afterTimeMs = timeMs;
            afterTxId = txId;
            return this;
        }

        public Builder fetch(int v) {
            fetch = v;
            return this;
        }

        public TransactionLogQuery build() {
            if ((afterTimeMs == null) != (afterTxId == null)) {
                throw new IllegalArgumentException("游标的时间与流水号必须同时给或同时不给");
            }
            if (fetch < 1) {
                throw new IllegalArgumentException("fetch 必须 ≥ 1");
            }
            return new TransactionLogQuery(this);
        }
    }
}
