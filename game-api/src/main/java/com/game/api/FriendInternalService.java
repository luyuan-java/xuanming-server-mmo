package com.game.api;

import com.game.proto.friend.BatchCheckSocialRelationsRequest;
import com.game.proto.friend.BatchCheckSocialRelationsResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 好友服务的服务间内部只读查询（Dubbo，game-friend 提供，group {@link DubboGroups#FRIEND}）。
 *
 * <p>不占客户端消息号、不进客户端会话白名单（契约标记 {@code CLIENT_RPC_SERVER_ONLY}），供 {@code game-match}
 * 天墉生死状发起战书前权威校验两队间拉黑与全员互为好友关系。
 */
public interface FriendInternalService {

    /** 批量校验两组角色之间是否存在任意单向/双向拉黑以及是否全员互为双向好友。 */
    CompletableFuture<BatchCheckSocialRelationsResponse> batchCheckSocialRelations(
            BatchCheckSocialRelationsRequest request);
}
