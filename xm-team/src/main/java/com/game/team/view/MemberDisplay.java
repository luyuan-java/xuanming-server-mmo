package com.game.team.view;

/**
 * 视图里一名玩家的运行时展示信息（基线 presence.go:29-38 memberDisplay）。显示层数据：只在构建视图时按需批量读，绝不写进记录。
 * 一次提交 / 一次读只加载一份（{@code Map<Long, MemberDisplay>}，基线 displayCache），所有接收者复用。
 *
 * <p>数值字段是 uint32 的位模式；字符串不为 null（缺失为空串）。读失败的字段一律零值（尽力而为，不让 RPC 失败，基线 presence.go:25-27）。
 *
 * @param online       在线（Java：{@code xm:presence} 宽松批量读，读失败按离线，team-spec §6.6）
 * @param inBattle     战斗中（咨询性；Java 在批次 6.3 之前没有战斗锁，恒为 false，D10）
 * @param level        等级（Java：player 表，到存盘才更新，D4）
 * @param classId      职业
 * @param name         昵称
 * @param appearanceId 外观
 * @param gender       性别
 */
public record MemberDisplay(boolean online, boolean inBattle, int level, int classId, String name, String appearanceId,
                            int gender) {

    /** 缺失时的零值（基线 dc[pid] 取不到时的 memberDisplay{}）。 */
    public static final MemberDisplay NONE = new MemberDisplay(false, false, 0, 0, "", "", 0);

    public MemberDisplay {
        name = name == null ? "" : name;
        appearanceId = appearanceId == null ? "" : appearanceId;
    }
}
