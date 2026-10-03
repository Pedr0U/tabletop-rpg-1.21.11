package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.DiaryEntry;
import com.pedro.tabletoprpg.DiaryStore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Os textos que a jogadora digitou e que ainda nao foram aceitos (03/10/2026).
 *
 * <p><b>O pedido que este cache atende:</b> "navegar dentro do diario nao salva; so salva
 * quando eu sair". So que cada tela e um objeto novo: se o texto vivesse so na caixa,
 * subir um nivel e voltar traria a caixa vazia e a alteracao seria perdida — que era
 * exatamente o defeito reportado antes.
 *
 * <p><b>Por que fica aqui e nao dentro da tela:</b> a tela morre a cada navegacao. Guardando
 * aqui, a secao continua com o que foi digitado em qualquer ponto da sessao, e o aviso
 * amarelo continua aceso sem que nada tenha sido gravado no servidor.
 *
 * <p><b>Por que so do cliente:</b> enquanto o texto nao sai daqui, ele nao existe para o
 * servidor e portanto nao existe no NBT do jogador. Fechar o jogo com o diario aberto
 * perde o rascunho — e e o preco de "navegar nao salva". O aviso amarelo existe justamente
 * para dizer que aquilo ainda nao foi aceito.
 *
 * <p><b>Diferente do espelho do servidor:</b> o {@code COMMITTED} do {@link DiaryStore} e
 * outra coisa, la no servidor, e serve ao {@code [Reverter]}. Este mapa e so "o que esta
 * na caixa e nao foi aceito", e nao sobrevive a sessao.
 */
public final class DiaryDrafts {

    /** Titulo e descricao pendentes, por no. */
    private static final Map<Integer, String[]> PENDING = new HashMap<>();

    /**
     * {@link #PENDING} no formato que {@link DiaryStore#hasPendingInSubtree} consome,
     * rebuilt so quando o mapa muda.
     *
     * <p><b>Por que existe:</b> a marcacao amarela e consultada POR CARTAO, POR QUADRO — com
     * 10 cartoes a 60 fps sao 600 consultas por segundo. Montar um {@code HashSet} novo em
     * cada uma seriam 600 alocacoes por segundo na tela, e nao e o que o desenho de um
     * cartao deveria pagar. A versao validada e o valor do cache; mudar o mapa o invalida.
     */
    private static Set<Integer> cachedIds;

    private DiaryDrafts() {
    }

    /**
     * Guarda o texto de um no, sobrescrevendo o rascunho anterior deste mesmo no.
     *
     * @return {@code true} se este no estava sem rascunho e acabou de ganhar um — ou seja,
     *         se e a primeira vez que a jogadora mexiu nele. E o sinal para o
     *         {@link DiaryUndo} registrar UM passo, e nao um por tecla digitada.
     */
    public static boolean put(int nodeId, String title, String description) {
        boolean novo = !PENDING.containsKey(nodeId);
        PENDING.put(nodeId, new String[]{title, description});
        cachedIds = null;
        return novo;
    }

    /**
     * O rascunho de um no, ou {@code null} se nao ha.
     *
     * <p>Um rascunho com titulo vazio e devolvido assim mesmo: e o que a jogadora digitou,
     * e a caixa mostra e deixa ela terminar. E o servidor que recusa, na hora de gravar.
     */
    public static String[] get(int nodeId) {
        return PENDING.get(nodeId);
    }

    /** Ha rascunho deste no? */
    public static boolean has(int nodeId) {
        return PENDING.containsKey(nodeId);
    }

    /** Apaga o rascunho de um no. Chamado quando o texto foi aceito. */
    public static void clear(int nodeId) {
        if (PENDING.remove(nodeId) != null) {
            cachedIds = null;
        }
    }

    /** Esquece tudo. Chamado quando o diario inteiro e aceito. */
    public static void clearAll() {
        if (!PENDING.isEmpty()) {
            PENDING.clear();
            cachedIds = null;
        }
    }

    /** Quantos nos tem rascunho. So para o log de diagnostico e para acender os botoes. */
    public static int size() {
        return PENDING.size();
    }

    /**
     * Uma copia de todos os rascunhos, por id.
     *
     * <p>Copia e nao a propria colecao: quem chama vai percorrer e descartar, e a colecao
     * interna nao pode mudar no meio do percurso (o {@code clearAll} do fim do flush).
     */
    public static Map<Integer, String[]> all() {
        return new HashMap<>(PENDING);
    }

    /**
     * Este no, ou algum descendente dele, tem rascunho?
     *
     * <p>Mesma regra do aviso que vem do servidor ({@link DiaryStore#hasPendingInSubtree}):
     * a marcacao sobe pelo caminho ate o que foi alterado. Editando a Subsecao 1.2, acendem
     * a 1.2, a 1 e a Secao 1 — e <b>nao</b> a Subsecao 2, que e irma.
     *
     * <p>Reaproveita a busca do servidor em vez de reescrever: as duas precisam concordar
     * sobre quem acende, e duas copias da mesma regra divergem na primeira edicao.
     */
    public static boolean hasInSubtree(List<DiaryEntry> all, int nodeId) {
        Set<Integer> ids = ids();
        return !ids.isEmpty() && DiaryStore.hasPendingInSubtree(all, nodeId, ids);
    }

    /**
     * Os ids com rascunho, no formato que a busca do servidor espera.
     *
     * <p>A versao validada do {@link #cachedIds}. Nao devolve a propria colecao de ids,
     * porque quem chama a busca do servidor so a percorre, mas devolve o conjunto de ids e
     * nao o mapa de textos — e os textos nao interessam a busca.
     */
    public static Set<Integer> ids() {
        Set<Integer> local = cachedIds;
        if (local == null) {
            local = new HashSet<>(PENDING.keySet());
            cachedIds = local;
        }
        return local;
    }
}