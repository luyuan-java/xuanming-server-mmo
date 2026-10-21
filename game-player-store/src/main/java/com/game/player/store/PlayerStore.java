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
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    /** 交出、探测与带时限的建角 / 夺权用编程式事务（带超时）；null = 本实例不支持这些（只用于不连库的单测）。 */
    private final PlatformTransactionManager transactions;
    /** 单调时钟（纳秒）：只用于带时限的建角 / 夺权在进事务时与提交前复核调用方截止。 */
    private final LongSupplier nanoClock;

    /**
     * 不支持交出 / 探测 / 带时限的建角与夺权（{@link #handOffOwnership}、{@link #probeOwnership}、
     * 带 {@code timeout} 的 {@link #createPlayerWithinCap} / {@link #claimOwnership} 抛 {@link IllegalStateException}）；只用于不连库的单测。
     */
    public PlayerStore(PlayerMapper mapper, LongSupplier clockMs) {
        this(mapper, clockMs, null);
    }

    /**
     * @param transactions 与 {@code mapper} 同一数据源的事务管理器：交出、探测与带时限的建角 / 夺权要给事务设超时
     *                     （语句超时由 MyBatis 从事务剩余时间推出），注解式事务做不到按调用给时限
     */
    public PlayerStore(PlayerMapper mapper, LongSupplier clockMs, PlatformTransactionManager transactions) {
        this(mapper, clockMs, transactions, System::nanoTime);
    }

    /**
     * 同上；单调时钟可注入（测试用：让调用方截止在事务中途到点）。
     *
     * @param nanoClock 单调时钟（纳秒），只用于带时限的建角 / 夺权复核调用方截止
     */
    PlayerStore(PlayerMapper mapper, LongSupplier clockMs, PlatformTransactionManager transactions, LongSupplier nanoClock) {
        this.mapper = mapper;
        this.clockMs = clockMs;
        this.transactions = transactions;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
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
        ACCOUNT_MISSING,
        /**
         * 只有带时限的重载（{@link #createPlayerWithinCap(PlayerRow, int, List, Duration)}）会返回：事务没赶在调用方截止之前完成，
         * 已回滚、什么也没写（{@code existing} 为空）。
         */
        TIMED_OUT
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
        return insertWithinCap(row, maxPlayers, nameCandidates);
    }

    /**
     * 同 {@link #createPlayerWithinCap(PlayerRow, int, List)}，但事务带时限：login 建角用，gate 按超时放行同一会话的下一条请求之后
     * 不得再提交（mmorpg client-request-ordering.md §6.1）。时限语义见 {@link #withinDeadline}，结局多一种
     * {@link CreateStatus#TIMED_OUT}（已回滚、什么也没写）。不得在外层事务里调用（加入外层事务时时限不生效）。
     *
     * @param timeout 调用方剩余的预算，必须为正
     * @throws IllegalStateException 撞到名字以外的唯一约束（事务回滚），或本实例没有事务管理器
     */
    public CreateOutcome createPlayerWithinCap(PlayerRow row, int maxPlayers, List<String> nameCandidates,
                                               Duration timeout) {
        return withinDeadline(timeout, () -> insertWithinCap(row, maxPlayers, nameCandidates),
                new CreateOutcome(CreateStatus.TIMED_OUT, List.of()));
    }

    /** 建角事务的主体（锁与一致性见 {@link #createPlayerWithinCap(PlayerRow, int, List)}），必须在事务里调用。 */
    private CreateOutcome insertWithinCap(PlayerRow row, int maxPlayers, List<String> nameCandidates) {
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


    /** SQL 持久接管和最终应用是两种不同结局；ACCEPTED 不能代替资产 durable。 */
    public enum FinalSaveStatus { ACCEPTED, APPLIED, FENCED, CONFLICT, TIMED_OUT, NOT_FOUND }
    public record FinalSaveReceipt(FinalSaveStatus status,long playerId,long ownerEpoch,long saveSeq,
                                   String sourceInstance,String payloadDigest) { }
    public record FinalSaveKey(long playerId,long ownerEpoch) { }
    public record SceneLoad(PlayerRow row,PlayerState state) { }
    /** 仅 SQL 行适配，玩法仍使用原 PlayerState 字节；OPEN 不含最终快照。 */
    public record FinalSaveRow(long playerId,long ownerEpoch,long saveSeq,int protocolVersion,String sourceInstance,
                               String status,Long level,Long sceneConfigId,Double posX,Double posY,Double posZ,
                               byte[] data,byte[] payloadDigest) {
        public FinalSaveRow { data=data==null?null:data.clone();payloadDigest=payloadDigest==null?null:payloadDigest.clone(); }
        @Override public byte[] data(){return data==null?null:data.clone();}
        @Override public byte[] payloadDigest(){return payloadDigest==null?null:payloadDigest.clone();}
    }
    private static final int FINAL_SAVE_MAX_BYTES=16_777_215;

    private static void requireSource(String source) {
        if(source==null || !source.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("非法scene实例标识");
    }
    private static PlayerRow finalScalars(PlayerRow row) {
        if(row==null || row.getPlayerId()==0 || row.getOwnerEpoch()<=0 || row.getOwnerEpoch()==Long.MAX_VALUE || row.getLevel()<0
                || row.getLevel()>0xffff_ffffL || !Double.isFinite(row.getPosX())
                || !Double.isFinite(row.getPosY()) || !Double.isFinite(row.getPosZ())) {
            throw new IllegalArgumentException("非法最终玩家快照");
        }
        PlayerRow copy=new PlayerRow();copy.setPlayerId(row.getPlayerId());copy.setOwnerEpoch(row.getOwnerEpoch());
        copy.setLevel(row.getLevel());copy.setSceneConfigId(row.getSceneConfigId());copy.setPosX(row.getPosX());
        copy.setPosY(row.getPosY());copy.setPosZ(row.getPosZ());return copy;
    }
    /** 内容摘要不包括传输来源；source_instance 单独不可变等值认重，不允许恢复者改绑。 */
    public static byte[] finalSaveDigest(PlayerRow row,byte[] bytes) {
        PlayerRow snapshot=finalScalars(row);
        if(bytes==null || bytes.length>FINAL_SAVE_MAX_BYTES) throw new IllegalArgumentException("最终玩法快照超界");
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            digest.update("game-final-save/v1\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(java.nio.ByteBuffer.allocate(9*Long.BYTES).putLong(snapshot.getPlayerId())
                    .putLong(snapshot.getOwnerEpoch()).putLong(1).putLong(snapshot.getLevel())
                    .putLong(Integer.toUnsignedLong(snapshot.getSceneConfigId()))
                    .putLong(Double.doubleToRawLongBits(snapshot.getPosX()))
                    .putLong(Double.doubleToRawLongBits(snapshot.getPosY()))
                    .putLong(Double.doubleToRawLongBits(snapshot.getPosZ())).putLong(bytes.length).array());
            return digest.digest(bytes);
        } catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static FinalSaveReceipt receipt(FinalSaveStatus status,long player,long epoch,String source,byte[] digest) {
        return new FinalSaveReceipt(status,player,epoch,1,source,
                digest==null?"":java.util.HexFormat.of().formatHex(digest));
    }
    private static FinalSaveReceipt receipt(FinalSaveStatus status,FinalSaveRow row) {
        return receipt(status,row.playerId(),row.ownerEpoch(),row.sourceInstance(),row.payloadDigest());
    }
    private static PlayerRow snapshotOf(FinalSaveRow row) {
        if(row.protocolVersion()!=1 || row.saveSeq()!=1 || row.level()==null || row.sceneConfigId()==null
                || row.sceneConfigId()<0 || row.sceneConfigId()>0xffff_ffffL || row.posX()==null
                || row.posY()==null || row.posZ()==null) throw new IllegalArgumentException("最终SQL快照元数据损坏");
        PlayerRow snapshot=new PlayerRow();snapshot.setPlayerId(row.playerId());snapshot.setOwnerEpoch(row.ownerEpoch());
        snapshot.setLevel(row.level());snapshot.setSceneConfigId((int)(long)row.sceneConfigId());
        snapshot.setPosX(row.posX());snapshot.setPosY(row.posY());snapshot.setPosZ(row.posZ());return finalScalars(snapshot);
    }
    private static boolean validFinal(FinalSaveRow row) {
        try {
            requireSource(row.sourceInstance());
            byte[] bytes=row.data();
            if(bytes==null || row.payloadDigest()==null || !java.security.MessageDigest.isEqual(
                    row.payloadDigest(),finalSaveDigest(snapshotOf(row),bytes))) return false;
            PlayerState.parseFrom(bytes);return true;
        } catch(IllegalArgumentException|InvalidProtocolBufferException error){return false;}
    }
    private static boolean sameFinal(FinalSaveRow old,PlayerRow row,byte[] bytes,byte[] digest,String source) {
        return validFinal(old) && old.sourceInstance().equals(source)
                && java.security.MessageDigest.isEqual(old.payloadDigest(),digest)
                && java.util.Arrays.equals(old.data(),bytes)
                && old.level()==row.getLevel() && old.sceneConfigId()==Integer.toUnsignedLong(row.getSceneConfigId())
                && Double.doubleToRawLongBits(old.posX())==Double.doubleToRawLongBits(row.getPosX())
                && Double.doubleToRawLongBits(old.posY())==Double.doubleToRawLongBits(row.getPosY())
                && Double.doubleToRawLongBits(old.posZ())==Double.doubleToRawLongBits(row.getPosZ());
    }
    private static void independentFinalTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("最终意图登记/完成必须各自提交，禁止加入外层事务合并两阶段");
        }
    }

    /** 读取、同代准入绑定与快照处于同一行锁事务。OPEN 不阻止原租约到期后取得新 epoch。 */
    public Optional<SceneLoad> loadForScene(long playerId,long expectedEpoch,String source,Duration timeout) {
        requireSource(source);
        return withinDeadline(timeout,()->{
            OwnerState owner=mapper.selectOwnerForUpdate(playerId);
            if(owner==null || owner.ownerEpoch()!=expectedEpoch || owner.released()
                    || tradeLocked(playerId)
                    || mapper.countTradeSeals(playerId, expectedEpoch) != 0
                    || mapper.selectBlockingFinalEpoch(playerId)!=null) return Optional.empty();
            FinalSaveRow admission=mapper.selectFinalSave(playerId,expectedEpoch);
            if(admission==null) {
                if(mapper.insertFinalAdmission(playerId,expectedEpoch,source,clockMs.getAsLong())!=1)
                    throw new IllegalStateException("加载准入没有写入唯一行");
            } else if(!"OPEN".equals(admission.status()) || admission.protocolVersion()!=1
                    || !admission.sourceInstance().equals(source)) return Optional.empty();
            PlayerRow row=mapper.selectById(playerId);
            return row==null?Optional.empty():Optional.of(new SceneLoad(row,loadState(playerId)));
        },Optional.empty());
    }

    /** TX1：只写完整意图，不碰 player_state。应答不明时必须以原key/digest/source重查或重投。 */
    public FinalSaveReceipt registerFinalSave(PlayerRow row,byte[] stateBytes,String source,Duration timeout) {
        independentFinalTransaction();requireSource(source);PlayerRow frozen=finalScalars(row);
        byte[] bytes=Objects.requireNonNull(stateBytes,"stateBytes").clone();byte[] digest=finalSaveDigest(frozen,bytes);
        try{PlayerState.parseFrom(bytes);}catch(InvalidProtocolBufferException error){throw new IllegalArgumentException("最终玩法快照损坏",error);}
        return withinDeadline(timeout,()->{
            OwnerState owner=mapper.selectOwnerForUpdate(frozen.getPlayerId());
            if(owner==null) return receipt(FinalSaveStatus.NOT_FOUND,frozen.getPlayerId(),frozen.getOwnerEpoch(),source,digest);
            if(mapper.selectOtherBlockingFinalEpoch(frozen.getPlayerId(),frozen.getOwnerEpoch())!=null)
                return receipt(FinalSaveStatus.CONFLICT,frozen.getPlayerId(),frozen.getOwnerEpoch(),source,digest);
            FinalSaveRow old=mapper.selectFinalSave(frozen.getPlayerId(),frozen.getOwnerEpoch());
            if(old!=null && !"OPEN".equals(old.status())) {
                if(!"PENDING".equals(old.status()) && !"APPLIED".equals(old.status()))
                    return receipt(FinalSaveStatus.CONFLICT,old);
                if(!sameFinal(old,frozen,bytes,digest,source)) return receipt(FinalSaveStatus.CONFLICT,old);
                if("PENDING".equals(old.status()) && (owner.ownerEpoch()!=frozen.getOwnerEpoch() || owner.released()))
                    return receipt(FinalSaveStatus.CONFLICT,old);
                return receipt("APPLIED".equals(old.status())?FinalSaveStatus.APPLIED:FinalSaveStatus.ACCEPTED,old);
            }
            if(owner.ownerEpoch()!=frozen.getOwnerEpoch() || owner.released())
                return receipt(FinalSaveStatus.FENCED,frozen.getPlayerId(),frozen.getOwnerEpoch(),source,digest);
            if(mapper.selectBlockingFinalEpoch(frozen.getPlayerId())!=null || old!=null &&
                    (old.protocolVersion()!=1 || old.saveSeq()!=1 || !old.sourceInstance().equals(source)))
                return receipt(FinalSaveStatus.CONFLICT,frozen.getPlayerId(),frozen.getOwnerEpoch(),source,digest);
            long now=clockMs.getAsLong();
            int changed=old==null?mapper.insertFinalSave(frozen,bytes,digest,source,now)
                    :mapper.registerOpenFinalSave(frozen,bytes,digest,source,now);
            if(changed!=1) throw new IllegalStateException("最终意图未写入唯一行");
            return receipt(FinalSaveStatus.ACCEPTED,frozen.getPlayerId(),frozen.getOwnerEpoch(),source,digest);
        },receipt(FinalSaveStatus.TIMED_OUT,frozen.getPlayerId(),frozen.getOwnerEpoch(),source,digest));
    }

    /** 调用方已持有player锁；TX2与普通claim里的帮助完成共用该提交点。 */
    private FinalSaveReceipt completeFinalInTransaction(long playerId,long epoch,OwnerState owner) {
        FinalSaveRow row=mapper.selectFinalSave(playerId,epoch);
        if(row==null) return receipt(FinalSaveStatus.NOT_FOUND,playerId,epoch,"",null);
        if(mapper.selectOtherBlockingFinalEpoch(playerId,epoch)!=null)return receipt(FinalSaveStatus.CONFLICT,row);
        if("APPLIED".equals(row.status())) return receipt(validFinal(row)?FinalSaveStatus.APPLIED:FinalSaveStatus.CONFLICT,row);
        if(!"PENDING".equals(row.status()) || !validFinal(row)) return receipt(FinalSaveStatus.CONFLICT,row);
        if(owner==null || owner.ownerEpoch()!=epoch || owner.released()) return receipt(FinalSaveStatus.FENCED,row);
        PlayerRow frozen=snapshotOf(row);long now=clockMs.getAsLong();frozen.setUpdatedAt(now);
        if(mapper.updateStateAndRelease(frozen)!=1) throw new IllegalStateException("最终应用围栏未改到唯一行");
        mapper.upsertState(playerId,row.data(),epoch,now);
        if(mapper.markFinalApplied(playerId,epoch,row.payloadDigest(),now)!=1)
            throw new IllegalStateException("最终应用收据未改到唯一行");
        return receipt(FinalSaveStatus.APPLIED,row);
    }
    public FinalSaveReceipt completeFinalSave(long playerId,long epoch,Duration timeout) {
        independentFinalTransaction();
        return withinDeadline(timeout,()->completeFinalInTransaction(playerId,epoch,mapper.selectOwnerForUpdate(playerId)),
                receipt(FinalSaveStatus.TIMED_OUT,playerId,epoch,"",null));
    }
    public FinalSaveReceipt probeFinalSave(long playerId,long epoch,Duration timeout) {
        independentFinalTransaction();
        return withinDeadline(timeout,()->{
            mapper.selectOwnerForUpdate(playerId);FinalSaveRow row=mapper.selectFinalSave(playerId,epoch);
            if(row==null) return receipt(FinalSaveStatus.NOT_FOUND,playerId,epoch,"",null);
            return receipt(validFinal(row)?("APPLIED".equals(row.status())?FinalSaveStatus.APPLIED:
                    "PENDING".equals(row.status())?FinalSaveStatus.ACCEPTED:FinalSaveStatus.CONFLICT):FinalSaveStatus.CONFLICT,row);
        },receipt(FinalSaveStatus.TIMED_OUT,playerId,epoch,"",null));
    }
    public List<FinalSaveKey> dueFinalSaves(long now,int limit) {
        if(limit<1 || limit>32) throw new IllegalArgumentException("最终补偿批次必须在1..32");
        return withinDeadline(Duration.ofSeconds(2),()->List.copyOf(mapper.selectDueFinalSaves(now,limit)),List.of());
    }
    public void postponeFinalSave(long playerId,long epoch,long next,String code,Duration timeout) {
        if(code==null || !code.matches("[a-z_]{1,32}")) throw new IllegalArgumentException("非法最终补偿结果分类");
        withinDeadline(timeout,()->{mapper.selectOwnerForUpdate(playerId);mapper.selectFinalSave(playerId,epoch);
            mapper.postponeFinalSave(playerId,epoch,next,code);return true;},false);
    }
    /** 只回收保留期外且已有新E的OPEN/APPLIED；当前E认重收据、所有PENDING永不删除。 */
    public int cleanRetiredFinalSaves(long cutoff,int limit,Duration timeout) {
        if(limit<1 || limit>32)throw new IllegalArgumentException("最终收据清理批次必须在1..32");
        independentFinalTransaction();
        return withinDeadline(timeout,()->{
            int removed=0;
            for(String state:List.of("OPEN","APPLIED")) {
                if(removed>=limit)break;
                for(var key:mapper.selectRetiredFinalKeys(state,cutoff,limit-removed)) {
                    OwnerState owner=mapper.selectOwnerForUpdate(key.playerId());
                    if(owner!=null && owner.ownerEpoch()>key.ownerEpoch())
                        removed+=mapper.deleteRetiredFinal(key.playerId(),key.ownerEpoch(),cutoff);
                }
            }
            return removed;
        },0);
    }
    private boolean completePendingForOwner(long playerId,OwnerState owner) {
        // 这里只读取epoch键；损坏/未知状态同PENDING一起隔离，不能按TTL越过。
        Long blocking=mapper.selectBlockingFinalEpoch(playerId);
        return blocking==null || owner!=null && blocking==owner.ownerEpoch()
                && completeFinalInTransaction(playerId,blocking,owner).status()==FinalSaveStatus.APPLIED
                && mapper.selectBlockingFinalEpoch(playerId)==null;
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

        /**
         * 带时限的夺权与历史恢复会返回：事务没赶在调用方截止之前完成，已回滚、什么也没改——没有夺权。
         * 不带时限的重载永远不会得到它。
         */
        record TimedOut() implements ClaimResult {
        }
    }

    /**
     * 进场景时夺取玩家数据归属：上一个写者已释放或租约已过期才成功（epoch 加一，新租约 {@link #OWNER_LEASE}）。
     * 规则见类注释「归属协议」。
     */
    @Transactional
    public ClaimResult claimOwnership(long playerId) {
        return claimInTransaction(playerId);
    }

    /**
     * 同 {@link #claimOwnership(long)}，但事务带时限：login 进游戏用，gate 按超时放行同一会话的下一条请求之后不得再夺权
     * （夺到的归属没人接，玩家要等租约过期；mmorpg client-request-ordering.md §6.1）。时限语义见 {@link #withinDeadline}，
     * 结局多一种 {@link ClaimResult.TimedOut}（已回滚、没有夺权）。不得在外层事务里调用（加入外层事务时时限不生效）。
     *
     * @param timeout 调用方剩余的预算，必须为正
     * @throws IllegalStateException 本实例没有事务管理器
     */
    public ClaimResult claimOwnership(long playerId, Duration timeout) {
        return withinDeadline(timeout, () -> claimInTransaction(playerId), new ClaimResult.TimedOut());
    }

    /** 登录提交点复核原账号及原修订，阻止过户前已受理的旧异步链取得新归属。 */
    public ClaimResult claimOwnership(long playerId, String expectedAccount, long expectedRevision, Duration timeout) {
        if (expectedAccount == null || expectedAccount.isEmpty() || expectedRevision <= 0) {
            throw new IllegalArgumentException("登录归属凭据缺失");
        }
        return withinDeadline(timeout, () -> claimInTransaction(playerId, expectedAccount, expectedRevision), new ClaimResult.TimedOut());
    }

    /**
     * 历史 SQL 恢复后的维护夺权；日常登录仍使用 {@link #claimOwnership}。
     *
     * <p>调用前必须关闭受影响玩家入口、停止旧写者与旧发号者，且审核上界须独立于被恢复的 SQL/备份保留，覆盖所有曾发出的
     * 纪元（含交出生成的 E+1）。本方法不能证明上界来源，也不能撤销新围栏安装之前的僵尸写。缺少可靠上界时不得调用或猜 0。
     *
     * <p>自有事务锁住玩家后，只有已释放或租约严格过期才把纪元设为 {@code max(SQL 当前纪元, 审核上界)+1} 并持有。
     * 只更新归属/租约元数据，{@code player_state.data} 原字节与 {@code saved_epoch} 完全不变。
     * 时限与提交结局不明语义沿用 {@link #withinDeadline}：异常不代表未提交，重投见 Held 不得认作自己的提交。
     *
     * @param auditedOldEpochUpperBound 独立审核的旧纪元上界，0 到 Long.MAX_VALUE-1；已知 0 与缺少上界不同
     * @param timeout 正的 SQL 事务预算
     * @throws IllegalArgumentException 上界超出可安全递增的 Java long 范围
     * @throws IllegalStateException 外层事务存在、SQL 当前纪元无法安全递增或锁定后的 CAS 未改到唯一行
     */
    public ClaimResult recoverOwnershipAfterRestore(long playerId, long auditedOldEpochUpperBound, Duration timeout) {
        if (auditedOldEpochUpperBound < 0 || auditedOldEpochUpperBound == Long.MAX_VALUE) {
            throw new IllegalArgumentException("审核旧纪元上界无法安全递增");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("历史归属恢复不得加入外层事务");
        }
        return withinDeadline(timeout, () -> {
            OwnerState owner = mapper.selectOwnerForUpdate(playerId);
            if (owner == null) return new ClaimResult.NotFound();
            if (tradeLocked(playerId)) return new ClaimResult.Held(owner.ownerEpoch());
            if (mapper.selectBlockingFinalEpoch(playerId) != null) return new ClaimResult.Held(owner.ownerEpoch());
            long current = owner.ownerEpoch();
            if (current < 0 || current == Long.MAX_VALUE) {
                throw new IllegalStateException("SQL 当前归属纪元无法安全递增");
            }
            // 取锁可能等待；租约判断使用拿到锁之后的时刻，等于 now 保守地仍算持有。
            long now = clockMs.getAsLong();
            if (!owner.released() && owner.leaseUntil() >= now) return new ClaimResult.Held(current);
            long next = Math.max(current, auditedOldEpochUpperBound) + 1;
            if (mapper.recoverOwnerEpoch(playerId, current, next, now, now + OWNER_LEASE.toMillis()) != 1) {
                throw new IllegalStateException("锁定后的历史归属恢复未改到唯一玩家行");
            }
            return new ClaimResult.Claimed(next);
        }, new ClaimResult.TimedOut());
    }

    /** 夺权事务的主体，必须在事务里调用。 */
    private ClaimResult claimInTransaction(long playerId) {
        return claimInTransaction(playerId, null, 0);
    }

    private boolean tradeLocked(long playerId) {
        Long listing = mapper.selectTradeListingId(playerId);
        if (listing == null || listing < 0) throw new IllegalStateException("玩家交易锁原件缺失或损坏");
        return listing != 0;
    }

    private ClaimResult claimInTransaction(long playerId, String expectedAccount, long expectedRevision) {
        OwnerState owner=mapper.selectOwnerForUpdate(playerId);
        if(owner==null) return new ClaimResult.NotFound();
        if (expectedAccount != null) {
            PlayerRow row = mapper.selectById(playerId);
            if (row == null || !expectedAccount.equals(row.getAccount()) || expectedRevision != row.getOwnerRevision()) {
                return new ClaimResult.NotFound();
            }
        }
        if (tradeLocked(playerId)) return new ClaimResult.Held(owner.ownerEpoch());
        if(!completePendingForOwner(playerId,owner)) return new ClaimResult.Held(owner.ownerEpoch());
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
        OwnerState owner=mapper.selectOwnerForUpdate(row.getPlayerId());
        if(owner==null || owner.ownerEpoch()!=row.getOwnerEpoch()) return false;
        FinalSaveRow existing=mapper.selectFinalSave(row.getPlayerId(),row.getOwnerEpoch());
        if(existing!=null) {
            // 旧签名没有原source凭据：不能把它冒充原最终请求，尤其不能覆盖APPLIED。
            throw new IllegalStateException("兼容最终写缺少原持久接管凭据，必须走registerFinalSave");
        }
        if(owner.released())return false;
        if(mapper.selectBlockingFinalEpoch(row.getPlayerId())!=null) return false;
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
        OwnerState owner=mapper.selectOwnerForUpdate(row.getPlayerId());
        if(owner==null || owner.ownerEpoch()!=row.getOwnerEpoch() || owner.released()
                || mapper.selectBlockingFinalEpoch(row.getPlayerId())!=null) return false;
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
    @Transactional
    public boolean releaseOwnership(long playerId, long ownerEpoch) {
        OwnerState owner=mapper.selectOwnerForUpdate(playerId);
        if(owner==null || owner.ownerEpoch()!=ownerEpoch || owner.released()
                || mapper.selectBlockingFinalEpoch(playerId)!=null || mapper.selectFinalSave(playerId,ownerEpoch)!=null) return false;
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
     * 也可能是「本次没提交、E 是被别人夺走的」，不能当成已提交的证据。改判规则在调用方（game-scene 的存储适配），这里只负责把这一笔事务做对。
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
            OwnerState locked=mapper.selectOwnerForUpdate(playerId);
            if (locked != null && tradeLocked(playerId)) throw new IllegalStateException("角色交易冻结中，禁止交出归属");
            if(!completePendingForOwner(playerId,locked))
                throw new IllegalStateException("最终意图未完成，禁止交出归属");
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

    /**
     * 在带时限的事务里执行 {@code work}（带时限的建角 / 夺权共用）。调用方截止 = 进入本方法时 + {@code timeout}（{@link #nanoClock}）：
     * <ul>
     *   <li>事务时限取 {@code timeout}（{@link #transaction}）：每条语句的 JDBC 查询超时取事务剩余时间，行锁等待也受它约束；
     *       语句超时（{@link QueryTimeoutException}）与事务超时（{@link TransactionTimedOutException}）时事务已回滚，回 {@code timedOut}；</li>
     *   <li>事务时限向上取整到秒、又从拿到连接才开始算，比调用方截止宽：进事务时（取连接可能等了连接池上限）与回调返回前
     *       （提交之前）各按纳秒复核一次调用方截止，到点就 {@code setRollbackOnly}、回 {@code timedOut}——截止之后不提交；</li>
     *   <li>剩下的窗口：截止之前已开始的提交可能在截止之后才完成（提交只受连接 socketTimeout 约束），同 {@link #handOffOwnership}。</li>
     * </ul>
     * 其余异常原样抛出（事务回滚）。
     *
     * @throws IllegalArgumentException {@code timeout} 不为正
     * @throws IllegalStateException    本实例没有事务管理器
     */
    private <T> T withinDeadline(Duration timeout, Supplier<T> work, T timedOut) {
        TransactionTemplate template = transaction(timeout);
        long deadline = nanoClock.getAsLong() + timeout.toNanos();
        try {
            return template.execute(status -> {
                if (deadline - nanoClock.getAsLong() <= 0) {
                    status.setRollbackOnly();
                    return timedOut;
                }
                T result = work.get();
                if (deadline - nanoClock.getAsLong() <= 0) {
                    status.setRollbackOnly();
                    return timedOut;
                }
                return result;
            });
        } catch (QueryTimeoutException | TransactionTimedOutException e) {
            return timedOut;
        }
    }

    /** 带时限的编程式事务（缺省传播 / 隔离级别）。 */
    private TransactionTemplate transaction(Duration timeout) {
        if (transactions == null) {
            throw new IllegalStateException("PlayerStore 没有事务管理器，不支持交出 / 探测 / 带时限的建角与夺权");
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
