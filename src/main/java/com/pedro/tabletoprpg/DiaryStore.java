package com.pedro.tabletoprpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * O Diario de cada jogador, em memoria: anotacoes, arvore chata por {@code parentId}.
 *
 * <p><b>02/10/2026.</b> A persistencia em disco e do {@code PlayerDiaryPersistenceMixin},
 * no padrao ja usado pelos presets ({@code ValueOutput.store} / {@code ValueInput.read}
 * com a chave {@value #NBT_KEY}); aqui fica so o cache, como o {@link RollPresetStore}.
 *
 * <p><b>Por que lista chata e nao arvore aninhada:</b> a jogadora pediu subsecao de
 * profundidade infinita. Guardar o id do pai da a profundidade de graca, deixa o
 * {@link Diary#CODEC} ser um {@code Codec.list} e faz o pacote do servidor ser uma lista
 * so. Uma arvore aninhada exigiria codec recursivo, que no DFU precisa de inicializacao
 * preguicosa, e nao compra nada aqui: subir o breadcrumb e um while com guarda.
 *
 * <p><b>Por que o desfazer fica SO em memoria:</b> a jogadora escreveu "restaura e recupera
 * a ultima secao/subsecação que foi deletada <b>no uso atual da interface</b>". Guardar o
 * apagado no NBT faria um desfazer velho sobreviver a sessao e, quando ela clicasse, devolver
 * uma subarvore que nao combina com o diario de agora. O que fica em disco e so o conteudo.
 *
 * <p><b>Por que apagar e em cascata:</b> apagar uma secao sem os filhos deixaria anotacoes
 * orfas, invisiveis na tela e impossiveis de apagar pela interface. E o desfazer precisa
 * devolver a subarvore inteira, senao a secao voltaria vazia.
 *
 * <p><b>Nao chame {@link #forget} no disconnect</b>: o cache e lido no save do jogador, e
 * limpar antes faria o save sair vazado. Ver {@code PlayerRollPresetPersistenceMixin:24-28}.
 */
public final class DiaryStore {

    /** Chave deste bloco dentro do NBT do jogador. */
    public static final String NBT_KEY = "tabletoprpg_diary";

    /**
     * Teto de anotacoes por jogador, contando subsecoes.
     *
     * <p><b>Nao e uma regra do jogo:</b> a jogadora pediu profundidade infinita, e este
     * numero nao corta profundidade. E o que segura um NBT editado a mao de meter 1 milhao de
     * anotacoes e travar a carga do jogador -- e o pacote de sincronia, que vai inteiro.
     */
    public static final int MAX_ENTRIES = 2_048;

    /** Titulo automatico da subsecao recem-criada: "Subseção 1", "Subseção 2", ... */
    private static final String SUBSECTION_PREFIX = "Subseção ";

    /**
     * O conteudo salvo: a lista chata e o proximo id livre.
     *
     * <p><b>Por que o {@code nextId} vai no NBT e nao e um contador global:</b> dois
     * jogadores podem ter o mesmo id local sem colidir, porque toda operacao acontece
     * dentro de um {@code playerUuid}. E gravar o valor evita que, ao renomear/reordenar, o
     * proximo id colida com um que ja existe.
     */
    public record Diary(List<DiaryEntry> entries, int nextId) {
        public static final Diary EMPTY = new Diary(List.of(), 1);

        public static final Codec<Diary> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.list(DiaryEntry.CODEC).optionalFieldOf("entries", List.of()).forGetter(Diary::entries),
                Codec.INT.optionalFieldOf("nextId", 1).forGetter(Diary::nextId)
        ).apply(i, Diary::new));
    }

    /** Como a lista de cards e ordenada. O rotulo do botao e o {@link #label()}. */
    public enum SortMode {
        MODIFIED("Ordenar: Modificado"),
        ALPHABETICAL("Ordenar: Alfabética");

        private final String label;

        SortMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** O botao alterna entre os dois criterios. */
        public SortMode next() {
            return this == MODIFIED ? ALPHABETICAL : MODIFIED;
        }
    }

    /**
 * Uma alteracao que o botao Reverter sabe desfazer.
     *
     * <p><b>Por que duas formas:</b> apagar e editar desfazem coisas diferentes. O apagado
     * precisa devolver a SUBARVORE inteira no lugar; a edicao precisa devolver so o titulo
     * e a descricao de antes, porque a lista de ids e a mesma. Uma unica struct faria uma
     * das duas operacoes guardar dados que nao usa, e a outra transformar um texto numa
     * lista de entradas.
     */
    private sealed interface Change permits Deleted, Edited {
        /** O contador de ids no momento da mudanca, para o desfazer nao reusar id vivo. */
        int nextId();
    }

    /** Uma exclusao: a subarvore someu da lista. */
    private record Deleted(List<DiaryEntry> subtree, int nextId) implements Change {
    }

    /** Uma edicao de texto: o no continua, com o conteudo anterior. */
    private record Edited(int id, String title, String description, long modifiedAt, int nextId)
            implements Change {
    }

    /** O conteudo salvo de cada jogador. O indice e derivado da lista. */
    private static final Map<UUID, Diary> DIARIES = new HashMap<>();

    /**
     * O que o Reverter sabe desfazer, do mais antigo ao mais recente. So nesta sessao do
     * servidor: <b>nao vai para o NBT</b>.
     *
     * <p><b>Por que pilha e nao um slot:</b> a jogadora pediu que o Reverter exista
     * enquanto houver rascunho, e que o [Salvar] seja o que esvazia. Um slot unico so
     * desfaz a ultima coisa e nao daria para "voltar as alteracoes que forem feitas".
     */
    private static final Map<UUID, Deque<Change>> UNDO = new HashMap<>();

    /**
     * A ultima versao que o [Salvar] aceitou, por id. E a BASE do "tem alteracao
     * pendente": um no e pendente quando o texto dele e diferente do que esta aqui.
     *
     * <p><b>Por que um espelho e nao um flag por no:</b> a marcacao visual precisa subir ate
     * o caminho do que mudou (ela pediu: mudar 1.1.2 marca 1.1 e a secao 1, mas nao a
     * 1.2). "O no 1.1 mudou" e falso nesse caso. O que e verdade e "ha algo pendente na
     * subarvore do 1.1", e isso se calcula comparando com o espelho, andando para baixo.
     *
     * <p><b>Por que nao vai para o NBT:</b> um rascunho de ontem aparecendo hoje como
     * "pendente" seria mentira, e o Reverter poderia apagar trabalho legitimo.
     */
    private static final Map<UUID, Map<Integer, Committed>> COMMITTED = new HashMap<>();

    /** O conteudo aceito de um no: so o texto, que e o que a jogadora edita. */
    private record Committed(String title, String description) {
    }

    /**
     * Ordem de fixacao, global e crescente.
     *
     * <p><b>Estatico e global de proposito:</b> e o relogio que define "quem foi fixado
     * depois", e ele precisa aumentar mesmo entre jogadores e entre sessoes do servidor.
     * Como a ordenacao so compara {@code pinSeq} DENTRO da lista de um jogador, um contador
     * compartilhado nao faz as listas se misturarem.
     */
    private static long pinCounter = 1;

    private DiaryStore() {
    }

    // ---------------------------------------------------------------- leitura

    /** O diario inteiro do jogador, ou vazio. Copia defensiva da lista. */
    public static Diary diary(UUID playerUuid) {
        Diary stored = playerUuid == null ? null : DIARIES.get(playerUuid);
        return stored == null ? Diary.EMPTY : stored;
    }

    /** Todas as anotacoes, planas. A tela monta os niveis a partir do {@code parentId}. */
    public static List<DiaryEntry> entries(UUID playerUuid) {
        return new ArrayList<>(diary(playerUuid).entries());
    }

    public static Optional<DiaryEntry> find(UUID playerUuid, int id) {
        for (DiaryEntry entry : diary(playerUuid).entries()) {
            if (entry.id() == id) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /**
     * As anotacoes que sao filhas diretas de {@code parentId}.
     *
     * <p>Passe {@link DiaryEntry#ROOT} para pegar as secoes de raiz. A varredura e sobre a
     * lista toda porque nao ha indice por pai; com o teto de {@value #MAX_ENTRIES} a varredura
     * e curta e manter um indice derivado nao se paga.
     */
    public static List<DiaryEntry> children(UUID playerUuid, int parentId) {
        return childrenOf(diary(playerUuid).entries(), parentId);
    }

    /**
     * O mesmo filtro, sobre uma lista que a jogadora ja tem em maos.
     *
     * <p><b>Por que duas entradas e nao uma:</b> a tela precisa montar os cards a partir da
     * <b>copia</b> que o servidor mandou, e nao do store -- o store esta no servidor, e no
     * cliente esta vazio. Fazer a tela filtrar por conta propria criaria uma segunda
     * implementacao da mesma regra, e as duas divergiriam assim que uma delas mudasse.
     */
    public static List<DiaryEntry> childrenOf(List<DiaryEntry> all, int parentId) {
        List<DiaryEntry> out = new ArrayList<>();
        for (DiaryEntry entry : all) {
            if (entry.parentId() == parentId) {
                out.add(entry);
            }
        }
        return out;
    }

    /** As secoes de raiz, que e o que a Tela 1 lista. */
    public static List<DiaryEntry> roots(UUID playerUuid) {
        return children(playerUuid, DiaryEntry.ROOT);
    }

    /**
     * A linha de ancestrais da anotacao, da raiz ate ela, na ordem em que se sobe.
     *
     * <p><b>Por que a guarda e o tamanho da lista:</b> o {@link Diary#CODEC} le NBT editado a
     * mao, e um {@code parentId} que aponta para o proprio cria laco. Subir ate o numero de
     * anotacoes existentes e o corte mais barato: um no real nunca tem mais ancestrais que
     * isso, e um laco para no primeiro passo em que a busca falha.
     */
    public static List<DiaryEntry> ancestry(UUID playerUuid, int id) {
        return ancestryOf(diary(playerUuid).entries(), id);
    }

    /**
     * A mesma subida, sobre uma lista que a jogadora ja tem em maos.
     *
     * <p><b>Por que duas entradas e nao uma:</b> ver {@link #childrenOf}. A tela precisa do
     * caminho para desenhar o breadcrumb, e usa a copia que o servidor mandou.
     */
    public static List<DiaryEntry> ancestryOf(List<DiaryEntry> all, int id) {
        Map<Integer, DiaryEntry> byId = indexById(all);
        Deque<DiaryEntry> path = new ArrayDeque<>();
        int cursor = id;
        int guard = byId.size() + 1;
        while (guard-- > 0) {
            DiaryEntry entry = byId.get(cursor);
            if (entry == null) {
                break;
            }
            path.addFirst(entry);
            if (entry.parentId() == DiaryEntry.ROOT) {
                break;
            }
            cursor = entry.parentId();
        }
        return new ArrayList<>(path);
    }

    /** O caminho como texto, com " > " entre os niveis. Vazio se o id nao existe. */
    public static String breadcrumb(UUID playerUuid, int id) {
        return breadcrumbOf(diary(playerUuid).entries(), id);
    }

    /** O mesmo caminho em texto, sobre uma lista que a jogadora ja tem em maos. */
    public static String breadcrumbOf(List<DiaryEntry> all, int id) {
        StringBuilder sb = new StringBuilder();
        for (DiaryEntry entry : ancestryOf(all, id)) {
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(entry.title());
        }
        return sb.toString();
    }

    /**
     * Filtra a lista pelo campo "Busca:", comparando com o titulo normalizado.
     *
     * <p>Busca vazia devolve a lista inteira, para a tela nao precisar tratar o caso
     * separado.
     */
    public static List<DiaryEntry> filtered(List<DiaryEntry> list, String rawQuery) {
        String query = DiaryEntry.searchKey(rawQuery);
        if (query.isEmpty()) {
            return new ArrayList<>(list);
        }
        List<DiaryEntry> out = new ArrayList<>();
        for (DiaryEntry entry : list) {
            if (entry.searchKey().contains(query)) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * Teto do vanilla por pacote: <b>1.048.576 bytes</b>. E a constante de
     * {@code net.minecraft.network.PacketDecoder}, confirmada no bytecode em 07/10/2026
     * (nao e chute: nao havia como confiar no comentario antigo, que dizia "~100 KB" e
     * era ~40x menor que o pior caso real de 2048 x 2040 caracteres).
     */
    public static final int MAX_PACKET_BYTES = 1_048_576;

    /**
     * Pior caso, por anotacao, de tudo que o {@code STREAM_CODEC} escreve ALEM do texto:
     * id (varint ate 5) + pai (5) + varint do titulo (1) + varint da descricao (2) +
     * modificado (varlong ate 10) + pinSeq (10). Os pendentes adicionam no maximo mais
     * 5 por anotacao no pior caso. 38 cobre os dois e sobra.
     */
    private static final int WORST_OVERHEAD_BYTES_PER_ENTRY = 38;

    /** Versao + count de entradas + canUndo + focus + count de pendentes, pior caso. */
    private static final int PAYLOAD_HEADER_BYTES = 24;

    /**
     * Bytes que o diario inteiro ocuparia no pacote, contando o texto em UTF-8 (uma
     * anotacao so de acentos pesa o dobro dos mesmos caracteres em ASCII) e o pior caso
     * dos campos que nao sao texto.
     *
     * <p><b>Por que conservador para cima:</b> esta conta alimenta o aviso "a
     * sincronizacao vai parar". Errar PARA BAIXO esconderia um diario que de fato nao
     * sincroniza. Errar para cima faz o aviso aparecer um pouco antes do necessario --
     * aceitavel, e so acontece perto do teto.
     */
    public static long estimatedPacketBytes(List<DiaryEntry> list) {
        long texto = 0;
        for (DiaryEntry entry : list) {
            texto += entry.title().getBytes(StandardCharsets.UTF_8).length
                    + entry.description().getBytes(StandardCharsets.UTF_8).length;
        }
        return PAYLOAD_HEADER_BYTES + texto + (long) list.size() * WORST_OVERHEAD_BYTES_PER_ENTRY;
    }

    /** O diario inteiro ainda cabe num pacote do vanilla? */
    public static boolean overPayloadBudget(List<DiaryEntry> list) {
        return estimatedPacketBytes(list) > MAX_PACKET_BYTES;
    }

    /**
     * A lista na ordem do criterio escolhido.
     *
     * <p><b>Por que dois grupos e nao um unico comparador:</b> a jogadora pediu que os
     * fixados fiquem no topo "na ordem de chegada, o ultimo fixado vai para o topo", e isso
     * vale qualquer que seja o criterio escolhido. Encaixar o modo dentro do mesmo
     * comparador faria o criterio reordenar os fixados e perder essa ordem. Desfazer a
     * fixagem devolve a anotacao "a ordenacao que estiver no momento", que e o segundo grupo.
     */
    public static List<DiaryEntry> sorted(List<DiaryEntry> list, SortMode mode) {
        return sorted(list, list, mode);
    }

    /**
     * Ordena um nivel ({@code shown}) considerando o <b>diario inteiro</b> ({@code all}).
     *
     * <p><b>Por que o contexto e obrigatorio no MODIFIED (07/10/2026, bug da
     * jogadora):</b> o ordenador media o {@code modifiedAt} do proprio no. Uma edicao
     * dentro de {@code CARACTERISTICAS} mexia so no timestamp dela, e a mae
     * {@code NPC 1} continuava com o timestamp antigo -- entao nada la dentro fazia a
     * secao subir na lista. Ela descreveu assim: "ao alterar algo na subsecao
     * CARACTERISTICAS, a subsecao NPC 1 devia ficar em primeiro na ordenacao das
     * subsecoes da sessao NPCs".
     *
     * <p>Por isso a ordem e uma funcao da <b>subarvore</b>, nao do no: o que vale e o
     * {@code modifiedAt} mais novo de qualquer lugar dentro daquele no. Sem a lista
     * completa nao ha como saber isso -- os dois chamadores passavam so os irmaos, e
     * os descendentes nem chegam ao metodo.
     *
     * <p>A troca e so de leitura: nada e gravado, e o botao Reverter nao e afetado,
     * porque o timestamp de cada no continua sendo o dele mesmo no armazenamento.
     */
    public static List<DiaryEntry> sorted(List<DiaryEntry> shown, List<DiaryEntry> all,
                                          SortMode mode) {
        // So o MODIFIED paga o custo. ALPHABETICAL nao olha timestamp nenhum, e o
        // metodo roda dentro do render -- construir um mapa por frame sem precisar seria
        // jogar memoria fora na tela de diario, que fica aberta o tempo todo.
        Map<Integer, Long> effective = mode == SortMode.MODIFIED
                ? effectiveModified(all)
                : Map.of();
        List<DiaryEntry> pinned = new ArrayList<>();
        List<DiaryEntry> loose = new ArrayList<>();
        for (DiaryEntry entry : shown) {
            if (entry.pinned()) {
                pinned.add(entry);
            } else {
                loose.add(entry);
            }
        }
        pinned.sort(Comparator.comparingLong(DiaryEntry::pinSeq).reversed());
        if (mode == SortMode.ALPHABETICAL) {
            loose.sort(Comparator.comparing(DiaryEntry::sortKey));
        } else {
            // `getOrDefault` na frente, nao `get`: se o mapa veio vazio (fora do modo
            // MODIFIED), cada no volta para o proprio timestamp e o resultado e o de
            // antes, sem NPE e sem comportamento novo por acidente.
            loose.sort(Comparator.comparingLong(
                    (DiaryEntry e) -> effective.getOrDefault(e.id(), e.modifiedAt())).reversed());
        }
        List<DiaryEntry> out = new ArrayList<>(pinned.size() + loose.size());
        out.addAll(pinned);
        out.addAll(loose);
        return out;
    }

    /**
     * Para cada no, o <b>mais novo</b> {@code modifiedAt} da sua subarvore: dele e de
     * todos os descendentes.
     *
     * <p>Sobe por propagacao em vez de descer por recursao: o {@link DiaryStore} nao
     * tem teto de profundidade (o teste {@code profundidadeIlimitada} sobe 40 niveis),
     * e recursao aqui estouraria a pilha num diario ja editado -- que e o caso normal,
     * nao o caso exotico. A subida para quando o ancestral ja tem um timestamp
     * igual ou maior, porque la em cima ja ha algo mais novo e a contribuicao deste no
     * nao muda nada.
     */
    private static Map<Integer, Long> effectiveModified(List<DiaryEntry> list) {
        Map<Integer, Long> novo = new HashMap<>();
        Map<Integer, Integer> pai = new HashMap<>();
        for (DiaryEntry entry : list) {
            novo.put(entry.id(), entry.modifiedAt());
            pai.put(entry.id(), entry.parentId());
        }
        for (DiaryEntry entry : list) {
            long meu = entry.modifiedAt();
            Integer atual = pai.get(entry.id());
            // `atual == entry.id()` nao acontece (o construtor corta auto-pai, ver
            // `anelNaoTrava`), mas um no em que o pai nao esta na lista encerra o laço
            // por `containsKey`, nao por confiança.
            while (atual != null && !atual.equals(entry.id()) && novo.containsKey(atual)) {
                if (meu <= novo.get(atual)) {
                    break;
                }
                novo.put(atual, meu);
                atual = pai.get(atual);
            }
        }
        return novo;
    }

    // --------------------------------------------------------------- escrita

    /**
     * Cria uma anotacao e devolve o que foi criado.
     *
     * @param parentId {@link DiaryEntry#ROOT} para uma secao, ou o id do pai
     * @throws DiaryException se o titulo vier vazio, se o pai nao existir, ou se o diario
     *                        ja estiver no teto
     */
    public static DiaryEntry create(UUID playerUuid, int parentId, String title, String description)
            throws DiaryException {

        String clean = title == null ? "" : title.trim();
        if (clean.isEmpty()) {
            throw new DiaryException("diary title is required");
        }
        // Recusa com mensagem em vez de cortar: o corte do `DiaryEntry` existe para o NBT
        // antigo, e um titulo cortado aqui viraria uma anotacao com cara de duplicada da
        // que ja existe, sem nenhuma pista do porque. O campo da tela tem o mesmo teto,
        // entao isto e a rede de seguranca, nao o caminho do dia a dia.
        if (clean.length() > DiaryEntry.MAX_TITLE) {
            throw new DiaryException("diary title must be at most " + DiaryEntry.MAX_TITLE
                    + " characters (got " + clean.length() + ")");
        }
        List<DiaryEntry> current = entries(playerUuid);
        if (current.size() >= MAX_ENTRIES) {
            throw new DiaryException("diary is full (max " + MAX_ENTRIES + " entries)");
        }
        if (parentId != DiaryEntry.ROOT && find(playerUuid, parentId).isEmpty()) {
            throw new DiaryException("parent entry does not exist: " + parentId);
        }

        Diary diary = diary(playerUuid);
        int id = diary.nextId() <= 0 ? 1 : diary.nextId();
        DiaryEntry entry = new DiaryEntry(id, parentId, clean, description,
                System.currentTimeMillis(), 0L);
        // ANTES de gravar a subarvore nova: o espelho tem de ser do diario como ele ESTAVA
        // antes deste pedido. Sem esta linha, uma jogadora que abre um diario com 5 secoes
        // e cria a 6a primeiro ficaria com um espelho so da 6a, e nenhuma das outras 5
        // poderia nunca mais ser marcada como pendente -- o `ensureCommitted` nao roda
        // depois, porque o mapa ja existe.
        ensureCommitted(playerUuid);
        List<DiaryEntry> next = new ArrayList<>(current);
        next.add(entry);
        DIARIES.put(playerUuid, new Diary(next, id + 1));
        // Uma secao que a jogadora acabou de criar por pedido dela NAO e rascunho. Sem esta
        // linha o no nasceria sem espelho, "pendente" por definicao, e a secao apareceria
        // com a marca amarela de alteracao nao salva que ela nunca fez.
        markCommitted(playerUuid, entry);
        return entry;
    }

    /**
     * Grava o espelho do jogador na primeira alteracao da sessao.
     *
     * <p><b>Por que preguicoso e nao no {@link #replaceAll}:</b> o espelho tem de ser o
     * estado de <i>antes</i> da primeira mudanca. Criar ele na carga do NBT daria o mesmo
     * resultado hoje, mas quebraria no caso em que o diario ja foi aberto, alterado e o
     * jogador saiu sem salvar: recarregar o NBT sobrescreveria o rascunho como se fosse o
     * aceito.
     */
    private static void ensureCommitted(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        COMMITTED.computeIfAbsent(playerUuid, uuid -> {
            Map<Integer, Committed> snapshot = new HashMap<>();
            for (DiaryEntry entry : entries(uuid)) {
                snapshot.put(entry.id(), new Committed(entry.title(), entry.description()));
            }
            return snapshot;
        });
    }

    /** Coloca (ou atualiza) o texto de um no como aceito. */
    private static void markCommitted(UUID playerUuid, DiaryEntry entry) {
        if (playerUuid == null || entry == null) {
            return;
        }
        COMMITTED.computeIfAbsent(playerUuid, uuid -> new HashMap<>())
                .put(entry.id(), new Committed(entry.title(), entry.description()));
    }

    /** A pilha de desfazer do jogador, criada vazia se ainda nao existir. */
    private static Deque<Change> undoStack(UUID playerUuid) {
        return UNDO.computeIfAbsent(playerUuid, uuid -> new ArrayDeque<>());
    }

    /**
     * O titulo automatico de uma subsecao recem-criada: "Subseção 1", "Subseção 2", ...
     *
     * <p><b>Por que numerar e nao deixar em branco:</b> a jogadora pediu o titulo automatico
     * "para evitar conflitos de validacao (ja que o titulo e obrigatorio)", e porque o
     * botao "Criar Subseção" abre a tela ja em branco para ela digitar. Um numero sempre e
     * um titulo valido.
     */
    public static String defaultSubsectionTitle(UUID playerUuid, int parentId) {
        return SUBSECTION_PREFIX + (children(playerUuid, parentId).size() + 1);
    }

    /**
     * Grava o titulo e a descricao que a jogadora digitou, e marca como modificada agora.
     *
     * @throws DiaryException se o titulo vier vazio ou o id nao existir
     */
    public static DiaryEntry save(UUID playerUuid, int id, String title, String description)
            throws DiaryException {

        String clean = title == null ? "" : title.trim();
        if (clean.isEmpty()) {
            throw new DiaryException("diary title is required");
        }
        if (clean.length() > DiaryEntry.MAX_TITLE) {
            throw new DiaryException("diary title must be at most " + DiaryEntry.MAX_TITLE
                    + " characters (got " + clean.length() + ")");
        }
        DiaryEntry found = find(playerUuid, id)
                .orElseThrow(() -> new DiaryException("entry does not exist: " + id));
        String cleanDesc = description == null ? "" : description;

        // Gravar o mesmo texto de novo e o que o salvamento automatico faz a cada troca de
        // tela, entao precisa ser silencioso: sem esta comparacao, sair da tela empilhava
        // uma alteracao fantasma no Reverter e a marca de "pendente" nao apareceria.
        if (clean.equals(found.title()) && cleanDesc.equals(found.description())) {
            return found;
        }

        ensureCommitted(playerUuid);
        List<DiaryEntry> next = entries(playerUuid);
        for (int i = 0; i < next.size(); i++) {
            DiaryEntry entry = next.get(i);
            if (entry.id() == id) {
                // O `pinSeq` passa intacto de proposito: salvar o texto de uma anotacao
                // fixada nao pode desarrumar a lista.
                next.set(i, new DiaryEntry(entry.id(), entry.parentId(), clean, cleanDesc,
                        System.currentTimeMillis(), entry.pinSeq()));
                undoStack(playerUuid).push(new Edited(entry.id(), entry.title(),
                        entry.description(), entry.modifiedAt(), diary(playerUuid).nextId()));
                DIARIES.put(playerUuid, new Diary(next, diary(playerUuid).nextId()));
                return next.get(i);
            }
        }
        return found;
    }

    /**
     * Apaga a anotacao e tudo que estiver dentro dela.
     *
     * <p>O apagado fica no slot do {@link #undo}, que e sobrescrito a cada nova delecao
     * (a jogadora pediu desfazer so a ultima).
     *
     * @return {@code false} se o id nao existir, para nao limpar um desfazer por engano
     */
    public static boolean delete(UUID playerUuid, int id) {
        List<DiaryEntry> current = entries(playerUuid);
        DiaryEntry target = find(playerUuid, id).orElse(null);
        if (target == null) {
            return false;
        }
        ensureCommitted(playerUuid);
        List<DiaryEntry> subtree = subtreeOf(current, id);
        Map<Integer, DiaryEntry> gone = indexById(subtree);
        List<DiaryEntry> next = new ArrayList<>();
        for (DiaryEntry entry : current) {
            if (!gone.containsKey(entry.id())) {
                next.add(entry);
            }
        }
        // O espelho NAO e apagado junto. O desfazer traz a subarvore de volta com o texto
        // que ela tinha, e e contra esse texto aceito que ela passa a ser comparada: volta
        // limpa se nao tinha rascunho, e continua marcada se tinha.
        undoStack(playerUuid).push(new Deleted(subtree, diary(playerUuid).nextId()));
        DIARIES.put(playerUuid, new Diary(next, diary(playerUuid).nextId()));
        return true;
    }

    /** A anotacao e todas as descendentes, em qualquer ordem. */
    private static List<DiaryEntry> subtreeOf(List<DiaryEntry> all, int rootId) {
        Map<Integer, List<DiaryEntry>> byParent = new HashMap<>();
        Map<Integer, DiaryEntry> byId = new HashMap<>();
        for (DiaryEntry entry : all) {
            byParent.computeIfAbsent(entry.parentId(), k -> new ArrayList<>()).add(entry);
            byId.putIfAbsent(entry.id(), entry);
        }
        List<DiaryEntry> out = new ArrayList<>();
        Deque<Integer> pending = new ArrayDeque<>();
        // A raiz da cascata entra na lista. Sem esta linha, apagar uma secao tirava os
        // filhos e mantinha a secao, e apagar um no sem filho nao tirava nada -- o
        // "Del?" pareceria nao fazer nada. (Achado pelo teste, 02/10/2026.)
        DiaryEntry root = byId.get(rootId);
        if (root != null) {
            out.add(root);
        }
        pending.add(rootId);
        int guard = all.size() + 1;
        while (!pending.isEmpty() && guard-- > 0) {
            int current = pending.poll();
            for (DiaryEntry entry : byParent.getOrDefault(current, List.of())) {
                out.add(entry);
                pending.add(entry.id());
            }
        }
        return out;
    }

    /** Indexa um trecho por id, para tirar o que sumiu do diario em uma varredura so. */
    private static Map<Integer, DiaryEntry> indexById(List<DiaryEntry> list) {
        Map<Integer, DiaryEntry> byId = new HashMap<>();
        for (DiaryEntry entry : list) {
            byId.put(entry.id(), entry);
        }
        return byId;
    }

    /** O botao Reverter so aparece quando isto e verdadeiro. */
    public static boolean canUndo(UUID playerUuid) {
        Deque<Change> stack = playerUuid == null ? null : UNDO.get(playerUuid);
        return stack != null && !stack.isEmpty();
    }

    /**
     * Aceita os rascunhos: o que esta na lista passa a ser o texto aceito, e a pilha de
     * desfazer esvazia. E o que o botao [Salvar] faz, e e a unica coisa que faz o
     * [Reverter] sumir.
     *
     * <p>Nao ha como o [Salvar] perder alteracao: ela ja esta no diario desde o
     * salvamento automatico. O que o botao faz e marcar o texto atual como aceito, para o
     * Reverter deixar de oferecer devolver algo que ela ja viu.
     */
    public static void accept(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        UNDO.remove(playerUuid);
        Map<Integer, Committed> snapshot = new HashMap<>();
        for (DiaryEntry entry : entries(playerUuid)) {
            snapshot.put(entry.id(), new Committed(entry.title(), entry.description()));
        }
        COMMITTED.put(playerUuid, snapshot);
    }

    /**
     * Os ids cujo texto esta diferente do aceito: as raizes do que a jogadora tem para
     * salvar. E o que a tela usa para acender a marca amarela e o caminho ate ela.
     *
     * <p>So os ids que EXISTEM sao devolvidos. Um no apagado e um no que ainda nao ha
     * espelho nao tem o que comparar, e nao interessa a marcacao de nada.
     */
    public static List<Integer> pendingIds(UUID playerUuid) {
        List<Integer> out = new ArrayList<>();
        Map<Integer, Committed> snapshot = playerUuid == null ? null : COMMITTED.get(playerUuid);
        if (snapshot == null) {
            return out;
        }
        for (DiaryEntry entry : entries(playerUuid)) {
            Committed accepted = snapshot.get(entry.id());
            if (accepted != null && (!accepted.title().equals(entry.title())
                    || !accepted.description().equals(entry.description()))) {
                out.add(entry.id());
            }
        }
        return out;
    }

    /**
     * O no tem algo pendente em qualquer lugar da subarvore dele?
     *
     * <p><b>Por que isso e o que a marcacao precisa:</b> a jogadora pediu que mexer em
     * "subsecao 1.2" acenda tambem o cartao da "subsecao 1" e o da "secao 1", e <b>nao</b> o
     * da "subsecao 2", que e irma e nao ancestral. Comparar o texto do no com o aceito
     * responderia "o 1.1 mudou", que e falso; a pergunta certa e se ha algo pendente em
     * qualquer descendente, e a resposta sobe ate a raiz sem vazar para os irmaos.
     *
     * @param pendingIds os ids de {@link #pendingIds}
     */
    public static boolean hasPendingInSubtree(List<DiaryEntry> all, int id,
                                              Set<Integer> pendingIds) {
        if (pendingIds == null || pendingIds.isEmpty()) {
            return false;
        }
        Map<Integer, List<DiaryEntry>> byParent = new HashMap<>();
        for (DiaryEntry entry : all) {
            byParent.computeIfAbsent(entry.parentId(), k -> new ArrayList<>()).add(entry);
        }
        Deque<Integer> pending = new ArrayDeque<>();
        pending.add(id);
        int guard = all.size() + 1;
        while (guard-- > 0) {
            Integer cursor = pending.poll();
            if (cursor == null) {
                return false;
            }
            if (pendingIds.contains(cursor)) {
                return true;
            }
            for (DiaryEntry child : byParent.getOrDefault(cursor, List.of())) {
                pending.add(child.id());
            }
        }
        return false;
    }

    /**
     * Desfaz a alteracao mais recente: uma edicao de texto ou uma exclusao.
     *
     * @return {@code false} se nao havia nada para desfazer
     */
    public static boolean undo(UUID playerUuid) {
        Deque<Change> stack = playerUuid == null ? null : UNDO.get(playerUuid);
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Change change = stack.pop();
        List<DiaryEntry> next = entries(playerUuid);
        if (change instanceof Deleted deleted) {
            next.addAll(deleted.subtree());
        } else if (change instanceof Edited edited) {
            for (int i = 0; i < next.size(); i++) {
                DiaryEntry entry = next.get(i);
                if (entry.id() == edited.id()) {
                    // O `modifiedAt` volta tambem: senao desfazer uma edicao deixaria o no
                    // no topo do "Ordenar: Modificado" mesmo com o texto de volta.
                    next.set(i, new DiaryEntry(entry.id(), entry.parentId(), edited.title(),
                            edited.description(), edited.modifiedAt(), entry.pinSeq()));
                    break;
                }
            }
        } else {
            return false;
        }
        DIARIES.put(playerUuid, new Diary(next, change.nextId()));
        return true;
    }

    /** Fixa ou desfixa. Fixar joga para o topo da lista; desfixar devolve a ordenacao atual. */
    public static void setPinned(UUID playerUuid, int id, boolean pinned) {
        List<DiaryEntry> next = entries(playerUuid);
        for (int i = 0; i < next.size(); i++) {
            DiaryEntry entry = next.get(i);
            if (entry.id() == id) {
                long pinSeq = pinned ? ++pinCounter : 0L;
                next.set(i, new DiaryEntry(entry.id(), entry.parentId(), entry.title(),
                        entry.description(), entry.modifiedAt(), pinSeq));
                DIARIES.put(playerUuid, new Diary(next, diary(playerUuid).nextId()));
                return;
            }
        }
    }

    /** Substitui tudo. E o caminho da carga do NBT, pelo mixin. */
    public static void replaceAll(UUID playerUuid, Diary diary) {
        if (playerUuid == null || diary == null) {
            return;
        }
        DIARIES.put(playerUuid, diary);
        // O espelho NAO e recriado aqui. Se o diario ja foi aberto nesta sessao, o rascunho
        // da jogadora esta em `COMMITTED` e recriar o espelho aqui apagaria a marcacao de
        // "pendente" e esvaziaria na mao o que o [Salvar] deveria esvaziar. Ver
        // `ensureCommitted`, que e quem decide a linha de base.
    }

    /**
     * Esquece o jogador. <b>Nao chame no disconnect:</b> o cache e lido no save, e limpar
     * antes faz o diario sair vazado. Existe para o fim do servidor e para os testes.
     */
    public static void forget(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        DIARIES.remove(playerUuid);
        UNDO.remove(playerUuid);
        COMMITTED.remove(playerUuid);
    }

    /** Erro de criacao ou de edicao com frase ja pronta para o log (sem prefixo de cor). */
    public static class DiaryException extends Exception {
        public DiaryException(String message) {
            super(message);
        }
    }
}