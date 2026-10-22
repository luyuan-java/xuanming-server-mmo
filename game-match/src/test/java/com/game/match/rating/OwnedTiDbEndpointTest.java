package com.game.match.rating;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OwnedTiDbEndpointTest {
    @Test void 使用显式owned端口_不默认连4000() {
        assertEquals("jdbc:mysql://127.0.0.1:24000/", RatingTestDatabase.tidbRoot("127.0.0.1:24000"));
        assertEquals("jdbc:mysql://127.0.0.1:65535/", RatingTestDatabase.tidbRoot("127.0.0.1:65535"));
        for (String bad : java.util.List.of("", "127.0.0.1", "127.0.0.1:0", "127.0.0.1:65536",
                "127.0.0.1:024000", "localhost:24000", "192.168.1.2:24000", "127.0.0.1:24000/game", "127.0.0.1:24000?x=y"))
            assertThrows(IllegalArgumentException.class, () -> RatingTestDatabase.tidbRoot(bad));
        assertThrows(IllegalArgumentException.class, () -> RatingTestDatabase.tidbRoot(null));
    }
}
