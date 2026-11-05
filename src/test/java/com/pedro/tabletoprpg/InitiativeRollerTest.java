package com.pedro.tabletoprpg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitiativeRollerTest {

    @Test
    @DisplayName("formula padrao da mesa vazia volta a 1d20")
    void emptyModelFormulaFallsBackToD20() {
        assertEquals("1d20", SheetModel.defaults().withInitiativeFormula("").initiativeFormula());
        assertEquals("1d20", SheetModel.defaults().withInitiativeFormula("   ").initiativeFormula());
        assertEquals("1d20", SheetModel.defaults().withInitiativeFormula(null).initiativeFormula());
    }

    @Test
    @DisplayName("formula padrao da mesa e guardada sem espacos, como o parser le")
    void modelFormulaIsCompacted() {
        assertEquals("1d20+3", SheetModel.defaults().withInitiativeFormula("1d20+3").initiativeFormula());
        assertEquals("2d6+1", SheetModel.defaults().withInitiativeFormula(" 2d6 + 1 ").initiativeFormula());
    }

    @Test
    @DisplayName("formula padrao da mesa so aceita dados e numeros")
    void modelFormulaRejectsNames() {
        assertEquals("1d20", SheetModel.defaults().withInitiativeFormula("1d20+Destreza").initiativeFormula());
        assertFalse(InitiativeRoller.isValidPlainFormula("1d20+Destreza"));
        assertTrue(InitiativeRoller.isValidPlainFormula("2d6+3"));
    }

    @Test
    @DisplayName("formula vazia e valida (rola 1d20 seco)")
    void emptyFormulaIsValid() {
        assertTrue(InitiativeRoller.isValidFormula("", SheetModel.defaults()));
        assertTrue(InitiativeRoller.isValidFormula("   ", SheetModel.defaults()));
    }

    @Test
    @DisplayName("formulas de dado com ou sem espacos sao reconhecidas")
    void diceFormulasAreValid() {
        assertTrue(InitiativeRoller.isValidFormula("1d20+3", SheetModel.defaults()));
        assertTrue(InitiativeRoller.isValidFormula("1d20 + 3", SheetModel.defaults()));
        assertTrue(InitiativeRoller.isValidFormula("2d20+1d6-2", SheetModel.defaults()));
    }

    @Test
    @DisplayName("texto que nao e formula e recusado")
    void garbageIsInvalid() {
        assertFalse(InitiativeRoller.isValidFormula("abc", SheetModel.defaults()));
        assertFalse(InitiativeRoller.isValidFormula("1d", SheetModel.defaults()));
        assertFalse(InitiativeRoller.isValidFormula("1d20+", SheetModel.defaults()));
    }

    @Test
    @DisplayName("formula da ficha aceita dado, numero puro e sinal inicial")
    void sheetFormulaAcceptsDiceAndSign() {
        // O campo da ficha virou formula propria (04/11/2026) e a dica sugere
        // "+3"; o parser precisa aceitar o sinal inicial, senao digitar o que a
        // dica mostra seria recusado.
        assertTrue(InitiativeRoller.isValidPlainFormula("1d4"));
        assertTrue(InitiativeRoller.isValidPlainFormula("2d6+1"));
        assertTrue(InitiativeRoller.isValidPlainFormula("3"));
        assertTrue(InitiativeRoller.isValidPlainFormula("-2"));
        assertTrue(InitiativeRoller.isValidPlainFormula("+3"));
        // A soma da mesa com a ficha tem que continuar valida nos dois sinais.
        assertTrue(InitiativeRoller.isValidPlainFormula("1d20-2"));
        assertTrue(InitiativeRoller.isValidPlainFormula("1d20+3"));
        assertFalse(InitiativeRoller.isValidPlainFormula("++3"));
    }
}
