package com.game.robot.scenario;

import com.game.proto.SceneInfoComp;
import com.game.robot.client.GameConnection;
import com.game.robot.flow.EnteredPlayer;

/**
 * mirror / dungeon 探针里一个已进场的账号：分工、所在场景、自己当前的实体号与位置，以及这次进场从本连接第几条下行开始
 * （判断「进场之后看见过谁」）。不可变，换场景 / 重登后换一个新的。
 *
 * @param since 进入当前场景时本连接收件箱的序号（登录进场为 0）
 */
record ProbeBot(String role, EnteredPlayer player, SceneInfoComp scene, long entity, Vec3 at, int since) {

    GameConnection connection() {
        return player.connection();
    }

    long playerId() {
        return player.playerId();
    }

    String account() {
        return player.account();
    }

    ProbeBot movedTo(SceneInfoComp newScene, long newEntity, Vec3 newAt, int newSince) {
        return new ProbeBot(role, player, newScene, newEntity, newAt, newSince);
    }

    ProbeBot at(Vec3 newAt) {
        return new ProbeBot(role, player, scene, entity, newAt, since);
    }
}
