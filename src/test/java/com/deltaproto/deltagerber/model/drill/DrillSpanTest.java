package com.deltaproto.deltagerber.model.drill;

import com.deltaproto.deltagerber.parser.ExcellonParser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrillSpanTest {

    @Test
    void readsTheSpanAnX2DrillFunctionStates() {
        assertEquals(new DrillSpan(1, 2), DrillSpan.ofFileFunction(List.of("Plated", "1", "2", "Blind", "Drill")));
        assertEquals(new DrillSpan(3, 10), DrillSpan.ofFileFunction(List.of("Plated", "3", "10", "Buried")));
        assertEquals(new DrillSpan(1, 6), DrillSpan.ofFileFunction(List.of("NonPlated", "1", "6", "NPTH")));
        assertNull(DrillSpan.ofFileFunction(List.of("Copper", "L1", "Top")));
        assertNull(DrillSpan.ofFileFunction(List.of("Drillmap")));
        assertNull(DrillSpan.ofFileFunction(null));
    }

    @Test
    void reachesTheLayersBetweenItsEnds() {
        DrillSpan buried = new DrillSpan(10, 3);
        assertEquals(3, buried.from(), "given either way round");
        assertTrue(buried.reaches(3));
        assertTrue(buried.reaches(10));
        assertFalse(buried.reaches(1));
        assertFalse(buried.reaches(12));
    }

    @Test
    void anExcellonFileCarriesTheSpanKiCadWritesInItsHeader() {
        DrillDocument doc = new ExcellonParser().parse("""
                M48
                ; DRILL file {KiCad 8.0.4} date 2024-06-01
                ; FORMAT={-:-/ absolute / metric / decimal}
                ; #@! TF.FileFunction,Plated,1,2,Blind
                FMAT,2
                METRIC
                T1C0.100
                %
                G90
                G05
                T1
                X10.0Y10.0
                M30
                """);
        assertEquals(new DrillSpan(1, 2), doc.getSpan());
    }

    @Test
    void aPlainExcellonFileHasNoSpan() {
        DrillDocument doc = new ExcellonParser().parse("M48\nMETRIC,TZ\n;TYPE=PLATED\nT1C0.300\n%\nT1\nX010000Y010000\nM30\n");
        assertNull(doc.getSpan());
    }
}
