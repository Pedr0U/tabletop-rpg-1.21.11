package com.pedro.tabletoprpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ficha do personagem de um jogador (FASE 3).
 *
 * <p>Estrutura agrupada em sub-records para manter cada
 * {@code StreamCodec.composite} com no máximo 6 campos (limite da API):
     * {@code identity} (4), {@code vitals} (4), {@code progress} (2),
 * {@code attributes} (6) e {@code skills} (lista).
 *
 * <p><b>Invariante de faixa:</b> os construtores compactos limitam o que
 * <b>precisa</b> ser limitado - vida (com piso {@link #MAX_HP_FLOOR} e teto
 * {@link #MAX_RESOURCE}), mana, nível, textos (nome/descrição) e o valor da
 * perícia (0-3). A validação acontece <b>por construção</b> e não depende do
 * chamador lembrar de clamp. O servidor continua sendo a autoridade (a edição
 * chega por payload e é convertida com {@link #withField}), mas mesmo um cliente
 * malicioso não consegue gravar um valor fora de faixa.
 *
 * <p><b>Atributos: teto 30, sem piso</b> (27/09/2026). Eles viraram modificadores
 * somados às rolagens e podem ser negativos, então o piso é
 * {@link Integer#MIN_VALUE} - ver {@link Attributes}. O teto 30 entrou depois,
 * quando a ficha ganhou botões {@code -}/{@code +} de dois dígitos: sem teto, o
 * número cresce até invadir o rótulo e os botões. A aritmética das rolagens usa
 * {@code long} para não estourar.
 *
 * <p><b>HP pode ser negativo</b> (decisão do usuário): o piso é
 * {@link #MAX_HP_FLOOR} e {@code hp <= 0} significa personagem deitado
 * ({@link #isDowned()}). <b>HP e Mana também podem PASSAR do máximo</b>
 * (decisão do usuário): {@code 12/10} = 10 permanentes + 2 temporários. Por
 * isso o teto do valor atual é {@link #MAX_RESOURCE}, e não
 * {@code hpMax}/{@code manaMax} - se fosse o máximo, o excedente seria
 * truncado na construção e nunca apareceria. O <b>máximo</b> em si continua
 * valendo {@code 1..MAX_RESOURCE} (HP) e {@code 0..MAX_RESOURCE} (Mana).
 *
 * <p>Os limites de tamanho de texto também protegem o codec: o
 * {@code ByteBufCodecs.stringUtf8(n)} lança exceção ao decodificar uma
 * string maior que {@code n}, então truncar aqui evita derrubar a conexão.
 */
public record SheetData(
        Identity identity,
        Vitals vitals,
        Progress progress,
        Attributes attributes,
        List<Skill> skills,
        List<Pericia> pericias
    ) {

    /** Teto de caracteres dos textos livres (nome, raça, classe). */
    public static final int MAX_NAME = 32;
    /** Teto de caracteres do nome de uma skill. */
    public static final int SKILL_MAX = 48;
    /**
     * Teto de caracteres da descrição de uma skill.
     *
     * <p><b>Não é um limite de uso, é um limite de segurança.</b> O usuário
     * pediu explicitamente para tirar o limite de caracteres da descrição: o
     * que aparece truncado é só a <i>exibição</i> no tooltip. O valor aqui é
     * alto o bastante para não atrapalhar (10.000 caracteres) e existe porque
     * {@code ByteBufCodecs.stringUtf8(n)} lança exceção ao decodificar uma
     * string maior que {@code n} - ou seja, um cliente modificado mandando
     * 50 MB derrubaria a conexão do jogador. O texto é cortado silenciosamente
     * nesse caso, em vez de derrubar a conexão.
     */
    public static final int SKILL_DESC_MAX = 10_000;
    /** Número máximo de skills por ficha. */
    public static final int MAX_SKILLS = 24;
    /** Teto de nível. */
    public static final int MAX_LEVEL = 99;
    /**
     * <b>DESUSADO.</b> Era o teto dos atributos (0..30). O clamp foi removido em
     * 26/09/2026 por decisão do usuário (atributos sem limite e podendo ser
     * negativos, porque viraram modificadores das rolagens) e <b>voltou em
     * 27/09/2026</b> com o mesmo valor, agora como {@link Attributes#VALUE_MAX}.
     * Esta constante segue sem uso: quem usa é {@code Attributes}.
     */
    @Deprecated
    public static final int MAX_STAT = 30;
    /** Teto de resource (HP máximo / Mana máxima). */
    public static final int MAX_RESOURCE = 9999;
    /**
     * Piso do HP: o personagem pode ficar com HP negativo (decisão do
     * usuário). Abaixo disso o valor é truncado - o piso existe só para
     * impedir overflow no protocolo e não tem significado de jogo.
     * {@code hp <= 0} = personagem deitado (ver {@link #isDowned()}).
     */
    public static final int MAX_HP_FLOOR = -999;
    /** Teto de XP. */
    public static final int MAX_XP = 999_999;

    /** Campos de texto livre (editáveis). */
    public static final List<String> TEXT_FIELDS = List.of("characterName", "race", "characterClass", "background");
    /**
     * Campos numericos que NAO sao atributos: vida, mana e progressao.
     *
     * <p><b>27/09/2026 (Sheet Editor):</b> esta lista deixou de enumerar os
     * atributos, porque a quantidade e os nomes deles passam a vir do
     * {@link SheetModel}. Os atributos sao resolvidos por {@link #getNumeric}
     * depois destes casos, e o que decide se o campo existe e
     * {@link #attributes()} da ficha.
     */
    public static final List<String> NUMERIC_FIELDS = List.of(
            "hp", "hpMax", "mana", "manaMax",
            "level", "xp"
    );

    /**
     * Codec da ficha. Cada grupo tem seu próprio codec; a lista de skills é
     * limitada por item ({@code stringUtf8(SKILL_MAX)}) e o
     * {@code ByteBufCodecs.list()} já impõe um teto de quantidade.
     *
     * <p>São 6 grupos, que é exatamente o limite de {@link
     * StreamCodec#composite} - por isso {@code skills} e {@code pericias} são
     * listas de records separados em vez de um record único com 8 campos.
     */
    public static final StreamCodec<FriendlyByteBuf, SheetData> STREAM_CODEC = StreamCodec.composite(
            Identity.STREAM_CODEC, SheetData::identity,
            Vitals.STREAM_CODEC, SheetData::vitals,
            Progress.STREAM_CODEC, SheetData::progress,
            Attributes.STREAM_CODEC, SheetData::attributes,
            Skill.STREAM_CODEC.apply(ByteBufCodecs.list()), SheetData::skills,
            Pericia.STREAM_CODEC.apply(ByteBufCodecs.list()), SheetData::pericias,
            SheetData::new
    );

    // ------------------------------------------------------------------
    // PERSISTENCIA EM NBT (Codec do DataFixer, nao o StreamCodec acima)
    // ------------------------------------------------------------------

    // Nao ha mais um "ATTRIBUTE_CODEC": o atributo de uma pericia passou a ser
    // gravado como o id cru (String), dentro de PERICIA_CODEC. O save antigo
    // gravava o mesmo id ("strength"), entao nenhum valor se perde na troca.

    private static final Codec<Identity> IDENTITY_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("characterName", "").forGetter(Identity::characterName),
            Codec.STRING.optionalFieldOf("race", "").forGetter(Identity::race),
            Codec.STRING.optionalFieldOf("characterClass", "").forGetter(Identity::characterClass),
            Codec.STRING.optionalFieldOf("background", "").forGetter(Identity::background)
    ).apply(i, Identity::new));

    private static final Codec<Vitals> VITALS_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("hp", Vitals.defaults().hp()).forGetter(Vitals::hp),
            Codec.INT.optionalFieldOf("hpMax", Vitals.defaults().hpMax()).forGetter(Vitals::hpMax),
            Codec.INT.optionalFieldOf("mana", Vitals.defaults().mana()).forGetter(Vitals::mana),
            Codec.INT.optionalFieldOf("manaMax", Vitals.defaults().manaMax()).forGetter(Vitals::manaMax)
    ).apply(i, Vitals::new));

    private static final Codec<Progress> PROGRESS_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("level", Progress.defaults().level()).forGetter(Progress::level),
            Codec.INT.optionalFieldOf("xp", Progress.defaults().xp()).forGetter(Progress::xp),
            Codec.STRING.optionalFieldOf("xpText", "").forGetter(Progress::xpText)
    ).apply(i, Progress::new));

    /**
     * Codec dos atributos no formato ATUAL: {@code {values: [{id, value}...]}}.
     *
     * <p>Para o save gravado quando ainda nao havia Sheet Editor, os atributos
     * eram seis inteiros soltos ({@code {strength: 3, dexterity: 1, ...}}). Ver
     * {@link #ATTRIBUTES_CODEC} para a migracao.
     */
    private static final Codec<Attributes> ATTRIBUTES_CURRENT_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.list(Attributes.AttributeValue.CODEC).fieldOf("values").forGetter(Attributes::values)
    ).apply(i, Attributes::new));

    /**
     * Migracao do NBT: o formato antigo dos seis inteiros soltos.
     *
     * <p>Os ids do formato antigo sao exatamente os que o {@link SheetModel}
     * padrao ainda usa, entao o valor de cada atributo sobrevive a mudanca sem
     * nenhum mapa de conversao.
     */
    private static final Codec<Attributes> ATTRIBUTES_LEGACY_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("strength", 0).forGetter(s -> s.valueOf("strength")),
            Codec.INT.optionalFieldOf("dexterity", 0).forGetter(s -> s.valueOf("dexterity")),
            Codec.INT.optionalFieldOf("constitution", 0).forGetter(s -> s.valueOf("constitution")),
            Codec.INT.optionalFieldOf("intelligence", 0).forGetter(s -> s.valueOf("intelligence")),
            Codec.INT.optionalFieldOf("wisdom", 0).forGetter(s -> s.valueOf("wisdom")),
            Codec.INT.optionalFieldOf("charisma", 0).forGetter(s -> s.valueOf("charisma"))
    ).apply(i, Attributes::fromLegacyIds));

    /**
     * Tenta o formato novo; se o NBT nao tiver {@code values}, cai no antigo.
     *
     * <p><b>Por que {@code values} e obrigatorio no codec novo:</b> se fosse
     * opcional, um save antigo seria lido com sucesso e a lista sairia vazia —
     * a migracao nunca rodaria e todo mundo perderia os seis numeros sem erro
     * nenhum. Obrigatorio faz o save antigo falhar aqui e cair no codec legado.
     */
    private static final Codec<Attributes> ATTRIBUTES_CODEC =
            ATTRIBUTES_CURRENT_CODEC.withAlternative(ATTRIBUTES_LEGACY_CODEC);

    private static final Codec<Skill> SKILL_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("name", "").forGetter(Skill::name),
            Codec.STRING.optionalFieldOf("description", "").forGetter(Skill::description)
    ).apply(i, Skill::new));

    private static final Codec<Pericia> PERICIA_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("name", "").forGetter(Pericia::name),
            Codec.INT.optionalFieldOf("value", 0).forGetter(Pericia::value),
            Codec.STRING.optionalFieldOf("attribute", "").forGetter(Pericia::attributeId)
    ).apply(i, Pericia::new));

    /**
     * Codec de NBT da ficha completa, usado por
     * {@code PlayerSheetPersistenceMixin} para salvar a ficha no proprio NBT do
     * jogador (sobrevive a restart e a desconexao).
     *
     * <p><b>Por que um {@code Codec} e nao NBT escrito a mao:</b> em 1.21.11
     * {@code Player.addAdditionalSaveData}/{@code readAdditionalSaveData} nao
     * recebem mais um {@code CompoundTag}, e sim {@code ValueOutput} e
     * {@code ValueInput}, que so expõem {@code store}/{@code read} com
     * {@code Codec}. Confirmado por {@code javap} em 1.21.11.
     *
     * <p><b>Por que todo campo e opcional com padrao:</b> um save escrito por
     * uma versao anterior, ou editado a mao, nao pode derrubar o login do
     * jogador. O que faltar volta ao padrao do proprio record. E o construtor
     * compacto de {@link SheetData} roda depois de decodificar, entao
     * {@code pericias} ausente/errado ainda passa por
     * {@link #sanitizePericias} e volta a lista fixa.
     */
    public static final Codec<SheetData> CODEC = RecordCodecBuilder.create(i -> i.group(
            IDENTITY_CODEC.optionalFieldOf("identity", new Identity("", "", "", "")).forGetter(SheetData::identity),
            VITALS_CODEC.optionalFieldOf("vitals", Vitals.defaults()).forGetter(SheetData::vitals),
            PROGRESS_CODEC.optionalFieldOf("progress", Progress.defaults()).forGetter(SheetData::progress),
            ATTRIBUTES_CODEC.optionalFieldOf("attributes", Attributes.defaults()).forGetter(SheetData::attributes),
            Codec.list(SKILL_CODEC).optionalFieldOf("skills", List.of()).forGetter(SheetData::skills),
            Codec.list(PERICIA_CODEC).optionalFieldOf("pericias", List.of()).forGetter(SheetData::pericias)
    ).apply(i, SheetData::new));

    /** Normaliza nulos, a lista de skills e a lista de perícias. */
    public SheetData {
        identity = identity == null ? new Identity("", "", "", "") : identity;
        vitals = vitals == null ? Vitals.defaults() : vitals;
        progress = progress == null ? Progress.defaults() : progress;
        attributes = attributes == null ? Attributes.defaults() : attributes;
        skills = sanitizeSkills(skills);
        pericias = sanitizePericias(pericias);
    }

    // ------------------------------------------------------------------
    // SUB-RECORDS
    // ------------------------------------------------------------------

    /**
     * Nome do personagem, raça, classe e origem. Textos livres.
     *
     * <p><b>{@code background} (27/09/2026):</b> quarto campo, pedido do
     * usuario como "campo embaixo da classe". Ele fica dentro de
     * {@code Identity} e <b>nao</b> vira um sétimo grupo de
     * {@link SheetData#STREAM_CODEC}: o limite de 6 grupos é do record de
     * fora, e um record de 4 campos cabe folgadamente.
     *
     * <p>Ordem importa no codec de rede ({@code StreamCodec.composite} é
     * posicional) mas nao no de NBT, que é por nome. <b>Não há negociação de
     * versão entre cliente e servidor</b>, então acrescentar o 4o campo quebra
     * cliente antigo de qualquer jeito: ele leria a 4a string como se fosse o
     * próximo campo do record. Por isso {@code background} foi posto no fim
     * apenas por convencao de leitura - a posição é indiferente para a
     * segurança, e a compatibilidade real vem de o mod ir junto com o cliente.
     */
    public record Identity(String characterName, String race, String characterClass, String background) {
        public static final StreamCodec<FriendlyByteBuf, Identity> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(MAX_NAME), Identity::characterName,
                ByteBufCodecs.stringUtf8(MAX_NAME), Identity::race,
                ByteBufCodecs.stringUtf8(MAX_NAME), Identity::characterClass,
                ByteBufCodecs.stringUtf8(MAX_NAME), Identity::background,
                Identity::new
        );

        public Identity {
            characterName = clean(characterName);
            race = clean(race);
            characterClass = clean(characterClass);
            background = clean(background);
        }

        /** Cópia com o nome do personagem trocado. */
        public Identity withCharacterName(String value) {
            return new Identity(value, race, characterClass, background);
        }

        /** Cópia com a raça trocada. */
        public Identity withRace(String value) {
            return new Identity(characterName, value, characterClass, background);
        }

        /** Cópia com a classe trocada. */
        public Identity withCharacterClass(String value) {
            return new Identity(characterName, race, value, background);
        }

        /** Cópia com a origem trocada. */
        public Identity withBackground(String value) {
            return new Identity(characterName, race, characterClass, value);
        }
    }

    /** HP e Mana (valor atual e máximo). */
    public record Vitals(int hp, int hpMax, int mana, int manaMax) {
        public static final StreamCodec<FriendlyByteBuf, Vitals> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Vitals::hp,
                ByteBufCodecs.VAR_INT, Vitals::hpMax,
                ByteBufCodecs.VAR_INT, Vitals::mana,
                ByteBufCodecs.VAR_INT, Vitals::manaMax,
                Vitals::new
        );

        public Vitals {
            hpMax = clamp(hpMax, 1, MAX_RESOURCE);
            manaMax = clamp(manaMax, 0, MAX_RESOURCE);
            // HP: pode ser NEGATIVO (ate MAX_HP_FLOOR) e pode PASSAR do maximo
            // -- ex.: 12/10 = 10 de vida + 2 temporarios (pedido do usuario).
            // O teto e MAX_RESOURCE e NAO hpMax: se fosse hpMax, o excedente
            // seria truncado no construtor e o "+" nunca passaria de 10/10.
            // hp <= 0 significa personagem deitado (isDowned()).
            hp = clamp(hp, MAX_HP_FLOOR, MAX_RESOURCE);
            // Mana: piso 0, sem regra especial, mas tambem pode passar do maximo.
            mana = clamp(mana, 0, MAX_RESOURCE);
        }

        /** HP <= 0: o personagem está deitado (não morre). */
        public boolean downed() {
            return hp <= 0;
        }

        public static Vitals defaults() {
            // Mana comeca em 4/4 (0/0 deixava a barra travada: com manaMax=0 o
            // "+" na tinha para onde ir, porque o clamp seria 0..0).
            return new Vitals(10, 10, 4, 4);
        }
    }

    /**
     * Nivel, XP numerico e XP em texto.
     *
     * <p><b>Por que dois campos de XP (27/09/2026):</b> o pedido do Mestre foi
     * "renomear XP, mudar o tipo de dado (numero/texto) ou desativar". Numero e
     * texto nao cabem no mesmo {@code int}, e um {@code String} so para o modo
     * texto obrigaria as setas de -/+ a fazerem parse a cada tecla. Os dois
     * campos coexistem e <b>so um deles e lido</b>, escolhido pelo
     * {@link SheetModel.XpMode}: e o que mantem cada modo simples.
     */
    public record Progress(int level, int xp, String xpText) {
        public static final StreamCodec<FriendlyByteBuf, Progress> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Progress::level,
                ByteBufCodecs.VAR_INT, Progress::xp,
                ByteBufCodecs.stringUtf8(MAX_NAME), Progress::xpText,
                Progress::new
        );

        public Progress {
            level = clamp(level, 1, MAX_LEVEL);
            xp = clamp(xp, 0, MAX_XP);
            xpText = clean(xpText);
        }

        public static Progress defaults() {
            return new Progress(1, 0, "");
        }
    }

    /**
     * Os valores dos atributos: um par (id, valor) por atributo do modelo.
     *
     * <p><b>Mudanca de 27/09/2026 (Sheet Editor).</b> Antes isto era um record
     * com seis campos {@code int} fixos e o enum {@code Attribute} tinha
     * exatamente seis constantes. Isso impedia o que o Mestre pediu: renomear,
     * remover e ate ter dez atributos. Um enum nao cresce, e um record com seis
     * campos nao encolhe.
     *
     * <p>Agora o <b>rotulo</b> do atributo vive no {@link SheetModel} e aqui
     * mora so o <b>valor</b>, ligado pelo {@code id}. O id e a chave estavel: e
     * o que o NBT guarda e o que uma pericia aponta. Renomear um atributo mexe
     * so no modelo, e nenhuma ficha de jogador e reescrita.
     */
    public record Attributes(List<AttributeValue> values) {

        /** Teto do valor de um atributo (decisao do usuario, 27/09/2026). */
        public static final int VALUE_MAX = 30;
        /** Piso do valor de um atributo (decisao do usuario, 27/09/2026). */
        public static final int VALUE_MIN = -30;

        /**
         * O valor de UM atributo.
         *
         * <p>{@code id} e a chave com que o {@link SheetModel} chama este
         * atributo ("strength", "attr_1"). E estavel: mudar o rotulo do
         * atributo no editor nao muda o id, e por isso nao perde o valor de
         * ninguem.
         *
         * <p>Fica dentro de {@link Attributes} porque e o elemento da lista
         * dela, e nao um campo solto da ficha.
         */
        public record AttributeValue(String id, int value) {
            public static final StreamCodec<FriendlyByteBuf, AttributeValue> STREAM_CODEC =
                    StreamCodec.composite(
                            ByteBufCodecs.stringUtf8(32), AttributeValue::id,
                            ByteBufCodecs.VAR_INT, AttributeValue::value,
                            AttributeValue::new
                    );

            public static final Codec<AttributeValue> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Codec.STRING.fieldOf("id").forGetter(AttributeValue::id),
                    Codec.INT.optionalFieldOf("value", 0).forGetter(AttributeValue::value)
            ).apply(i, AttributeValue::new));

            public AttributeValue {
                id = id == null ? "" : id.trim();
                if (id.length() > 32) {
                    id = id.substring(0, 32);
                }
                value = clamp(value, Attributes.VALUE_MIN, Attributes.VALUE_MAX);
            }
        }

        /**
         * O {@code collection} sem {@code map}, com o tipo de destino escrito
         * explicitamente.
         *
         * <p><b>Por que nao encadear o {@code map} direto:</b> o
         * {@code collection} so aceita {@code ArrayList::new} e devolve
         * {@code ArrayList<E>}; o {@code map} projeta o
         * {@link Attributes#values()}, que devolve {@code List<E>}. Como
         * {@code List} nao e subtipo de {@code ArrayList}, o encadeamento nao
         * compila. Declarar o destino como {@code List} resolve e ainda deixa
         * este codec reaproveitavel.
         */
        public static final StreamCodec<FriendlyByteBuf, List<AttributeValue>> VALUES_STREAM_CODEC =
                ByteBufCodecs.collection(ArrayList::new, AttributeValue.STREAM_CODEC, SheetModel.MAX_ATTRIBUTES);

        public static final StreamCodec<FriendlyByteBuf, Attributes> STREAM_CODEC =
                VALUES_STREAM_CODEC.map(Attributes::new, Attributes::values);

        public Attributes {
            values = sanitizeValues(values);
        }

        /** Ficha nova: um zero para cada atributo que o modelo declara. */
        public static Attributes defaults() {
            List<AttributeValue> out = new ArrayList<>();
            for (SheetModel.AttributeDef def : SheetModelHolder.current().attributes()) {
                out.add(new AttributeValue(def.id(), 0));
            }
            return new Attributes(out);
        }

        /**
         * Le os seis campos do formato ANTIGO (quando os atributos eram fixos no
         * record) e monta a lista nova. Usado so pela migracao do NBT.
         */
        static Attributes fromLegacyIds(int strength, int dexterity, int constitution,
                                        int intelligence, int wisdom, int charisma) {
            return new Attributes(List.of(
                    new AttributeValue("strength", strength),
                    new AttributeValue("dexterity", dexterity),
                    new AttributeValue("constitution", constitution),
                    new AttributeValue("intelligence", intelligence),
                    new AttributeValue("wisdom", wisdom),
                    new AttributeValue("charisma", charisma)
            ));
        }

        /** Valor do atributo com este id; 0 se a ficha nao o tem. */
        public int valueOf(String id) {
            if (id == null) {
                return 0;
            }
            for (AttributeValue value : values) {
                if (value.id().equals(id)) {
                    return value.value();
                }
            }
            return 0;
        }

        /** Copia com um atributo trocado. Id que a ficha nao tem nao muda nada. */
        public Attributes withValue(String id, int value) {
            if (id == null) {
                return this;
            }
            List<AttributeValue> out = new ArrayList<>(values.size());
            boolean found = false;
            for (AttributeValue current : values) {
                if (current.id().equals(id)) {
                    out.add(new AttributeValue(id, value));
                    found = true;
                } else {
                    out.add(current);
                }
            }
            return found ? new Attributes(out) : this;
        }

        /**
         * Descarta nulos, ids vazios e ids repetidos, e corta no teto do modelo.
         *
         * <p>Nao descarta valor fora de faixa: quem faz isso e o construtor de
         * {@link AttributeValue}, e um save editado a mao nao pode fazer a ficha
         * perder o atributo inteiro.
         */
        private static List<AttributeValue> sanitizeValues(List<AttributeValue> raw) {
            List<AttributeValue> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            if (raw != null) {
                for (AttributeValue value : raw) {
                    if (value == null || out.size() >= SheetModel.MAX_ATTRIBUTES) {
                        continue;
                    }
                    if (!value.id().isEmpty() && seen.add(value.id().toLowerCase(Locale.ROOT))) {
                        out.add(value);
                    }
                }
            }
            return List.copyOf(out);
        }
    }

    /**
     * Os seis ids que a ficha gravava antes do Sheet Editor, na ordem em que
     * eram campos do record {@code Attributes}.
     *
     * <p>Serve a migracao do NBT e a {@link Attributes#fromLegacyIds}. Nao e a
     * lista de atributos do jogo anymore - essa vem do {@link SheetModel}.
     */
    private static final List<String> LEGACY_ATTRIBUTE_IDS =
            List.of("strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma");

    /**
     * O que um {@code SheetSkillPayload} quer fazer com a <b>skill</b>.
     *
     * <p><b>Skill e Perícia são coisas diferentes</b> (decisão do usuário, 25/09/2026).
     * A skill é a lista <i>livre</i> que o jogador monta: um nome e uma
     * descrição, adicionar e remover. A perícia é uma lista <i>fixa</i>,
     * definida em {@link SheetModel}, em que só o valor e o atributo
     * mudam. Por isso existem dois enums e dois payloads: {@code SET_VALUE} e
     * {@code SET_ATTRIBUTE} pertencem a {@link PericiaOp}, não a este.
     *
     * <p>O codec e escrito a mao em vez de usar ordinal direto porque um
     * cliente modificado pode mandar qualquer inteiro: o valor e validado e
     * cai em {@link #INVALID} em vez de estourar {@code values()[i]}.
     */
    public enum SkillOp {
        /** Cria a skill, ou atualiza a descricao se o nome ja existir. */
        ADD,
        /** Remove a skill pelo nome. */
        REMOVE,
        /**
         * Move a skill um passo na lista (decisao do usuario em 26/09/2026).
         *
         * <p>O passo vai no campo {@code description} do payload (que e' o que
         * sobra livre nessa operacao): {@code "-1"} sobe, {@code "+1"} desce.
         * Nao existe indice na ficha porque a ordem e' a ordem da {@code List},
         * e e' ela que vai para o NBT.
         */
        MOVE,
        /**
         * Valor invalido vindo da rede. <b>Nao fazer nada com ele.</b>
         *
         * <p>Existe para que um indice corrompido seja descartado em vez de
         * virar uma operacao valida: sem esta opcao, o decode cairia em
         * {@link #ADD} e um {@code REMOVE} adulterado viraria "criar skill".
         */
        INVALID;

        public static final StreamCodec<io.netty.buffer.ByteBuf, SkillOp> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public SkillOp decode(io.netty.buffer.ByteBuf buffer) {
                        // ByteBufCodecs.VAR_INT, e não buffer.readVarInt():
                        // readVarInt/writeVarInt são do FriendlyByteBuf, e o
                        // StreamCodec genérico trabalha com ByteBuf puro.
                        int index = ByteBufCodecs.VAR_INT.decode(buffer);
                        SkillOp[] values = SkillOp.values();
                        return index >= 0 && index < values.length ? values[index] : INVALID;
                    }

                    @Override
                    public void encode(io.netty.buffer.ByteBuf buffer, SkillOp op) {
                        ByteBufCodecs.VAR_INT.encode(buffer, op == null ? 0 : op.ordinal());
                    }
                };
    }

    /**
     * Uma <b>skill</b> da ficha: {@code name} + {@code description}. Só isso.
     *
     * <p><b>Por que só dois campos:</b> decisão do usuário em 25/09/2026 -
     * "skills" e "perícias são completamente diferentes". A skill é a lista
     * livre que o jogador monta, servindo para descrever o que ele sabe fazer
     * (uma técnica, um ofício, um truque). O que ela <i>soma</i> na rolagem é
     * assunto da {@link Pericia}, que tem valor e atributo. Antes as duas coisas
     * dividiam o mesmo record, e por isso o "Add" da tela apagava o valor e o
     * atributo.
     *
     * <p>A descrição é opcional (pode ser vazia: a skill aparece, só não abre
     * tooltip) e o usuário pediu <b>sem limite de caracteres</b> - o corte
     * acontece só na exibição. O teto em {@link #SKILL_DESC_MAX} é um limite
     * de segurança do protocolo, não de uso.
     */
    public record Skill(String name, String description) {
        public static final StreamCodec<FriendlyByteBuf, Skill> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(SKILL_MAX), Skill::name,
                ByteBufCodecs.stringUtf8(SKILL_DESC_MAX), Skill::description,
                Skill::new
        );

        public Skill {
            name = cleanSkill(name);
            description = cleanDescription(description);
        }
    }

    /**
     * O que um {@code SheetPericiaPayload} quer fazer com a perícia.
     *
     * <p>Só duas operações, porque a lista de perícias é <b>fixa</b>: não há
     * como criar nem remover (decisão do usuário em 25/09/2026 - "perícias são
     * fixas que serão personalizados para cada sistema"). O que muda é o valor
     * (0-3) e o atributo que ela soma.
     */
    public enum PericiaOp {
        /** Muda só o valor (0-3). */
        SET_VALUE,
        /** Muda só o atributo que a perícia soma. */
        SET_ATTRIBUTE,
        /**
         * Valor invalido vindo da rede. <b>Nao fazer nada com ele.</b>
         *
         * <p>Mesma razao do {@link SkillOp#INVALID}: um indice corrompido nao
         * pode virar uma operacao valida por acidente.
         */
        INVALID;

        public static final StreamCodec<io.netty.buffer.ByteBuf, PericiaOp> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public PericiaOp decode(io.netty.buffer.ByteBuf buffer) {
                        int index = ByteBufCodecs.VAR_INT.decode(buffer);
                        PericiaOp[] values = PericiaOp.values();
                        return index >= 0 && index < values.length ? values[index] : INVALID;
                    }

                    @Override
                    public void encode(io.netty.buffer.ByteBuf buffer, PericiaOp op) {
                        ByteBufCodecs.VAR_INT.encode(buffer, op == null ? 0 : op.ordinal());
                    }
                };
    }

    /**
     * Uma <b>perícia</b> da ficha: {@code name} + {@code value} + {@code attribute}.
     *
     * <p><b>Por que a lista é fixa:</b> o usuário decides (25/09/2026) que as
     * perícias são definidas em código e personalizadas por sistema de RPG; o
     * jogador não pode criar nem apagar, só ajustar o <b>valor</b> e o
     * <b>atributo</b> que a perícia soma. A lista que vale é
     * {@link SheetModel} e {@link #sanitizePericias} garante que a ficha
     * sempre tenha exatamente essa lista - nem a mais, nem a menos.
     *
     * <p>Sem descrição de propósito: o usuário não pediu descrição de perícia,
     * e o nome já identifica a perícia. Se um dia quiser, é um campo a mais
     * aqui e no codec.
     */
    public record Pericia(String name, int value, String attributeId) {
        public static final StreamCodec<FriendlyByteBuf, Pericia> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(SKILL_MAX), Pericia::name,
                ByteBufCodecs.VAR_INT, Pericia::value,
                ByteBufCodecs.stringUtf8(32), Pericia::attributeId,
                Pericia::new
        );

        /**
         * Valor minimo e maximo da pericia.
         *
         * <p><b>27/09/2026:</b> o teto era 3 e foi elevado para 30, a pedido
         * do usuario, junto com a caixa de numero responsiva e o "+" que
         * escurece no limite. O piso continua 0: o bonus de pericia e o que
         * <b>soma</b> na rolagem ({@code 1d20 + valor + atributo}), entao
         * negativo aqui significaria penalidade em vez de bonus -- e penalidade
         * pertence ao atributo, que aceita negativo.
         *
         * <p>Este é o <b>único</b> ponto autoritativo do teto. Quem lê esta
         * constante: a tela ({@code StatusScreen.stepPericiaValue}) e o próprio
         * construtor deste record, que corta o valor na carga do NBT.
         * <b>{@code MasterCommands.rollSkill} NÃO lê</b>: ele confia em
         * {@code pericia.value()}, que já passou por aqui.
         */
        public static final int VALUE_MIN = 0;
        public static final int VALUE_MAX = 30;

        public Pericia {
            name = cleanSkill(name);
            value = clamp(value, VALUE_MIN, VALUE_MAX);
            // Cenario defensivo: um payload antigo, um save editado a mao ou um
            // cliente modificado poderiam mandar null. Sem isto, o NPE rebentaria
            // a ficha inteira. String vazia significa "ainda nao sabe com qual
            // atributo soma" e e resolvido por SheetModel.align.
            attributeId = attributeId == null ? "" : attributeId.trim();
            if (attributeId.length() > 32) {
                attributeId = attributeId.substring(0, 32);
            }
        }

        /** Total que esta pericia soma na rolagem: valor + atributo. */
        public int rollBonus(SheetData sheet) {
            return value + (sheet == null ? 0 : sheet.attributeValue(attributeId));
        }
    }

    // ------------------------------------------------------------------
    // FÁBRICA
    // ------------------------------------------------------------------

    /**
     * As pericias do <b>modelo padrao</b>, ja como ficha.
     *
     * <p><b>27/09/2026 (Sheet Editor):</b> esta lista deixou de ser a definicao
     * do sistema. Quem decide agora e o Mestre, no item Sheet Editor, e o que
     * existe em {@link SheetModel}. Este metodo existe so para o modelo padrao
     * (as 18 basicas de D&amp;D 5e) e para o cliente, que antes de receber o
     * modelo do servidor mostra o padrao em vez de uma ficha vazia.
     *
     * <p><b>Nao e mais a fonte da verdade:</b> {@link #sanitizePericias} nao
     * mais prende a ficha a esta lista. Quem alinha a ficha com a lista de
     * pericias do sistema e {@link SheetModel#align}, que e chamado no login e
     * a cada edicao do modelo.
     */
    public static List<Pericia> defaultPericias() {
        SheetModel model = SheetModelHolder.current();
        List<Pericia> out = new ArrayList<>(model.periciaCount());
        for (SheetModel.PericiaDef def : model.pericias()) {
            out.add(new Pericia(def.name(), 0, def.attributeId()));
        }
        return List.copyOf(out);
    }

    /**
     *apelidos ANTIGOS de cada pericia do padrao, indexados pelo nome ATUAL.
     * Um nome novo pode ter VARIOS apelidos velhos.
     *
     * <p><b>Por que existe:</b> a identidade de uma pericia no NBT e o NOME
     * (ver {@link #PERICIA_CODEC}) e {@link #sanitizePericias} casa o nome salvo
     * com o nome do padrao. Sem este mapa toda ficha salva ANTES da mudanca
     * perderia, <b>em silencio</b>, o valor e o atributo: o sanitize nao
     * devolve erro, so substitui pelo padrao.
     *
     * <p><b>27/09/2026:</b> a lista trocou as 16 pericias de espaco reservado
     * ("skill 0".."skill 15") pelas 18 basicas de D&D 5e. Duas situacoes
     * diferentes acontecem aqui:
     * <ul>
     *   <li><b>Mesmo nome, apelido so em portugues.</b> "Luta" e "Acrobacia"
     *       viraram "Melee" e "Acrobatics". O apelido antigo continua valendo,
     *       para a ficha de quem jogou antes.</li>
     *   <li><b>Nome mudado de verdade.</b> "Diplomacy"/"Diplomacia" nao existe
     *       mais em D&D 5e: o equivalente e "Persuasion", mesmo atributo
     *       (CHA). O valor antigo cai em "Persuasion" em vez de sumir.</li>
     * </ul>
     *
     * <p>As 16 "skill N" <b>nao</b> aparecem aqui de proposito: elas eram
     * espaco reservado. <b>Descartar PODE perder valor:</b> a tela antiga
     * mostrava as 20 linhas com {@code -}/{@code +} editaveis, e o padrao 0
     * nao impediu ninguem de mexer nelas. Quem ajustou "skill 3" para 5 perde
     * os 5 na migration, sem erro e sem log. Nao ha como recuperar: o espaco
     * reservado nao tem destino no padrao novo. E o motivo de a troca para as
     * 18 de D&amp;D ter sido feita com aviso, e nao em silencio.
     *
     * <p><b>Perda inevitavel, por decisao do usuario:</b> "Melee" (apelido
     * "Luta") e "Initiative" (apelido "Iniciativa") sairam do padrao em
     * 27/09/2026. Como nao ha linha no padrao novo que as receba, o valor
     * delas e descartado junto com os {@code skill N}. O padrao antigo dava
     * 2 a elas; quem investiu mais perde esse valor. Nao ha apelido que
     * resolva, porque resolveria inventando uma linha que o usuario nao pediu.
     *
     * <p>Nao apagar nenhum apelido: uma ficha pode ter sido salva em qualquer
     * momento.
     */
    private static final Map<String, List<String>> LEGACY_PERICIA_NAMES = buildLegacyPericiaNames();

    private static Map<String, List<String>> buildLegacyPericiaNames() {
        Map<String, List<String>> map = new HashMap<>();
        // O indice e do modelo PADRAO, nao do modelo salvo: e a lista de apelidos
        // de 26/09/2026 que se quer preservar, e ela nao muda com o Sheet Editor.
        for (SheetModel.PericiaDef def : SheetModel.defaults().pericias()) {
            List<String> aliases = legacyPericiaNames(def.name());
            if (!aliases.isEmpty()) {
                map.put(def.name().toLowerCase(Locale.ROOT), List.copyOf(aliases));
            }
        }
        return Map.copyOf(map);
    }

    /**
     * Todos os nomes que a pericia do padrao ja teve, do mais antigo ao atual.
     * A lista e vazia para as 18 de D&D, que nasceram com o nome definitivo.
     */
    private static List<String> legacyPericiaNames(String standardName) {
        return switch (standardName) {
            // Traducao de 26/09/2026 (portugues -> ingles).
            case "Acrobatics" -> List.of("Acrobacia");
            // Troca de 27/09/2026: "Diplomacy" virou "Persuasion" em D&D 5e.
            // Vale o nome em ingles E o de portugues, porque a ficha pode ter
            // sido salva nas duas epocas.
            case "Persuasion" -> List.of("Diplomacy", "Diplomacia");
            // <b>"Melee"/"Luta" e "Initiative"/"Iniciativa" NAO tem apelido aqui
            // de proposito:</b> as duas saiu do padrao em 27/09/2026, por decisao
            // do usuario. Nao existe nome no padrao novo para onde levar o valor,
            // entao qualquer apelido seria codigo morto. As duas sao as unicas
            // perda de dados conhecidas desta migracao, e sao inevitaveis sem
            // inventar uma 19a e 20a linha que o usuario nao pediu.
            default -> List.of();
        };
    }

    /**
     * Casa o nome salvo com o nome atual do padrao, aceitando tambem qualquer
     * apelido antigo (ver {@link #LEGACY_PERICIA_NAMES}).
     */
    private static boolean matchesPericiaName(String savedName, String standardName) {
        if (savedName.equalsIgnoreCase(standardName)) {
            return true;
        }
        for (String legacy : LEGACY_PERICIA_NAMES.getOrDefault(
                standardName.toLowerCase(Locale.ROOT), List.of())) {
            if (savedName.equalsIgnoreCase(legacy)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A pericia deste nome, aceitando tambem os apelidos antigos.
     *
     * <p><b>27/09/2026:</b> existia mas nao era chamado por ninguem, e
     * {@link #LEGACY_PERICIA_NAMES} so servia a ele — ou seja, a protecao contra a
     * perda de valor de uma ficha salva ANTES da troca da lista de pericias
     * estava escrita e desligada. O {@code align} do modelo casava o nome
     * exato, nao achava "Acrobacia" para o padrao "Acrobatics", e o valor
     * virava 0 <b>em silencio</b>, no proximo login ou na proxima edicao do
     * Mestre.
     *
     * <p>E por isso que este metodo e o ponto de entrada do {@code align}: ele
     * tenta o nome exato primeiro, e so depois cai nos apelidos, para nunca
     * confundir uma pericia renomeada pelo Mestre com uma de um save antigo.
     */
    public Pericia periciaByNameOrLegacy(String name) {
        Pericia exact = periciaByName(name);
        if (exact != null || name == null) {
            return exact;
        }
        for (Pericia candidate : pericias) {
            if (matchesPericiaName(candidate.name(), name)) {
                return candidate;
            }
        }
        return null;
    }

    /** Ficha inicial de um jogador: nome = nome da conta, resto no padrão. */
    public static SheetData defaultSheet(String playerName) {
        return new SheetData(
                new Identity(clean(playerName), "", "", ""),
                Vitals.defaults(),
                Progress.defaults(),
                Attributes.defaults(),
                List.of(),
                defaultPericias()
        );
    }

    // ------------------------------------------------------------------
    // ESTADO
    // ------------------------------------------------------------------

    /**
     * <b>FACT (regra do usuário):</b> o personagem está <b>deitado</b> quando
     * {@code hp <= 0}. Ele não morre por isso: a vida real do Minecraft é uma
     * camada separada (e os jogadores já são imunes a dano físico - ver
     * {@code DamageControlHandler}) e o estado deitado é aplicado por
     * {@code DownedController}. Com {@code hp > 0} o personagem levanta
     * automaticamente.
     */
    public boolean isDowned() {
        return vitals.downed();
    }

    // ------------------------------------------------------------------
    // EDIÇÃO (usada pelo servidor ao aplicar um payload de edição)
    // ------------------------------------------------------------------

    /**
     * Devolve uma <b>nova</b> ficha com o campo indicado alterado.
     *
     * <p>O valor chega como texto (o cliente pode enviar qualquer string), então
     * é convertido e limitado aqui. Campo desconhecido ou valor numérico
     * inválido devolve a ficha inalterada - o servidor nunca confi no cliente.
     */
    public SheetData withField(String field, String rawValue) {
        if (field == null || rawValue == null) {
            return this;
        }
        String key = field.toLowerCase(Locale.ROOT);
        String value = rawValue.trim();

        return switch (key) {
            case "charactername" -> new SheetData(identity.withCharacterName(value), vitals, progress, attributes, skills, pericias);
            case "race" -> new SheetData(identity.withRace(value), vitals, progress, attributes, skills, pericias);
            case "characterclass" -> new SheetData(identity.withCharacterClass(value), vitals, progress, attributes, skills, pericias);
            case "background" -> new SheetData(identity.withBackground(value), vitals, progress, attributes, skills, pericias);

            case "hp" -> replaceVitals(new Vitals(parseInt(value, vitals.hp()), vitals.hpMax(), vitals.mana(), vitals.manaMax()));
            case "hpmax" -> replaceVitals(new Vitals(vitals.hp(), parseInt(value, vitals.hpMax()), vitals.mana(), vitals.manaMax()));
            case "mana" -> replaceVitals(new Vitals(vitals.hp(), vitals.hpMax(), parseInt(value, vitals.mana()), vitals.manaMax()));
            case "manamax" -> replaceVitals(new Vitals(vitals.hp(), vitals.hpMax(), vitals.mana(), parseInt(value, vitals.manaMax())));

            case "level" -> new SheetData(identity, vitals, new Progress(parseInt(value, progress.level()), progress.xp(), progress.xpText()), attributes, skills, pericias);
            // Com o modelo em modo TEXT, o campo da barra mostra o texto que o
            // Mestre digitou (ex.: "Fiel aogrupo"). O numero continua guardado
            // para quando o modelo voltar para NUMBER.
            case "xp" -> SheetModelHolder.current().xp() == SheetModel.XpMode.TEXT
                    ? new SheetData(identity, vitals, new Progress(progress.level(), progress.xp(), value), attributes, skills, pericias)
                    : new SheetData(identity, vitals, new Progress(progress.level(), parseInt(value, progress.xp()), progress.xpText()), attributes, skills, pericias);

            // O texto do XP vem num campo SEPARADO do numero, e nao no mesmo
            // "xp": e o que a tela de Status abre em modo TEXT. Sem este caso o
            // pacote caia no `default` abaixo, que e o ramo de atributo, e
            // Attributes.withValue devolvia a propria ficha -- o texto aparecia
            // na caixa do Mestre e sumia no proximo refresh, porque getText
            // ("xptext") lia de novo o valor do servidor.
            //
            // So aceita em modo TEXT, pelo mesmo motivo do "xp" acima: em NUMBER
            // a tela nao mostra essa caixa, entao um pacote com "xptext" so pode
            // vir de um cliente forjado.
            case "xptext" -> SheetModelHolder.current().xp() == SheetModel.XpMode.TEXT
                    ? new SheetData(identity, vitals, new Progress(progress.level(), progress.xp(), value), attributes, skills, pericias)
                    : this;

            // Qualquer outra chave e um id de atributo. O que decide se ela
            // existe e Attributes.withValue: id que a ficha nao tem devolve a
            // propria ficha, entao um cliente nao inventa atributo.
            default -> {
                Attributes next = attributes.withValue(key, parseInt(value, attributes.valueOf(key)));
                yield next == attributes ? this : replaceAttributes(next);
            }
        };
    }

    private SheetData replaceVitals(Vitals newVitals) {
        return new SheetData(identity, newVitals, progress, attributes, skills, pericias);
    }

    private SheetData replaceAttributes(Attributes newAttributes) {
        return new SheetData(identity, vitals, progress, newAttributes, skills, pericias);
    }

    /**
     * Adiciona uma skill (nome + descrição).
     *
     * <p>Se o nome JA existe, só a descrição é ATUALIZADA no lugar (preservando
     * a posição) em vez de a entrada ser duplicada. <b>Decisão:</b> sem isso o
     * jogador não teria como corrigir uma descrição errada - só poderia remover
     * e digitar tudo de novo.
     */
    public SheetData withSkill(String skill, String description) {
        String cleanName = cleanSkill(skill);
        String cleanDesc = cleanDescription(description);
        if (cleanName.isEmpty()) {
            return this;
        }
        List<Skill> next = new ArrayList<>(skills.size() + 1);
        boolean updated = false;
        for (Skill existing : skills) {
            if (existing.name().equalsIgnoreCase(cleanName)) {
                next.add(new Skill(existing.name(), cleanDesc));
                updated = true;
            } else {
                next.add(existing);
            }
        }
        if (updated) {
            return new SheetData(identity, vitals, progress, attributes, next, pericias);
        }
        if (skills.size() >= MAX_SKILLS) {
            return this; // lista cheia
        }
        next.add(new Skill(cleanName, cleanDesc));
        return new SheetData(identity, vitals, progress, attributes, next, pericias);
    }

    /**
     * Muda SÓ o valor de uma perícia (0-3), preservando o atributo.
     *
     * <p>Usado pelas setas da lista de perícias na aba Status. Se a perícia não
     * existir na ficha, devolve a ficha intacta: a lista é fixa e não aceita
     * entrada nova.
     */
    public SheetData withPericiaValue(String pericia, int value) {
        return mutatePericia(pericia, existing ->
                new Pericia(existing.name(), value, existing.attributeId()));
    }

    /**
     * Troca com qual atributo a pericia soma (botao de lista suspensa).
     *
     * <p>27/09/2026: o parametro virou o <b>id</b> do atributo (String) em vez
     * do enum {@code Attribute}, que nao existe mais. O id precisa existir no
     * modelo: um id desconhecido e ignorado, e a pericia fica com o atributo que
     * ela ja tinha.
     */
    public SheetData withPericiaAttribute(String pericia, String attributeId) {
        if (attributeId == null || SheetModelHolder.current().attribute(attributeId) == null) {
            return this;
        }
        return mutatePericia(pericia, existing ->
                new Pericia(existing.name(), existing.value(), attributeId));
    }

    /** Altera UM campo de uma perícia existente; nunca cria nem remove. */
    private SheetData mutatePericia(String pericia, java.util.function.UnaryOperator<Pericia> mutator) {
        String clean = cleanSkill(pericia);
        if (clean.isEmpty()) {
            return this;
        }
        List<Pericia> next = new ArrayList<>(pericias.size());
        boolean changed = false;
        for (Pericia existing : pericias) {
            if (existing.name().equalsIgnoreCase(clean)) {
                Pericia updated = mutator.apply(existing);
                next.add(updated);
                changed = changed || !updated.equals(existing);
            } else {
                next.add(existing);
            }
        }
        return changed ? new SheetData(identity, vitals, progress, attributes, skills, next) : this;
    }

    /** Remove uma skill pelo nome (comparação sem diferenciar maiusculas). */
    public SheetData withoutSkill(String skill) {
        String clean = cleanSkill(skill);
        if (clean.isEmpty()) {
            return this;
        }
        List<Skill> next = new ArrayList<>(skills.size());
        boolean removed = false;
        for (Skill existing : skills) {
            if (!removed && existing.name().equalsIgnoreCase(clean)) {
                removed = true;
                continue;
            }
            next.add(existing);
        }
        return removed ? new SheetData(identity, vitals, progress, attributes, next, pericias) : this;
    }

    /**
     * Move uma skill UM PASSO na lista (delta -1 sobe, +1 desce).
     *
     * <p>Usado pelas setas de reordenar da tela de Skills (decisao do usuario em
     * 26/09/2026). Nao existe indice guardado por skill: a ordem <b>e' a ordem
     * da {@code List}</b> e e' ela que o codec leva para o NBT, entao mover ja
     * persiste como qualquer outra edicao da ficha.
     *
     * <p>Devolve a ficha intacta quando o nome nao existir, quando delta e' zero
     * ou quando o passo sair da lista (primeira subindo, ultima descendo). Quem
     * recusa esses casos antes e' a UI, que deixa a seta cinza.
     */
    public SheetData withSkillMoved(String skill, int delta) {
        String clean = cleanSkill(skill);
        if (clean.isEmpty() || delta == 0) {
            return this;
        }
        int from = -1;
        for (int i = 0; i < skills.size(); i++) {
            if (skills.get(i).name().equalsIgnoreCase(clean)) {
                from = i;
                break;
            }
        }
        int to = from + delta;
        if (from < 0 || to < 0 || to >= skills.size()) {
            return this;
        }
        List<Skill> next = new ArrayList<>(skills);
        next.add(to, next.remove(from));
        return new SheetData(identity, vitals, progress, attributes, next, pericias);
    }

    // ------------------------------------------------------------------
    // LEITURA POR CAMPO (usada pela UI do cliente)
    // ------------------------------------------------------------------

    /** Valor de um campo de texto; texto vazio se o campo não for de texto. */
    public String getText(String field) {
        if (field == null) {
            return "";
        }
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "charactername" -> identity.characterName();
            case "race" -> identity.race();
            case "characterclass" -> identity.characterClass();
            case "background" -> identity.background();
            // XP em modo TEXT e um campo de texto, e nao um numero. Sem esta
            // linha, a caixa de texto da tela de Status cairia no `default` e o
            // Mestre nao veria o que digitou.
            case "xptext" -> progress.xpText();
            default -> "";
        };
    }

    /**
     * Valor de um campo numérico. Para HP/Mana devolve o valor atual
     * (não o máximo), que é o que a UI exibe e edita.
     */
    public int getNumeric(String field) {
        if (field == null) {
            return 0;
        }
        String key = field.toLowerCase(Locale.ROOT);
        // HP/Mana/Level/XP sao resolvidos aqui; qualquer outra chave e o id de
        // um atributo. Um id que a ficha nao tem devolve 0, que e a mesma
        // resposta de antes quando o campo nao existia.
        return switch (key) {
            case "hp" -> vitals.hp();
            case "hpmax" -> vitals.hpMax();
            case "mana" -> vitals.mana();
            case "manamax" -> vitals.manaMax();
            case "level" -> progress.level();
            case "xp" -> progress.xp();
            default -> attributes.valueOf(key);
        };
    }

    /**
     * Valor de um atributo pelo id do modelo.
     *
     * <p>Este e o metodo que o resto do codigo usa para "soma este atributo na
     * rolagem" (ex.: {@link Pericia#rollBonus}). Ele nao passa por
     * {@link #getNumeric} de proposito: um id de atributo e um dado de
     * <b>valor</b>, e nao um campo editavel da ficha.
     */
    public int attributeValue(String attributeId) {
        return attributeId == null ? 0 : attributes.valueOf(attributeId.toLowerCase(Locale.ROOT));
    }

    /**
     * Devolve a copia desta ficha alinhada com o modelo atual.
     *
     * <p>Alinhar e seguro de chamar a cada leitura: quando a ficha ja esta
     * alinhada, devolve a propria instancia. Quem chama e {@code SessionManager}
     * (ao criar/carregar) e o servidor (a cada edicao do modelo).
     */
    public SheetData aligned() {
        return SheetModelHolder.current().align(this);
    }

    /** A pericia deste nome, ou {@code null}. Busca ignora maiusculas. */
    public Pericia periciaByName(String name) {
        if (name == null) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        for (Pericia pericia : pericias) {
            if (pericia.name().toLowerCase(Locale.ROOT).equals(key)) {
                return pericia;
            }
        }
        return null;
    }

    /**
     * Rotulo amigavel de um campo, para a UI.
     *
     * <p><b>27/09/2026 (Sheet Editor):</b> o rotulo vem do
     * {@link SheetModelHolder#current()}, e nao mais deste switch. E por isso
     * que renomear um campo no editor muda o texto na ficha de todo mundo sem
     * tocar em uma linha de codigo.
     *
     * <p>Este metodo so resolve os campos <b>de sistema</b> (identidade, vitais,
     * progressao). O rotulo de um atributo mora no modelo e e lido por
     * {@link SheetModel#attributeLabel(String)}; o de uma pericia e o proprio
     * {@code name} que o Mestre digitou no editor.
     */
    public static String labelOf(String field) {
        if (field == null) {
            return "";
        }
        String label = SheetModelHolder.current().labelOf(field);
        if (!label.isEmpty()) {
            return label;
        }
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "hpmax" -> "Max HP";
            case "manamax" -> "Max Mana";
            default -> field;
        };
    }

    // ------------------------------------------------------------------
    // HELPERS
    // ------------------------------------------------------------------

    /** Remove espaços das pontas e corta no teto; nunca retorna null. */
    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > MAX_NAME ? trimmed.substring(0, MAX_NAME) : trimmed;
    }

    private static String cleanSkill(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > SKILL_MAX ? trimmed.substring(0, SKILL_MAX) : trimmed;
    }

    /**
     * Descricao da skill. Descricao vazia e VALIDA (a skill aparece e apenas
     * nao abre tooltip). Respeita SKILL_DESC_MAX porque o
     * {@code ByteBufCodecs.stringUtf8(n)} lanca excecao acima desse tamanho.
     */
    private static String cleanDescription(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > SKILL_DESC_MAX
                ? trimmed.substring(0, SKILL_DESC_MAX)
                : trimmed;
    }

    private static List<Skill> sanitizeSkills(List<Skill> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Skill> out = new ArrayList<>(Math.min(raw.size(), MAX_SKILLS));
        for (Skill skill : raw) {
            if (skill == null) {
                continue;
            }
            // O construtor do record normaliza nome/descricao de novo: a lista
            // pode vir de uma fonte externa (payload) sem passar por withSkill.
            Skill clean = new Skill(skill.name(), skill.description());
            if (clean.name().isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (Skill existing : out) {
                if (existing.name().equalsIgnoreCase(clean.name())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                out.add(clean);
            }
            if (out.size() >= MAX_SKILLS) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /**
     * Higiene estrutural da lista de pericias: descarta nulo, nome vazio e nome
     * repetido, e corta no teto do modelo.
     *
     * <p><b>27/09/2026 (Sheet Editor) - regra mudou.</b> Este metodo JA NAO
     * prende a ficha a lista do codigo. Antes ele reescrevia a lista inteira
     * para casar com {@code PERICIAS_PADRAO}, o que tornava impossivel ao
     * Mestre adicionar, renomear ou remover uma pericia em tempo de jogo.
     *
     * <p>Agora quem decide a lista e o {@link SheetModel}, e o metodo que
     * casa a ficha com ela e {@link SheetModel#align}. A separacao e
     * deliberada:
     * <ul>
     *   <li>Aqui: a ficha nao pode vir com lixo (nulo, vazio, duplicata) de
     *       nenhuma origem, inclusive um payload adulterado.</li>
     *   <li>Em {@code align}: a ficha ganha as pericias novas, perde as
     *       removidas e mantem o valor das que sobreviveram pelo nome.</li>
     * </ul>
     *
     * <p>Por que nao foi tudo deixado em {@code align}: {@code align} roda no
     * construtor de {@link SheetModel#align} e o construtor de {@code SheetData}
     * chama este metodo. Se o {@code align} fosse chamado aqui, o construtor
     * chamaria o align, que constroi uma ficha, que chama este metodo, que
     * chamaria o align outra vez. A limpeza fica estrutural para o loop
     * terminar.
     */
    private static List<Pericia> sanitizePericias(List<Pericia> raw) {
        List<Pericia> out = new ArrayList<>();
        if (raw == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Pericia pericia : raw) {
            if (pericia == null || out.size() >= SheetModel.MAX_PERICIAS) {
                continue;
            }
            if (!pericia.name().isEmpty() && seen.add(pericia.name().toLowerCase(Locale.ROOT))) {
                out.add(pericia);
            }
        }
        return List.copyOf(out);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback; // texto não numérico: mantém o valor anterior
        }
    }
}
