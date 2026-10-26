package com.game.api;

import com.game.api.proto.SceneTeamRefresh;
import com.game.api.proto.SceneTeamRefreshReply;
import java.util.concurrent.CompletableFuture;

/** 队伍变化/归队提醒；按节点直连、实例围栏、零重试，scene 收到后强读 SQL 成员关系。 */
public interface SceneTeamService {
    CompletableFuture<SceneTeamRefreshReply> refresh(SceneTeamRefresh request);
}
