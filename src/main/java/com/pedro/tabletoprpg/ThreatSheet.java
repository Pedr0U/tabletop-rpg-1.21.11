package com.pedro.tabletoprpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ficha de Ameaca: o monstro que o Mestre criou.
 *
 * <p><b>02/10/2026.</b> A ficha e do Mestre (fica no NBT dele, como os presets de
 * rolagem) e o item no inventario e so a apresentacao dela. Por isso as duas coisas
 * sao separadas: apagar a ficha nao tira o item da mochila.
 *
 * <p><b>Por que o dado e um record com tres sub-records:</b> sao 8 campos, e o
 * {@code RecordCodecBuilder} do DFU e comfortable ate 16 -- mas o
 * {@link StreamCodec#composite} comfortable ate 6 pares. Quebrar em
 * {@link Identity} (7 campos de texto) e {@link Vitals} deixa cada parte pequena o
 * bastante para os dois codec, sem o encoder manual que o {@link SheetModel} precisou.
 *
 * <p><b>Os limites sao deste arquivo, e nao do {@link SheetModel}:</b> o modelo
 * guarda o teto de atributo e de pericia dos JOGADORES (editavel pelo Mestre no Sheet
 * Editor). A ameaca tem regra propria, pedida em 02/10/2026: valor de atributo de -999
 * a 999 em 4 caracteres, em vez dos +-30 dos jogadores.
 */
public record ThreatSheet(
        Identity identity,
        Vitals vitals,
        String description,
        List<AttributeValue> attributes,
        List<PericiaValue> pericias,
        List<String> features,
        List<Ability> passives,
        List<Action> actions) {

    // ------------------------------------------------------------------
    // LIMITES
    // ------------------------------------------------------------------

    public static final int MAX_NAME = 32;
    /** ND: 4 caracteres, como pedido em 02/10/2026. */
    public static final int LEVEL_CHARS = 4;
    public static final int MIN_LEVEL = -999;
    public static final int MAX_LEVEL = 9999;
    public static final int MAX_DISPLAY_NAME = 30;
    public static final int MAX_TYPE = 32;
    public static final int MAX_SIZE = 24;
    public static final int MAX_SPEED = 32;
    public static final int MAX_DESCRIPTION = 256;
    public static final int MAX_FEATURE = 96;
    public static final int MAX_ABILITY_NAME = 48;
    public static final int MAX_ABILITY_DESC = 256;
    public static final int MAX_ACTION_NAME = 48;
    public static final int MAX_ACTION_BONUS = 24;
    public static final int MAX_ACTION_DAMAGE = 24;
    public static final int MAX_ACTION_DESC = 256;

    /** Valor de atributo e de pericia da ameaca: 4 caracteres, piso -999. */
    public static final int VALUE_CHARS = 4;
    public static final int VALUE_MIN = -999;
    public static final int VALUE_MAX = 999;

    /** HP e Mana da ameaca seguem o mesmo teto do jogador (piso e teto inclusos). */
    public static final int MAX_RESOURCE = SheetData.MAX_RESOURCE;
    public static final int MIN_RESOURCE = SheetData.MAX_HP_FLOOR;

    /** Quantidade de atributos e de pericias: 999, pedido em 02/10/2026. */
    public static final int MAX_ATTRIBUTES = 999;
    public static final int MAX_PERICIAS = 999;

    /**
     * Caracteristicas, passivas e ativas nao chegaram a 999 como atributos.
     *
     * <p><b>Por que 64 e nao 999:</b> o payload de save leva a ficha INTEIRA num
     * pacote. Atributo custa poucos bytes, mas uma passiva tem nome + descricao de ate
     * 256 caracteres: 999 delas passariam de 250 KB num unico pacote custom, e o
     * vanilla recusa payload acima do limite do canal. 64 ja e mais do que qualquer
     * ameaca usa e mantem o pacote pequeno. Nao e limite de regra do jogo, e limite
     * de transporte.
     */
    public static final int MAX_FEATURES = 64;
    public static final int MAX_ABILITIES = 64;
    public static final int MAX_ACTIONS = 64;

    /**
     * Pericias do padrao da ameaca, em portugues.
     *
     * <p><b>Por que a lista esta aqui e nao no {@link SheetModel}:</b> o modelo e o
     * que o Mestre edita e vale para os jogadores. A ameaca tem o proprio padrao,
     * pedido em 02/10/2026.
     */
    public static final List<String> DEFAULT_PERICIA_NAMES =
            List.of("Iniciativa", "Percepção", "Fortitude", "Reflexos", "Vontade");

    /**
     * Apelido em portugues -> nome no modelo, para o padrao acima encontrar a pericia
     * que o modelo realmente tem.
     *
     * <p><b>Por que existe:</b> os nomes do modelo padrao estao em ingles
     * ({@code Perception}), mas a lista padrao foi escrita em portugues. Sem esta
     * tabela, "Percepção" nao casaria com "Perception" e a ameaca nasceria sem
     * nenhuma pericia.
     *
     * <p><b>Por que so um apelido:</b> {@code Initiative}, {@code Fortitude},
     * {@code Reflexes} e {@code Will} NAO existem no modelo padrao -- a
     * {@code Initiative} saiu em 27/09/2026 por decisao do Mestre. Se o Mestre criar
     * esses nomes no Sheet Editor, o mesmo apelido passa a casar e a pericia aparece.
     * Se ele nao criar, a regra do proprio pedido vale: padrao que nao existe e
     * retirado.
     */
    private static final Map<String, String> PERICIA_ALIASES = Map.of(
            "iniciativa", "initiative",
            "percepcao", "perception",
            "fortitude", "fortitude",
            "reflexos", "reflexes",
            "vontade", "will");

    // ------------------------------------------------------------------
    // SUB-RECORDS
    // ------------------------------------------------------------------

    /**
     * Identidade e corpo da ameaca: quem ela e.
     *
     * @param name             nome da ameaca, como o Mestre digitou
     * @param level            ND (nivel de ameaca), 4 caracteres
     * @param displayName      nome de exibicao, 30 caracteres
     * @param showDisplayName  marcar "Exibir nome em cima da ameaca?"
     * @param type             tipo (ex.: "Humanoide")
     * @param size             tamanho (texto livre: "Pequeno", "1,80 m")
     * @param speed            deslocamento (texto livre: "30 ft.")
     */
    public record Identity(
            String name,
            int level,
            String displayName,
            boolean showDisplayName,
            String type,
            String size,
            String speed) {

        /**
         * Escrito a mao porque sao 7 campos e o {@link StreamCodec#composite} deste DFU
         * so tem sobrecarga de 2, 3 e 6 pares -- nao existe a de 4 nem a de 7. Desenhar
         * o par a mao e o que sobrou, e o {@link SheetModel} faz o mesmo pelo mesmo
         * motivo.
         */
        public static final StreamCodec<FriendlyByteBuf, Identity> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public Identity decode(FriendlyByteBuf buf) {
                        String name = ByteBufCodecs.stringUtf8(MAX_NAME).decode(buf);
                        int level = ByteBufCodecs.VAR_INT.decode(buf);
                        String displayName = ByteBufCodecs.stringUtf8(MAX_DISPLAY_NAME).decode(buf);
                        boolean show = ByteBufCodecs.BOOL.decode(buf);
                        String type = ByteBufCodecs.stringUtf8(MAX_TYPE).decode(buf);
                        String size = ByteBufCodecs.stringUtf8(MAX_SIZE).decode(buf);
                        String speed = ByteBufCodecs.stringUtf8(MAX_SPEED).decode(buf);
                        return new Identity(name, level, displayName, show, type, size, speed);
                    }

                    @Override
                    public void encode(FriendlyByteBuf buf, Identity value) {
                        ByteBufCodecs.stringUtf8(MAX_NAME).encode(buf, value.name());
                        ByteBufCodecs.VAR_INT.encode(buf, value.level());
                        ByteBufCodecs.stringUtf8(MAX_DISPLAY_NAME).encode(buf, value.displayName());
                        ByteBufCodecs.BOOL.encode(buf, value.showDisplayName());
                        ByteBufCodecs.stringUtf8(MAX_TYPE).encode(buf, value.type());
                        ByteBufCodecs.stringUtf8(MAX_SIZE).encode(buf, value.size());
                        ByteBufCodecs.stringUtf8(MAX_SPEED).encode(buf, value.speed());
                    }
                };

        public static final Codec<Identity> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Identity::name),
                Codec.INT.fieldOf("level").forGetter(Identity::level),
                Codec.STRING.fieldOf("display_name").forGetter(Identity::displayName),
                Codec.BOOL.fieldOf("show_display_name").forGetter(Identity::showDisplayName),
                Codec.STRING.fieldOf("type").forGetter(Identity::type),
                Codec.STRING.fieldOf("size").forGetter(Identity::size),
                Codec.STRING.fieldOf("speed").forGetter(Identity::speed)
        ).apply(i, Identity::new));

        /** Nunca lanca: e o caminho do codec, sobre NBT antigo ou editado a mao. */
        public Identity {
            name = text(name, MAX_NAME);
            level = clamp(level, MIN_LEVEL, MAX_LEVEL);
            displayName = text(displayName, MAX_DISPLAY_NAME);
            type = text(type, MAX_TYPE);
            size = text(size, MAX_SIZE);
            speed = text(speed, MAX_SPEED);
        }
    }

    /**
     * Vida e defesa da ameaca.
     *
     * <p><b>Por que dois campos de vida e nao um:</b> e o mesmo par do jogador (valor
     * atual + teto), e e o que o bloco de 3 linhas da ficha desenha. {@code hp} pode
     * passar do teto (excedente), entao o teto do clamp e {@link #MAX_RESOURCE} e nao
     * {@code hpMax}.
     *
     * <p><b>Por que o CA entra aqui e nao na identidade:</b> CA e um numero de defesa
     * como o HP, e nao um texto de nome. Ele fica em 0..{@link #MAX_RESOURCE}, que e o
     * mesmo clamp que a ficha do jogador usa para o CA.
     */
    public record Vitals(int hp, int hpMax, int ca) {

        public static final StreamCodec<FriendlyByteBuf, Vitals> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public Vitals decode(FriendlyByteBuf buf) {
                        int hp = ByteBufCodecs.VAR_INT.decode(buf);
                        int hpMax = ByteBufCodecs.VAR_INT.decode(buf);
                        int ca = ByteBufCodecs.VAR_INT.decode(buf);
                        return new Vitals(hp, hpMax, ca);
                    }

                    @Override
                    public void encode(FriendlyByteBuf buf, Vitals value) {
                        ByteBufCodecs.VAR_INT.encode(buf, value.hp());
                        ByteBufCodecs.VAR_INT.encode(buf, value.hpMax());
                        ByteBufCodecs.VAR_INT.encode(buf, value.ca());
                    }
                };

        public static final Codec<Vitals> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("hp").forGetter(Vitals::hp),
                Codec.INT.fieldOf("hp_max").forGetter(Vitals::hpMax),
                Codec.INT.fieldOf("ca").orElse(0).forGetter(Vitals::ca)
        ).apply(i, Vitals::new));

        public Vitals {
            hp = clamp(hp, MIN_RESOURCE, MAX_RESOURCE);
            hpMax = clamp(hpMax, 0, MAX_RESOURCE);
            ca = clamp(ca, 0, MAX_RESOURCE);
        }
    }

    /** Um atributo da ameaca: o {@code id} do modelo e o valor que o Mestre deu. */
    public record AttributeValue(String id, int value) {

        public static final StreamCodec<FriendlyByteBuf, AttributeValue> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(32), AttributeValue::id,
                        ByteBufCodecs.VAR_INT, AttributeValue::value,
                        AttributeValue::new);

        public static final Codec<AttributeValue> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(AttributeValue::id),
                Codec.INT.fieldOf("value").forGetter(AttributeValue::value)
        ).apply(i, AttributeValue::new));

        public AttributeValue {
            id = text(id, 32);
            value = clamp(value, VALUE_MIN, VALUE_MAX);
        }
    }

    /** Uma pericia da ameaca: o {@code id} do modelo e o valor do Mestre. */
    public record PericiaValue(String id, int value) {

        public static final StreamCodec<FriendlyByteBuf, PericiaValue> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(32), PericiaValue::id,
                        ByteBufCodecs.VAR_INT, PericiaValue::value,
                        PericiaValue::new);

        public static final Codec<PericiaValue> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(PericiaValue::id),
                Codec.INT.fieldOf("value").forGetter(PericiaValue::value)
        ).apply(i, PericiaValue::new));

        public PericiaValue {
            id = text(id, 32);
            value = clamp(value, VALUE_MIN, VALUE_MAX);
        }
    }

    /**
     * Habilidade passiva: nome e efeito.
     *
     * <p><b>Por que descricao e String e nao lista de linhas:</b> a tela mostra o texto
     * inteiro, com quebra por largura. Guardar lista de linhas obrigaria o servidor a
     * saber a largura da tela do Mestre.
     */
    public record Ability(String name, String description) {

        public static final StreamCodec<FriendlyByteBuf, Ability> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(MAX_ABILITY_NAME), Ability::name,
                        ByteBufCodecs.stringUtf8(MAX_ABILITY_DESC), Ability::description,
                        Ability::new);

        public static final Codec<Ability> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Ability::name),
                Codec.STRING.fieldOf("description").forGetter(Ability::description)
        ).apply(i, Ability::new));

        public Ability {
            name = text(name, MAX_ABILITY_NAME);
            description = text(description, MAX_ABILITY_DESC);
        }
    }

    /** Ataque ou habilidade ativa: nome, bonus de ataque, dano e descricao. */
    public record Action(String name, String attackBonus, String damage, String description) {

        /** Escrito a mao: sao 4 campos e o {@code composite} deste DFU nao tem 4 pares. */
        public static final StreamCodec<FriendlyByteBuf, Action> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public Action decode(FriendlyByteBuf buf) {
                        String name = ByteBufCodecs.stringUtf8(MAX_ACTION_NAME).decode(buf);
                        String bonus = ByteBufCodecs.stringUtf8(MAX_ACTION_BONUS).decode(buf);
                        String damage = ByteBufCodecs.stringUtf8(MAX_ACTION_DAMAGE).decode(buf);
                        String description = ByteBufCodecs.stringUtf8(MAX_ACTION_DESC).decode(buf);
                        return new Action(name, bonus, damage, description);
                    }

                    @Override
                    public void encode(FriendlyByteBuf buf, Action value) {
                        ByteBufCodecs.stringUtf8(MAX_ACTION_NAME).encode(buf, value.name());
                        ByteBufCodecs.stringUtf8(MAX_ACTION_BONUS).encode(buf, value.attackBonus());
                        ByteBufCodecs.stringUtf8(MAX_ACTION_DAMAGE).encode(buf, value.damage());
                        ByteBufCodecs.stringUtf8(MAX_ACTION_DESC).encode(buf, value.description());
                    }
                };

        public static final Codec<Action> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Action::name),
                Codec.STRING.fieldOf("attack_bonus").forGetter(Action::attackBonus),
                Codec.STRING.fieldOf("damage").forGetter(Action::damage),
                Codec.STRING.fieldOf("description").forGetter(Action::description)
        ).apply(i, Action::new));

        public Action {
            name = text(name, MAX_ACTION_NAME);
            attackBonus = text(attackBonus, MAX_ACTION_BONUS);
            damage = text(damage, MAX_ACTION_DAMAGE);
            description = text(description, MAX_ACTION_DESC);
        }
    }

    // ------------------------------------------------------------------
    // CODEC
    // ------------------------------------------------------------------

    private static final StreamCodec<FriendlyByteBuf, List<AttributeValue>> ATTRIBUTES_STREAM =
            ByteBufCodecs.collection(ArrayList::new, AttributeValue.STREAM_CODEC, MAX_ATTRIBUTES);
    private static final StreamCodec<FriendlyByteBuf, List<PericiaValue>> PERICIAS_STREAM =
            ByteBufCodecs.collection(ArrayList::new, PericiaValue.STREAM_CODEC, MAX_PERICIAS);
    private static final StreamCodec<FriendlyByteBuf, List<String>> FEATURES_STREAM =
            ByteBufCodecs.collection(ArrayList::new,
                    ByteBufCodecs.stringUtf8(MAX_FEATURE), MAX_FEATURES);
    private static final StreamCodec<FriendlyByteBuf, List<Ability>> PASSIVES_STREAM =
            ByteBufCodecs.collection(ArrayList::new, Ability.STREAM_CODEC, MAX_ABILITIES);
    private static final StreamCodec<FriendlyByteBuf, List<Action>> ACTIONS_STREAM =
            ByteBufCodecs.collection(ArrayList::new, Action.STREAM_CODEC, MAX_ACTIONS);

    /**
     * Na rede. Escrito a mao (encode/decode) em vez de {@code composite} porque sao 8
     * campos e o composite comfortable ate 6 pares; a decomposicao em dois
     * {@code composite} de 4 seria legivel, mas escrever a ordem dos campos aqui deixa
     * a ordem do NBT e a da rede na mesma lista, o que e mais facil de conferir.
     */
    public static final StreamCodec<FriendlyByteBuf, ThreatSheet> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ThreatSheet decode(FriendlyByteBuf buf) {
                    Identity id = Identity.STREAM_CODEC.decode(buf);
                    Vitals vit = Vitals.STREAM_CODEC.decode(buf);
                    String desc = ByteBufCodecs.stringUtf8(MAX_DESCRIPTION).decode(buf);
                    List<AttributeValue> attrs = ATTRIBUTES_STREAM.decode(buf);
                    List<PericiaValue> per = PERICIAS_STREAM.decode(buf);
                    List<String> feats = FEATURES_STREAM.decode(buf);
                    List<Ability> pass = PASSIVES_STREAM.decode(buf);
                    List<Action> acts = ACTIONS_STREAM.decode(buf);
                    return new ThreatSheet(id, vit, desc, attrs, per, feats, pass, acts);
                }

                @Override
                public void encode(FriendlyByteBuf buf, ThreatSheet value) {
                    Identity.STREAM_CODEC.encode(buf, value.identity());
                    Vitals.STREAM_CODEC.encode(buf, value.vitals());
                    ByteBufCodecs.stringUtf8(MAX_DESCRIPTION).encode(buf, value.description());
                    ATTRIBUTES_STREAM.encode(buf, value.attributes());
                    PERICIAS_STREAM.encode(buf, value.pericias());
                    FEATURES_STREAM.encode(buf, value.features());
                    PASSIVES_STREAM.encode(buf, value.passives());
                    ACTIONS_STREAM.encode(buf, value.actions());
                }
    };

    /** NBT. Oito campos cabem no {@code RecordCodecBuilder} sem gambiarra. */
    public static final Codec<ThreatSheet> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identity.CODEC.fieldOf("identity").forGetter(ThreatSheet::identity),
            Vitals.CODEC.fieldOf("vitals").forGetter(ThreatSheet::vitals),
            Codec.STRING.fieldOf("description").forGetter(ThreatSheet::description),
            AttributeValue.CODEC.listOf().fieldOf("attributes").forGetter(ThreatSheet::attributes),
            PericiaValue.CODEC.listOf().fieldOf("pericias").forGetter(ThreatSheet::pericias),
            Codec.STRING.listOf().fieldOf("features").forGetter(ThreatSheet::features),
            Ability.CODEC.listOf().fieldOf("passives").forGetter(ThreatSheet::passives),
            Action.CODEC.listOf().fieldOf("actions").forGetter(ThreatSheet::actions)
    ).apply(i, ThreatSheet::new));

    // ------------------------------------------------------------------
    // CONSTRUTOR
    // ------------------------------------------------------------------

    /**
     * Nunca lanca: e o caminho do codec (NBT antigo, pacote) e da tela. Corta o que
     * passou do limite e vira {@code null} em lista vazia.
     */
    public ThreatSheet {
        identity = identity == null
                ? new Identity("", 0, "", false, "", "", "")
                : identity;
        vitals = vitals == null ? new Vitals(0, 0, 0) : vitals;
        description = text(description, MAX_DESCRIPTION);
        attributes = cap(attributes, MAX_ATTRIBUTES);
        pericias = cap(pericias, MAX_PERICIAS);
        features = capText(features, MAX_FEATURES, MAX_FEATURE);
        passives = cap(passives, MAX_ABILITIES);
        actions = cap(actions, MAX_ACTIONS);
    }

    // ------------------------------------------------------------------
    // FABRICA
    // ------------------------------------------------------------------

    /**
     * Ficha nova em branco, ja com a lista de atributos e as pericias padrao que o
     * modelo corrente tem.
     *
     * @param model o modelo global do Mestre; se {@code null}, cai em
     *              {@link SheetModel#defaults()}
     */
    public static ThreatSheet blank(SheetModel model) {
        SheetModel source = model == null ? SheetModel.defaults() : model;
        List<AttributeValue> attrs = new ArrayList<>();
        for (SheetModel.AttributeDef def : source.attributes()) {
            attrs.add(new AttributeValue(def.id(), 0));
        }
        return new ThreatSheet(
                new Identity("", 0, "", false, "", "", ""),
                new Vitals(0, 1, 10),
                "",
                attrs,
                defaultPericias(source),
                List.of(),
                List.of(),
                List.of());
    }

    /**
     * As pericias do padrao que existem no modelo, com valor 0.
     *
     * <p>Casa por nome normalizado (sem acento, sem espaco) e tambem pelo apelido de
     * {@link #PERICIA_ALIASES}. Pericia padrao que o modelo nao tem nao entra: e a
     * regra do proprio pedido ("caso alguma pericia padrao nao exista, retirar ela").
     */
    public static List<PericiaValue> defaultPericias(SheetModel model) {
        SheetModel source = model == null ? SheetModel.defaults() : model;
        List<PericiaValue> out = new ArrayList<>();
        for (String wanted : DEFAULT_PERICIA_NAMES) {
            SheetModel.PericiaDef def = findPericia(source, wanted);
            if (def != null) {
                out.add(new PericiaValue(def.id(), 0));
            }
        }
        return List.copyOf(out);
    }

    /** Procura a pericia do modelo pelo nome normalizado ou pelo apelido conhecido. */
    public static SheetModel.PericiaDef findPericia(SheetModel model, String name) {
        String wanted = norm(name);
        if (wanted.isEmpty() || model == null) {
            return null;
        }
        String alias = PERICIA_ALIASES.getOrDefault(wanted, wanted);
        for (SheetModel.PericiaDef def : model.pericias()) {
            String candidate = norm(def.name());
            if (candidate.equals(wanted) || candidate.equals(alias)) {
                return def;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // APOIO
    // ------------------------------------------------------------------

    /** Chave de busca: minuscula, sem acento, sem espaco. Igual a dos presets. */
    public String key() {
        return RollPreset.normalizeKey(identity.name());
    }

    /** O nome que a lista de fichas mostra: nome + ND. */
    public String listLabel() {
        return identity.name() + " (ND " + identity.level() + ")";
    }

    /**
     * Coloca a ficha em dia com o modelo: atributo novo do modelo entra com valor 0 e
     * atributo que o modelo nao tem mais sai.
     *
     * <p><b>Por que sai:</b> e o mesmo que a ficha do jogador faz com
     * {@code sanitizePericias} -- o modelo manda no que existe. O valor guardado se
     * perde, e isso e perda declarada, nao bug.
     *
     * <p><b>Por que isso mora aqui e nao so na tela:</b> a tela precisa do mesmo
     * resultado no cliente (para mostrar) e o servidor precisa dele no save, e os dois
     * lados tem o mesmo {@link SheetModel}.
     */
    public ThreatSheet alignedTo(SheetModel model) {
        SheetModel source = model == null ? SheetModel.defaults() : model;
        List<AttributeValue> aligned = new ArrayList<>();
        for (SheetModel.AttributeDef def : source.attributes()) {
            int value = 0;
            for (AttributeValue existing : attributes) {
                if (existing.id().equals(def.id())) {
                    value = existing.value();
                    break;
                }
            }
            aligned.add(new AttributeValue(def.id(), value));
        }
        List<PericiaValue> kept = new ArrayList<>();
        for (PericiaValue per : pericias) {
            if (source.periciaById(per.id()) != null) {
                kept.add(per);
            }
        }
        if (aligned.equals(attributes) && kept.equals(pericias)) {
            return this;
        }
        return new ThreatSheet(identity, vitals, description, aligned, kept,
                features, passives, actions);
    }

    private static String text(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static <T> List<T> cap(List<T> values, int max) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> out = new ArrayList<>(Math.min(values.size(), max));
        for (T value : values) {
            if (value == null) {
                continue;
            }
            out.add(value);
            if (out.size() >= max) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static List<String> capText(List<String> values, int maxCount, int maxLen) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String value : values) {
            String clean = text(value, maxLen);
            if (!clean.isEmpty()) {
                out.add(clean);
            }
            if (out.size() >= maxCount) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** Minuscula, sem acento e sem espaco: a forma de comparar nome de pericia. */
    private static String norm(String value) {
        if (value == null) {
            return "";
        }
        String stripped = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}