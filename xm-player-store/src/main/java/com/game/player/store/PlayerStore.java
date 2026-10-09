package com.game.player.store;

import com.game.player.store.state.PlayerState;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
 *   <li>持有期间写者可用 {@link #saveStateHeld} 在线存盘（不释放）；离开时用 {@link #saveStateAndRelease} 写回并释放；
 *       没进成场景（进场失败 / 取消 / 从未送达）用
 *       {@link #releaseOwnership} 只释放。都带 epoch 围栏，旧写者碰不到新 epoch；</li>
 *   <li>跨节点换图时持有者用 {@link #handOffOwnership} <b>原子交出</b>：一笔事务里带围栏写回冻结快照、epoch 加一、保持未释放、
 *       给新租约。提交即同时证明「最终状态已落库」与「从此只有新 epoch 的持有者能写」，释放与夺权之间没有可被第三方夺走的窗口。
 *       任何时刻库里仍只有一个写者（交出之后、目标节点加载之前没有写者）。结局不明时用 {@link #probeOwnership} 加锁读探测。
 *       跨 zone 传送用同一笔事务的另一种模式 {@link HandOffMode#RELEASE}（批次 5.4）：新 epoch 同时释放，库里没有持有者，
 *       目标 zone 的 login 按第 1 步夺权立即成功。</li>
 * </ol>
 * 租约用各进程的墙钟（login 判过期、scene 续约、交出的安全边际）：各进程时钟偏差必须远小于 {@link #OWNER_LEASE} 与交出安全边际
 * （部署要求 NTP）。
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
    /** 交出与探测用编程式事务（带超时）；null = 本实例不支持交出（只用于不连库的单测）。 */
    private final PlatformTransactionManager transactions;

    /** 不支持交出 / 探测（{@link #handOffOwnership}、{@link #probeOwnership} 抛 {@link IllegalStateException}）；只用于不连库的单测。 */
    public PlayerStore(PlayerMapper mapper, LongSupplier clockMs) {
        this(mapper, clockMs, null);
    }

    /**
     * @param transactions 与 {@code mapper} 同一数据源的事务管理器：交出与探测要给事务设超时（语句超时由 MyBatis 从事务剩余时间推出），
     *                     注解式事务做不到按调用给时限
     */
    public PlayerStore(PlayerMapper mapper, LongSupplier clockMs, PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.clockMs = clockMs;
        this.transactions = transactions;
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

    /** 账号的口令记录（生产口令认证用，只读）；账号不存在为空。 */
    public Optional<AccountPassword> findAccountPassword(String account) {
        return Optional.ofNullable(mapper.selectAccountPassword(account));
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
     * 写者离开：带围栏写回玩家状态（player 行的等级、所在场景、坐标 + {@code player_state} 玩法数据）并释放归属。
     * 一个事务：先更新 player 行（围栏 + 行锁），通过才写玩法数据，玩法数据写失败整体回滚。
     *
     * @return false 表示 epoch 已过期（被新的进场夺权）或玩家已不存在，本次写入被丢弃
     */
    @Transactional
    public boolean saveStateAndRelease(PlayerRow row, PlayerState state) {
        row.setUpdatedAt(clockMs.getAsLong());
        if (mapper.updateStateAndRelease(row) != 1) {
            return false;
        }
        mapper.upsertState(row.getPlayerId(), state.toByteArray(), row.getOwnerEpoch(), row.getUpdatedAt());
        return true;
    }

    /**
     * 写者在线存盘：同 {@link #saveStateAndRelease}，但不释放归属；归属已释放（最终写回已提交）时也拒绝，
     * 所以迟到的在线存盘盖不过最终写回。
     *
     * @return false 表示 epoch 已过期、已释放或玩家已不存在，本次写入被丢弃
     */
    @Transactional
    public boolean saveStateHeld(PlayerRow row, PlayerState state) {
        row.setUpdatedAt(clockMs.getAsLong());
        if (mapper.updateStateHeld(row) != 1) {
            return false;
        }
        mapper.upsertState(row.getPlayerId(), state.toByteArray(), row.getOwnerEpoch(), row.getUpdatedAt());
        return true;
    }

    /**
     * 读玩家玩法数据。从未写过返回默认实例（各玩法都取初始状态）。
     *
     * @throws IllegalStateException 存量字节解析失败（损坏）：调用方按加载失败处理，不让玩家带着丢了玩法数据的状态进场
     */
    public PlayerState loadState(long playerId) {
        PlayerStateRow row = mapper.selectState(playerId);
        byte[] data = row == null ? null : row.getData();
        if (data == null) {
            return PlayerState.getDefaultInstance();
        }
        try {
            return PlayerState.parseFrom(data);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("player_state 解析失败 player_id=" + Long.toUnsignedString(playerId), e);
        }
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
     * 交出之后新 epoch（E+1）由谁持有（{@link #handOffOwnership} 的最后一个参数）。两种模式只差提交时 {@code owner_released} 写 0 还是 1：
     * 判定条件（仍由 E 持有、未释放、剩余租约够）、写回的冻结快照、E → E+1、租约值都相同。
     */
    public enum HandOffMode {
        /**
         * 交出并保持（批次 5.2，跨节点换图）：E+1 <b>未释放</b>，租约 = {@code leaseUntil}，等同 zone 的目标节点收到交出通知后续约。
         * 目标节点没接住时要有人释放 E+1，否则只能等租约过期。
         */
        HOLD(0),
        /**
         * 交出并释放（批次 5.4，跨 zone 传送，zone-travel-spec §5.2）：E+1 <b>同时释放</b>，库里没有持有者——目标 zone 的 login
         * 夺权立即成功、得到 E+2，不必等租约。{@code leaseUntil} 照样写，但这时它对夺权没有语义（夺权先看释放标记），
         * 只当这次尝试的标识。提交之后 E 的一切写（在线存盘、最终写回、续约、释放）都被围栏拒；对 E+1 的释放是空操作。
         */
        RELEASE(1);

        /** 提交时写进 {@code owner_released} 的值。 */
        private final int released;

        HandOffMode(int released) {
            this.released = released;
        }
    }

    /** {@link #handOffOwnership} 的结果。 */
    public sealed interface HandOffResult {

        /**
         * 已交出：库里 epoch = {@code newEpoch}（交出前 + 1）、租约 = 传入的 {@code leaseUntil}；冻结快照已落库。
         * 释放标记随模式：{@link HandOffMode#HOLD} 未释放（由接手的节点持有），{@link HandOffMode#RELEASE} 已释放（没有持有者）。
         */
        record HandedOff(long newEpoch) implements HandOffResult {
        }

        /**
         * 没提交、什么也没改：仍由交出方的 epoch 持有、未释放，但剩余租约不足安全边际（续约近期在失败，交出不安全）。
         * {@code owner} 是同一事务里加锁读到的那一行。
         */
        record LeaseTooShort(OwnerState owner) implements HandOffResult {
        }

        /**
         * 没提交、什么也没改：epoch 已不是交出方的，或已释放（{@code owner} 是同一事务里加锁读到的那一行），
         * 或玩家不存在（{@code owner == null}）。注意：交出方自己更早一次已提交、只是应答丢了的交出，在这里也表现为
         * Fenced(epoch + 1, 未释放, 那次写下的租约)，由调用方按租约值认领（见 {@link #handOffOwnership}）。
         */
        record Fenced(OwnerState owner) implements HandOffResult {
        }
    }

    /**
     * 原子交出归属（跨节点换图，归属协议第 6 步）：一笔事务里带围栏写回冻结快照（player 行 + {@code player_state}，
     * {@code saved_epoch} 写交出方的 epoch E），并把 {@code owner_epoch} 加一到 E+1、保持未释放、租约置 {@code leaseUntil}。
     * 只有仍由 E 持有、尚未释放、且剩余租约 {@code owner_lease_until >= requireLeaseAtLeast} 时才提交；否则同一事务里加锁读出那一行，
     * 区分 {@link HandOffResult.LeaseTooShort} 与 {@link HandOffResult.Fenced}，什么也不改。
     *
     * <p><b>为什么要剩余租约下限与租约值</b>：调用方的瞬时故障重试会把「已提交、应答丢了」的交出再执行一遍，第二次读到 (E+1, 未释放)。
     * 别人能拿到 E 的下一代只有一种可能——E 的租约过期后夺权。调用方每次尝试取 {@code now_i}，传
     * {@code requireLeaseAtLeast = now_i + M}、{@code leaseUntil = L_i = now_i + 租约}：只要某次尝试提交了，那一刻 E 至少还有 M 的租约，
     * 而且 (E+1, 未释放, 租约 ∈ {L_i}) 只可能是自己写下的（目标节点拿到交出通知之前不会续 E+1）。
     *
     * <p>{@code timeout} 是这笔事务（不含提交）的时限：每条语句的 JDBC 查询超时取事务剩余时间（MyBatis 从 Spring 事务推出），
     * 行锁等待也受它约束；超时抛 {@link org.springframework.dao.QueryTimeoutException} 或
     * {@link org.springframework.transaction.TransactionTimedOutException}，事务回滚。提交本身只受连接的 socketTimeout 约束，
     * 提交中途断开就是「结局不明」，用 {@link #probeOwnership} 判定。
     *
     * @param frozen              冻结快照（{@code ownerEpoch} = 交出方持有的 E；{@code updatedAt} 由本方法填）
     * @param leaseUntil          E+1 的租约到期时刻（Unix 毫秒），同时是这次尝试的标识
     * @param requireLeaseAtLeast E 的剩余租约下限（Unix 毫秒）：{@code owner_lease_until} 小于它就不交出
     * @param timeout             事务时限，向上取整到秒，至少 1 秒
     * @throws IllegalStateException 提交后读到的 epoch 不是 E+1（不变量被破坏，事务回滚），或本实例没有事务管理器
     */
    public HandOffResult handOffOwnership(PlayerRow frozen, PlayerState state, long leaseUntil, long requireLeaseAtLeast,
                                          Duration timeout) {
        return handOffOwnership(frozen, state, leaseUntil, requireLeaseAtLeast, timeout, HandOffMode.HOLD);
    }

    /**
     * 原子交出归属，按 {@code mode} 决定 E+1 是否同时释放。{@link HandOffMode#HOLD} 就是上面五个参数的形式（跨节点换图）；
     * {@link HandOffMode#RELEASE}（跨 zone 传送）在同一笔事务里把 {@code owner_released} 置 1：提交即「冻结快照已落库、E 再也写不进来、
     * 库里没有持有者」，目标 zone 的 {@link #claimOwnership} 立即得到 E+2。
     *
     * <p>除释放标记外，两种模式的事务完全相同：同一个判定条件、同样写回 player 行与 {@code player_state}（{@code saved_epoch} = E）、
     * 同样的三种结果与时限语义，参数含义见五个参数的重载。没提交的两种结果（{@link HandOffResult.LeaseTooShort}、
     * {@link HandOffResult.Fenced}）不看模式——库里什么也没改。
     *
     * <p>RELEASE 下调用方的重试改判与探测要按另一张表判：「已提交、应答丢了」的那次尝试读回来是 (E+1, <b>已释放</b>, 那次写下的租约)，
     * 而不是 HOLD 的 (E+1, 未释放, …)；提交后别人立即就能夺到 E+2，所以读到 ≥ E+2 既可能是「本次已提交、随后被接手」，
     * 也可能是「本次没提交、E 是被别人夺走的」，不能当成已提交的证据。改判规则在调用方（xm-scene 的存储适配），这里只负责把这一笔事务做对。
     *
     * @param mode 不能为 null
     * @throws IllegalStateException 提交后读到的 epoch 不是 E+1（不变量被破坏，事务回滚），或本实例没有事务管理器
     */
    public HandOffResult handOffOwnership(PlayerRow frozen, PlayerState state, long leaseUntil, long requireLeaseAtLeast,
                                          Duration timeout, HandOffMode mode) {
        Objects.requireNonNull(mode, "mode");
        return transaction(timeout).execute(status -> {
            long playerId = frozen.getPlayerId();
            long epoch = frozen.getOwnerEpoch();
            long now = clockMs.getAsLong();
            frozen.setUpdatedAt(now);
            if (mapper.updateStateAndHandOff(frozen, leaseUntil, requireLeaseAtLeast, mode.released) == 1) {
                mapper.upsertState(playerId, state.toByteArray(), epoch, now);
                Long next = mapper.selectOwnerEpoch(playerId);
                if (next == null || next != epoch + 1) {
                    // 行锁保证同一事务里读到的是本次自增的值；读不到 / 对不上说明表被外部改坏，回滚、不当成交出
                    throw new IllegalStateException("交出后 owner_epoch 不是 E+1 player_id=" + Long.toUnsignedString(playerId)
                            + " E=" + epoch + " 读到=" + next);
                }
                return new HandOffResult.HandedOff(next);
            }
            OwnerState owner = mapper.selectOwnerForUpdate(playerId);
            if (owner != null && owner.ownerEpoch() == epoch && !owner.released()) {
                return new HandOffResult.LeaseTooShort(owner);
            }
            return new HandOffResult.Fenced(owner);
        });
    }

    /**
     * 交出结局不明之后的探测：加锁读归属三列。加锁读会等任何仍持有这一行行锁的在途交出事务结束，所以读到 (E, 未释放) 时
     * 那笔事务一定已回滚、不会再提交；读到 (E+1, 未释放, 自己写过的租约值) 就是自己的提交。
     *
     * @param timeout 事务时限（含行锁等待），向上取整到秒，至少 1 秒；超时抛 {@link org.springframework.dao.QueryTimeoutException}
     *                或 {@link org.springframework.transaction.TransactionTimedOutException}
     * @return 玩家不存在为空
     * @throws IllegalStateException 本实例没有事务管理器
     */
    public Optional<OwnerState> probeOwnership(long playerId, Duration timeout) {
        return Optional.ofNullable(transaction(timeout).execute(status -> mapper.selectOwnerForUpdate(playerId)));
    }

    /** 带时限的编程式事务（缺省传播 / 隔离级别）。 */
    private TransactionTemplate transaction(Duration timeout) {
        if (transactions == null) {
            throw new IllegalStateException("PlayerStore 没有事务管理器，不支持交出 / 探测");
        }
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setTimeout(timeoutSeconds(timeout));
        return template;
    }

    /** 事务时限向上取整到秒（JDBC 查询超时的粒度），至少 1 秒。 */
    static int timeoutSeconds(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("事务时限必须为正: " + timeout);
        }
        long seconds = timeout.plusMillis(999).toSeconds();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, seconds));
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
