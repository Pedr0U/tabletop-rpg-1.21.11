package com.pedro.tabletoprpg;

import com.pedro.tabletoprpg.client.DiaryDrafts;
import com.pedro.tabletoprpg.client.DiaryUndo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ordem do {@code [Reverter]}: uma pilha so, com as acoes da jogadora na ordem em que
 * ela as fez.
 *
 * <p><b>Por que o cenario central e o da jogadora, palavra por palavra:</b> "alterei a
 * descricao da secao 1 e o titulo da subsecao 1 e deletei a secao 2 nessa ordem, entao um
 * clique em reverter restaura a secao 2, o proximo volta o titulo da subsecao 1, o proximo
 * volta a descricao da secao 1". Qualquer outra ordem faz o recurso estar errado para ela,
 * e a ordem e o unico contrato deste indice.
 */
class DiaryUndoTest {

    private UUID player;
    private DiaryEntry secao1;
    private DiaryEntry sub1;
    private DiaryEntry secao2;

    @BeforeEach
    void montaArvore() throws Exception {
        player = UUID.randomUUID();
        secao1 = DiaryStore.create(player, DiaryEntry.ROOT, "Seção 1", "");
        sub1 = DiaryStore.create(player, secao1.id(), "Subseção 1", "");
        secao2 = DiaryStore.create(player, DiaryEntry.ROOT, "Seção 2", "");
        DiaryStore.accept(player);
        DiaryDrafts.clearAll();
        DiaryUndo.limpar();
    }

    /** Simula o que a tela faz a cada quadro enquanto a jogadora digita. */
    private void digita(int nodeId, String title, String descricao) {
        if (DiaryDrafts.put(nodeId, title, descricao)) {
            DiaryUndo.registrar(DiaryUndo.Tipo.EDICAO, nodeId);
        }
    }

    /** Simula o segundo clique do "Del?", que e o que apaga. */
    private void apaga(int nodeId) {
        DiaryStore.delete(player, nodeId);
        DiaryUndo.registrar(DiaryUndo.Tipo.EXCLUSAO, nodeId);
    }

    /** O que o botao faz: executa o passo do topo e o tira da pilha. */
    private DiaryUndo.Tipo reverter() {
        DiaryUndo.Passo passo = DiaryUndo.proximo();
        assertTrue(passo != null, "nao havia nada para reverter");
        if (passo.tipo() == DiaryUndo.Tipo.EDICAO) {
            DiaryDrafts.clear(passo.nodeId());
        } else {
            DiaryStore.undo(player);
        }
        DiaryUndo.remover(passo);
        return passo.tipo();
    }

    // ------------------------------------------------------------------ o cenario da jogadora

    @Test
    @DisplayName("a ordem e a ordem dela: edicao, edicao, exclusao, e volta na ordem inversa")
    void cenarioDaJogadora() throws Exception {
        // 1. descricao da Secao 1
        digita(secao1.id(), "Seção 1", "descrição nova");
        // 2. titulo da Subsecao 1
        digita(sub1.id(), "Subseção 1 nova", "");
        // 3. deletei a Secao 2
        apaga(secao2.id());

        assertEquals(3, DiaryUndo.tamanho());

        // Um clique: volta a ultima coisa, a Secao 2.
        assertEquals(DiaryUndo.Tipo.EXCLUSAO, reverter());
        assertTrue(DiaryStore.find(player, secao2.id()).isPresent(), "a Secao 2 voltou");

        // Dois cliques: volta o titulo da Subsecao 1.
        assertEquals(DiaryUndo.Tipo.EDICAO, reverter());
        assertFalse(DiaryDrafts.has(sub1.id()), "o titulo da Subsecao 1 voltou");

        // Tres cliques: volta a descricao da Secao 1.
        assertEquals(DiaryUndo.Tipo.EDICAO, reverter());
        assertFalse(DiaryDrafts.has(secao1.id()), "a descricao da Secao 1 voltou");

        assertFalse(DiaryUndo.tem());
    }

    // ------------------------------------------------------------- uma edicao por tecla

    @Test
    @DisplayName("digitar 30 vezes empilha UM passo, nao 30")
    void umaEdicaoPorNo() {
        // Se cada tecla empilhasse, um titulo atrasaria o botao e o primeiro clique
        // desfaria uma letra em vez do que a jogadora fez.
        for (int i = 1; i <= 30; i++) {
            digita(sub1.id(), "S".repeat(i), "");
        }

        assertEquals(1, DiaryUndo.tamanho());
        assertEquals(sub1.id(), DiaryUndo.proximo().nodeId());
        assertEquals(DiaryUndo.Tipo.EDICAO, DiaryUndo.proximo().tipo());
    }

