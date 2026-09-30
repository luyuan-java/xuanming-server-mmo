package com.game.player.store;

import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号与玩家持久化的唯一入口。
 *
 * <p>不变量：
 * <ul>
 *   <li>玩家名全服唯一且<b>大小写不敏感</b>：唯一索引建在 {@code name_key} 上，键只由 {@link #nameKey} 计算
 *       （调用方传展示名，拿不到也填不了键）；重名以 {@link CreateResult#NAME_TAKEN} 返回，不抛异常；</li>
 *   <li>建角撞到名字以外的唯一约束（只可能是主键 {@code player_id}）是不变量被破坏（雪花发号重号），
 *       抛 {@link IllegalStateException}，绝不伪装成重名；</li>
 *   <li>每账号角色数上限由 {@link #createPlayerWithinCap} 在数据库事务里保证（账号行锁），多个 login 实例并发建角也突破不了；</li>
 *   <li><b>玩家数据归属</b>（{@code owner_epoch} 围栏 + 释放标记 + 租约）：见下。</li>
 * </ul>
 *
 * <p><b>归属协议</b>（architecture.md §7）：
 * <ol>
 *   <li>进游戏时 login 调 {@link #claimOwnership}：只有上一个写者<b>已释放</b>（最终写回落库）或它的<b>租约已过期</b>
 *       （写者进程死了 / 与库失联超过 {@link #OWNER_LEASE}）时，epoch 才加一并由新写者持有；否则返回
 *       {@link ClaimResult.Held}，调用方稍后重试（2005）。这样上一个写者的最终写回一定先于新写者的加载落库，
 *       不会被新 epoch 的围栏拒掉；</li>
 *   <li>持有期间写者（scene）每 {@link #OWNER_LEASE} 的 1/3 调一次 {@link #renewOwnerLeases} 续约；续不上的归属已被别人夺走，
 *       写者必须立刻丢弃内存实例（它的写回只会被围栏拒绝）；</li>
 *   <li>写者离开时用 {@link #saveStateAndRelease} 写回并释放；没进成场景（进场失败 / 取消 / 从未送达）用
 *       {@link #releaseOwnership} 只释放。两者都带 epoch 围栏，旧写者碰不到新 epoch。</li>
 * </ol>
 * 租约用各进程的墙钟（login 判过期、scene 续约）：两端时钟偏差必须远小于 {@link #OWNER_LEASE}（部署要求 NTP）。
 *
 * <p>所有方法都是阻塞 I/O，调用方不得在 Netty I/O 线程或场景逻辑线程上直接调用。
 */
public class PlayerStore {

    /** 写者归属租约的时长；写者每 1/3 续约一次。夺权时间窗与写者失联判定都以它为准，只有这一个出处。 */
    public static final Duration OWNER_LEASE = Duration.ofSeconds(30);
    /** 一条批量续约 SQL 最多带多少个 (player_id, epoch)。 */
    static final int RENEW_BATCH = 500;

    /** 名字唯一索引名（schema 里的 {@code uk_player_name_key}）；靠它从重键异常里认出「重名」。 */
    static final String NAME_KEY_INDEX = "uk_player_name_key";
    /** MySQL ER_DUP_ENTRY。 */
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;
    private static final String DUPLICATE_KEY_MARKER = "for key '";

    private final PlayerMapper mapper;
    private final LongSupplier clockMs;

    public PlayerStore(PlayerMapper mapper, LongSupplier clockMs) {
        this.mapper = mapper;
        this.clockMs = clockMs;
    }

    /**
     * 名字唯一键：NFKC → 去首尾空白 → {@link Locale#ROOT} 小写。「Alice」「alice」「ＡＬＩＣＥ」得到同一个键。
     * 这是唯一键的唯一出处；比较「两个名字是否算同名」也必须用它。
     *
     * @throws IllegalArgumentException name 为 null
     */
    public static String nameKey(String name) {
        if (name == null) {
            throw new IllegalArgumentException("name 不能为 null");
        }
        return Normalizer.normalize(name, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    public void ensureAccount(String account) {
        mapper.insertAccountIfAbsent(account, clockMs.getAsLong());
    }

    public List<PlayerRow> listPlayers(String account) {
        return mapper.selectByAccount(account);
    }

    public int countPlayers(String account) {
        return mapper.countByAccount(account);
    }

    public Optional<PlayerRow> findPlayer(long playerId) {
        return Optional.ofNullable(mapper.selectById(playerId));
    }

    public enum CreateResult {
        CREATED,
        NAME_TAKEN
    }

    /**
     * 插入新角色（{@code name_key} 由本方法按 {@link #nameKey} 计算）。不检查角色数上限，建角走 {@link #createPlayerWithinCap}。
     *
     * @return {@link CreateResult#NAME_TAKEN}：与已有角色同名（大小写 / 全半角不敏感）
     * @throws IllegalStateException 撞到名字以外的唯一约束（主键重复 = 发号不变量被破坏），或无法判定撞的是哪个约束
     */
    public CreateResult createPlayer(PlayerRow row) {
        long now = clockMs.getAsLong();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            mapper.insertPlayer(row, nameKey(row.getName()));
            return CreateResult.CREATED;
        } catch (DuplicateKeyException e) {
            String key = duplicatedKeyName(e);
            if (NAME_KEY_INDEX.equals(key)) {
                return CreateResult.NAME_TAKEN;
            }
            // fail-closed：主键重复说明雪花发号撞号，回「重名」会掩盖它、让客户端换名重试时继续撞。
            throw new IllegalStateException("建角撞到名字以外的唯一约束 key=" + key
                    + " player_id=" + Long.toUnsignedString(row.getPlayerId()), e);
        }
    }

    /** {@link #createPlayerWithinCap} 的结果。 */
    public enum CreateStatus {
        /** 已插入（{@code row} 的名字是最终用上的那个候选名）。 */
        CREATED,
        /** 全部候选名都被占用。 */
        NAME_TAKEN,
        /** 账号下角色数已达上限。 */
        PLAYER_FULL,
        /** 账号行不存在（登录时 {@link #ensureAccount} 应已建好；这里 fail-closed）。 */
        ACCOUNT_MISSING
    }

    /**
     * @param existing 插入前账号下的全部角色（按建角先后）；ACCOUNT_MISSING 时为空
     */
    public record CreateOutcome(CreateStatus status, List<PlayerRow> existing) {

        public CreateOutcome {
            existing = List.copyOf(existing);
        }
    }

    /**
     * 在上限内给账号建一个角色：数据库事务内「锁账号行 → 数现有角色 → 逐个候选名插入」，每账号上限由数据库保证，
     * 多个 login 实例并发建角也最多建到 {@code maxPlayers} 个。
     *
     * <p>锁与一致性（MySQL InnoDB，REPEATABLE READ）：
     * <ul>
     *   <li>第一条语句必须是账号行的 {@code SELECT ... FOR UPDATE}（主键记录锁，没有间隙锁）。它是加锁读，不建立一致性读快照；
     *       快照在随后第一条普通 {@code SELECT} 建立，此时已拿到锁，一定能看到上一个持锁者已提交的插入；</li>
     *   <li>不对 player 表做加锁读（避免 idx_player_account 上的间隙锁让不同账号的建角互相等待甚至死锁）；
     *       每个事务只锁自己账号的一行，没有跨账号的锁序，不引入死锁；</li>
     *   <li>持锁期间只有一次 SELECT 与最多 {@code nameCandidates.size()} 次 INSERT，受数据源 socketTimeout 约束；
     *       撞名在 InnoDB 里只回滚该条语句，事务继续试下一个候选名。</li>
     * </ul>
     *
     * @param row            待插入的行（player_id、账号、职业等已填好；名字由本方法依次填候选名）
     * @param nameCandidates 候选名（玩家指定的名字就只有一个；服务端生成的名字按顺序逐个试），至少一个
     * @throws IllegalStateException 撞到名字以外的唯一约束（见 {@link #createPlayer}），事务回滚
     */
    @Transactional
    public CreateOutcome createPlayerWithinCap(PlayerRow row, int maxPlayers, List<String> nameCandidates) {
        if (nameCandidates.isEmpty()) {
            throw new IllegalArgumentException("至少要有一个候选名");
        }
        if (mapper.lockAccount(row.getAccount()) == null) {
            return new CreateOutcome(CreateStatus.ACCOUNT_MISSING, List.of());
        }
        List<PlayerRow> existing = mapper.selectByAccount(row.getAccount());
        if (existing.size() >= maxPlayers) {
            return new CreateOutcome(CreateStatus.PLAYER_FULL, existing);
        }
        for (String candidate : nameCandidates) {
            row.setName(candidate);
            if (createPlayer(row) == CreateResult.CREATED) {
                return new CreateOutcome(CreateStatus.CREATED, existing);
            }
        }
        return new CreateOutcome(CreateStatus.NAME_TAKEN, existing);
    }

    /** {@link #claimOwnership} 的结果。 */
    public sealed interface ClaimResult {

        /** 夺权成功，新写者持有 {@code ownerEpoch}。 */
        record Claimed(long ownerEpoch) implements ClaimResult {
        }

        /** 归属仍被 {@code ownerEpoch} 的写者持有（未释放、租约未过期），这次没有夺权。 */
        record Held(long ownerEpoch) implements ClaimResult {
        }

        /** 玩家不存在。 */
        record NotFound() implements ClaimResult {
        }
    }

    /**
     * 进场景时夺取玩家数据归属：上一个写者已释放或租约已过期才成功（epoch 加一，新租约 {@link #OWNER_LEASE}）。
     * 规则见类注释「归属协议」。
     */
    @Transactional
    public ClaimResult claimOwnership(long playerId) {
        long now = clockMs.getAsLong();
        if (mapper.claimOwnerEpoch(playerId, now, now + OWNER_LEASE.toMillis()) == 1) {
            Long epoch = mapper.selectOwnerEpoch(playerId);
            if (epoch == null) {
                throw new IllegalStateException("夺权成功后读不到 owner_epoch player_id=" + Long.toUnsignedString(playerId));
            }
            return new ClaimResult.Claimed(epoch);
        }
        Long held = mapper.selectOwnerEpoch(playerId);
        return held == null ? new ClaimResult.NotFound() : new ClaimResult.Held(held);
    }

    /**
     * 写者离开：带围栏写回玩家状态（等级、所在场景、坐标）并释放归属。
     *
     * @return false 表示 epoch 已过期（被新的进场夺权）或玩家已不存在，本次写入被丢弃
     */
    public boolean saveStateAndRelease(PlayerRow row) {
        row.setUpdatedAt(clockMs.getAsLong());
        return mapper.updateStateAndRelease(row) == 1;
    }

    /**
     * 只释放归属、不写状态（进场失败 / 取消 / 从未送达）。带围栏：epoch 已变或已释放时什么也不做。
     *
     * @return true 表示这次确实释放了
     */
    public boolean releaseOwnership(long playerId, long ownerEpoch) {
        return mapper.releaseOwner(playerId, ownerEpoch, clockMs.getAsLong()) == 1;
    }

    /**
     * 写者续约：把仍由对应 epoch 持有的归属的租约延到现在 + {@link #OWNER_LEASE}。
     *
     * @param leases 同一玩家至多一项（一个写者对一个玩家只持有一个 epoch）
     * @return 续不上的归属（epoch 已被夺走、已释放或玩家已不存在）：持有它们的内存实例必须丢弃
     * @throws IllegalArgumentException 同一玩家出现多次
     */
    public List<OwnerLease> renewOwnerLeases(Collection<OwnerLease> leases) {
        List<OwnerLease> all = List.copyOf(leases);
        Set<Long> distinct = new HashSet<>();
        for (OwnerLease lease : all) {
            if (!distinct.add(lease.playerId())) {
                throw new IllegalArgumentException("续约列表里同一玩家出现多次 player_id=" + Long.toUnsignedString(lease.playerId()));
            }
        }
        List<OwnerLease> lost = new ArrayList<>();
        long until = clockMs.getAsLong() + OWNER_LEASE.toMillis();
        for (int from = 0; from < all.size(); from += RENEW_BATCH) {
            List<OwnerLease> batch = all.subList(from, Math.min(all.size(), from + RENEW_BATCH));
            if (mapper.renewOwnerLeases(batch, until) >= batch.size()) {
                continue;
            }
            Set<Long> stillHeld = new HashSet<>(mapper.selectStillHeld(batch));
            for (OwnerLease lease : batch) {
                if (!stillHeld.contains(lease.playerId())) {
                    lost.add(lease);
                }
            }
        }
        return lost;
    }

    /**
     * 从重键异常链里取出 MySQL 报告的索引名（去掉 8.0.19+ 带的表名前缀），取不到返回 null。
     * MySQL 的消息形如 {@code Duplicate entry 'x' for key 'player.uk_player_name_key'}；
     * 取<b>最后一个</b> {@code for key '}，因为被撞的值本身出现在它前面。
     */
    static String duplicatedKeyName(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (!(t instanceof SQLException sql) || sql.getErrorCode() != MYSQL_DUPLICATE_ENTRY || sql.getMessage() == null) {
                continue;
            }
            String message = sql.getMessage();
            int start = message.lastIndexOf(DUPLICATE_KEY_MARKER);
            if (start < 0) {
                continue;
            }
            start += DUPLICATE_KEY_MARKER.length();
            int end = message.indexOf('\'', start);
            if (end < 0) {
                continue;
            }
            String key = message.substring(start, end);
            int dot = key.lastIndexOf('.');
            return dot >= 0 ? key.substring(dot + 1) : key;
        }
        return null;
    }
}
