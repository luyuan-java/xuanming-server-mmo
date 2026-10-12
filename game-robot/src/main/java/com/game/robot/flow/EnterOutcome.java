package com.game.robot.flow;

import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RedirectTarget;
import java.util.List;

/**
 * 进游戏（26）成功之后的两种结局（批次 5.4）：收到 79 进了场，或者收到 124 被让去别的 gate。
 * 后者是登录期重定向（GO-5）：这个角色正在别的区，入口区的 login 不夺权、不分配场景，只回 26 成功再推一条 124——这条连接上不会再有 79。
 */
public sealed interface EnterOutcome permits EnterOutcome.Entered, EnterOutcome.Redirected {

    /** 走登录流程的那条连接。 */
    GameConnection connection();

    /** 进游戏的角色。 */
    long playerId();

    /** 进了场。 */
    record Entered(EnteredPlayer player) implements EnterOutcome {

        @Override
        public GameConnection connection() {
            return player.connection();
        }

        @Override
        public long playerId() {
            return player.playerId();
        }
    }

    /**
     * 被重定向：26 无错，随后来的是 124。接下来由调用方决定跟不跟（{@link RedirectFollower#follow} 之后在新连接上
     * {@link PlayerFlow#reenter}）；{@code connection} 仍然打开，由调用方或跟随件关闭。
     *
     * @param connection 收到 124 的那条连接（入口区的 gate；推出 124 之后它上面的请求不再有回包）
     * @param playerId   26 进的角色
     * @param target     124 的内容
     * @param frame      那条 124 本身（到达序号与到达时刻：核对「26 的应答在前、124 在后」）
     * @param roles      这次登录（48）给的角色列表
     */
    record Redirected(GameConnection connection, long playerId, RedirectTarget target, Received frame,
                      List<RoleZone> roles) implements EnterOutcome {

        public Redirected {
            roles = List.copyOf(roles);
        }
    }
}
