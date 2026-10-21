package com.game.guild.activity;

import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.proto.match.CancelActivityBattleRequest;
import com.game.proto.match.CancelActivityBattleResponse;
import com.game.proto.match.StartActivityBattleRequest;
import java.util.Map;
import java.util.function.LongSupplier;

/** 只有核签并绑定整份原 Start 的取消回执，才能证明可释放角色 ACTIVE 投影。 */
public final class GuildTrialCancellation {
    public static final String METHOD = "/match.MatchInternal/CancelActivityBattle";
    public static final String SECRET_ENV = "MMORPG_MATCH_INTERNAL_SECRET_GUILD";
    private final String secret;
    private final LongSupplier clock;
    private final ServiceCallSignatures replies;

    public GuildTrialCancellation(String secret, LongSupplier clock) {
        this.replies = new ServiceCallSignatures(Map.of("match", secret == null ? "" : secret), clock);
        this.secret = secret;
        this.clock = clock;
    }
    public static GuildTrialCancellation fromEnvironment() {
        return new GuildTrialCancellation(System.getenv(SECRET_ENV), System::currentTimeMillis);
    }
    public CancelActivityBattleRequest request(StartActivityBattleRequest original) {
        if (original == null || original.getRequestId() == 0 || original.getActivityContext().getLaunchRequestId() != original.getRequestId())
            throw new SecurityException("历练取消缺少完整原开战请求");
        return ServiceCallSignatures.sign(METHOD, CancelActivityBattleRequest.newBuilder().setOriginal(original).build(),
                "guild", secret, clock.getAsLong());
    }
    public Verified verify(CancelActivityBattleRequest request, CancelActivityBattleResponse response) {
        if (request == null || response == null || !"match".equals(replies.verify(METHOD + "/reply",response))
                || !ServiceCallSignatures.digest(request).equals(response.getRequestDigest())
                || response.getFormationState() < 0 || response.getFormationState() > 5
                || response.getRequestCancelled() && (response.getBattleId() != 0 || response.getFormationState() != 0)
                || response.getFormationState() != 0 && response.getBattleId() == 0)
            throw new SecurityException("历练取消回执签名、原件或形状不符");
        return new Verified(request.getOriginal(), response);
    }
    public static final class Verified {
        private final StartActivityBattleRequest original;
        private final CancelActivityBattleResponse response;
        private Verified(StartActivityBattleRequest original, CancelActivityBattleResponse response) {
            this.original = original;
            this.response = response;
        }
        public StartActivityBattleRequest original() { return original; }
        public CancelActivityBattleResponse response() { return response; }
        public boolean terminal() {
            return response.getRequestCancelled() && response.getBattleId() == 0
                    || response.getBattleId() != 0 && response.getFormationState() == 5;
        }
    }
}
