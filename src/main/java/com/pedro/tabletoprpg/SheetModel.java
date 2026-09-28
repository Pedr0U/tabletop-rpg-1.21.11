package com.pedro.tabletoprpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * O <b>modelo da ficha</b>: o formato que o Mestre decide, compartilhado por
 * todos os jogadores. E o que o item Sheet Editor edita.
 *
 * <p><b>Por que isto e separado de {@link SheetData}:</b> {@code SheetData} e o
 * que o <i>personagem</i> tem (o quanto de vida, o valor de cada pericia), e
 * muda o tempo todo. {@code SheetModel} e o <i>formato</i> da ficha (os rotulos,
 * quantos atributos existem, quais pericias existem) e muda so quando o Mestre
 * edita. Misturar os dois significaria reescrever o NBT de todos os jogadores a
 * cada renomeacao de um rotulo, e o "mudar o modelo para todos" do pedido
 * viraria uma operacao por jogador.
 *
 * <p><b>Defaults = o que existe hoje (27/09/2026).</b> Um mundo sem este
 * arquivo salvo, ou um save antigo, cai em {@link #defaults()} e a ficha
 * aparece exatamente como antes desta feature: 6 atributos e 18 pericias de
 * D&amp;D 5e, rotulos em ingles.
 *
 * <p><b>O {@code id} do atributo nao e editavel, o rotulo e.</b> O id e a chave
 * estavel com que a ficha do jogador guarda o valor ("strength") e com que uma
 * pericia diz qual atributo ela soma. Se o Mestre renomear "Forca" para "Vigor",
 * as fichas dos jogadores continuam com o valor em "strength" e nada se perde.
 * So quem <i>cria</i> atributo novo recebe um id gerado.
 *
 * <p><b>O mesmo vale para a pericia (28/09/2026).</b> O {@code id} dela
 * ({@code pericia_N}) e a identidade, e o nome e o rotulo: renomear a pericia
 * preserva o valor e o atributo que o jogador ja tinha nela.
 *
 * <p>Todos os limites aqui existem por causa do codec de rede: um cliente
 * modificado pode mandar qualquer coisa, e um record sem teto viraria um numero
 * gigante na tela ou um {@code IndexOutOfBounds} no desenho.
 */
public record SheetModel(
        String nameLabel,
        String raceLabel,
        boolean raceEnabled,
        String classLabel,
        String backgroundLabel,
        String hpLabel,
        String manaLabel,
        boolean manaEnabled,
        String levelLabel,
        String xpLabel,
        XpMode xp,
        List<AttributeDef> attributes,
        List<PericiaDef> pericias
    ) {

    /**
     * Os tres estados que o pedido do Mestre permite para XP: numero (com as
     * setas de -/+ como hoje), texto livre (a caixa aceita o que o jogador
     * quiser) e escondido (a linha some da ficha).
     *
     * <p><b>Por que um enum e nao dois booleanos:</b> "texto" e "escondido" sao
     * mutually exclusive, e dois booleanos deixam existir "texto e escondido ao
     * mesmo tempo", que e um estado que nenhuma tela sabe desenhar. Aqui o
     * estado invalido nao existe.
     */
    public enum XpMode {
        /** Valor numerico, editado pelas setas de -/+. */
        NUMBER,
        /** Texto livre, editado numa caixa de texto. */
        TEXT,
        /** A linha de XP nao e desenhada. */
        HIDDEN;

        /** Leitura leniente: qualquer valor desconhecido vira {@link #NUMBER}. */
        public static XpMode decode(String raw) {
            if (raw == null) {
                return NUMBER;
            }
            for (XpMode mode : values()) {
                if (mode.name().equalsIgnoreCase(raw.trim())) {
                    return mode;
                }
            }
            return NUMBER;
        }

        /** Le a partir de um indice de rede; fora de faixa cai em {@link #NUMBER}. */
        public static XpMode byOrdinal(int ordinal) {
            XpMode[] all = values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : NUMBER;
        }
    }

    // ------------------------------------------------------------------
    // LIMITES
    // ------------------------------------------------------------------

    /** Teto de caracteres de um rotulo, de um nome de atributo e de uma pericia. */
    public static final int LABEL_MAX = 32;
    /** Menos atributos que o Mestre pode deixar. */
    public static final int MIN_ATTRIBUTES = 1;
    /** Mais atributos que o Mestre pode ter (pedido do usuario). */
    public static final int MAX_ATTRIBUTES = 10;
    /** Menos pericias que o Mestre pode deixar. */
    public static final int MIN_PERICIAS = 1;
    /** Mais pericias que o Mestre pode ter (pedido do usuario). */
    public static final int MAX_PERICIAS = 30;

    // ------------------------------------------------------------------
    // SUB-REGISTROS
    // ------------------------------------------------------------------

    /**
     * Um atributo do modelo: a chave estavel, o rotulo curto (o que aparece no
     * botao da pericia) e o nome por extenso (o que aparece na linha do
     * atributo quando a coluna e larga).
     */
    public record AttributeDef(String id, String label, String name) {
        public static final StreamCodec<FriendlyByteBuf, AttributeDef> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(32), AttributeDef::id,
                        ByteBufCodecs.stringUtf8(LABEL_MAX), AttributeDef::label,
                        ByteBufCodecs.stringUtf8(LABEL_MAX), AttributeDef::name,
                        AttributeDef::new
                );

        public static final Codec<AttributeDef> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("id", "").forGetter(AttributeDef::id),
                Codec.STRING.optionalFieldOf("label", "").forGetter(AttributeDef::label),
                Codec.STRING.optionalFieldOf("name", "").forGetter(AttributeDef::name)
        ).apply(i, AttributeDef::new));

        public AttributeDef {
            id = clean(id, 32);
            label = clean(label, LABEL_MAX);
            name = clean(name, LABEL_MAX);
        }
    }

    /**
     * Uma pericia do modelo: a chave estavel, o nome (que e o rotulo, editavel)
     * e o atributo que ela soma por padrao.
     *
     * <p><b>28/09/2026 - a pericia ganhou id, como o atributo ja tinha.</b>
     * Antes a pericia era identificada pelo <b>nome</b> em toda parte, entao
     * renomear uma pericia para um nome novo fazia o servidor nao acha-la: o
     * valor zerava e o atributo escolhido se perdia. O {@code id} e a identidade
     * (a chave com que a ficha do jogador guarda valor e atributo, e com que o
     * {@link SheetModel#align} casa), e o nome e so o que aparece na linha.
     *
     * <p>O id e gerado uma vez, na criacao, e <b>congelado</b>: nem
     * {@link #withPericiaText} nem {@link #removeAttribute} o trocam. O formato
     * e {@code pericia_N} (N &gt;= 1), espelhando o {@code attr_N} dos atributos.
     */
    public record PericiaDef(String id, String name, String attributeId) {
        public static final StreamCodec<FriendlyByteBuf, PericiaDef> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(32), PericiaDef::id,
                        ByteBufCodecs.stringUtf8(LABEL_MAX), PericiaDef::name,
                        ByteBufCodecs.stringUtf8(32), PericiaDef::attributeId,
                        PericiaDef::new
                );

        public static final Codec<PericiaDef> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("id", "").forGetter(PericiaDef::id),
                Codec.STRING.optionalFieldOf("name", "").forGetter(PericiaDef::name),
                Codec.STRING.optionalFieldOf("attribute", "").forGetter(PericiaDef::attributeId)
        ).apply(i, PericiaDef::new));

        public PericiaDef {
            id = clean(id, 32);
            name = clean(name, LABEL_MAX);
            attributeId = clean(attributeId, 32);
        }
    }

    // ------------------------------------------------------------------
    // PADRAO (o que existe hoje, antes do Sheet Editor)
    // ------------------------------------------------------------------

    /**
     * O modelo padrao: identicos rotulos, 6 atributos e as 18 pericias basicas
     * de D&amp;D 5e, cada uma com o atributo que o D&amp;D define.
     *
     * <p>Os ids dos 6 atributos sao os mesmos que a ficha usava quando os
     * atributos eram campos fixos do record ({@code SheetData.Attributes}), e por
     * isso as fichas ja saldas continuam legiveis sem migracaoloss.
     *
     * <p>Os ids das 18 pericias sao {@code pericia_1}..{@code pericia_18}, na
     * ordem da lista. Eles estao escritos a mao (e nao gerados) porque a ordem
     * desta lista e a ordem em que uma ficha salva <b>antes</b> dos ids recebe
     * os dela, por {@link #sanitizePericias}: e a lista fixa, nao uma lista que
     * o Mestre editou, entao o id pode ser constante em vez de gerado.
     */
    public static SheetModel defaults() {
        return new SheetModel(
                "Name",
                "Race",
                true,
                "Class",
                "Background",
                "HP",
                "Mana",
                true,
                "Level",
                "XP",
                XpMode.NUMBER,
                List.of(
                        new AttributeDef("strength", "STR", "Strength"),
                        new AttributeDef("dexterity", "DEX", "Dexterity"),
                        new AttributeDef("constitution", "CON", "Constitution"),
                        new AttributeDef("intelligence", "INT", "Intelligence"),
                        new AttributeDef("wisdom", "WIS", "Wisdom"),
                        new AttributeDef("charisma", "CHA", "Charisma")
                ),
                List.of(
                        new PericiaDef("pericia_1", "Acrobatics", "dexterity"),
                        new PericiaDef("pericia_2", "Animal Handling", "wisdom"),
                        new PericiaDef("pericia_3", "Arcana", "intelligence"),
                        new PericiaDef("pericia_4", "Athletics", "strength"),
                        new PericiaDef("pericia_5", "Deception", "charisma"),
                        new PericiaDef("pericia_6", "History", "intelligence"),
                        new PericiaDef("pericia_7", "Insight", "wisdom"),
                        new PericiaDef("pericia_8", "Intimidation", "charisma"),
                        new PericiaDef("pericia_9", "Investigation", "intelligence"),
                        new PericiaDef("pericia_10", "Medicine", "wisdom"),
                        new PericiaDef("pericia_11", "Nature", "intelligence"),
                        new PericiaDef("pericia_12", "Perception", "wisdom"),
                        new PericiaDef("pericia_13", "Performance", "charisma"),
                        new PericiaDef("pericia_14", "Persuasion", "charisma"),
                        new PericiaDef("pericia_15", "Religion", "intelligence"),
                        new PericiaDef("pericia_16", "Stealth", "dexterity"),
                        new PericiaDef("pericia_17", "Survival", "wisdom"),
                        new PericiaDef("pericia_18", "Thievery", "dexterity")
                )
        );
    }

    /** Nomes de campo de texto, na ordem em que a ficha os desenha. */
    public static final List<String> TEXT_FIELDS =
            List.of("characterName", "race", "characterClass", "background");
    /** Campos numericos com rotulo proprio (HP, Mana, Level, XP). */
    public static final List<String> LABELLED_NUMERIC_FIELDS = List.of("hp", "mana", "level", "xp");
    /** Campos que o Mestre pode desativar. */
    public static final List<String> TOGGLEABLE_FIELDS = List.of("race", "mana", "xp");

    // ------------------------------------------------------------------
    // CONSTRUTOR
    // ------------------------------------------------------------------

    /**
     * Normaliza tudo que vem de fora (NBT, rede, payload de edicao).
     *
     * <p><b>Lista vazia cai no padrao, e nao numa lista vazia.</b> Isso e o que
     * mantem o minimo de 1 atributo e 1 pericia sem bloquear o removimento na
     * tela: a tela so chega aqui com a lista ja sem o ultimo elemento quando o
     * Mestre really removeu, e nesse caso o {@link SheetModelStore} reverte a
     * operacao antes de chegar aqui. Um vazio que chega e, portanto, dado
     * corrompido — e o padrao e a resposta segura.
     */
    public SheetModel {
        nameLabel = clean(nameLabel, LABEL_MAX, "Name");
        raceLabel = clean(raceLabel, LABEL_MAX, "Race");
        classLabel = clean(classLabel, LABEL_MAX, "Class");
        backgroundLabel = clean(backgroundLabel, LABEL_MAX, "Background");
        hpLabel = clean(hpLabel, LABEL_MAX, "HP");
        manaLabel = clean(manaLabel, LABEL_MAX, "Mana");
        levelLabel = clean(levelLabel, LABEL_MAX, "Level");
        xpLabel = clean(xpLabel, LABEL_MAX, "XP");
        attributes = sanitizeAttributes(attributes);
        pericias = sanitizePericias(pericias);
    }

    private static List<AttributeDef> sanitizeAttributes(List<AttributeDef> raw) {
        List<AttributeDef> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (raw != null) {
            for (AttributeDef def : raw) {
                if (def == null || out.size() >= MAX_ATTRIBUTES) {
                    continue;
                }
                String id = def.id();
                if (id.isEmpty() || !seen.add(id.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                AttributeDef fixed = new AttributeDef(
                        id,
                        def.label().isEmpty() ? def.id().toUpperCase(Locale.ROOT) : def.label(),
                        def.name().isEmpty() ? def.label() : def.name());
                if (fixed.label().isEmpty()) {
                    fixed = new AttributeDef(id, id.toUpperCase(Locale.ROOT), id);
                }
                out.add(fixed);
            }
        }
        if (out.isEmpty()) {
            return defaults().attributes();
        }
        return List.copyOf(out);
    }

    /**
     * Higiene da lista de pericias: o id primeiro, a deduplicacao depois.
     *
     * <p><b>28/09/2026 - a ordem das duas etapas e o que impede a perda de
     * ficha inteira.</b> A deduplicacao e por {@code id}, e uma ficha (ou um
     * modelo) gravada antes dos ids chega aqui com 18 pericias, todas sem id.
     * Se a deduplicacao rodasse antes do preenchimento, as 18 teriam o mesmo id
     * {@code ""}, uma venceria e a lista <b>colapsaria para 1</b>: silencioso,
     * sem erro e sem log. Por isso {@link #freshPericiaId} roda ANTES do
     * {@code seen.add}, e um id vazio nunca entra no {@code seen}.
     *
     * <p>Se duas entradas disputarem o mesmo id, quem ja estava na lista vence e
     * a outra e descartada - que e o mesmo tratamento que o duplicado de um
     * atributo recebe.
     */
    private static List<PericiaDef> sanitizePericias(List<PericiaDef> raw) {
        List<PericiaDef> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (raw != null) {
            for (PericiaDef def : raw) {
                if (def == null || out.size() >= MAX_PERICIAS) {
                    continue;
                }
                if (def.name().isEmpty()) {
                    continue;
                }
                // Preenche ANTES da deduplicacao (ver o Javadoc acima): o id
                // gerado ja entra no seen, e `out` so cresce com pericias que
                // tem id, entao o proximo id gerado nao colide com ele.
                String id = def.id().isEmpty() ? freshPericiaId(out) : def.id();
                if (!seen.add(id.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                out.add(new PericiaDef(id, def.name(), def.attributeId()));
            }
        }
        if (out.isEmpty()) {
            return defaults().pericias();
        }
        return List.copyOf(out);
    }

    /**
     * O primeiro {@code pericia_N} (N &gt;= 1) cujo id nao esta em uso na lista
     * dada.
     *
     * <p><b>Por que um contador e nao um valor aleatorio:</b> duas razoes, e o
     * {@code omitempty} do {@code Codec} do DataFixerUpper <b>nao e</b> uma
     * delas (ele omitiria o campo, mas o {@code .dat} fecharia do mesmo jeito).
     * <b>1. Reprodutibilidade do NBT:</b> o id gerado vai para o arquivo do
     * modelo, e o mesmo modelo tem de gerar sempre o mesmo id - um UUID ou
     * {@code nanoTime} tornaria o arquivo diferente a cada criacao de pericia,
     * sem ganho nenhum. <b>2. Ausencia de reuso:</b> um id reusado faria a
     * pericia nova herdar, no {@link #align}, o valor da pericia antiga em todas
     * as fichas.
     *
     * <p>A lista ja vem saneada (todo id nela e nao vazio e unico), entao o
     * primeiro N livre nunca colide com o que ja existe.
     */
    private static String freshPericiaId(List<PericiaDef> current) {
        Set<String> used = new LinkedHashSet<>();
        for (PericiaDef def : current) {
            if (def.id().isEmpty()) {
                continue;
            }
            used.add(def.id().toLowerCase(Locale.ROOT));
        }
        int n = 1;
        while (used.contains(("pericia_" + n).toLowerCase(Locale.ROOT))) {
            n++;
        }
        return "pericia_" + n;
    }

    // ------------------------------------------------------------------
    // CONSULTA
    // ------------------------------------------------------------------

    /** Rotulo de um campo, nunca vazio. */
    public String labelOf(String field) {
        if (field == null) {
            return "";
        }
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "charactername" -> nameLabel;
            case "race" -> raceLabel;
            case "characterclass" -> classLabel;
            case "background" -> backgroundLabel;
            case "hp" -> hpLabel;
            case "mana" -> manaLabel;
            case "level" -> levelLabel;
            case "xp" -> xpLabel;
            // XP em modo TEXT e o MESMO campo de XP (numero ou texto sao modos
            // de exibicao, nao campos diferentes), entao usa o mesmo rotulo.
            // Sem este caso o `default` devolvia a chave crua e a ficha
            // desenhava "xptext" no lugar do rotulo.
            case "xptext" -> xpLabel;
            default -> field;
        };
    }

    /** Um campo desativado nao e desenhado nem editado. */
    public boolean isEnabled(String field) {
        if (field == null) {
            return true;
        }
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "race" -> raceEnabled;
            case "mana" -> manaEnabled;
            case "xp" -> xp != XpMode.HIDDEN;
            default -> true;
        };
    }

    /** O atributo com este id, ou {@code null}. */
    public AttributeDef attribute(String id) {
        if (id == null) {
            return null;
        }
        for (AttributeDef def : attributes) {
            if (def.id().equals(id)) {
                return def;
            }
        }
        return null;
    }

    /** Rotulo curto do atributo; cai no primeiro quando o id nao existe mais. */
    public String attributeLabel(String id) {
        AttributeDef def = attribute(id);
        return def == null ? attributes.get(0).label() : def.label();
    }

    /** O id do primeiro atributo — para quando uma pericia perde o seu. */
    public String firstAttributeId() {
        return attributes.get(0).id();
    }

    public int attributeCount() {
        return attributes.size();
    }

    public int periciaCount() {
        return pericias.size();
    }

    // ------------------------------------------------------------------
    // EDICAO (usada pelo servidor ao aplicar um payload do Mestre)
    // ------------------------------------------------------------------

    /** Copia com outro rotulo. Rotulo vazio esconde o campo. */
    public SheetModel withLabel(String field, String label) {
        if (field == null) {
            return this;
        }
        String value = clean(label, LABEL_MAX);
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "charactername" -> new SheetModel(value.isEmpty() ? nameLabel : value, raceLabel,
                    raceEnabled, classLabel, backgroundLabel, hpLabel, manaLabel, manaEnabled,
                    levelLabel, xpLabel, xp, attributes, pericias);
            case "race" -> new SheetModel(nameLabel, value, raceEnabled, classLabel,
                    backgroundLabel, hpLabel, manaLabel, manaEnabled, levelLabel, xpLabel, xp,
                    attributes, pericias);
            case "characterclass" -> new SheetModel(nameLabel, raceLabel, raceEnabled, value,
                    backgroundLabel, hpLabel, manaLabel, manaEnabled, levelLabel, xpLabel, xp,
                    attributes, pericias);
            case "background" -> new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel, value,
                    hpLabel, manaLabel, manaEnabled, levelLabel, xpLabel, xp, attributes, pericias);
            case "hp" -> new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel,
                    backgroundLabel, value, manaLabel, manaEnabled, levelLabel, xpLabel, xp,
                    attributes, pericias);
            case "mana" -> new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel,
                    backgroundLabel, hpLabel, value, manaEnabled, levelLabel, xpLabel, xp,
                    attributes, pericias);
            case "level" -> new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel,
                    backgroundLabel, hpLabel, manaLabel, manaEnabled, value, xpLabel, xp,
                    attributes, pericias);
            case "xp" -> new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel,
                    backgroundLabel, hpLabel, manaLabel, manaEnabled, levelLabel,
                    value.isEmpty() ? xpLabel : value, xp, attributes, pericias);
            default -> this;
        };
    }

    /** Copia com o campo ligado ou desligado. */
    public SheetModel withEnabled(String field, boolean enabled) {
        if (field == null) {
            return this;
        }
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "race" -> new SheetModel(nameLabel, raceLabel, enabled, classLabel, backgroundLabel,
                    hpLabel, manaLabel, manaEnabled, levelLabel, xpLabel, xp, attributes, pericias);
            case "mana" -> new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel,
                    backgroundLabel, hpLabel, manaLabel, enabled, levelLabel, xpLabel, xp,
                    attributes, pericias);
            // Desligar XP e o mesmo que dar um rotulo vazio: e assim que a tela
            // faz, e mantem um unico caminho para "campo que nao aparece".
            case "xp" -> new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel, backgroundLabel,
                    hpLabel, manaLabel, manaEnabled, levelLabel, xpLabel,
                    enabled ? (xp == XpMode.HIDDEN ? XpMode.NUMBER : xp) : XpMode.HIDDEN, attributes,
                    pericias);
            default -> this;
        };
    }

    /** Copia com XP no modo texto (livre) ou numero (setas de -/+). */
    public SheetModel withXpMode(XpMode mode) {
        return new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel, backgroundLabel, hpLabel,
                manaLabel, manaEnabled, levelLabel, xpLabel, mode == null ? XpMode.NUMBER : mode,
                attributes, pericias);
    }

    /** Copia com outro rotulo/nome para um atributo. */
    public SheetModel withAttributeText(String id, String label, String name) {
        if (id == null || attribute(id) == null) {
            return this;
        }
        List<AttributeDef> out = new ArrayList<>(attributes.size());
        for (AttributeDef def : attributes) {
            out.add(def.id().equals(id) ? new AttributeDef(id, label, name) : def);
        }
        return new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel, backgroundLabel, hpLabel,
                manaLabel, manaEnabled, levelLabel, xpLabel, xp, out, pericias);
    }

    /**
     * Acrescenta um atributo com id gerado ({@code attr_N}), congelado desde este
     * ponto: nem {@link #withAttributeText} nem {@link #removeAttribute} o
     * trocam.
     *
     * <p><b>GARANTIA REAL (28/09/2026) - o Javadoc antigo prometia mais do que o
     * codigo fazia.</b> O id gerado nunca colide com um atributo que o modelo
     * <b>ainda tem</b>: ele e o primeiro N livre na lista atual. Ele
     * <b>nao</b> e garantido para sempre, e nem por uma sessao inteira:
     * {@code align} casa o valor do atributo pelo id, entao, se o Mestre remover
     * {@code attr_1} e criar outro atributo depois, o primeiro N livre volta a
     * ser o 1 e o atributo novo nasce herdando o valor do atributo removido em
     * todas as fichas do mundo.
     *
     * <p><b>Por que nao da para prometer mais aqui:</b> um record sem estado nao
     * sabe o que aconteceu antes dele, e o unico lugar que sabe e o
     * {@code SessionManager}, que guarda as fichas em memoria mas nao expoe uma
     * consulta do tipo "alguma ficha tem valor para este id". Fechar isso exigiria
     * um registro de ids ja emitidos (estado estatico, que atravessa cliente e
     * servidor), e o cliente e quem gera o id: o servidor recebe o id ja pronto
     * no payload do Sheet Editor, entao um registro do servidor nao impediria o
     * reuso, so o faria o cliente. <b>Contorno hoje: renomear o atributo em vez
     * de remover e recriar</b> - o renomear preserva o id.
     *
     * @return o modelo novo, ou <b>este mesmo</b> se ja houver
     *         {@link #MAX_ATTRIBUTES}.
     */
    public SheetModel addAttribute() {
        if (attributes.size() >= MAX_ATTRIBUTES) {
            return this;
        }
        int index = nextFreshAttributeIndex();
        String id = "attr_" + index;
        List<AttributeDef> out = new ArrayList<>(attributes);
        out.add(new AttributeDef(id, "AT" + out.size(), "Attribute " + out.size()));
        return new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel, backgroundLabel,
                hpLabel, manaLabel, manaEnabled, levelLabel, xpLabel, xp, out, pericias);
    }

    /**
     * O menor numero que ainda nao esta em uso por um atributo <b>da lista
     * atual</b> (N &gt;= 1), espelhando {@link #freshPericiaId}.
     *
     * <p>Considera tambem os <b>nomes</b> das pericias: uma pericia nasce
     * sempre apontando para um atributo, e se o id gerado colidisse com o nome
     * de uma pericia ja salva, os dois se confundem no align. Os
     * <b>ids</b> das pericias ja nao podem colidir: sao {@code pericia_N}.
     *
     * <p><b>Limitacao:</b> "ainda nao esta em uso" quer dizer "nao esta na lista
     * <b>agora</b>". Um id que o Mestre removeu fica livre outra vez, e o
     * defeito e o do {@link #addAttribute} acima - este metodo e o unico lugar
     * que escolhe o numero, entao a correcao de fundo nao cabe aqui.
     */
    private int nextFreshAttributeIndex() {
        int n = 1;
        while (true) {
            String candidate = "attr_" + n;
            if (attribute(candidate) == null && periciaByName(candidate) == null) {
                return n;
            }
            n++;
        }
    }

    /**
     * Remove um atributo.
     *
     * <p>As pericias que somavam com ele passam a somar com o primeiro
     * atributo da lista: e melhor uma pericia somar com o atributo errado do que
     * uma ficha ter uma pericia apontando para um atributo que nao existe.
     *
     * @return o modelo novo, ou <b>este mesmo</b> se restaria menos de
     *         {@link #MIN_ATTRIBUTES}.
     */
    public SheetModel removeAttribute(String id) {
        if (id == null || attribute(id) == null || attributes.size() <= MIN_ATTRIBUTES) {
            return this;
        }
        List<AttributeDef> out = new ArrayList<>();
        for (AttributeDef def : attributes) {
            if (!def.id().equals(id)) {
                out.add(def);
            }
        }
        if (out.isEmpty()) {
            return this;
        }
        String fallback = out.get(0).id();
        List<PericiaDef> newPericias = new ArrayList<>(pericias.size());
        for (PericiaDef def : pericias) {
            // O id vai junto: um id perdido aqui quebraria a identidade da
            // pericia e o align comecaria a perder o valor dela.
            newPericias.add(def.attributeId().equals(id) || out.stream().noneMatch(a -> a.id().equals(def.attributeId()))
                    ? new PericiaDef(def.id(), def.name(), fallback)
                    : def);
        }
        return new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel, backgroundLabel, hpLabel,
                manaLabel, manaEnabled, levelLabel, xpLabel, xp, out, newPericias);
    }

    /**
     * Acrescenta uma pericia com o primeiro atributo e um id gerado
     * ({@code pericia_N}), que fica <b>congelado</b> desde este ponto.
     *
     * <p><b>Limitacao honesta (28/09/2026):</b> o id pertence a <b>pericia
     * logica</b>, e o modelo so sabe os ids que ele tem <b>agora</b>. Se o Mestre
     * remover {@code pericia_3} e depois criar outra pericia, o primeiro N livre
     * pode ser o 3 de novo, e a pericia nova nasce herdando o valor (e o
     * atributo) que a pericia removida tinha em todas as fichas, porque o
     * {@link #align} casa por id. Nao existe estado no modelo que guarde o id
     * apos a remocao, e o {@code SessionManager} nao expoe as fichas em memoria
     * para uma consulta. Contorno: <b>renomear</b> a pericia em vez de remover e
     * recriar - o renomear preserva o id, que e o que o jogador ja tem.
     *
     * <p>O nome continua sendo gerado como antes ({@code "Skill N"}, o primeiro N
     * livre): o nome e rotulo, e nao identidade.
     *
     * @return o modelo novo, ou <b>este mesmo</b> se ja houver
     *         {@link #MAX_PERICIAS}.
     */
    public SheetModel addPericia() {
        if (pericias.size() >= MAX_PERICIAS) {
            return this;
        }
        int n = 1;
        while (periciaByName("Skill " + n) != null) {
            n++;
        }
        List<PericiaDef> out = new ArrayList<>(pericias);
        out.add(new PericiaDef(freshPericiaId(out), "Skill " + n, firstAttributeId()));
        return new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel, backgroundLabel, hpLabel,
                manaLabel, manaEnabled, levelLabel, xpLabel, xp, attributes, out);
    }

    /**
     * Remove uma pericia pelo <b>id</b>.
     *
     * <p>Antes era pelo nome, e o nome era a identidade. Hoje o id e a
     * identidade, entao a tela do Sheet Editor passa o id de
     * {@link PericiaDef#id()}: uma pericia que o Mestre renomeou no mesmo dia
     * continua sendo a mesma para o botao de remover.
     *
     * @return o modelo novo, ou <b>este mesmo</b> se restaria menos de
     *         {@link #MIN_PERICIAS}.
     */
    public SheetModel removePericia(String id) {
        if (id == null || periciaById(id) == null || pericias.size() <= MIN_PERICIAS) {
            return this;
        }
        List<PericiaDef> out = new ArrayList<>();
        for (PericiaDef def : pericias) {
            if (!def.id().equals(id)) {
                out.add(def);
            }
        }
        if (out.isEmpty()) {
            return this;
        }
        return new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel, backgroundLabel, hpLabel,
                manaLabel, manaEnabled, levelLabel, xpLabel, xp, attributes, out);
    }

    /**
     * Copia com outro nome (ou outro atributo padrao) para a pericia de este
     * <b>id</b>, <b>preservando o id</b>.
     *
     * <p>Continua recusando um nome ja usado por outra pericia: o id resolveu o
     * problema do renomear, nao a UX de duas linhas com o mesmo texto, e essa
     * recusa e o que impede o Mestre de digitar um nome que ja existe. Comparacao
     * exata de id, e o {@code id} vazio devolve a ficha intacta.
     */
    public SheetModel withPericiaText(String id, String newName, String attributeId) {
        PericiaDef def = periciaById(id);
        if (def == null) {
            return this;
        }
        String target = newName == null ? "" : clean(newName, LABEL_MAX);
        // Renomear para o nome de outra pericia deixaria duas linhas com o mesmo
        // nome, e o nome e o que o Mestre le: a recusa e de UX (e o que a tela
        // usa para avisar), e nao de identidade - a identidade agora e o id.
        if (target.isEmpty() || (!target.equalsIgnoreCase(def.name()) && periciaByName(target) != null)) {
            return this;
        }
        String attr = attributeId != null && attribute(attributeId) != null ? attributeId : def.attributeId();
        List<PericiaDef> out = new ArrayList<>(pericias.size());
        for (PericiaDef current : pericias) {
            out.add(current.id().equals(def.id()) ? new PericiaDef(def.id(), target, attr) : current);
        }
        return new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel, backgroundLabel, hpLabel,
                manaLabel, manaEnabled, levelLabel, xpLabel, xp, attributes, out);
    }

    /**
     * A pericia deste <b>id</b>, ou {@code null}.
     *
     * <p>Comparacao exata e id vazio devolvendo {@code null}, como em
     * {@link #attribute(String)}: o id e gerado pelo proprio mod, em minusculas,
     * e um id desconhecido (payload forjado, id de uma pericia removida) tem de
     * falhar em vez de casar com a linha errada.
     */
    public PericiaDef periciaById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (PericiaDef def : pericias) {
            if (def.id().equals(id)) {
                return def;
            }
        }
        return null;
    }

    /**
     * A pericia com este nome, ou {@code null}.
     *
     * <p><b>28/09/2026: o nome NAO e mais a identidade</b> - e o {@code id} (ver
     * {@link #periciaById}). Esta busca continua porque o {@code MasterCommands}
     * (/rpg roll) resolve por nome digitado e porque o editor recusa um nome ja
     * usado: as duas coisas sao interface com a pessoa, nao armazenamento.
     *
     * <p>A comparacao ignora caixa porque o nome e o que aparece na linha. <b>Publico
     * desde 27/09/2026</b>: a tela do Sheet Editor precisa reencontrar a pericia
     * de uma linha para devolver o texto a caixa quando o Mestre digita um nome
     * vazio ou ja usado.
     */
    public PericiaDef periciaByName(String name) {
        if (name == null) {
            return null;
        }
        for (PericiaDef def : pericias) {
            if (def.name().equalsIgnoreCase(name)) {
                return def;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // ALINHAMENTO COM A FICHA
    // ------------------------------------------------------------------

    /**
     * Faz a ficha bater com o modelo: os atributos que o modelo tem ficam com o
     * valor que a ficha tinha (ou 0 se nunca foram editados), os que a ficha tem
     * e o modelo nao sao descartados, e as pericias seguem a lista do modelo,
     * mantendo valor e atributo de quem ja existia.
     *
     * <p><b>28/09/2026: a pericia e casada pelo {@link PericiaDef#id()}, nao pelo
     * nome</b> - e por isso que renomear uma pericia preserva o valor e o
     * atributo que o jogador ja tinha nela. Se a ficha <b>tem</b> ids e o id nao
     * bate, nao ha fallback por nome: um nome igual com id diferente e outra
     * pericia, e aceitar o nome esconderia o bug e transplantaria o valor para a
     * linha errada.
     *
     * <p><b>O que carrega o valor de um save pre-migration hoje: o
     * preenchimento POSICIONAL, nao o nome.</b> Uma lista gravada antes dos ids
     * chega sem o campo, e o construtor de {@link SheetData} preenche
     * {@code pericia_1}, {@code pericia_2}, ... <b>na ordem da lista</b> (o
     * {@code sanitizePericias} de la, com o {@code id} obrigatorio para a
     * entrada ser retida). A ordem do save e a ordem do modelo, porque o
     * {@code align} antigo percorria a lista do modelo e gravava a ficha nessa
     * ordem, e o modelo nao tem botao de mover linha. Entao casar por id
     * <b>reproduz</b> o que casar por nome reproduzia: posicao N da ficha antiga
     * e pericia N do modelo.
     *
     * <p><b>O fallback por nome nao e o que carrega a migracao, e hoje ele nem
     * pode dar certo se rodar.</b> O unico jeito de ele rodar e
     * {@link SheetData#hasPericiaIds()} devolver {@code false}, e isso so
     * acontece com a lista de pericias <b>VAZIA</b>: o
     * {@code sanitizePericias} preenche o id de toda entrada que ele retida, entao
     * nao existe estado misto (alguns com id, outros sem) e uma lista nao vazia
     * sempre tem id. Com a lista vazia, {@link SheetData#periciaByNameOrLegacy}
     * itera a lista vazia e devolve {@code null} para toda linha do modelo - ou
     * seja, o resultado e o mesmo de {@code found == null}. O ramo fica por
     * <b>principio de defesa</b> (um dia em que uma lista com entrada sem id
     * chegue ao alinhamento, ele passa a ter efeito), e <b>nao</b> porque a
     * migracao dependa dele.
     *
     * <p>Esta e a regra que substitui a antiga {@code sanitizePericias}, que
     * prendia a ficha a lista de codigo. O comportamento e o mesmo — a ficha
     * sempre tem exatamente as pericias do sistema — mas a lista passou a vir
     * do Mestre.
     */
    public SheetData align(SheetData sheet) {
        if (sheet == null) {
            return null;
        }
        List<SheetData.Attributes.AttributeValue> values = new ArrayList<>(attributes.size());
        for (AttributeDef def : attributes) {
            values.add(new SheetData.Attributes.AttributeValue(def.id(), sheet.attributeValue(def.id())));
        }
        List<SheetData.Pericia> newPericias = new ArrayList<>(pericias.size());
        // Defense-in-depth, hoje sem efeito (ver o Javadoc deste metodo): so uma
        // lista de pericias VAZIA chega aqui sem id, e uma lista vazia nao tem
        // nome nenhum para casar. Fica porque um dia uma lista com entrada sem
        // id pode chegar aqui, e nesse caso o nome ainda seria a unica chave.
        // Calculado uma vez, e nao dentro do laco, porque e a mesma resposta para
        // todas as linhas.
        boolean sheetWithoutIds = !sheet.hasPericiaIds();
        for (PericiaDef def : pericias) {
            SheetData.Pericia found = sheet.periciaById(def.id());
            // periciaByNameOrLegacy, e nao periciaByName, porque um nome salvo
            // pode ser um apelido antigo (ver SheetData.LEGACY_PERICIA_NAMES) e
            // a busca exata perderia o valor sem nenhuma mensagem. Pelo motivo
            // acima, este ramo devolve sempre null hoje.
            if (found == null && sheetWithoutIds) {
                found = sheet.periciaByNameOrLegacy(def.name());
            }
            String attrId = found == null || attribute(found.attributeId()) == null
                    ? def.attributeId()
                    : found.attributeId();
            if (attribute(attrId) == null) {
                attrId = firstAttributeId();
            }
            newPericias.add(new SheetData.Pericia(def.id(), def.name(),
                    found == null ? 0 : found.value(), attrId));
        }
        return new SheetData(sheet.identity(), sheet.vitals(), sheet.progress(),
                new SheetData.Attributes(values), sheet.skills(), newPericias);
    }

    // ------------------------------------------------------------------
    // LIMITES AUXILIARES
    // ------------------------------------------------------------------

    private static String clean(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static String clean(String text, int max, String fallback) {
        String value = clean(text, max);
        return value.isEmpty() ? fallback : value;
    }

    // ------------------------------------------------------------------
    // CODEC DE NBT
    // ------------------------------------------------------------------

    public static final Codec<SheetModel> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("nameLabel", "Name").forGetter(SheetModel::nameLabel),
            Codec.STRING.optionalFieldOf("raceLabel", "Race").forGetter(SheetModel::raceLabel),
            Codec.BOOL.optionalFieldOf("raceEnabled", true).forGetter(SheetModel::raceEnabled),
            Codec.STRING.optionalFieldOf("classLabel", "Class").forGetter(SheetModel::classLabel),
            Codec.STRING.optionalFieldOf("backgroundLabel", "Background").forGetter(SheetModel::backgroundLabel),
            Codec.STRING.optionalFieldOf("hpLabel", "HP").forGetter(SheetModel::hpLabel),
            Codec.STRING.optionalFieldOf("manaLabel", "Mana").forGetter(SheetModel::manaLabel),
            Codec.BOOL.optionalFieldOf("manaEnabled", true).forGetter(SheetModel::manaEnabled),
            Codec.STRING.optionalFieldOf("levelLabel", "Level").forGetter(SheetModel::levelLabel),
            Codec.STRING.optionalFieldOf("xpLabel", "XP").forGetter(SheetModel::xpLabel),
            Codec.STRING.xmap(XpMode::decode, XpMode::name)
                    .optionalFieldOf("xp", XpMode.NUMBER).forGetter(SheetModel::xp),
            Codec.list(AttributeDef.CODEC).optionalFieldOf("attributes", List.of()).forGetter(SheetModel::attributes),
            Codec.list(PericiaDef.CODEC).optionalFieldOf("pericias", List.of()).forGetter(SheetModel::pericias)
    ).apply(i, SheetModel::new));

    // ------------------------------------------------------------------
    // CODEC DE REDE
    // ------------------------------------------------------------------

    private static final StreamCodec<FriendlyByteBuf, List<AttributeDef>> ATTRIBUTES_CODEC =
            ByteBufCodecs.collection(ArrayList::new, AttributeDef.STREAM_CODEC, MAX_ATTRIBUTES);
    private static final StreamCodec<FriendlyByteBuf, List<PericiaDef>> PERICIAS_CODEC =
            ByteBufCodecs.collection(ArrayList::new, PericiaDef.STREAM_CODEC, MAX_PERICIAS);

    /**
     * Escrito campo a campo, e nao com {@code composite}.
     *
     * <p><b>Por que:</b> {@code StreamCodec.composite} tem overloads ate 6
     * campos, e este record tem 13. Um {@code StreamCodec.of} com encoder e
     * decoder proprios nao tem esse limite e, ao contrario do composite, aceita
     * listas com tamanho variavel.
     */
    /**
     * <b>A ORDEM DESTE METODO TEM DE SER IGUAL A DO DECODER.</b> O encoder e o
     * decoder sao dois lambdas independentes e nada os amarra em tempo de
     * compilacao: se um campo e acrescentado so num dos lados, o pacote continua
     * compilando e falha so em runtime, com o cliente ou o servidor lendo um
     * comprimento de string no lugar do valor. Foi exatamente o que aconteceu com
     * as listas, que estavam no inicio do encoder e no fim do decoder.
     *
     * <p>Regra pratica: o encoder segue a ordem dos componentes do record, que e
     * a mesma que o decoder le.
     */
    private static final StreamEncoder<FriendlyByteBuf, SheetModel> ENCODER = (buf, model) -> {
        ByteBufCodecs.stringUtf8(LABEL_MAX).encode(buf, model.nameLabel());
        ByteBufCodecs.stringUtf8(LABEL_MAX).encode(buf, model.raceLabel());
        ByteBufCodecs.BOOL.encode(buf, model.raceEnabled());
        ByteBufCodecs.stringUtf8(LABEL_MAX).encode(buf, model.classLabel());
        ByteBufCodecs.stringUtf8(LABEL_MAX).encode(buf, model.backgroundLabel());
        ByteBufCodecs.stringUtf8(LABEL_MAX).encode(buf, model.hpLabel());
        ByteBufCodecs.stringUtf8(LABEL_MAX).encode(buf, model.manaLabel());
        ByteBufCodecs.BOOL.encode(buf, model.manaEnabled());
        ByteBufCodecs.stringUtf8(LABEL_MAX).encode(buf, model.levelLabel());
        ByteBufCodecs.stringUtf8(LABEL_MAX).encode(buf, model.xpLabel());
        ByteBufCodecs.VAR_INT.encode(buf, model.xp().ordinal());
        ATTRIBUTES_CODEC.encode(buf, model.attributes());
        PERICIAS_CODEC.encode(buf, model.pericias());
    };

    private static final StreamDecoder<FriendlyByteBuf, SheetModel> DECODER = buf -> new SheetModel(
            ByteBufCodecs.stringUtf8(LABEL_MAX).decode(buf),
            ByteBufCodecs.stringUtf8(LABEL_MAX).decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.stringUtf8(LABEL_MAX).decode(buf),
            ByteBufCodecs.stringUtf8(LABEL_MAX).decode(buf),
            ByteBufCodecs.stringUtf8(LABEL_MAX).decode(buf),
            ByteBufCodecs.stringUtf8(LABEL_MAX).decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.stringUtf8(LABEL_MAX).decode(buf),
            ByteBufCodecs.stringUtf8(LABEL_MAX).decode(buf),
            XpMode.byOrdinal(ByteBufCodecs.VAR_INT.decode(buf)),
            ATTRIBUTES_CODEC.decode(buf),
            PERICIAS_CODEC.decode(buf)
    );

    public static final StreamCodec<FriendlyByteBuf, SheetModel> STREAM_CODEC =
            StreamCodec.of(ENCODER, DECODER);
}
