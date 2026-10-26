package com.pedro.tabletoprpg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InitiativeRollerTest {

    @Test
    @DisplayName("formula vazia rola 1d20 puro")
    void emptyFormulaIsPlainD20() {
        assertEquals("1d20", InitiativeRoller.formulaOrDefault(""));
        assertEquals("1d20", InitiativeRoller.formulaOrDefault("   "));
        assertEquals("1d20", InitiativeRoller.formulaOrDefault(null));
    }

    @Test
    @DisplayName("formula digitada pelo mestre e a rolagem inteira, sem 1d20 extra")
    void typedFormulaIsTheWholeRoll() {
        assertEquals("1d20+3", InitiativeRoller.formulaOrDefault("1d20+3"));
        assertEquals("2d6+1", InitiativeRoller.formulaOrDefault("  2d6+1  "));
    }

    @Test
    @DisplayName("formula com sinal inicial e mantida como digitada")
    void signedFormulaIsKept() {
        assertEquals("-2", InitiativeRoller.formulaOrDefault("-2"));
    }
}
