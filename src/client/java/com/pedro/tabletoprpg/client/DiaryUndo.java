package com.pedro.tabletoprpg.client;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A ordem em que a jogadora fez as coisas, para o {@code [Reverter]} desfazer na ordem
 * inversa (03/10/2026).
 *
 * <p><b>O pedido que este indice atende:</b> "alterei a descricao da secao 1, o titulo da
 * subsecao 1 e deletei a secao 2, nessa ordem; um clique em reverter restaura a secao 2, o
 * proximo volta o titulo da subsecao 1, o proximo volta a descricao da secao 1". Uma pilha
 * so.
 *
 * <p><b>Por que a pilha esta aqui e nao no servidor:</b> ate a rodada 5b, tudo que a
 * jogadora escrevia ia para o servidor a cada troca de tela, e o servidor guardava o
 * {@code undo} com o texto anterior. Ela pediu para nao gravar ao navegar, entao o texto
 * novo passou a existir so no cliente ({@link DiaryDrafts}) e o servidor nunca chega a ver
 * uma edicao *pendente*. Um {@code undo} so no servidor passaria a valer apenas para
 * exclusoes — que foi exatamente o que aconteceu e o que ela rejeitou.
 *
 * <p><b>Por que so a ordem, e nao o dado:</b> um passo guarda o tipo e o id, nunca o texto.
 * O texto de uma edicao esta no {@link DiaryDrafts} e o texto anterior de uma exclusao esta
 * no servidor. Guardar uma copia aqui seria um terceiro lugar com o mesmo dado, e a copia
 * envelheceria na hora em que qualquer um dos outros dois mudasse.
 *
 * <p><b>Por que a ordem de uma edicao e quando ela COMECOU, e nao a ultima tecla:</b> o
 * `DiaryDrafts` e reescrito a cada quadro enquanto ela digita. Se cada tecla empilhasse,
 * um titulo de trinta segundos atrasaria o {@code [Reverter]} por baixo de tudo, e o
 * primeiro clique desfaria uma letra em vez do que ela fez. O passo e registrado uma vez,
 * no primeiro quadro em que o texto ficou diferente do servidor.
 *
 * <p><b>Nao substitui a pilha do servidor, e sim a ordena:</b> uma exclusao real continua
 * sendo desfeita por {@code DiaryUndoPayload}, que consome o topo da pilha do servidor. As
 * duas pilhas concordam na ordem relativa das exclusoes, porque so o servidor recebe
 * exclusoes, e so na ordem em que a jogadora as fez.
 *
 * <p>Vive so no cliente e nao sobrevive a sessao: e a mesma razao do {@link DiaryDrafts}.
 */
public final class DiaryUndo {

    /** O que a jogadora fez, e o que o {@code [Reverter]} desfaz. */
    public enum Tipo {
        /**
         * Texto digitado que so existe no {@link DiaryDrafts}.
         *
         * <p>Desfazer e apagar o rascunho: a caixa volta a mostrar o texto do servidor,
         * porque e o que havia antes de ela digitar.
         */
        EDICAO,

        /**
         * Anotacao apagada, que o servidor ja tirou do diario.
         *
         * <p>Desfazer e pedir ao servidor o {@code DiaryUndoPayload}: ele recoloca a
         * anotacao e todas as descendentes, e consome o topo da pilha dele.
         */
        EXCLUSAO
    }

    /** Um passo da pilha. Sem dado de texto, so o tipo e o id (ver o comentario da classe). */
    public record Passo(Tipo tipo, int nodeId, long ordem) {
    }

    /**
     * O mais recente primeiro, como uma pilha de verdade.
     *
     * <p>{@code ArrayDeque} e limitado a 64 passos. Aqui isso e irrelevante: a jogadora
     * desfar um por vez e a pilha so cresce com o que ela fez nesta sessao; se chegar a
     * 64, o mais antigo simplesmente deixa de ser reversivel, e o aviso amarelo continua
     * dizendo que ha alteracao nao aceita.
     */
    private static final Deque<Passo> PILHA = new ArrayDeque<>();

    /**
     * O proximo numero de ordem.
     *
     * <p>Um contador e nao o relogio: dois passos no mesmo milissegundo (editar e apagar no
     * mesmo clique) ainda precisam de uma ordem definida, e {@code currentTimeMillis()} nao
     * garante isso. A ordem so e comparada com outra ordem, entao tanto zero quanto um
     * serviria; comeca em 1 porque "ordem 0" parece um valor ausente.
     */
    private static long proximaOrdem;

    private DiaryUndo() {
    }

    /** Um passo novo no topo, com a ordem mais recente de todas. */
    public static void registrar(Tipo tipo, int nodeId) {
        PILHA.addFirst(new Passo(tipo, nodeId, ++proximaOrdem));
    }

    /** Ha algo para desfazer? E o que acende o {@code [Reverter]}. */
    public static boolean tem() {
        return !PILHA.isEmpty();
    }

    /** Quantos passos tem. So para o log de diagnostico. */
    public static int tamanho() {
        return PILHA.size();
    }

    /** O passo do topo, ou {@code null} se a pilha esta vazia. */
    public static Passo proximo() {
        return PILHA.peekFirst();
    }

    /** Tira um passo da pilha. E o chamador que ja o executou. */
    public static void remover(Passo passo) {
        PILHA.remove(passo);
    }

    /**
     * Tira todos os passos de exclusao, preservando a ordem das edicoes.
     *
     * <p><b>Por que isso existe:</b> uma exclusao so pode ser desfeita se o servidor ainda
     * a tiver na pilha dele. Quando o servidor avisa que nao tem nada, os passos de
     * exclusao aqui viraram promessas que o {@code DiaryUndoPayload} nao consegue cumprir, e
     * o botao ficaria acionado sem efeito. As edicoes sao preservadas porque elas nao
     * dependem do servidor.
     */
    public static void removerExclusoes() {
        PILHA.removeIf(passo -> passo.tipo() == Tipo.EXCLUSAO);
    }

    /**
     * Tira o passo de edicao de um no, se houver.
     *
     * <p><b>Por que existe:</b> a jogadora pode digitar e depois apagar tudo, voltando o
     * texto ao que o servidor tem. O {@link DiaryDrafts} percebe (o rascunho fica igual ao
     * espelho e some), mas o passo na pilha ficaria: o primeiro clique do [Reverter]
     * desfaria uma edicao que nao existe mais, e a jogadora acharia que o botao quebrou.
     *
     * @return {@code true} se removeu alguma coisa
     */
    public static boolean removerEdicao(int nodeId) {
        return PILHA.removeIf(passo -> passo.tipo() == Tipo.EDICAO && passo.nodeId() == nodeId);
    }

    /** Esquece tudo. Chamado no [Salvar] e na saida do diario. */
    public static void limpar() {
        PILHA.clear();
        proximaOrdem = 0;
    }
}
