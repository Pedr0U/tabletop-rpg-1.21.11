package com.pedro.tabletoprpg;

import com.pedro.tabletoprpg.client.DiaryDrafts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O cache de rascunhos do cliente: o texto que a jogadora digitou e que, enquanto ela navega
 * dentro do diario, existe SO aqui.
 *
 * <p><b>Por que estes testes existem:</b> a marcacao amarela e a regra que a jogadora mais
 * reclamou, e ela depende de duas fontes — o que o servidor tem e nao aceitou
 * ({@code pendingIds}) e o que esta na caixa e ainda nao chegou ao servidor (este cache).
 * Sem cobertura aqui, a volta de `ids()` a um `new HashSet<>(...)` por quadro, ou um
 * `clear` no lugar errado, so apareceriam em jogo.
 */
class DiaryDraftsTest {

    private UUID player;
    private DiaryEntry secao;
    private DiaryEntry sub1;
    private DiaryEntry sub2;

    @BeforeEach
    void montaArvore() throws Exception {
        player = UUID.randomUUID();
        secao = DiaryStore.create(player, DiaryEntry.ROOT, "Seção 1", "");
        sub1 = DiaryStore.create(player, secao.id(), "Subseção 1", "");
        sub2 = DiaryStore.create(player, secao.id(), "Subseção 2", "");
        DiaryStore.accept(player);
        DiaryDrafts.clearAll();
    }

    private List<DiaryEntry> arvore() {
        return DiaryStore.entries(player);
    }

    // -------------------------------------------------------------- guardar e recuperar

    @Test
    @DisplayName("o rascunho volta exatamente como a jogadora digitou")
    void guardaTexto() {
        DiaryDrafts.put(sub1.id(), "  Subseção 1 editada  ", "linha 1\nlinha 2");

        assertArrayEqualsTexto(new String[]{"  Subseção 1 editada  ", "linha 1\nlinha 2"},
                DiaryDrafts.get(sub1.id()));
        assertTrue(DiaryDrafts.has(sub1.id()));
        assertEquals(1, DiaryDrafts.size());
    }

    @Test
    @DisplayName("digitar de novo sobrescreve, e nao acumula")
    void sobrescreve() {
        DiaryDrafts.put(sub1.id(), "primeiro", "");
        DiaryDrafts.put(sub1.id(), "segundo", "");

        assertArrayEqualsTexto(new String[]{"segundo", ""}, DiaryDrafts.get(sub1.id()));
        assertEquals(1, DiaryDrafts.size());
    }

    @Test
    @DisplayName("um titulo vazio e devolvido vazio: e o que a jogadora digitou")
    void tituloVazioVolta() {
        // A tela mostra e deixa ela terminar de digitar. E o SERVIDOR que recusa na hora de
        // gravar (ver `DiaryStore.save`); se o cache devolvesse null, a caixa perderia o que
        // ela escreveu ao navegar e voltar.
        DiaryDrafts.put(sub1.id(), "", "descricao");

        String[] texto = DiaryDrafts.get(sub1.id());
        assertEquals("", texto[0]);
        assertEquals("descricao", texto[1]);
    }

    @Test
    @DisplayName("no sem rascunho devolve null")
    void semRascunho() {
        assertNull(DiaryDrafts.get(sub1.id()));
        assertFalse(DiaryDrafts.has(sub1.id()));
        assertEquals(0, DiaryDrafts.size());
    }

    @Test
    @DisplayName("limpar um no apaga so ele, e o resto continua")
    void limparUm() {
        DiaryDrafts.put(sub1.id(), "a", "");
        DiaryDrafts.put(sub2.id(), "b", "");

        DiaryDrafts.clear(sub1.id());

        assertFalse(DiaryDrafts.has(sub1.id()));
        assertTrue(DiaryDrafts.has(sub2.id()));
    }

