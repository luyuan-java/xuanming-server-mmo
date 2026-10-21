package com.game.match.global;

import static org.junit.jupiter.api.Assertions.*;
import com.game.match.global.pb.GlobalQueueUnitRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class GlobalQueueUnitsTest {
    private GlobalQueueUnitRow unit(String id,int count){return GlobalQueueUnitRow.newBuilder().setUnitId(id).setTargetSize(5).setMemberCount(count).build();}
    @Test void 完整单位精确装箱_不会拆三人队填一格(){
        var a=unit("a",2);var b=unit("b",2);var c=unit("c",1);var d=unit("d",3);
        assertEquals(List.of(a,b,c),GlobalQueueUnits.pack(a,List.of(b,c,d)));
        assertEquals(List.of(d,a),GlobalQueueUnits.pack(d,List.of(a,b)));
        assertTrue(GlobalQueueUnits.pack(a,List.of(b)).isEmpty());
        assertEquals(List.of(unit("full",5)),GlobalQueueUnits.pack(unit("full",5),List.of()));
    }
    @Test void 候选数量与队伍容量始终有界(){
        assertThrows(IllegalArgumentException.class,()->GlobalQueueUnits.pack(unit("bad",6),List.of()));
        assertThrows(IllegalArgumentException.class,()->GlobalQueueUnits.pack(unit("a",1),java.util.Collections.nCopies(26,unit("b",1))));
    }
}
