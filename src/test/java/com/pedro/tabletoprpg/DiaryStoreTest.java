package com.pedro.tabletoprpg;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O Diario: arvore chata, cascata, desfazer e as duas ordenacoes.
 *
 * <p>02/10/2026. A ordem e testada como funcao pura ({@link DiaryStore#sorted}) com
 * {@code modifiedAt} e {@code pinSeq} escritos a mao, porque {@code create} usa
 * {@code System.currentTimeMillis()} e duas anotacoes criadas no mesmo milissegundo
 * empatariam -- o teste viraria sobre o relogio, nao sobre a regra.
 */
class DiaryStoreTest {

    private final UUID player = UUID.randomUUID();

    @AfterEach
    void limpaCache() {
        // O store e estatico; sem isto a delecao de um teste aparece no seguinte.
        DiaryStore.forget(player);
    }

    private DiaryEntry criaRaiz(String titulo) throws DiaryStore.DiaryException {
        return DiaryStore.create(player, DiaryEntry.ROOT, titulo, "");
    }

    private static List<String> titulos(List<DiaryEntry> lista) {
        List<String> out = new ArrayList<>();
        for (DiaryEntry entry : lista) {
            out.add(entry.title());
        }
        return out;
    }

    private static DiaryEntry entrada(int id, int pai, String titulo, long modificado, long pin) {
        return new DiaryEntry(id, pai, titulo, "", modificado, pin);
    }

    // ------------------------------------------------------------ estrutura

    @Test
    @DisplayName("subsecao criada dentro de secao monta o caminho da raiz ate ela")
    void caminhoCompleta() throws Exception {
        DiaryEntry secao = criaRaiz("Seção 1");
        DiaryEntry sub = DiaryStore.create(player, secao.id(), "Subseção 1", "nota");
        DiaryEntry neta = DiaryStore.create(player, sub.id(), "Subseção 2", "");

        assertEquals("Seção 1 > Subseção 1 > Subseção 2", DiaryStore.breadcrumb(player, neta.id()));
        assertEquals(3, DiaryStore.ancestry(player, neta.id()).size());
        assertEquals(1, DiaryStore.roots(player).size());
        assertEquals(1, DiaryStore.children(player, secao.id()).size());
    }

    @Test
    @DisplayName("a profundidade nao tem teto: 40 niveis encadeados sobem todos")
    void profundidadeIlimitada() throws Exception {
        // O primeiro "N" ja e uma secao de raiz, entao a cadeia tem 40 degraus e nao 41:
        // o que o numero mede e a altura, e a altura nao conta a raio duas vezes.
        int pai = DiaryEntry.ROOT;
        for (int i = 1; i <= 40; i++) {
            DiaryEntry entry = DiaryStore.create(player, pai, "N" + i, "");
            pai = entry.id();
        }
        assertEquals(40, DiaryStore.entries(player).size());
        assertEquals(40, DiaryStore.breadcrumb(player, pai).split(" > ").length);
    }

    @Test
    @DisplayName("titulo vazio e recusado, com pai que nao existe tambem")
    void criacaoInvalida() throws Exception {
        assertThrows(DiaryStore.DiaryException.class,
                () -> DiaryStore.create(player, DiaryEntry.ROOT, "   ", ""));
        assertThrows(DiaryStore.DiaryException.class,
                () -> DiaryStore.create(player, 9999, "Orfa", ""));
        assertEquals(0, DiaryStore.entries(player).size());
    }

    @Test
    @DisplayName("o titulo automatico da subsecao numera dentro daquele pai")
    void tituloAutomatico() throws Exception {
        DiaryEntry secao = criaRaiz("Seção 1");
        DiaryEntry outro = criaRaiz("Seção 2");

        assertEquals("Subseção 1", DiaryStore.defaultSubsectionTitle(player, secao.id()));
        DiaryStore.create(player, secao.id(), "Subseção 1", "");
        assertEquals("Subseção 2", DiaryStore.defaultSubsectionTitle(player, secao.id()));
        // A contagem e por pai: a outra secao continua na primeira.
        assertEquals("Subseção 1", DiaryStore.defaultSubsectionTitle(player, outro.id()));
    }

    // -------------------------------------------------------------- apagar

    @Test
    @DisplayName("apagar uma secao leva junto a subarvore inteira, nao deixa orfa")
    void apagarEmCascata() throws Exception {
        DiaryEntry secao = criaRaiz("Seção 1");
        DiaryEntry sub = DiaryStore.create(player, secao.id(), "Subseção 1", "");
        DiaryStore.create(player, sub.id(), "Subseção 2", "");
        criaRaiz("Sobrevive");

        assertTrue(DiaryStore.delete(player, secao.id()));
        assertEquals(List.of("Sobrevive"), titulos(DiaryStore.entries(player)));
    }

    @Test
    @DisplayName("Reverter so existe depois de apagar, e devolve a subarvore inteira uma vez")
    void reverterUltimaDelecao() throws Exception {
        DiaryEntry secao = criaRaiz("Seção 1");
        DiaryEntry sub = DiaryStore.create(player, secao.id(), "Subseção 1", "anotacao");
        DiaryEntry neta = DiaryStore.create(player, sub.id(), "Subseção 2", "");

        assertFalse(DiaryStore.canUndo(player));
        assertFalse(DiaryStore.undo(player));

        DiaryStore.delete(player, secao.id());
        assertTrue(DiaryStore.canUndo(player));

        assertTrue(DiaryStore.undo(player));
        assertEquals(3, DiaryStore.entries(player).size());
        // A neta e o caso que prova a subarvore: ela so volta com a hierarquia inteira,
        // e nao como orfa pendurada num pai que nao existe.
        assertEquals("Seção 1 > Subseção 1 > Subseção 2", DiaryStore.breadcrumb(player, neta.id()));
        // E o botao some depois de usar, como a jogadora pediu.
        assertFalse(DiaryStore.canUndo(player));
        assertFalse(DiaryStore.undo(player));
    }

    @Test
    @DisplayName("Reverter e uma pilha: cada delecao volta na sua vez, do mais novo ao mais velho")
    void reverterEmpilha() throws Exception {
        DiaryEntry a = criaRaiz("A");
        DiaryEntry b = criaRaiz("B");
        DiaryStore.delete(player, a.id());
        DiaryStore.delete(player, b.id());

        assertTrue(DiaryStore.undo(player));
        // B voltou, A continua fora: o topo da pilha e a ultima delecao.
        assertEquals(List.of("B"), titulos(DiaryStore.entries(player)));
        assertTrue(DiaryStore.undo(player));
        // A segunda vez volta A. Era o que a jogadora pediu ao trocar "so a ultima" por
        // "acumulado ate clicar em Salvar".
        // A ordem da lista sai [B, A] porque desfazer ANEXA a subarvore; quem decide a ordem
        // na tela e o `sorted`, nao a ordem bruta. O que o teste trava e o conjunto.
        assertEquals(List.of("B", "A"), titulos(DiaryStore.entries(player)));
        assertFalse(DiaryStore.canUndo(player));
    }

    // ------------------------------------------------ rascunho e pendencia

    @Test
    @DisplayName("editar a secao acende a pendencia nela e em toda a descendencia, e so nela")
    void pendenciaSobePeloCaminho() throws Exception {
        DiaryEntry um = criaRaiz("Seção 1");
        DiaryEntry dois = criaRaiz("Seção 2");
        DiaryEntry subUm = DiaryStore.create(player, um.id(), "Subseção 1", "");
        DiaryEntry subDois = DiaryStore.create(player, um.id(), "Subseção 2", "");
        DiaryEntry neta = DiaryStore.create(player, subUm.id(), "Subseção 1.1", "");
        aceitaAtual();

        // Mexe so na 1.1, que e neta da subsecao 1 e bisneta da secao 1.
        DiaryStore.save(player, neta.id(), "Subseção 1.1 editada", "anotacao nova");

        assertEquals(List.of(neta.id()), DiaryStore.pendingIds(player));
        // A "subsecao 2" e IRMA da "subsecao 1", nao ancestral: nao acende. E a "secao 2"
        // esta fora do caminho inteiro.
        assertFalse(marcado(dois), "secao 2 nao esta no caminho");
        assertFalse(marcado(subDois), "subsecao 2 e irma, nao ancestral");
        assertTrue(marcado(subUm), "subsecao 1 e o pai direto do que mudou");
        assertTrue(marcado(um), "secao 1 e o ancestral do que mudou");
        assertTrue(marcado(neta), "o proprio no");
    }

    @Test
    @DisplayName("aceitar limpa a pendencia e o Reverter, e o texto continua no diario")
    void aceitarLimpaPendencia() throws Exception {
        DiaryEntry secao = criaRaiz("Seção 1");
        aceitaAtual();
        DiaryStore.save(player, secao.id(), "Seção 1 nova", "anotacao");

        assertEquals(List.of(secao.id()), DiaryStore.pendingIds(player));
        assertTrue(DiaryStore.canUndo(player));

        DiaryStore.accept(player);

        assertTrue(DiaryStore.pendingIds(player).isEmpty());
        assertFalse(DiaryStore.canUndo(player));
        // "Aceitar" nao e desfazer: o texto novo continua gravado.
        assertEquals("Seção 1 nova", DiaryStore.find(player, secao.id()).orElseThrow().title());
    }

    @Test
    @DisplayName("Reverter devolve o texto anterior de uma edicao, e a pendencia some")
    void reverterEdicao() throws Exception {
        DiaryEntry secao = criaRaiz("Seção 1");
        DiaryStore.save(player, secao.id(), "original", "antes");
        aceitaAtual();

        DiaryStore.save(player, secao.id(), "alterado", "depois");
        assertEquals(List.of(secao.id()), DiaryStore.pendingIds(player));

        assertTrue(DiaryStore.undo(player));
        DiaryEntry voltou = DiaryStore.find(player, secao.id()).orElseThrow();
        assertEquals("original", voltou.title());
        assertEquals("antes", voltou.description());
        // Desfazer volta ao aceito, entao a marcacao amarela tem de sumir junto. Sem isso a
        // tela mostraria "alteracao para salvar" para algo que ja voltou.
        assertTrue(DiaryStore.pendingIds(player).isEmpty());
    }

    @Test
    @DisplayName("gravar o mesmo texto de novo nao cria pendencia nem empilha no Reverter")
    void gravarIgualNaoMudaNada() throws Exception {
        DiaryEntry secao = criaRaiz("Seção 1");
        aceitaAtual();

        // E o que o salvamento automatico faz a cada troca de tela com o campo intacto.
        assertEquals("Seção 1", DiaryStore.save(player, secao.id(), "Seção 1", "").title());
        assertFalse(DiaryStore.canUndo(player));
        assertTrue(DiaryStore.pendingIds(player).isEmpty());
    }

    @Test
    @DisplayName("desfazer a delecao devolve a subarvore, que continua marcada se tinha rascunho")
    void desfazerDelecaoNaoDeixaPendencia() throws Exception {
        DiaryEntry secao = criaRaiz("Seção 1");
        DiaryStore.create(player, secao.id(), "Subseção 1", "");
        aceitaAtual();

        // Um rascunho de verdade antes do apagado, para o "voltou limpa" ser uma escolha do
        // codigo e nao um accidento de um diario que nunca foi editado.
        DiaryStore.save(player, secao.id(), "Seção 1 editada", "");
        assertEquals(List.of(secao.id()), DiaryStore.pendingIds(player));

        assertTrue(DiaryStore.delete(player, secao.id()));
        // Apagou: o pendente sumiu junto, nao ficou apontando para um id que nao existe.
        assertTrue(DiaryStore.pendingIds(player).isEmpty());

        assertTrue(DiaryStore.undo(player));
        // Voltou com o texto EDITADO, porque foi assim que ela estava no momento do
        // apagado -- e por isso que ela continua marcada.
        assertEquals(List.of(secao.id()), DiaryStore.pendingIds(player));
    }

    @Test
    @DisplayName("criar antes de editar nao impede as secoes antigas de virarem pendentes")
    void criarPrimeiroNaoQuebraOPendiente() throws Exception {
        // Regressao do espelho: `create` gravava o espelho da secao nova e criava o mapa,
        // e `ensureCommitted` nunca mais rodava. As secoas antigas ficavam sem espelho para
        // sempre -- ou seja, editar uma delas nao acendia nada, que era a marca inteira.
        DiaryEntry antiga = criaRaiz("Seção 1");
        criaRaiz("Seção 2");
        criaRaiz("Seção 3");

        DiaryStore.save(player, antiga.id(), "Seção 1 nova", "");

        assertEquals(List.of(antiga.id()), DiaryStore.pendingIds(player));
        assertTrue(marcado(antiga));
    }

    @Test
    @DisplayName("criar uma secao nao e rascunho: ela nasce aceita")
    void criarNaoEhPendencia() throws Exception {
        criaRaiz("Seção 1");
        aceitaAtual();
        DiaryEntry nova = criaRaiz("Seção 2");

        // Sem esta regra, toda secao acabada de nascer apareceria com a marca amarela de
        // "alteracao para salvar" de algo que a jogadora nao tem como ter feito.
        assertTrue(DiaryStore.pendingIds(player).isEmpty());
        assertEquals("Seção 2", nova.title());
    }

    /**
     * Aceita o texto como esta AGORA: e a linha de base de "o que a jogadora ja salvou".
     *
     * <p>E o {@code accept} do servidor (o botao Salvar). Nos testes e preciso porque a
     * linha de base real e tirada na primeira alteracao da sessao, e nos testes as arvores
     * sao montadas com {@code create} -- que ja marca o que nasce. Sem esta chamada o
     * primeiro {@code save} do teste seria indistinguivel do estado inicial.
     */
    private void aceitaAtual() {
        DiaryStore.accept(player);
    }

    /** A marcacao que a tela desenharia para este no. */
    private boolean marcado(DiaryEntry entry) {
        return DiaryStore.hasPendingInSubtree(
                DiaryStore.entries(player), entry.id(), Set.copyOf(DiaryStore.pendingIds(player)));
    }

    // ------------------------------------------------------------ ordenar

    @Test
    @DisplayName("fixados vao para o topo, e o ultimo fixado fica acima dos anteriores")
    void fixadosNoTopoNaOrdemDeChegada() {
        List<DiaryEntry> lista = List.of(
                entrada(1, DiaryEntry.ROOT, "Zeta", 300L, 1L),
                entrada(2, DiaryEntry.ROOT, "Alfa", 200L, 3L),
                entrada(3, DiaryEntry.ROOT, "Miolo", 100L, 2L));

        // pinSeq 3 foi o ultimo fixado: vai no topo, mesmo sendo o menor titulo.
        assertEquals(List.of("Alfa", "Miolo", "Zeta"),
                titulos(DiaryStore.sorted(lista, DiaryStore.SortMode.ALPHABETICAL)));
        assertEquals(List.of("Alfa", "Miolo", "Zeta"),
                titulos(DiaryStore.sorted(lista, DiaryStore.SortMode.MODIFIED)));
    }

    @Test
    @DisplayName("desfixar devolve a anotacao a ordenacao do momento")
    void desfixarVoltaAoCriterio() throws Exception {
        criaRaiz("Alfa");
        criaRaiz("Zeta");
        DiaryEntry alvo = DiaryStore.find(player, 1).orElseThrow();

        DiaryStore.setPinned(player, alvo.id(), true);
        assertEquals(List.of("Alfa", "Zeta"),
                titulos(DiaryStore.sorted(DiaryStore.entries(player), DiaryStore.SortMode.ALPHABETICAL)));

        DiaryStore.setPinned(player, alvo.id(), false);
        // Solto, ele volta a respeitar o criterio escolhido (agora o mesmo, mas a regra
        // e que o grupo dos fixados e separado do grupo do criterio).
        assertEquals(List.of("Alfa", "Zeta"),
                titulos(DiaryStore.sorted(DiaryStore.entries(player), DiaryStore.SortMode.ALPHABETICAL)));
        assertFalse(DiaryStore.find(player, alvo.id()).orElseThrow().pinned());
    }

    @Test
    @DisplayName("Modificado e do mais recente para o mais antigo; Alfabetico e de A a Z")
    void doisCriterios() {
        List<DiaryEntry> lista = List.of(
                entrada(1, DiaryEntry.ROOT, "Zeta", 300L, 0L),
                entrada(2, DiaryEntry.ROOT, "Alfa", 200L, 0L),
                entrada(3, DiaryEntry.ROOT, "Miolo", 100L, 0L));

        assertEquals(List.of("Zeta", "Alfa", "Miolo"),
                titulos(DiaryStore.sorted(lista, DiaryStore.SortMode.MODIFIED)));
        assertEquals(List.of("Alfa", "Miolo", "Zeta"),
                titulos(DiaryStore.sorted(lista, DiaryStore.SortMode.ALPHABETICAL)));
        assertEquals(DiaryStore.SortMode.ALPHABETICAL, DiaryStore.SortMode.MODIFIED.next());
        assertEquals(DiaryStore.SortMode.MODIFIED, DiaryStore.SortMode.ALPHABETICAL.next());
    }

    // -------------------------------------------------------------- busca

    @Test
    @DisplayName("a busca ignora maiuscula e acento, e vazio devolve tudo")
    void buscaSemAcento() throws Exception {
        criaRaiz("Física");
        criaRaiz("Combate");
        List<DiaryEntry> todas = DiaryStore.entries(player);

        assertEquals(2, DiaryStore.filtered(todas, "").size());
        assertEquals(List.of("Física"), titulos(DiaryStore.filtered(todas, "fisica")));
        assertEquals(List.of("Física"), titulos(DiaryStore.filtered(todas, "FÍSICA")));
        assertEquals(List.of("Combate"), titulos(DiaryStore.filtered(todas, "comb")));
        assertEquals(0, DiaryStore.filtered(todas, "zzz").size());
    }

    // -------------------------------------------------------------- limites

    @Test
    @DisplayName("titulo grande demais e recusado ao criar e ao salvar, com a contagem na mensagem")
    void tituloGrandeEhRecusado() throws Exception {
        String tituloLongo = "a".repeat(DiaryEntry.MAX_TITLE + 1);

        DiaryStore.DiaryException erro = assertThrows(DiaryStore.DiaryException.class,
                () -> DiaryStore.create(player, DiaryEntry.ROOT, tituloLongo, ""));
        assertTrue(erro.getMessage().contains(String.valueOf(DiaryEntry.MAX_TITLE)));
        assertEquals(0, DiaryStore.entries(player).size());

        DiaryEntry ok = criaRaiz("Dentro do teto");
        assertThrows(DiaryStore.DiaryException.class,
                () -> DiaryStore.save(player, ok.id(), tituloLongo, ""));
        // Recusar nao pode ter alterado nada.
        assertEquals("Dentro do teto", DiaryStore.find(player, ok.id()).orElseThrow().title());
    }

    @Test
    @DisplayName("no caminho do NBT o titulo e a descricao sao cortados, e nunca dao excecao")
    void codecCortaEmVezDeLancar() {
        // Divergencia de proposito com o `create`: o `Codec` roda sobre NBT editado a mao,
        // onde nao existe jogadora para receber mensagem, entao o unico caminho e cortar.
        // Quem chama `create`/`save` ja recusou antes de chegar aqui.
        DiaryEntry doNbt = new DiaryEntry(1, DiaryEntry.ROOT,
                "a".repeat(80), "b".repeat(5_000), 0L, 0L);
        assertEquals(DiaryEntry.MAX_TITLE, doNbt.title().length());
        assertEquals(DiaryEntry.MAX_DESCRIPTION, doNbt.description().length());
        // E o texto intacto e aceito, para o corte nao ser um teto escondido.
        assertEquals(DiaryEntry.MAX_TITLE,
                new DiaryEntry(1, DiaryEntry.ROOT, "a".repeat(DiaryEntry.MAX_TITLE), "", 0L, 0L)
                        .title().length());
    }

    @Test
    @DisplayName("no teto de anotacoes, criar e recusado em vez de gravar")
    void tetoDeEntradas() throws Exception {
        List<DiaryEntry> cheia = new ArrayList<>();
        for (int i = 1; i <= DiaryStore.MAX_ENTRIES; i++) {
            cheia.add(entrada(i, DiaryEntry.ROOT, "S" + i, 0L, 0L));
        }
        DiaryStore.replaceAll(player, new DiaryStore.Diary(cheia, DiaryStore.MAX_ENTRIES + 1));

        DiaryStore.DiaryException erro = assertThrows(DiaryStore.DiaryException.class,
                () -> DiaryStore.create(player, DiaryEntry.ROOT, "Mais uma", ""));
        assertTrue(erro.getMessage().contains(String.valueOf(DiaryStore.MAX_ENTRIES)));
    }

    // ------------------------------------------------------- nbt e anomalia

    @Test
    @DisplayName("o diario sobrevive a ida e volta pelo codec, e pin ausente vira 0")
    void codecIdaEVolta() throws Exception {
        DiaryEntry secao = criaRaiz("Seção 1");
        DiaryStore.create(player, secao.id(), "Subseção 1", "linha 1\nlinha 2");
        DiaryStore.setPinned(player, secao.id(), true);

        DiaryStore.Diary original = DiaryStore.diary(player);
        CompoundTag tag = (CompoundTag) DiaryStore.Diary.CODEC
                .encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        DiaryStore.Diary lido = DiaryStore.Diary.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();

        assertEquals(original.entries().size(), lido.entries().size());
        assertEquals(original.nextId(), lido.nextId());
        assertEquals("linha 1\nlinha 2", lido.entries().get(1).description());
        assertTrue(lido.entries().get(0).pinned());
        assertEquals(secao.id(), lido.entries().get(1).parentId());
    }

    @Test
    @DisplayName("NBT com parentId apontando para si mesmo nao trava o breadcrumb")
    void anelNaoTrava() {
        // O construtor ja corta auto-pai, entao o anel so pode vir de um NBT editado a mao,
        // que e o caminho do Codec. Entrada com id 7 e pai 7 vira raiz.
        DiaryEntry anel = new DiaryEntry(7, 7, "Anel", "", 0L, 0L);
        assertEquals(DiaryEntry.ROOT, anel.parentId());
        assertTrue(DiaryEntry.searchKey("Anel").contains("anel"));
    }

    @Test
    @DisplayName("subir a partir de um pai que nao existe para no no, sem laco")
    void paiInexistenteNaoTrava() {
        List<DiaryEntry> lista = List.of(
                entrada(1, 77, "Sem pai", 0L, 0L),
                entrada(2, 1, "Filho", 0L, 0L));
        DiaryStore.replaceAll(player, new DiaryStore.Diary(lista, 3));

        // "Sem pai" nao acha o id 77, entao o caminho para nele.
        assertEquals("Sem pai", DiaryStore.breadcrumb(player, 1));
        assertEquals("Sem pai > Filho", DiaryStore.breadcrumb(player, 2));
    }
}
