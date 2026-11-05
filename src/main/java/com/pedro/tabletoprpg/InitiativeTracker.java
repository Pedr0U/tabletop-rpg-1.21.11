package com.pedro.tabletoprpg;

import java.util.ArrayList;
import java.util.Collections;
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
 * <p>A leitura sai do maior para o menor, e a entrada nova e inserida na posicao do
 * proprio valor (empate na ordem de chegada). O mestre pode reordenar a lista com
 * {@link #move}; a entrada nova nao desfaz a ordem manual das que ja estavam.
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
    /** Rodada atual (04/11/2026): 1 ao comecar, +1 quando o ultimo passa o turno. */
    private int round = 1;
    /** Incrementa a cada mudanca na lista; o turno usa para saber se mudou durante a vez. */
    private long version;

    public State state() {
        return state;
    }

    /** Versao da lista: muda em start, end, add, move e remove. */
    public long version() {
        return version;
    }

    public boolean isCollecting() {
        return state == State.COLLECTING;
    }

    /** Abre a coleta de rolagens e limpa qualquer lista anterior (rodada volta a 1). */
    public void start() {
        entries.clear();
        state = State.COLLECTING;
        round = 1;
        version++;
    }

    /** Encerra a iniciativa e limpa a lista. */
    public void end() {
        entries.clear();
        state = State.IDLE;
        round = 1;
        version++;
    }

    /**
     * Numero da rodada (04/11/2026). Comeca em 1 no {@link #start} e sobe quando o
     * ultimo da lista passa o turno -- decisao do usuario, nao mudanca de lista.
     */
    public int round() {
        return round;
    }

    /**
     * Proxima rodada: quem chama e o {@code TurnManager} no fim do turno do ultimo
     * da lista. Nao mexe na {@link #version}: a lista nao mudou, e a versao e o que
     * decide se o mestre precisa confirmar o proximo turno.
     */
    public void advanceRound() {
        round++;
    }

    /**
     * Participantes na ordem de exibicao. A lista interna ja fica ordenada por valor
     * ({@link #add}) e pode ser reordenada pelo mestre ({@link #move}).
     */
    public List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
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
     * Indice 0-based da primeira entrada com esse nome e esse tipo, ou {@code -1}.
     * Duplicatas sao permitidas ({@link #add}), entao a busca devolve a primeira.
     */
    public int indexOf(String name, boolean mob) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.mob() == mob && entry.name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Adiciona uma entrada. A regra de quem pode rolar (um jogador nao rola duas
     * vezes) fica no comando; aqui a entrada sempre entra, porque o mestre pode
     * repetir jogador ou mob para dar mais turnos.
     *
     * <p>A entrada entra na posicao do proprio valor: antes da primeira entrada com
     * valor menor. Empate fica atras de quem chegou antes.
     */
    public void add(String name, boolean mob, int value) {
        int index = 0;
        while (index < entries.size() && entries.get(index).value() >= value) {
            index++;
        }
        entries.add(index, new Entry(name, mob, value));
        version++;
    }

    /**
     * Move a entrada da posicao {@code from} para a posicao {@code to}, ambas
     * 0-based. Devolve {@code false} se alguma posicao estiver fora da lista.
     */
    public boolean move(int from, int to) {
        if (from < 0 || from >= entries.size() || to < 0 || to >= entries.size()) {
            return false;
        }
        if (from == to) {
            return true;
        }
        entries.add(to, entries.remove(from));
        version++;
        return true;
    }

    /**
     * Troca so o valor da entrada, mantendo nome e tipo (06/11/2026). Como a
     * lista e ordenada por valor, a entrada pode mudar de posicao. Devolve
     * {@code false} se o indice nao existe.
     */
    public boolean setValue(int index, int value) {
        if (index < 0 || index >= entries.size()) {
            return false;
        }
        Entry current = entries.get(index);
        if (current.value() == value) {
            return true;
        }
        entries.remove(index);
        add(current.name(), current.mob(), value);
        return true;
    }

    /** Remove a entrada na posicao (0-based); {@code false} se estiver fora da lista. */
    public boolean removeAt(int index) {
        if (index < 0 || index >= entries.size()) {
            return false;
        }
        entries.remove(index);
        version++;
        return true;
    }

    /** Remove todas as entradas com aquele nome e devolve quantas saiu. */
    public int remove(String name) {
        int before = entries.size();
        entries.removeIf(entry -> entry.name().equals(name));
        int removed = before - entries.size();
        if (removed > 0) {
            version++;
        }
        return removed;
    }
}
