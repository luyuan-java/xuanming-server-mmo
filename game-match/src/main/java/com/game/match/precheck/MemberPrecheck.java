package com.game.match.precheck;

import com.game.common.deadline.Deadline;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 点名开局前的逐成员预检（match-spec §7.4；基线 {@code tsvc.go:417-463}、{@code act.go:209-263}）：整队开战（{@code checkTeamMatch}）与帮会活动开战
 * 共用。<b>只负责查，不负责翻译</b>——两个入口对同一种情况的应答不同（例：读战斗锁出错，整队按「已在战斗」、活动按「内部错误」），由各入口按
 * §7.3 的映射表自己翻译 {@link Reason}。
 *
 * <p>按名单顺序逐人查，每人固定四项、固定顺序（顺序本身是语义：两名成员各有问题时，结论是名单里靠前的那个人；第 4 项的自愈依赖第 2 项的结论）：
 * <ol>
 *   <li>在线目录：读出错 → {@link Reason#PRESENCE_READ_FAILED}；没有条目 → {@link Reason#OFFLINE}；</li>
 *   <li>战斗锁：读出错 → {@link Reason#LOCK_READ_FAILED}；存在 → {@link Reason#IN_BATTLE}；</li>
 *   <li>位置记录（严格读）：读出错 → {@link Reason#LOCATION_READ_FAILED}；状态不是在线、或节点号为 0 → {@link Reason#NO_LOCATION}；否则记下 zone；</li>
 *   <li>排队票据（按排队入口的规则自愈）：读或自愈出错 → {@link Reason#TICKET_READ_FAILED}；自愈后仍在途 → {@link Reason#TICKET_IN_FLIGHT}。</li>
 * </ol>
 * 每查一个人之前先看截止：已过期 → {@link Reason#DEADLINE_EXPIRED}（调用方已放弃，不再读）。第一个不满足的人即为结论，后面的人不再查。
 *
 * <p><b>契约</b>：阻塞，至多等到 {@code d}；在工作线程上调。<b>永不抛异常</b>（依赖故障都落在 {@link Reason} 里）。除第 4 项的票据自愈之外只读。线程安全。
 */
public interface MemberPrecheck {

    /** 预检结论的原因。 */
    enum Reason {
        /** 全员通过。 */
        OK,
        PRESENCE_READ_FAILED,
        OFFLINE,
        LOCK_READ_FAILED,
        IN_BATTLE,
        LOCATION_READ_FAILED,
        NO_LOCATION,
        TICKET_READ_FAILED,
        TICKET_IN_FLIGHT,
        /** 请求预算在查某名成员之前已经用完。 */
        DEADLINE_EXPIRED;

        /** 是否是依赖故障 / 预算用完（不归咎于成员本身的状态）。 */
        public boolean fault() {
            return this == PRESENCE_READ_FAILED || this == LOCK_READ_FAILED || this == LOCATION_READ_FAILED || this == TICKET_READ_FAILED
                    || this == DEADLINE_EXPIRED;
        }
    }

    /**
     * 预检结果。
     *
     * @param reason   {@link Reason#OK} 或第一个不满足的原因
     * @param offender 不满足的那名成员；{@link Reason#OK} 时为 0。故障类原因也带上「查到谁时出的错」——入口按映射表决定用不用
     *                 （整队把读锁出错映射成 4025[这名成员]，活动映射成 INTERNAL、offender 置 0）
     * @param zones    {@link Reason#OK} 时：每名成员位置记录里的 zone（按名单顺序）；否则为空表
     */
    record Result(Reason reason, long offender, Map<Long, Integer> zones) {

        public Result {
            Objects.requireNonNull(reason, "reason");
            zones = Collections.unmodifiableMap(new LinkedHashMap<>(zones));
            if (reason == Reason.OK && offender != 0) {
                throw new IllegalArgumentException("通过的预检不该有 offender");
            }
            if (reason != Reason.OK && !zones.isEmpty()) {
                throw new IllegalArgumentException("没通过的预检不带 zones");
            }
        }

        public static Result ok(Map<Long, Integer> zones) {
            return new Result(Reason.OK, 0, zones);
        }

        public static Result failed(Reason reason, long offender) {
            if (reason == Reason.OK) {
                throw new IllegalArgumentException("failed 不能是 OK");
            }
            return new Result(reason, offender, Map.of());
        }

        public boolean passed() {
            return reason == Reason.OK;
        }
    }

    /**
     * 预检一份名单。
     *
     * @param roster 名单顺序（整队：队长在前；活动：发起人在前）；调用方已校验过非空、不含 0、不重复
     */
    Result check(List<Long> roster, Deadline d);
}
