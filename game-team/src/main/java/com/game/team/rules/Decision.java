package com.game.team.rules;

import com.game.proto.team.TeamChangeReason;
import com.game.team.proto.TeamRecord;
import java.util.List;

/**
 * 规则输出（基线 rules.go:145-182）。
 *
 * <p>三种形态：
 * <ul>
 *   <li>{@code code != 0}：业务拒绝，什么都不写；{@code record} 为 null，{@code param} 是 parameters[0]（0 = 不带）。</li>
 *   <li>{@code code == 0 && !changed}：成功但无需提交（幂等重放 / 无变化）。</li>
 *   <li>{@code code == 0 && changed}：按集合提交 S_COMMIT；{@code record} 是新记录，null 表示解散。</li>
 * </ul>
 *
 * <p>集合契约：{@code joined ∪ kept == 新记录成员}；{@code left ⊇ 旧成员 \ 新成员}；三者两两不相交；
 * {@code invitesRemoved ∩ invitesAdded == ∅}（基线注释说存储层提交前会校验，实际不校验 left ⊇ 旧 \ 新，team-spec §8.4 / D25）。
 * 成员列表一律按 (join_seq, player_id) 无符号升序；所有列表都不可变、不为 null。
 *
 * @param code                     tip 码，{@link TeamTips#OK} 表示成功
 * @param param                    parameters[0]（十进制 player_id；0 = 不带）
 * @param changed                  是否需要提交
 * @param record                   新记录；解散或无需提交时为 null
 * @param joined                   新加入者（索引写本队、epoch 变）
 * @param kept                     保留成员
 * @param left                     离开者（旧成员中不在新记录里的，再加上「索引指向本队但记录里没有」的调用者）
 * @param invitesAdded             要写进被邀请人反查索引的项（IA）
 * @param invitesRemoved           要从反查索引删掉的被邀请人（ID，已去掉 IA 里的人）
 * @param reason                   推送原因（TeamSnapshotS2C.reason）；不提交时为 UNSPECIFIED
 * @param actor                    推送的触发者（TeamSnapshotS2C.actor_id）；0 = 系统
 * @param leaderOfflineTransferred 本次惰性转让了队长（与自身操作同时发生时也为 true，推送范围扩到全员）
 * @param disbanded                本次解散（显式解散，或最后一人离队 / 被修复移出）
 * @param revokedInvitees          因解散而失效的未过期邀请的被邀请人（推 INVITE_REVOKED）
 * @param rejectedApplicant        被拒绝的申请人（推 APPLICATION_REJECTED）；0 = 无
 * @param invitedPlayer            本次新发 / 刷新邀请的被邀请人（推 NotifyTeamInvite）；0 = 无
 */
public record Decision(int code, long param, boolean changed, TeamRecord record,
                       List<Long> joined, List<Long> kept, List<Long> left,
                       List<InviteAdd> invitesAdded, List<Long> invitesRemoved,
                       TeamChangeReason reason, long actor,
                       boolean leaderOfflineTransferred, boolean disbanded,
                       List<Long> revokedInvitees, long rejectedApplicant, long invitedPlayer) {

    private static final Decision UNCHANGED = new Decision(TeamTips.OK, 0, false, null, List.of(), List.of(), List.of(),
            List.of(), List.of(), TeamChangeReason.TEAM_CHANGE_REASON_UNSPECIFIED, 0, false, false, List.of(), 0, 0);

    public Decision {
        joined = joined == null ? List.of() : List.copyOf(joined);
        kept = kept == null ? List.of() : List.copyOf(kept);
        left = left == null ? List.of() : List.copyOf(left);
        invitesAdded = invitesAdded == null ? List.of() : List.copyOf(invitesAdded);
        invitesRemoved = invitesRemoved == null ? List.of() : List.copyOf(invitesRemoved);
        revokedInvitees = revokedInvitees == null ? List.of() : List.copyOf(revokedInvitees);
        reason = reason == null ? TeamChangeReason.TEAM_CHANGE_REASON_UNSPECIFIED : reason;
    }

    /** 业务拒绝（基线 rules.go:421-423 reject）。 */
    public static Decision reject(int code, long param) {
        return new Decision(code, param, false, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_UNSPECIFIED, 0, false, false, List.of(), 0, 0);
    }

    /** 成功但无需提交（Go 的零值 {@code Decision{}}）。 */
    public static Decision unchanged() {
        return UNCHANGED;
    }
}
