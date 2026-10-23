package com.game.data.tools.playerbundle;

import com.game.discovery.proto.PlayerLocation;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 导出包的判读：直接回答「玩家在不在线」「最近一次存盘落库没有」。纯函数，不碰任何外部系统。
 *
 * <p>Java 版没有基线那样的「Redis 父快照 + 存盘屏障」：存盘直接带 {@code owner_epoch} 围栏写 MySQL（architecture.md §7）。等价的判据是
 * {@code player} 行的归属三列与 {@code player_state.saved_epoch}：
 * <ul>
 *   <li>{@code owner_released = 1}：上一个写者已最终写回并释放（或从未进过场景）——库里就是最终态；
 *       {@code saved_epoch = owner_epoch} 说明这一代写者自己写回过，{@code saved_epoch < owner_epoch} 说明这一代没写玩法数据就释放了
 *       （交出并释放的跨 zone 传送、进场失败只释放、运维夺权后释放），库里是更早一代落的盘；</li>
 *   <li>{@code owner_released = 0} 且租约未过期：有写者持有（在线、交出在途或运维作业）——库里是最近一次在线存盘，内存可能更新；</li>
 *   <li>{@code owner_released = 0} 且租约已过期：写者失联（进程死了或与库失联超过 30 s）——最终写回没落库，库里是最近一次在线存盘，
 *       之后的增量只在旧进程内存里，下次登录强制夺权以库为准；</li>
 *   <li>租约等于 {@link LocalPlayerImporter#IMPORT_HOLD_LEASE}：本地导入中断留下的占位（只会出现在本地环境）。</li>
 * </ul>
 */
final class BundleAnalyzer {

    static final String NO_PLAYER = "NO_PLAYER";
    static final String RELEASED = "RELEASED";
    static final String HELD = "HELD";
    static final String HELD_LEASE_EXPIRED = "HELD_LEASE_EXPIRED";
    static final String IMPORT_HOLD = "IMPORT_HOLD";

    private BundleAnalyzer() {
    }

    /**
     * @param location    位置记录的原始读数；null = 没读 Redis
     * @param battleLock  战斗锁的原始读数；null = 没读 Redis
     * @param settlements 待结算战斗奖励的原始读数；null = 没读 Redis
     * @param nowMs       导出时刻（判租约用）
     * @param readNotes   读取阶段的提示（没读 Redis、某部分读失败），排在警告最前面
     */
    static PlayerBundle.Analysis analyze(PlayerBundle.Player player, PlayerBundle.State state, PlayerBundle.TxlogCounts counts,
                                         PlayerRedis.RawEntry location, PlayerRedis.RawEntry battleLock,
                                         PlayerRedis.RawEntry settlements, long nowMs, List<String> readNotes) {
        List<String> warnings = new ArrayList<>(readNotes);
        if (player == null) {
            return new PlayerBundle.Analysis(NO_PLAYER, "库里没有这名玩家", "无玩家行：没有任何存盘", null, List.copyOf(warnings));
        }
        long ownerEpoch = Long.parseUnsignedLong(player.ownerEpoch());
        boolean stateFound = state != null && state.found();
        long savedEpoch = stateFound ? Long.parseUnsignedLong(state.savedEpoch()) : 0;
        if (stateFound && Long.compareUnsigned(savedEpoch, ownerEpoch) > 0) {
            warnings.add("player_state.saved_epoch=" + Long.toUnsignedString(savedEpoch) + " 大于 owner_epoch=" + player.ownerEpoch()
                    + "：正常写路径产生不了（库被部分恢复或人工改过），判读可能不可信");
        }
        String locationNote = describeLocation(location, player, warnings);

        String ownership;
        String online;
        String persistence;
        Long leaseRemaining = null;
        if (player.ownerReleased() == 1) {
            ownership = RELEASED;
            online = "离线：归属已释放（epoch " + player.ownerEpoch() + "）" + locationNote;
            persistence = releasedPersistence(stateFound, savedEpoch, ownerEpoch, state);
        } else if (player.ownerLeaseUntil() == LocalPlayerImporter.IMPORT_HOLD_LEASE) {
            ownership = IMPORT_HOLD;
            online = "本地导入中断留下的占位（epoch " + player.ownerEpoch() + "）：登录会一直回 2005，带 --replace 重跑导入即可";
            persistence = "导入占位：库里是导入第一段写下的数据，还没有发布";
            warnings.add("这是本地导入中断的痕迹，不是线上状态");
        } else {
            leaseRemaining = player.ownerLeaseUntil() - nowMs;
            if (leaseRemaining >= 0) {
                ownership = HELD;
                online = "在线或交出在途：归属由 epoch " + player.ownerEpoch() + " 持有，租约还剩 " + leaseRemaining + " ms" + locationNote;
                persistence = heldPersistence(stateFound, savedEpoch, ownerEpoch, state, nowMs)
                        + "。内存可能领先至多一个存盘周期（game.scene.save-interval，缺省 300 s；资产通道记账会立即存盘）";
                warnings.add("导出时玩家在线（或交出在途）：库里不是最终态，要确切数据等玩家下线、归属释放后重导");
            } else {
                ownership = HELD_LEASE_EXPIRED;
                online = "写者失联：epoch " + player.ownerEpoch() + " 的租约已过期 " + (-leaseRemaining)
                        + " ms（写者进程死了或与库失联超过 30 s），下次登录会强制夺权" + locationNote;
                persistence = "最终写回没有落库：" + heldPersistence(stateFound, savedEpoch, ownerEpoch, state, nowMs)
                        + "；之后的增量只在旧进程内存里，已经丢失";
                warnings.add("写者失联、最终写回没落库：自最近一次在线存盘以来的进度已丢失");
            }
        }
        if (counts != null && counts.afterStateSave() != null && counts.afterStateSave() > 0) {
            warnings.add(counts.afterStateSave() + " 条资产流水的时刻晚于 player_state.updated_at：这些变动可能还不在库里的玩法数据中"
                    + (HELD_LEASE_EXPIRED.equals(ownership) ? "（写者失联——「东西丢了」的首要嫌疑）" : ""));
        }
        if (counts != null && !stateFound && counts.total() > 0) {
            warnings.add("没有 player_state 行却有 " + counts.total() + " 条资产流水：存档缺失，不能推断从未落盘");
        }
        describeBattle(battleLock, settlements, warnings);
        return new PlayerBundle.Analysis(ownership, online, persistence, leaseRemaining, List.copyOf(warnings));
    }

    private static String releasedPersistence(boolean stateFound, long savedEpoch, long ownerEpoch, PlayerBundle.State state) {
        if (!stateFound) {
            return "player_state 存档缺失，无法区分历史损坏与旧数据，禁止按初始状态加载或导入";
        }
        if (savedEpoch == ownerEpoch) {
            return "最终写回已落库：epoch " + Long.toUnsignedString(ownerEpoch) + " 的写者写回并释放（player_state.updated_at="
                    + state.updatedAt() + "）；库里就是最终态";
        }
        return "库里的玩法数据由 epoch " + Long.toUnsignedString(savedEpoch) + " 写入（updated_at=" + state.updatedAt() + "），之后 epoch "
                + Long.toUnsignedString(ownerEpoch) + " 没写玩法数据就释放了（跨 zone 交出并释放、进场失败只释放、或运维夺权后释放）；"
                + "玩家离线，库里就是最终态";
    }

    private static String heldPersistence(boolean stateFound, long savedEpoch, long ownerEpoch, PlayerBundle.State state, long nowMs) {
        if (!stateFound) {
            return "player_state 存档缺失，不能判定历史存盘内容，禁止按初始状态加载或导入";
        }
        String age = "，距导出 " + (nowMs - state.updatedAt()) + " ms";
        if (savedEpoch == ownerEpoch) {
            return "库里是本代（epoch " + Long.toUnsignedString(ownerEpoch) + "）最近一次在线存盘（updated_at=" + state.updatedAt() + age + "）";
        }
        return "本代（epoch " + Long.toUnsignedString(ownerEpoch) + "）还没有在线存盘落库过，库里是 epoch " + Long.toUnsignedString(savedEpoch)
                + " 落的盘（updated_at=" + state.updatedAt() + age + "）";
    }

    /** 位置记录的一句话说明（附在 online 后面）；畸形记录记警告。 */
    private static String describeLocation(PlayerRedis.RawEntry location, PlayerBundle.Player player, List<String> warnings) {
        if (location == null) {
            return "；未读 Redis";
        }
        if (location.error() != null) {
            warnings.add("位置记录读取失败：" + location.error());
            return "";
        }
        if (location.hash() == null) {
            return "；无位置记录（首登、干净登出或租约已过期）";
        }
        String status = text(location.hash().get("s"));
        String epoch = text(location.hash().get("e"));
        PlayerLocation value = null;
        byte[] raw = location.hash().get("v");
        if (raw != null) {
            try {
                value = PlayerLocation.parseFrom(raw);
            } catch (InvalidProtocolBufferException e) {
                warnings.add("位置记录的 v 解不开：" + e.getMessage());
            }
        }
        String where = value == null ? "" : "，zone " + Integer.toUnsignedString(value.getZoneId()) + " 节点 "
                + Integer.toUnsignedString(value.getSceneNodeId()) + " 场景配置 " + Integer.toUnsignedString(value.getSceneConfigId());
        String meaning = switch (status == null ? "" : status) {
            case "o" -> "在线";
            case "l" -> value != null && value.getSceneNodeId() == 0 ? "跨 zone 待落点" : "断线重连租约";
            case "x" -> "登出墓碑（读者当没有）";
            default -> "未知状态 " + status;
        };
        if (value != null && "o".equals(status) && Integer.toUnsignedLong(value.getZoneId()) != player.zoneId()) {
            warnings.add("位置记录在 zone " + Integer.toUnsignedString(value.getZoneId()) + "，归属区 player.zone_id=" + player.zoneId()
                    + "（跨 zone 传送后的正常情况，也可能是重定向循环的线索）");
        }
        return "；位置记录：" + meaning + "（epoch " + epoch + where + "，TTL " + location.pttlMs() + " ms）";
    }

    private static void describeBattle(PlayerRedis.RawEntry battleLock, PlayerRedis.RawEntry settlements, List<String> warnings) {
        if (battleLock != null && battleLock.hash() != null) {
            warnings.add("存在战斗锁（battle_id=" + text(battleLock.hash().get("b")) + "，状态 " + text(battleLock.hash().get("s"))
                    + "）：玩家处于备战或战斗中，进场会恢复冻结");
        }
        if (settlements != null && settlements.hash() != null && !settlements.hash().isEmpty()) {
            warnings.add("有 " + settlements.hash().size() + " 笔待结算战斗奖励在 Redis（battle 已落、scene 尚未应用并销账，不在库里的玩法数据中）");
        }
        for (PlayerRedis.RawEntry entry : new PlayerRedis.RawEntry[] {battleLock, settlements}) {
            if (entry != null && entry.error() != null) {
                warnings.add("战斗键读取失败：" + entry.error());
            }
        }
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
