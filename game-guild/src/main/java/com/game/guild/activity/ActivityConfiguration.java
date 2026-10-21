package com.game.guild.activity;

import com.game.common.KafkaNames;
import com.game.common.RunMode;
import com.game.audit.AuditTopicInitializer;
import com.game.audit.BattleResultTopics;
import com.game.audit.KafkaTopicAdmin;
import com.game.audit.TopicAdmin;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.discovery.battle.BattleLockReader;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import com.game.guild.GuildConfiguration;
import com.game.guild.asset.GuildAssetRuntime;
import com.game.guild.cache.GuildCacheInvalidator;
import com.game.guild.cache.InvalidationOp;
import com.game.guild.id.GuildIds;
import com.game.guild.push.GuildPushes;
import com.game.guild.service.GuildAccess;
import com.game.guild.store.GuildActivityStore;
import com.game.proto.guild.GuildChangeKind;
import com.game.table.ConfigTables;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** 活动装配沿原生存储、资产、推送与MatchInternal接缝；缺资产工作器拒绝开放。 */
@Configuration(proxyBeanMethods = false)
public class ActivityConfiguration {
    @Bean
    public static StrongTiDbConnectionPolicy guildActivityConnectionPolicy(Environment environment) {
        // 此Bean先于数据源初始化：直接读取Environment，不能依赖较晚装配的运行模式Bean。
        boolean require = environment.getProperty("game.guild.activity.require-tidb", Boolean.class, true);
        if (!require && RunMode.parse(environment.getProperty("game.run-mode", "prod")) == RunMode.PROD)
            throw new IllegalStateException("生产帮会权威库不得关闭TiDB强读门禁");
        return new StrongTiDbConnectionPolicy("guild-activity", require);
    }
    public record Ready() {}
    @Bean
    public Ready guildActivityReady(DataSource source, ConfigTables tables, GuildConfiguration.SchemaReady core,
            @Value("${game.guild.activity.require-tidb:true}") boolean require) throws Exception {
        ActivityRules.validate(tables);
        try (var connection = source.getConnection()) {
            StrongTiDbConnectionPolicy.verify(connection, require);
        }
        ActivityTables.sync(source);
        try(var connection=source.getConnection();var statement=connection.createStatement()){
            statement.setQueryTimeout(3);
            try(var ignored=statement.executeQuery("SELECT player_id,state FROM match_global_ticket WHERE 1=0")){}
            try(var ignored=statement.executeQuery("SELECT player_id,request_id,state FROM team_launch_role WHERE 1=0")){}
            try(var ignored=statement.executeQuery("SELECT request_id,state FROM team_launch WHERE 1=0")){}
        }
        return new Ready();
    }
    /** 结果主题由Match管理；帮会只核验实际元数据，不抢建主题或自动迁移副本。 */
    public record ResultReady() {}
    @Bean
    @ConditionalOnMissingBean(name = "guildActivityResultAdmin")
    public Supplier<TopicAdmin> guildActivityResultAdmin(KafkaNames names,
            @Value("${game.guild.activity.kafka-brokers:127.0.0.1:9092}") String brokers) {
        return () -> new KafkaTopicAdmin(brokers, names.guildTrialClientId("startup"));
    }
    @Bean
    public ResultReady guildActivityResultReady(
            @Qualifier("guildActivityResultAdmin") Supplier<TopicAdmin> adminFactory, KafkaNames names,
            @Value("${game.guild.activity.enabled:true}") boolean enabled,
            @Value("${GLOBAL_MATCH_ENABLED:true}") boolean globalMatch,
            @Value("${GAME_BATTLE_RESULT_TOPIC_GENERATION:1}") int generation,
            @Value("${game.run-mode:prod}") String mode) {
        if (!enabled)
            return new ResultReady();
        if (!globalMatch && RunMode.parse(mode) == RunMode.PROD)
            throw new IllegalStateException("生产帮会活动必须验证全球结果持久契约");
        try (var admin = adminFactory.get()) {
            var topics = new BattleResultTopics(names);
            if (globalMatch)
                topics.ensureDurable(admin, generation, AuditTopicInitializer.Mode.VERIFY_ONLY, (short) 3, Duration.ofSeconds(3));
            else
                topics.ensure(admin, generation, AuditTopicInitializer.Mode.VERIFY_ONLY, (short) 1, Duration.ofSeconds(3));
        }
        return new ResultReady();
    }
    @Bean
    public GuildActivityStore guildActivityStore(
            DataSource source, ConfigTables tables, GuildIds ids, GuildAccess access, Ready guildActivityReady) {
        return new GuildActivityStore(new AuthorityJdbc(source), tables, ids::nextId, access.txFence());
    }
    @Bean
    public GuildActivityService.Effects guildActivityEffects(GuildCacheInvalidator invalidator, GuildPushes pushes) {
        return (guild, actor, players) -> {
            invalidator.afterCommit(InvalidationOp.ACTIVITY, guild, players, Deadline.after(300));
            for (long player : players)
                pushes.notify(GuildChangeKind.GUILD_CHANGE_KIND_ACTIVITY_CHANGED, guild, actor, player, java.util.List.of(player));
        };
    }
    private static <T> T await(CompletionStage<T> task, Deadline deadline) {
        try {
            return task.toCompletableFuture().get(Math.max(1, Math.min(800, deadline.remainingMillis())), TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            throw new IllegalStateException("活动在线/战斗状态未确认", error);
        }
    }
    @Bean
    public GuildActivityService guildActivityService(GuildActivityStore store, PlayerPresenceDirectory presence, RedissonClient redis,
            RedisKeys keys, GuildActivityService.Effects effects, ResultReady resultReady,
            @Value("${game.guild.activity.enabled:true}") boolean enabled) {
        var locks = new BattleLockReader(redis, keys);
        return new GuildActivityService(store,
                (players, d)
                        -> await(presence.findAllAsync(players), d).keySet(),
                (players, d) -> await(locks.existsAll(players), d), effects, System::currentTimeMillis, enabled);
    }
    @Bean
    public GuildActivityRuntime guildActivityRuntime(GuildActivityStore store, RedissonClient redis, RedisKeys keys, KafkaNames names,
            MeterRegistry metrics, GuildActivityService.Effects effects, GuildAssetRuntime assets, ResultReady resultReady,
            @Value("${game.guild.activity.kafka-brokers:127.0.0.1:9092}") String brokers,
            @Value("${GAME_BATTLE_RESULT_TOPIC_GENERATION:1}") int generation, @Value("${GLOBAL_MATCH_ENABLED:true}") boolean globalMatch,
            @Value("${game.guild.activity.enabled:true}") boolean enabled) {
        if (enabled && assets.loop().isEmpty())
            throw new IllegalStateException("活动开放要求可靠Guild资产工作器");
        return new GuildActivityRuntime(store, redis, keys, names, brokers, generation, enabled, globalMatch, metrics, effects);
    }
}