    @Test
    @DisplayName("limpar um no que nao tem rascunho nao estraga o cache de ids")
    void limparInexistente() {
        // `clear` so invalida o cache de ids quando algo sai do mapa. Invalidacao sem
        // necessidade e desperdicio; invalidacao faltando faria a marcacao ficar acesa para
        // sempre depois de aceitar.
        DiaryDrafts.put(sub1.id(), "a", "");

        DiaryDrafts.clear(sub2.id());
        assertTrue(DiaryDrafts.hasInSubtree(arvore(), secao.id()));

        DiaryDrafts.clear(sub1.id());
        assertFalse(DiaryDrafts.hasInSubtree(arvore(), secao.id()));
    }

    // ------------------------------------------------------- a regra da marcacao amarela

    @Test
    @DisplayName("a marcacao sobe: o no, o ancestral e a raiz acendem")
    void marcaSobePeloCaminho() {
        DiaryDrafts.put(sub1.id(), "editado", "");

        assertTrue(DiaryDrafts.hasInSubtree(arvore(), sub1.id()), "o proprio no");
        assertTrue(DiaryDrafts.hasInSubtree(arvore(), secao.id()), "o ancestral");
    }

    @Test
    @DisplayName("a IRMA nao acende: e o defeito que a jogadora reportou")
    void irmaNaoAcende() {
        // Regressao do pedido "o aviso e no caminho, nao na lista inteira": editando a
        // Subsecao 1, a Subsecao 2 (irma) tem de ficar sem ponto.
        DiaryDrafts.put(sub1.id(), "editado", "");

        assertFalse(DiaryDrafts.hasInSubtree(arvore(), sub2.id()));
    }

    @Test
    @DisplayName("a marcacao desce por toda a descendencia")
    void marcaDescePelaDescendencia() throws Exception {
        DiaryEntry neta = DiaryStore.create(player, sub1.id(), "Sub-subseção", "");
        DiaryStore.accept(player);

        DiaryDrafts.put(neta.id(), "editado", "");

        assertTrue(DiaryDrafts.hasInSubtree(arvore(), sub1.id()), "o pai imediato");
        assertTrue(DiaryDrafts.hasInSubtree(arvore(), secao.id()), "a raiz");
        assertFalse(DiaryDrafts.hasInSubtree(arvore(), sub2.id()), "a irma da Subsecao 1");
    }

    @Test
    @DisplayName("sem rascunho nada acende")
    void semRascunhoNadaAcende() {
        assertFalse(DiaryDrafts.hasInSubtree(arvore(), sub1.id()));
        assertFalse(DiaryDrafts.hasInSubtree(arvore(), secao.id()));
    }

    // --------------------------------------------------------------------- o cache de ids

    @Test
    @DisplayName("o cache de ids e invalidado quando o mapa muda")
    void cacheDeIdsInvalidado() {
        // `ids()` e chamado POR CARTAO, POR QUADRO. O teste pega o caso em que a versao
        // validada fica desatualizada: `hasInSubtree` devolveria o resultado do rascunho
        // antigo e a marcacao nao desapareceria ao aceitar.
        assertTrue(DiaryDrafts.ids().isEmpty());

        DiaryDrafts.put(sub1.id(), "a", "");
        assertEquals(1, DiaryDrafts.ids().size());

        DiaryDrafts.put(sub2.id(), "b", "");
        assertEquals(2, DiaryDrafts.ids().size());

        DiaryDrafts.clearAll();
        assertTrue(DiaryDrafts.ids().isEmpty());
    }

    @Test
    @DisplayName("o mapa de rascunhos devolvido e uma copia: mexer nele nao muda o cache")
    void mapaEhCopia() {
        // `flushLocalDrafts` percorre o mapa e DEPOIS chama `clearAll`. Se `all()` devolvesse
        // o proprio mapa, a alteracao durante o percurso quebraria o laco.
        DiaryDrafts.put(sub1.id(), "a", "");

        var copia = DiaryDrafts.all();
        copia.clear();
        copia.put(sub2.id(), new String[]{"b", ""});

        assertTrue(DiaryDrafts.has(sub1.id()));
        assertFalse(DiaryDrafts.has(sub2.id()));
        assertEquals(1, DiaryDrafts.size());
    }

    private static void assertArrayEqualsTexto(String[] esperado, String[] obtido) {
        assertEquals(esperado[0], obtido[0], "titulo");
        assertEquals(esperado[1], obtido[1], "descricao");
    }
}
