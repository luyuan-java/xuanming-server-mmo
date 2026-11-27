package com.game.scene.world;

import com.game.contract.MessageMethod;
import java.util.Set;

/**
 * 换场景节点期间「换节点执行含义不变」的客户端消息白名单（对齐主仓 {@code player_handoff_gate.h} 与
 * {@code docs/design/client-scene-request-handoff-hold.md} 定案及 S13 旧场景消息闸）：
 * <ul>
 *   <li>整服务进白名单（7 个）：背包与装备（{@code SceneBagClientPlayer}）、属性（{@code SceneAttributeClientPlayer}）、
 *       宝宝（{@code ScenePetClientPlayer}）、任务（{@code SceneMissionClientPlayer}）、货币（{@code SceneCurrencyClientPlayer}）、
 *       活跃度（{@code SceneActivityClientPlayer}）、战斗结果（{@code SceneBattleResultClientPlayer}）；</li>
 *   <li>按方法进白名单：
 *       <ul>
 *         <li>{@code SceneSkillClientPlayer}：仅看技能 / 升技能（{@code ListSkills} / {@code UpgradeSkill}），放技能（{@code ReleaseSkill}）不进；</li>
 *         <li>{@code SceneSceneClientPlayer}：仅取版本摘要（{@code GetSyncDigest}）、查场景信息（{@code SceneInfoC2S}）与观战交流个人偏好
 *             （{@code GetSpectateChatSetting} / {@code SetSpectateChatSetting}）；换图（{@code EnterScene}）、跨 zone 传送（{@code TravelToZone}）不进；</li>
 *       </ul>
 *   </li>
 *   <li>移动 / 瞬移、神秘商人、放技能、换图、跨 zone 传送、退出一律不在白名单内：收到 {@code received_on_previous_route=true}
 *       时请求回 1003、单向消息直接丢弃，绝不拿到新场景里执行。</li>
 * </ul>
 */
public final class SceneHandoffWhitelist {

    private static final Set<String> WHITELISTED_SERVICES = Set.of(
            "SceneBagClientPlayer",
            "SceneAttributeClientPlayer",
            "ScenePetClientPlayer",
            "SceneMissionClientPlayer",
            "SceneCurrencyClientPlayer",
            "SceneActivityClientPlayer",
            "SceneBattleResultClientPlayer"
    );

    private static final Set<String> WHITELISTED_METHODS = Set.of(
            "SceneSkillClientPlayer.ListSkills",
            "SceneSkillClientPlayer.UpgradeSkill",
            "SceneSceneClientPlayer.GetSyncDigest",
            "SceneSceneClientPlayer.SceneInfoC2S",
            "SceneSceneClientPlayer.GetSpectateChatSetting",
            "SceneSceneClientPlayer.SetSpectateChatSetting"
    );

    private SceneHandoffWhitelist() {
    }

    public static boolean isWhitelisted(MessageMethod method) {
        if (method == null) {
            return false;
        }
        return isWhitelisted(method.serviceName(), method.methodName());
    }

    public static boolean isWhitelisted(String serviceName, String methodName) {
        if (serviceName == null || methodName == null) {
            return false;
        }
        if (WHITELISTED_SERVICES.contains(serviceName)) {
            return true;
        }
        return WHITELISTED_METHODS.contains(serviceName + "." + methodName);
    }
}
