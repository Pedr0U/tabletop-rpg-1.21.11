package com.pedro.tabletoprpg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitiativeTrackerTest {

    @Test
    @DisplayName("comeca ocioso; start abre a coleta e limpa a lista")
    void startOpensCollectionAndClears() {
        InitiativeTracker tracker = new InitiativeTracker();
        assertEquals(InitiativeTracker.State.IDLE, tracker.state());

        tracker.start();
        tracker.add("Ana", false, 12);
        tracker.start();

        assertTrue(tracker.isCollecting());
        assertTrue(tracker.entries().isEmpty());
    }

    @Test
    @DisplayName("end zera a lista e volta para ocioso")
    void endClearsAndReturnsIdle() {
        InitiativeTracker tracker = new InitiativeTracker();
        tracker.start();
        tracker.add("Ana", false, 12);

        tracker.end();

        assertEquals(InitiativeTracker.State.IDLE, tracker.state());
        assertTrue(tracker.entries().isEmpty());
    }

    @Test
    @DisplayName("jogador nao entra duas vezes")
    void playerCannotEnterTwice() {
        InitiativeTracker tracker = new InitiativeTracker();
        tracker.start();

        assertTrue(tracker.add("Ana", false, 12));
        assertFalse(tracker.add("Ana", false, 18));

        assertEquals(1, tracker.entries().size());
        assertEquals(12, tracker.valueOf("Ana"));
    }

    @Test
    @DisplayName("mob pode entrar varias vezes com o mesmo valor do grupo")
    void mobCanEnterManyTimes() {
        InitiativeTracker tracker = new InitiativeTracker();
        tracker.start();
        tracker.add("Goblin", true, 14);
        tracker.add("Goblin", true, tracker.valueOf("Goblin"));

        assertEquals(2, tracker.entries().size());
        assertEquals(14, tracker.entries().get(1).value());
    }

    @Test
    @DisplayName("remove tira todas as entradas com o nome e devolve a contagem")
    void removeTakesEveryEntryWithName() {
        InitiativeTracker tracker = new InitiativeTracker();
        tracker.start();
        tracker.add("Goblin", true, 14);
        tracker.add("Goblin", true, 14);
        tracker.add("Ana", false, 9);

        assertEquals(2, tracker.remove("Goblin"));
        assertEquals(0, tracker.remove("Goblin"));
        assertEquals(1, tracker.entries().size());
        assertNull(tracker.valueOf("Goblin"));
    }

    @Test
    @DisplayName("entrada na ordem de chegada (ordenacao e da Fase 2)")
    void keepsInsertionOrder() {
        InitiativeTracker tracker = new InitiativeTracker();
        tracker.start();
        tracker.add("X", false, 15);
        tracker.add("Y", false, 15);

        assertEquals("X", tracker.entries().get(0).name());
        assertEquals("Y", tracker.entries().get(1).name());
    }
}
