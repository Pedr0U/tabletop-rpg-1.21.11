package com.pedro.tabletoprpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Uma anotacao do Diario: uma secao, ou uma subsecao dentro dela.
 *
 * <p><b>02/10/2026.</b> O diario e pessoal (fica no NBT do jogador, como os presets) e a
 * jogadora pediu subsecao de profundidade infinita. A arvore aqui e <b>CHATA</b>: cada
 * anotacao guarda o {@code id} do pai, e {@link #ROOT} significa "e uma secao de raiz".
 * Isso da profundidade infinita de graca e evita codec recursivo, que no DFU exigiria
 * inicializacao preguicosa sem ganho nenhum: a serializacao e um {@code Codec.list} e o
 * pacote do servidor e uma lista so.
 *
 * <p><b>Por que {@code pinSeq} substitui um booleano {@code pinned}:</b> a jogadora
 * pediu que o ultimo fixado va para o topo e os anteriores fiquem abaixo dele, na ordem
 * em que chegaram. Um booleano nao guarda essa ordem; um contador guarda, e "fixado" e
 * so a consequencia de {@code pinSeq > 0}. Um campo so, e nao existe estado impossivel
 * ({@code pinned = true} com sequencia zero).
 *
 * <p><b>Por que {@code parentId == id} vira raiz:</b> o {@link Codec} roda sobre NBT de
 * mundo antigo ou editado a mao, e um no que e pai de si mesmo transformaria a subida do
 * breadcrumb em laco infinito. Cortar no construtor e o lugar mais barato de impedir.
 *
 * @param id          identificador unico do jogador, para create/edit/delete/pin
 * @param parentId    id do pai, ou {@link #ROOT} se esta e uma secao de raiz
 * @param title       titulo, obrigatorio, ate {@value #MAX_TITLE} caracteres
 * @param description anotacao em varias linhas, ate {@value #MAX_DESCRIPTION} caracteres
 * @param modifiedAt  epoch em milissegundos da ultima alteracao, base do "Ordenar: Modificado"
 * @param pinSeq      0 se nao esta fixada; senao a ordem de fixacao, maior = mais embaixo
 */
public record DiaryEntry(int id, int parentId, String title, String description,
                         long modifiedAt, long pinSeq) {

    /**
     * Teto do titulo, em caracteres. 40 a pedido da jogadora (era 30).
     *
     * <p>E o mesmo numero no {@link #CODEC} do NBT e no {@link #STREAM_CODEC} da rede, e
     * isso e intencional: o construtor compacto corta em {@value #MAX_TITLE} e o
     * {@link DiaryStore#create}/{@link DiaryStore#save} recusam acima disso, entao um
     * titulo maior nunca chega a existir para ser decodificado. Um teto de rede maior que
     * o de dominio so serviria para o pacote aceitar uma string que o dominio proibe.
     */
    public static final int MAX_TITLE = 40;
    public static final int MAX_DESCRIPTION = 2_000;

    /** {@code parentId} de uma secao de raiz: nao esta dentro de nenhuma outra. */
    public static final int ROOT = -1;

    /**
     * O diario na rede, usado pela tela.
     *
     * <p>Existe pelo mesmo motivo do {@link RollPreset#STREAM_CODEC}: o {@link #CODEC} e
     * do NBT e o NBT nao viaja em pacote. Seis campos cabem no {@code composite}; um
     * sete exigiria escrever o encoder e o decoder a mao sem ganho.
     */
    public static final StreamCodec<FriendlyByteBuf, DiaryEntry> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, DiaryEntry::id,
                    ByteBufCodecs.VAR_INT, DiaryEntry::parentId,
                    ByteBufCodecs.stringUtf8(MAX_TITLE), DiaryEntry::title,
                    ByteBufCodecs.stringUtf8(MAX_DESCRIPTION), DiaryEntry::description,
                    ByteBufCodecs.VAR_LONG, DiaryEntry::modifiedAt,
                    ByteBufCodecs.VAR_LONG, DiaryEntry::pinSeq,
                    DiaryEntry::new
            );

    // Sem limite por campo: nesta versao do DFU o `Codec.STRING` nao tem `maxLen`. O teto
    // nao e o que segura uma anotacao enorme -- e o clamp do construtor compacto, que
    // corta depois de decodificar.
    public static final Codec<DiaryEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(DiaryEntry::id),
            Codec.INT.fieldOf("parent").forGetter(DiaryEntry::parentId),
            Codec.STRING.fieldOf("title").forGetter(DiaryEntry::title),
            Codec.STRING.fieldOf("desc").forGetter(DiaryEntry::description),
            Codec.LONG.fieldOf("modified").forGetter(DiaryEntry::modifiedAt),
            Codec.LONG.optionalFieldOf("pin", 0L).forGetter(DiaryEntry::pinSeq)
    ).apply(i, DiaryEntry::new));

    /**
     * Nunca lanca: e o caminho do {@link Codec}, que roda sobre NBT de mundo antigo ou
     * editado a mao. Corta o que passou do limite e corta o auto-pai. Quem valida de
     * verdade e o {@link DiaryStore#create}, que devolve erro de verdade para a jogadora.
     */
    public DiaryEntry {
        title = clamp(title, MAX_TITLE);
        description = clamp(description, MAX_DESCRIPTION);
        if (parentId == id) {
            parentId = ROOT;
        }
    }

    /** O pino do card so aparece quando isto e verdadeiro. */
    public boolean pinned() {
        return pinSeq > 0;
    }

    /**
     * Chave de busca: minuscula e sem acento.
     *
     * <p><b>Por que normalizar:</b> o campo "Busca:" compara com digitacao humana, e sem
     * normalizar {@code Fisica} e {@code física} seriam anotacoes diferentes para a busca.
     * Aqui o espaco vira espaco (diferente do {@link RollPreset#normalizeKey}, que troca
     * por {@code _} porque o nome vai para o Brigadier): na busca o espaco e um caractere
     * normal da frase.
     */
    public String searchKey() {
        return searchKey(title);
    }

    /**
     * <b>Estatico de proposito:</b> o filtro da tela precisa desta regra com o que a
     * jogadora digitou, que ainda nao virou anotacao.
     */
    public static String searchKey(String raw) {
        if (raw == null) {
            return "";
        }
        String stripped = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.ROOT).trim();
    }

    /** Chave de ordenar do titulo, na mesma forma normalizada da busca. */
    public String sortKey() {
        return searchKey(title);
    }

    /**
     * O titulo para o card, cortado com reticencias quando nao cabe na largura visivel.
     *
     * <p><b>Por que o corte mora no dado e nao na tela:</b> a largura do card muda com a
     * resolucao e com o numero de subsecoes, entao duas telas com a mesma anotacao
     * mostrariam prefixos de tamanhos diferentes. O que a tela decide e so quantos
     * caracteres cabem.
     */
    public static String fit(String raw, int maxChars) {
        String value = raw == null ? "" : raw;
        if (maxChars <= 0) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        // Tres pontos + reticencias: e o que a jogadora pediu ("..."), e some um caractere
        // do texto visivel, que e o troca-comer.
        if (maxChars <= 3) {
            return value.substring(0, maxChars);
        }
        return value.substring(0, maxChars - 3) + "...";
    }

    private static String clamp(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}