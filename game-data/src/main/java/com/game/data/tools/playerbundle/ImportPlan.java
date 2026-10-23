package com.game.data.tools.playerbundle;

import com.game.player.store.PlayerStore;
import com.game.player.store.state.PlayerState;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 导入计划：从导出包里取出要写进本地的东西，并在连任何外部系统之前把包本身校验完（格式、版本、玩家号、玩法数据的 sha256）。
 * 纯数据，不碰库。
 *
 * <p>写进本地的只有 {@code player} 行与 {@code player_state} 原字节——Java 版登录加载链路只读这两样（login 夺权读 {@code player}，scene
 * 加载读两者）。账号行由导入补建（同登录时的 {@code ensureAccount}），口令不导入：本地用 dev 口令规则登录。归属三列不照搬线上值，
 * 由 {@link LocalPlayerImporter} 在本地重铸。
 *
 * @param stateBytes 存在性明确且哈希匹配的玩法数据原字节；不得为 null，合法空 protobuf 仍可导入
 * @param baseEpoch  线上的 {@code owner_epoch}（本地重铸的 epoch 取它与本地值的较大者 + 1）
 * @param notes      预览里要提示的事项
 */
record ImportPlan(long playerId, String account, long zoneId, String name, String nameKey, long classId, long gender,
                  String appearanceId, long level, long sceneConfigId, double posX, double posY, double posZ, long createdAt,
                  byte[] stateBytes, long baseEpoch, List<String> notes) {

    /** 本地账号名上限（{@code account.account} 是 VARCHAR(64)）。 */
    static final int MAX_ACCOUNT_LENGTH = 64;

    /**
     * @param localAccount 本地登录用的账号（必填；写进 {@code player.account}，登录校验角色归属用它）
     * @param zoneOverride 非 null 时改写归属区 {@code player.zone_id}
     * @param nameOverride 非 null 时改写名字（本地已有同名玩家时用）
     * @throws IllegalArgumentException 包不合规或参数非法（原因在消息里）
     */
    static ImportPlan from(PlayerBundle bundle, String localAccount, Long zoneOverride, String nameOverride) {
        if (!PlayerBundle.FORMAT.equals(bundle.format())) {
            throw new IllegalArgumentException("不是玩家导出包：format=" + bundle.format());
        }
        if (bundle.formatVersion() != PlayerBundle.FORMAT_VERSION) {
            throw new IllegalArgumentException("导出包格式版本 " + bundle.formatVersion() + " 本工具不认识（只认 " + PlayerBundle.FORMAT_VERSION
                    + "），换与导出端同版本的工具");
        }
        if (bundle.mysql() == null || bundle.mysql().player() == null) {
            throw new IllegalArgumentException("导出包里没有 player 行");
        }
        PlayerBundle.Player p = bundle.mysql().player();
        long playerId = PlayerBundleExport.parsePlayerId(bundle.playerId());
        if (PlayerBundleExport.parsePlayerId(p.playerId()) != playerId) {
            throw new IllegalArgumentException("导出包的玩家号 " + bundle.playerId() + " 与 player 行的 " + p.playerId() + " 不一致");
        }
        if (localAccount == null || localAccount.isBlank()) {
            throw new IllegalArgumentException("必须用 --local-account 指定本地登录账号（写进 player.account）");
        }
        if (localAccount.codePointCount(0, localAccount.length()) > MAX_ACCOUNT_LENGTH) {
            throw new IllegalArgumentException("--local-account 超过 " + MAX_ACCOUNT_LENGTH + " 个字符");
        }
        List<String> notes = new ArrayList<>();
        if (!localAccount.startsWith("dev_") && !localAccount.startsWith("robot_")) {
            notes.add("本地账号 " + localAccount + " 不是 dev_ / robot_ 前缀：本地 login 的开发口令模式只认这两个前缀");
        }
        String name = nameOverride != null ? nameOverride : p.name();
        if (name.isBlank()) {
            throw new IllegalArgumentException("名字为空");
        }
        long zoneId = zoneOverride != null ? zoneOverride : p.zoneId();
        if (zoneId <= 0 || zoneId > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("归属区必须在 1–4294967295：" + zoneId);
        }
        byte[] state = null;
        PlayerBundle.State s = bundle.mysql().playerState();
        if (s == null) {
            throw new IllegalArgumentException("导出包没读出 player_state（导出时这部分失败）：不知道线上有没有玩法数据，拒绝导入");
        }
        if (s.found()) {
            if (s.dataBase64() == null || s.sha256() == null) {
                throw new IllegalArgumentException("player_state 缺原字节或 sha256");
            }
            state = Base64.getDecoder().decode(s.dataBase64());
            if (!BundleMysqlReader.sha256(state).equals(s.sha256())) {
                throw new IllegalArgumentException("player_state 原字节与 sha256 不符：导出包被改过或损坏");
            }
            try {
                PlayerState.parseFrom(state);
            } catch (InvalidProtocolBufferException e) {
                notes.add("玩法数据用本地检出的 proto 解不开（" + e.getMessage() + "）：本地 scene 加载会失败——如果要复现的正是加载失败，照常导入；"
                        + "否则先检出与线上一致的版本");
            }
        } else {
            throw new IllegalArgumentException("来源缺少 player_state，不能证明是初始存档，拒绝导入");
        }
        if (bundle.serverRevision() == null) {
            notes.add("导出包没有记录线上版本：确认本地检出与线上一致");
        }
        if (bundle.analysis() != null && !BundleAnalyzer.RELEASED.equals(bundle.analysis().ownership())) {
            notes.add("导出时归属状态是 " + bundle.analysis().ownership() + "：库里不一定是最终态（" + bundle.analysis().persistence() + "）");
        }
        return new ImportPlan(playerId, localAccount, zoneId, name, PlayerStore.nameKey(name), p.classId(), p.gender(), p.appearanceId(),
                p.level(), p.sceneConfigId(), p.posX(), p.posY(), p.posZ(), p.createdAt(), state,
                Long.parseUnsignedLong(p.ownerEpoch()), List.copyOf(notes));
    }
}
