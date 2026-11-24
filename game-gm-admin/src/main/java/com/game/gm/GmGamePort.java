package com.game.gm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.proto.common.base.CurrencyType;

/** Web GM 后台访问下游 Java 游戏服务的受限端口，不直接写任何游戏资产表。 */
public interface GmGamePort {

    int MAX_PLAYER_LEVEL = 85;

    boolean mailEnabled();

    boolean playerCommandsEnabled();

    boolean battleScriptReviewEnabled();

    ArrayNode currencyTypes();

    boolean supportsCurrency(int currencyType);

    ObjectNode player(String playerId);

    ObjectNode sendMail(ObjectNode frozenRequest);

    ObjectNode sendSystemMail(ObjectNode frozenRequest);

    ObjectNode sendMailCampaign(ObjectNode frozenCampaign);

    ObjectNode sendPlayerCommand(ObjectNode frozenRequest);

    ObjectNode listPendingBattleScripts(int limit);

    ObjectNode reviewBattleScript(long scriptId, int revision, boolean approve, String reviewer, String reason);

    /** 从契约枚举 {@link CurrencyType} 动态构造币种目录（不手写币种号）。 */
    static ArrayNode defaultCurrencyTypes(ObjectMapper mapper) {
        ArrayNode array = mapper.createArrayNode();
        for (CurrencyType type : CurrencyType.values()) {
            if (type == CurrencyType.UNRECOGNIZED) {
                continue;
            }
            array.addObject().put("id", type.getNumber()).put("name", type.name());
        }
        return array;
    }

    static boolean isKnownCurrency(int currencyType) {
        return CurrencyType.forNumber(currencyType) != null;
    }

    final class CallFailure extends RuntimeException {
        private final String code;
        private final boolean outcomeUnknown;
        private final int tipId;

        public CallFailure(String code, String message, boolean outcomeUnknown, int tipId) {
            super(message);
            this.code = code;
            this.outcomeUnknown = outcomeUnknown;
            this.tipId = tipId;
        }

        public String getCode() {
            return code;
        }

        public boolean isOutcomeUnknown() {
            return outcomeUnknown;
        }

        public int getTipId() {
            return tipId;
        }
    }
}
