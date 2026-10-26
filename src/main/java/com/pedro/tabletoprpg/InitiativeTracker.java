package com.pedro.tabletoprpg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Lista de iniciativa em memoria: estado da coleta e participantes com valor.
 *
 * <p>Classe pura, sem dependencias do Minecraft, para ter teste proprio. Quem
 * valida mundo, permissao e modo e o {@link InitiativeCommands}.
 *
 * <p><b>Regras:</b> um jogador rola uma vez so (o mestre pode repetir a entrada dele). Um mob e identificado pelo nome de
 * exibicao; todos os mobs com o mesmo nome compartilham o mesmo valor rolado, e o
 * mestre pode repetir a entrada (cada repeticao e um turno a mais no grupo).
 *
 * <p>A leitura sai ordenada do maior para o menor (ver {@link #entries()}).
 */
public final class InitiativeTracker {

    public enum State {
        IDLE,
        COLLECTING
    }

    /** Um participante. {@code mob} diferencia jogador de grupo de mobs. */
    public record Entry(String name, boolean mob, int value) {
    }

    private State state = State.IDLE;
    private final List<Entry> entries = new ArrayList<>();

    public State state() {
        return state;
    }

    public boolean isCollecting() {
        return state == State.COLLECTING;
    }

    /** Abre a coleta de rolagens e limpa qualquer lista anterior. */
    public void start() {
        entries.clear();
        state = State.COLLECTING;
    }

    /** Encerra a iniciativa e limpa a lista. */
    public void end() {
        entries.clear();
        state = State.IDLE;
    }

    /**
     * Participantes do maior valor para o menor. Empate mantem a ordem de entrada,
     * porque {@link List#sort} e estavel.
     */
    public List<Entry> entries() {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(Entry::value).reversed());
        return Collections.unmodifiableList(sorted);
    }

    /** Valor ja rolado para um nome (jogador ou grupo de mob), ou {@code null}. */
    public Integer valueOf(String name) {
        for (Entry entry : entries) {
            if (entry.name().equals(name)) {
                return entry.value();
            }
        }
        return null;
    }

    public boolean contains(String name) {
        return valueOf(name) != null;
    }

    /**
     * Adiciona uma entrada. A regra de quem pode rolar (um jogador nao rola duas
     * vezes) fica no comando; aqui a entrada sempre entra, porque o mestre pode
     * repetir jogador ou mob para dar mais turnos.
     */
    public void add(String name, boolean mob, int value) {
        entries.add(new Entry(name, mob, value));
    }

    /** Remove todas as entradas com aquele nome e devolve quantas saiu. */
    public int remove(String name) {
        int before = entries.size();
        entries.removeIf(entry -> entry.name().equals(name));
        return before - entries.size();
    }
}
