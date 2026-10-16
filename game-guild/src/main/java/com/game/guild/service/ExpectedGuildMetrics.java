package com.game.guild.service;

/**
 * 写请求「预期帮会」栅栏的计数接口（mmorpg {@code docs/design/client-request-ordering.md} O14 / §6.4；基线
 * {@code go/guild/internal/logic/expected_guild.go} 的 {@code guild_manage_expected_guild_total{method, outcome}}）。
 * 服务装配时由 {@code GuildMetrics} 绑定到 Micrometer（{@code game_guild_expected_guild_total}），单测可换成记录器。
 *
 * <p>标签只来自下面两个固定枚举（低基数，绝不带 player_id / guild_id）。带了且与当前帮会一致的请求<b>不计</b>：
 * 它与 {@code game_guild_requests_seconds{method}} 的请求数之差就是 absent，另计一份只是重复。
 *
 * <p>实现必须线程安全、不阻塞、不抛异常（在 guild-worker 线程上被调用）。
 */
@FunctionalInterface
public interface ExpectedGuildMetrics {

    /** 什么都不记的实现（未装配指标时用）。 */
    ExpectedGuildMetrics NONE = (method, outcome) -> {
    };

    /** 带 expected_guild_id 的五个写方法（标签 {@code method}，取值与基线逐字相同）。 */
    enum Method {
        LEAVE("leave"),
        DISBAND("disband"),
        KICK("kick"),
        TRANSFER("transfer"),
        SET_ROLE("set_role");

        private final String label;

        Method(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 标签 {@code outcome}。 */
    enum Outcome {
        /** 客户端没带（旧客户端）：按服务端此刻查到的本人帮会执行，行为与加字段之前相同。降到 0 之前，晚到的旧请求仍可能作用到新帮会。 */
        ABSENT("absent"),
        /** 带了，但操作者此刻不在该帮（在别的帮、不在任何帮、该帮已解散）：已拒绝、未写。 */
        MISMATCH("mismatch");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 记一次。 */
    void expectedGuild(Method method, Outcome outcome);
}
