package com.game.match.global;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 不访问数据库即可拒绝非法/空 UUID，并保存全部 128 位原意图身份。 */
class GlobalRequestIdentityTest {
    @Test void malformedNoncanonicalAndNilAreRejected(){
        for(String request:new String[]{"","bad","00000000-0000-0000-0000-000000000000","fF000000-0000-0000-0000-000000000001"})
            assertThrows(IllegalArgumentException.class,()->GlobalMatchStore.validateRequestId(request));
    }
    @Test void bothUnsignedHalvesRemainExact(){
        var id=UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffffe");var key=GlobalMatchStore.directUuidKey(1,7,id.toString());
        assertEquals(-1L,key.getRequestHigh());assertEquals(-2L,key.getRequestLow());
    }
}
