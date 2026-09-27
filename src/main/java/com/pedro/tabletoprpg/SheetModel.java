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
     * Uma pericia do modelo: o nome (que e a identidade dela, como ja era) e o
     * atributo que ela soma por padrao.
     */
    public record PericiaDef(String name, String attributeId) {
        public static final StreamCodec<FriendlyByteBuf, PericiaDef> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(LABEL_MAX), PericiaDef::name,
                        ByteBufCodecs.stringUtf8(32), PericiaDef::attributeId,
                        PericiaDef::new
                );

        public static final Codec<PericiaDef> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("name", "").forGetter(PericiaDef::name),
                Codec.STRING.optionalFieldOf("attribute", "").forGetter(PericiaDef::attributeId)
        ).apply(i, PericiaDef::new));

        public PericiaDef {
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
                        new PericiaDef("Acrobatics", "dexterity"),
                        new PericiaDef("Animal Handling", "wisdom"),
                        new PericiaDef("Arcana", "intelligence"),
                        new PericiaDef("Athletics", "strength"),
                        new PericiaDef("Deception", "charisma"),
                        new PericiaDef("History", "intelligence"),
                        new PericiaDef("Insight", "wisdom"),
                        new PericiaDef("Intimidation", "charisma"),
                        new PericiaDef("Investigation", "intelligence"),
                        new PericiaDef("Medicine", "wisdom"),
                        new PericiaDef("Nature", "intelligence"),
                        new PericiaDef("Perception", "wisdom"),
                        new PericiaDef("Performance", "charisma"),
                        new PericiaDef("Persuasion", "charisma"),
                        new PericiaDef("Religion", "intelligence"),
                        new PericiaDef("Stealth", "dexterity"),
                        new PericiaDef("Survival", "wisdom"),
                        new PericiaDef("Thievery", "dexterity")
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

    private static List<PericiaDef> sanitizePericias(List<PericiaDef> raw) {
        List<PericiaDef> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (raw != null) {
            for (PericiaDef def : raw) {
                if (def == null || out.size() >= MAX_PERICIAS) {
                    continue;
                }
                if (def.name().isEmpty() || !seen.add(def.name().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                out.add(def);
            }
        }
        if (out.isEmpty()) {
            return defaults().pericias();
        }
        return List.copyOf(out);
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
     * Acrescenta um atributo com id gerado.
     *
     * <p><b>Por que o id nao pode ser reciclado:</b> {@code align} casa o valor do
     * atributo pelo id. Se este metodo reusasse o id {@code attr_1} de um
     * atributo que o Mestre acabou de remover, o atributo novo nasceria com o
     * valor do atributo removido, em todas as fichas do mundo. Por isso o
     * contador sobe ate um id que ainda nao foi usado <b>nesta sessao</b>, e
     * nao apenas ate um id que nao esta na lista atual.
     *
     * @return o modelo novo, ou <b>este mesmo</b> se ja houver
     *         {@link #MAX_ATTRIBUTES}.
     */
    public SheetModel addAttribute() {
        if (attributes.size() >= MAX_ATTRIBUTES) {
            return this;
        }
        int n = 1;
        // nextFreshAttributeIndex: o mesmo numero de uma sessao anterior nao pode
        // ser reusado, senao o align herda o valor do atributo que saiu.
        int index = nextFreshAttributeIndex();
        String id = "attr_" + index;
        List<AttributeDef> out = new ArrayList<>(attributes);
        out.add(new AttributeDef(id, "AT" + out.size(), "Attribute " + out.size()));
        return new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel, backgroundLabel,
                hpLabel, manaLabel, manaEnabled, levelLabel, xpLabel, xp, out, pericias);
    }

    /**
     * O menor numero que ainda nao foi usado por um atributo <b>gerado</b>.
     *
     * <p>Considera tambem os ids das <b>pericias</b>: uma pericia nasce sempre
     * apontando para um atributo, e se o id gerado colidisse com o nome de uma
     * pericia ja salva, os dois se confundem no align.
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
            newPericias.add(def.attributeId().equals(id) || out.stream().noneMatch(a -> a.id().equals(def.attributeId()))
                    ? new PericiaDef(def.name(), fallback)
                    : def);
        }
        return new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel, backgroundLabel, hpLabel,
                manaLabel, manaEnabled, levelLabel, xpLabel, xp, out, newPericias);
    }

    /** Acrescenta uma pericia com o primeiro atributo. */
    public SheetModel addPericia() {
        if (pericias.size() >= MAX_PERICIAS) {
            return this;
        }
        int n = 1;
        while (periciaByName("Skill " + n) != null) {
            n++;
        }
        List<PericiaDef> out = new ArrayList<>(pericias);
        out.add(new PericiaDef("Skill " + n, firstAttributeId()));
        return new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel, backgroundLabel, hpLabel,
                manaLabel, manaEnabled, levelLabel, xpLabel, xp, attributes, out);
    }

    /**
     * Remove uma pericia pelo nome.
     *
     * @return o modelo novo, ou <b>este mesmo</b> se restaria menos de
     *         {@link #MIN_PERICIAS}.
     */
    public SheetModel removePericia(String name) {
        if (name == null || periciaByName(name) == null || pericias.size() <= MIN_PERICIAS) {
            return this;
        }
        List<PericiaDef> out = new ArrayList<>();
        for (PericiaDef def : pericias) {
            if (!def.name().equalsIgnoreCase(name)) {
                out.add(def);
            }
        }
        if (out.isEmpty()) {
            return this;
        }
        return new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel, backgroundLabel, hpLabel,
                manaLabel, manaEnabled, levelLabel, xpLabel, xp, attributes, out);
    }

    /** Copia com outro nome (ou outro atributo padrao) para uma pericia. */
    public SheetModel withPericiaText(String name, String newName, String attributeId) {
        PericiaDef def = periciaByName(name);
        if (def == null) {
            return this;
        }
        String target = newName == null ? "" : clean(newName, LABEL_MAX);
        // Renomear para o nome de outra pericia deixaria duas linhas com o mesmo
        // nome, e o nome e a identidade: a segunda sobrescreveria a primeira em
        // qualquer busca por nome.
        if (target.isEmpty() || (!target.equalsIgnoreCase(def.name()) && periciaByName(target) != null)) {
            return this;
        }
        String attr = attributeId != null && attribute(attributeId) != null ? attributeId : def.attributeId();
        List<PericiaDef> out = new ArrayList<>(pericias.size());
        for (PericiaDef current : pericias) {
            out.add(current.name().equalsIgnoreCase(def.name()) ? new PericiaDef(target, attr) : current);
        }
        return new SheetModel(nameLabel, raceLabel, raceEnabled, classLabel, backgroundLabel, hpLabel,
                manaLabel, manaEnabled, levelLabel, xpLabel, xp, attributes, out);
    }

    /**
     * A pericia com este nome, ou {@code null}.
     *
     * <p>A comparacao ignora caixa porque o nome e a identidade da pericia, e a
     * ficha do jogador tambem procura por ele assim. <b>Publico desde
     * 27/09/2026</b>: a tela do Sheet Editor precisa reencontrar a pericia de
     * uma linha para devolver o texto a caixa quando o Mestre digita um nome
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
        for (PericiaDef def : pericias) {
            // periciaByNameOrLegacy, e nao periciaByName: e o que preserva o valor
            // de uma ficha salva antes de 27/09/2026, quando os nomes das
            // pericias eram outros. Com a busca exata, o valor virava 0 sem
            // nenhuma mensagem.
            SheetData.Pericia found = sheet.periciaByNameOrLegacy(def.name());
            String attrId = found == null || attribute(found.attributeId()) == null
                    ? def.attributeId()
                    : found.attributeId();
            if (attribute(attrId) == null) {
                attrId = firstAttributeId();
            }
            newPericias.add(new SheetData.Pericia(def.name(), found == null ? 0 : found.value(), attrId));
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
