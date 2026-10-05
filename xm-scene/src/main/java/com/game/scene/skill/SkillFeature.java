package com.game.scene.skill;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.proto.ReleaseSkillRequest;
import com.game.proto.ReleaseSkillResponse;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.SceneFeature;

/**
 * 放技能的客户端方法（{@code SceneSkillClientPlayer} 84 ReleaseSkill）。规则在 {@link SkillService}；
 * 77 ListSkills 只读玩家身上的技能列表，留在场景核心（{@code ClientRequestHandler}）。
 *
 * <p>应答总带 {@code error_message}（成功 0）。基线 handler 直接写的 1001 会被生成代码的 TRANSFER_ERROR_MESSAGE 覆盖成空 tip，
 * Java 版如实回 1001（PARITY「放技能 1001」行）。
 */
public final class SkillFeature implements SceneFeature {

    private static final String SERVICE = "SceneSkillClientPlayer";

    private final SkillService skills;

    public SkillFeature(SkillService skills) {
        this.skills = skills;
    }

    /**
     * 冻结策略（scene-handoff-spec §5.9）：84 ALLOW——基线请求层不拒、只在施法点对冻结施法者 no-op；Java 的施法运行态
     * （施法阶段、冷却）不持久化，伤害 / buff 两版都未生效，冻结中照常处理不会改动冻结快照。
     */
    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "ReleaseSkill", ReleaseSkillRequest.class, FreezePolicy.ALLOW, (call, request) -> call.reply(
                ReleaseSkillResponse.newBuilder()
                        .setErrorMessage(tip(skills.release(call.world(), call.player(), request)))
                        .build()));
    }
}
