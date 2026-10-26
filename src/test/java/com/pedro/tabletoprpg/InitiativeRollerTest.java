package com.pedro.tabletoprpg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InitiativeRollerTest {

    @Test
    @DisplayName("bonus vazio rola 1d20 puro")
    void emptyBonusIsPlainD20() {
        assertEquals("1d20", InitiativeRoller.bonusFormula(""));
        assertEquals("1d20", InitiativeRoller.bonusFormula("   "));
        assertEquals("1d20", InitiativeRoller.bonusFormula(null));
    }

    @Test
    @DisplayName("numero vira 1d20+numero")
    void numberBonusIsAppended() {
        assertEquals("1d20+3", InitiativeRoller.bonusFormula("3"));
        assertEquals("1d20+3", InitiativeRoller.bonusFormula("  3  "));
    }

    @Test
    @DisplayName("bonus com sinal negativo mantem o sinal")
    void negativeBonusKeepsSign() {
        assertEquals("1d20-2", InitiativeRoller.bonusFormula("-2"));
        assertEquals("1d20+2", InitiativeRoller.bonusFormula("+2"));
    }

    @Test
    @DisplayName("formula de dado no bonus soma mais um dado (1d20+1d20+3)")
    void diceBonusAddsAnotherDie() {
        assertEquals("1d20+1d20+3", InitiativeRoller.bonusFormula("1d20+3"));
    }
}