    @Test
    @DisplayName("digitar, apagar tudo e voltar ao texto original nao deixa passo nenhum")
    void edicaoDesfeitaNaDigitacao() {
        digita(sub1.id(), "Subseção 1 nova", "");
        assertTrue(DiaryUndo.tem());

        // A tela percebe que o texto voltou a ser o do servidor e tira o passo.
        DiaryDrafts.clear(sub1.id());
        assertTrue(DiaryUndo.removerEdicao(sub1.id()));

        assertFalse(DiaryUndo.tem(), "um passo de edicao que nao existe mais faria o "
                + "primeiro clique do Reverter parecer quebrado");
    }

    @Test
    @DisplayName("duas anotacoes diferentes dao dois passos, cada um desfaz o seu")
    void doisNos() {
        digita(secao1.id(), "Seção 1", "d");
        digita(sub1.id(), "Subseção 1 nova", "");

        assertEquals(2, DiaryUndo.tamanho());
        reverter();
        assertTrue(DiaryDrafts.has(secao1.id()), "a Subsecao 1 foi desfeita, a Secao 1 nao");
        assertFalse(DiaryDrafts.has(sub1.id()));
    }

    // ------------------------------------------------------------------ exclusoes do servidor

    @Test
    @DisplayName("exclusoes voltam na ordem, e cada uma volta a subtree inteira")
    void exclusoesEmOrdem() throws Exception {
        DiaryEntry filha = DiaryStore.create(player, secao2.id(), "Subseção", "");
        DiaryStore.accept(player);

        apaga(secao2.id());
        apaga(secao1.id());
        assertEquals(2, DiaryUndo.tamanho());

        reverter();
        assertTrue(DiaryStore.find(player, secao1.id()).isPresent());

        reverter();
        assertTrue(DiaryStore.find(player, secao2.id()).isPresent(), "a Secao 2 voltou");
        assertTrue(DiaryStore.find(player, filha.id()).isPresent(),
                "a descendente veio junto: o apagado e a subarvore");
    }

    @Test
    @DisplayName("o servidor sem pilha tira as exclusoes do cliente e preserva as edicoes")
    void servidorSemPilha() {
        // Cenario de defensivo: o servidor reiniciou com o diario aberto. Uma exclusao aqui
        // viraria promessa que o `DiaryUndoPayload` nao cumpre, e o botao acionado sem
        // efeito; uma edicao nao depende do servidor e continua valendo.
        digita(secao1.id(), "Seção 1", "d");
        apaga(secao2.id());
        apaga(secao1.id());

        DiaryUndo.removerExclusoes();

        assertEquals(1, DiaryUndo.tamanho());
        assertEquals(DiaryUndo.Tipo.EDICAO, DiaryUndo.proximo().tipo());
    }

    @Test
    @DisplayName("a ordem dos passos e sempre crescente, mesmo no mesmo quadro")
    void ordemEstavel() {
        // `System.currentTimeMillis()` nao garante ordem dentro do mesmo milissegundo, e a
        // jogadora pode editar e apagar no mesmo clique.
        digita(secao1.id(), "Seção 1", "d");
        apaga(secao2.id());
        digita(sub1.id(), "Subseção 1 nova", "");

        List<Long> ordens = new ArrayList<>();
        DiaryUndo.Passo passo = DiaryUndo.proximo();
        while (passo != null) {
            ordens.add(passo.ordem());
            passo = proximoAposRemover(passo);
        }
        assertEquals(List.of(3L, 2L, 1L), ordens, "do mais novo para o mais antigo");
    }

    private DiaryUndo.Passo proximoAposRemover(DiaryUndo.Passo passo) {
        DiaryUndo.remover(passo);
        return DiaryUndo.proximo();
    }

    @Test
    @DisplayName("limpar esvazia e reinicia a contagem")
    void limpar() {
        digita(secao1.id(), "Seção 1", "d");
        apaga(secao2.id());
        assertTrue(DiaryUndo.tem());

        DiaryUndo.limpar();

        assertFalse(DiaryUndo.tem());
        assertEquals(0, DiaryUndo.tamanho());

        // A contagem reinicia para que dois passos nao possam ter a mesma ordem depois de
        // um [Salvar] — a ordem e o que decide quem e o topo.
        digita(sub1.id(), "Subseção 1", "");
        assertEquals(1L, DiaryUndo.proximo().ordem());
    }

    @Test
    @DisplayName("pilha vazia nao tem passo do topo")
    void vazia() {
        assertFalse(DiaryUndo.tem());
        assertNull(DiaryUndo.proximo());
    }
}
