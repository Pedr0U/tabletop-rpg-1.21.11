package com.pedro.tabletoprpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ficha do personagem de um jogador (FASE 3).
 *
 * <p>Estrutura agrupada em sub-records para manter cada
 * {@code StreamCodec.composite} com no máximo 6 campos (limite da API):
     * {@code identity} (4), {@code vitals} (4), {@code progress} (2),
 * {@code attributes} (6) e {@code skills} (lista).
 *
 * <p><b>Invariante de faixa:</b> os construtores compactos limitam o que
 * <b>precisa</b> ser limitado — vida (com piso {@link #MAX_HP_FLOOR} e teto
 * {@link #MAX_RESOURCE}), mana, nível, textos (nome/descrição) e o valor da
 * perícia (0-3). A validação acontece <b>por construção</b> e não depende do
 * chamador lembrar de clamp. O servidor continua sendo a autoridade (a edição
 * chega por payload e é convertida com {@link #withField}), mas mesmo um cliente
 * malicioso não consegue gravar um valor fora de faixa.
 *
 * <p><b>Atributos: teto 30, sem piso</b> (27/09/2026). Eles viraram modificadores
 * somados às rolagens e podem ser negativos, então o piso é
 * {@link Integer#MIN_VALUE} — ver {@link Attributes}. O teto 30 entrou depois,
 * quando a ficha ganhou botões {@code -}/{@code +} de dois dígitos: sem teto, o
 * número cresce até invadir o rótulo e os botões. A aritmética das rolagens usa
 * {@code long} para não estourar.
 *
 * <p><b>HP pode ser negativo</b> (decisão do usuário): o piso é
 * {@link #MAX_HP_FLOOR} e {@code hp <= 0} significa personagem deitado
 * ({@link #isDowned()}). <b>HP e Mana também podem PASSAR do máximo</b>
 * (decisão do usuário): {@code 12/10} = 10 permanentes + 2 temporários. Por
 * isso o teto do valor atual é {@link #MAX_RESOURCE}, e não
 * {@code hpMax}/{@code manaMax} — se fosse o máximo, o excedente seria
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
     * string maior que {@code n} — ou seja, um cliente modificado mandando
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
     * usuário). Abaixo disso o valor é truncado — o piso existe só para
     * impedir overflow no protocolo e não tem significado de jogo.
     * {@code hp <= 0} = personagem deitado (ver {@link #isDowned()}).
     */
    public static final int MAX_HP_FLOOR = -999;
    /** Teto de XP. */
    public static final int MAX_XP = 999_999;

    /** Campos de texto livre (editáveis). */
    public static final List<String> TEXT_FIELDS = List.of("characterName", "race", "characterClass", "background");
    /** Campos numéricos (editáveis). */
    public static final List<String> NUMERIC_FIELDS = List.of(
            "hp", "hpMax", "mana", "manaMax",
            "level", "xp",
            "strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma"
    );

    /**
     * Codec da ficha. Cada grupo tem seu próprio codec; a lista de skills é
     * limitada por item ({@code stringUtf8(SKILL_MAX)}) e o
     * {@code ByteBufCodecs.list()} já impõe um teto de quantidade.
     *
     * <p>São 6 grupos, que é exatamente o limite de {@link
     * StreamCodec#composite} — por isso {@code skills} e {@code pericias} são
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

    /**
     * Codec de atributo para NBT, gravado pelo <b>nome do campo</b>
     * ("strength") e nao pela sigla ("FOR").
     *
     * <p><b>Por que o nome do campo:</b> a sigla aparece na UI e poderia ser
     * reescrita a qualquer momento; o nome do campo e a chave estavel. E a
     * leitura e leniente de proposito ({@link Attribute#decode}), entao um
     * save antigo ou editado a mao nao derruba o login -- cai no atributo
     * padrao em vez de estourar a excecao.
     */
    public static final Codec<Attribute> ATTRIBUTE_CODEC =
            Codec.STRING.xmap(Attribute::decode, Attribute::field);

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
            Codec.INT.optionalFieldOf("xp", Progress.defaults().xp()).forGetter(Progress::xp)
    ).apply(i, Progress::new));

    private static final Codec<Attributes> ATTRIBUTES_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("strength", 0).forGetter(Attributes::strength),
            Codec.INT.optionalFieldOf("dexterity", 0).forGetter(Attributes::dexterity),
            Codec.INT.optionalFieldOf("constitution", 0).forGetter(Attributes::constitution),
            Codec.INT.optionalFieldOf("intelligence", 0).forGetter(Attributes::intelligence),
            Codec.INT.optionalFieldOf("wisdom", 0).forGetter(Attributes::wisdom),
            Codec.INT.optionalFieldOf("charisma", 0).forGetter(Attributes::charisma)
    ).apply(i, Attributes::new));

    private static final Codec<Skill> SKILL_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("name", "").forGetter(Skill::name),
            Codec.STRING.optionalFieldOf("description", "").forGetter(Skill::description)
    ).apply(i, Skill::new));

    private static final Codec<Pericia> PERICIA_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("name", "").forGetter(Pericia::name),
            Codec.INT.optionalFieldOf("value", 0).forGetter(Pericia::value),
            ATTRIBUTE_CODEC.optionalFieldOf("attribute", Attribute.STRENGTH).forGetter(Pericia::attribute)
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
     * apenas por convencao de leitura — a posição é indiferente para a
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

    /** Nível e XP. */
    public record Progress(int level, int xp) {
        public static final StreamCodec<FriendlyByteBuf, Progress> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Progress::level,
                ByteBufCodecs.VAR_INT, Progress::xp,
                Progress::new
        );

        public Progress {
            level = clamp(level, 1, MAX_LEVEL);
            xp = clamp(xp, 0, MAX_XP);
        }

        public static Progress defaults() {
            return new Progress(1, 0);
        }
    }

    /** Os seis atributos clássicos. Todos com o mesmo teto. */
    public record Attributes(
            int strength,
            int dexterity,
            int constitution,
            int intelligence,
            int wisdom,
            int charisma
    ) {
        public static final StreamCodec<FriendlyByteBuf, Attributes> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Attributes::strength,
                ByteBufCodecs.VAR_INT, Attributes::dexterity,
                ByteBufCodecs.VAR_INT, Attributes::constitution,
                ByteBufCodecs.VAR_INT, Attributes::intelligence,
                ByteBufCodecs.VAR_INT, Attributes::wisdom,
                ByteBufCodecs.VAR_INT, Attributes::charisma,
                Attributes::new
        );

        /**
         * Teto do valor de um atributo.
         *
         * <p><b>Mudanca de decisao (27/09/2026).</b> Antes o atributo nao tinha
         * clamp nenhum, por decisao do usuario de 25/09/2026 ("nao coloque
         * limitacao"). O pedido de 27/09/2026 foi o oposto: teto de 30, com o
         * botao "+" escurecendo quando o valor chega nele. O que continua valendo
         * da decisao antiga e o <b>piso livre</b>: o atributo e um modificador
         * somado, entao ainda pode ser negativo.
         *
         * <p>O clamp mora AQUI, e nao na tela, porque o servidor e a fonte da
         * verdade: sem ele, um cliente modificado mandaria
         * {@code withField("strength", "99999")} e o valor entraria assim. A UI
         * apenas reflete o limite e desliga o "+".
         *
         * <p>A aritmetica das rolagens (ver {@code RpgSkillRoll}) usa
         * {@code long} para um valor grande nao estourar o resultado.
         */
        public static final int VALUE_MAX = 30;

        public Attributes {
            strength = clamp(strength, Integer.MIN_VALUE, VALUE_MAX);
            dexterity = clamp(dexterity, Integer.MIN_VALUE, VALUE_MAX);
            constitution = clamp(constitution, Integer.MIN_VALUE, VALUE_MAX);
            intelligence = clamp(intelligence, Integer.MIN_VALUE, VALUE_MAX);
            wisdom = clamp(wisdom, Integer.MIN_VALUE, VALUE_MAX);
            charisma = clamp(charisma, Integer.MIN_VALUE, VALUE_MAX);
        }

        public static Attributes defaults() {
            // 0 e o padrao pedido ("que comece com 0"): o atributo e um
            // modificador somado a pericia, nao um "status" que quanto maior
            // melhor. O docx antigo falava em 2 -- desatualizado.
            return new Attributes(0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * Os 6 atributos, e a abreviacao de cada um nos botoes.
     *
     * <p><b>Por que um enum e nao um String solto:</b> o usuario pediu um
     * botao de "lista suspensa" na tela de Skills para escolher com qual
     * atributo a pericia soma. Um enum da exatamente as 6 opcoes finitas que a
     * UI precisa desenhar, e o codec grava a ABREVIACAO (FOR, DES...), que e
     * curta e estavel se a ordem do enum mudar.
     *
     * <p><b>Nota sobre o .docx:</b> a versao antiga do documento listava 5
     * (FOR, DES, INT, CON, CAR) e nao tinha Sabedoria. O usuario respondeu
     * "alternando os 6 atributos", entao<SAB> (Sabedoria) foi incluido.
     */
    public enum Attribute {
        // field e' a chave do NBT e abbr e' o valor que vai na rede -- ver
        // STREAM_CODEC. NAO traduzir nenhum dos dois. Para exibicao existe
        // shortName (3 letras em ingles) e fullName.
        STRENGTH("strength", "FOR", "STR", "Strength"),
        DEXTERITY("dexterity", "DES", "DEX", "Dexterity"),
        CONSTITUTION("constitution", "CON", "CON", "Constitution"),
        INTELLIGENCE("intelligence", "INT", "INT", "Intelligence"),
        WISDOM("wisdom", "SAB", "WIS", "Wisdom"),
        CHARISMA("charisma", "CAR", "CHA", "Charisma");

        /** Todos, na ordem em que a UI apresenta a lista suspensa. */
        public static final List<Attribute> VALUES = List.of(values());

        /**
         * Codec de rede por ABREVIACAO, escrito a mao.
         *
         * <p><b>Por que nao {@code StringRepresentable.fromEnum}:</b> apesar do
         * nome sugerir o contrario, o {@code EnumCodec} do vanilla implementa
         * {@code com.mojang.serialization.Codec} (serializacao JSON/DataFixer) e
         * <b>nao</b> {@code StreamCodec}, que e o que os payloads de rede
         * exigem. Confirmado por {@code javap}: a classe pai
         * {@code StringRepresentableCodec} implementa so {@code Codec}.
         *
         * <p>Vantagem de escrever a mao: um cliente modificado mandando
         * "XXX" cai em DEXTERITY em vez de estourar a conexao, e a busca aceita
         * tambem o nome do campo ("strength"), o que deixa o comando
         * {@code /rpg roll} mais tolerante.
         */
        public static final StreamCodec<io.netty.buffer.ByteBuf, Attribute> STREAM_CODEC =
                ByteBufCodecs.stringUtf8(16).map(Attribute::decode, Attribute::abbr);

        /** Nome do campo em {@link SheetData#getNumeric}, ex.: "strength". */
        private final String field;
        /** Abreviacao de 3 letras que VAI NA REDE (payload de pericia). */
        private final String abbr;
        /**
         * Abreviacao de 3 letras mostrada ao jogador. So exibicao.
         *
         * <p>Sai separada de {@link #abbr} porque {@code abbr} e o valor gravado
         * no pacote (ver {@link #STREAM_CODEC}): trocar o valor dela quebraria a
         * conversa entre cliente e servidor. Traduzir so o que o jogador ve e
         * decision do usuario em 26/09/2026.
         */
        private final String shortName;
        /** Nome por extenso, usado no texto de ajuda. */
        private final String fullName;

        Attribute(String field, String abbr, String shortName, String fullName) {
            this.field = field;
            this.abbr = abbr;
            this.shortName = shortName;
            this.fullName = fullName;
        }

        public String field() {
            return field;
        }

        public String abbr() {
            return abbr;
        }

        /** Abreviacao em ingles para exibicao; {@code abbr()} e' a da rede. */
        public String shortName() {
            return shortName;
        }

        public String fullName() {
            return fullName;
        }

        /**
         * Procura um atributo pela abreviacao ("FOR") ou pelo nome do campo
         * ("strength"), sem diferenciar maiusculas. Devolve {@code null} se
         * nao encontrar -- quem chama decide o fallback.
         */
        public static Attribute find(String name) {
            if (name == null) {
                return null;
            }
            for (Attribute attribute : VALUES) {
                if (attribute.abbr.equalsIgnoreCase(name) || attribute.field.equalsIgnoreCase(name)) {
                    return attribute;
                }
            }
            return null;
        }

        /** Igual a {@link #find}, mas cai em {@link #DEXTERITY} se nao achar. */
        public static Attribute decode(String name) {
            Attribute found = find(name);
            return found == null ? DEXTERITY : found;
        }
    }

    /**
     * O que um {@code SheetSkillPayload} quer fazer com a <b>skill</b>.
     *
     * <p><b>Skill e Perícia são coisas diferentes</b> (decisão do usuário, 25/09/2026).
     * A skill é a lista <i>livre</i> que o jogador monta: um nome e uma
     * descrição, adicionar e remover. A perícia é uma lista <i>fixa</i>,
     * definida em {@link #PERICIAS_PADRAO}, em que só o valor e o atributo
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
     * <p><b>Por que só dois campos:</b> decisão do usuário em 25/09/2026 —
     * "skills" e "perícias são completamente diferentes". A skill é a lista
     * livre que o jogador monta, servindo para descrever o que ele sabe fazer
     * (uma técnica, um ofício, um truque). O que ela <i>soma</i> na rolagem é
     * assunto da {@link Pericia}, que tem valor e atributo. Antes as duas coisas
     * dividiam o mesmo record, e por isso o "Add" da tela apagava o valor e o
     * atributo.
     *
     * <p>A descrição é opcional (pode ser vazia: a skill aparece, só não abre
     * tooltip) e o usuário pediu <b>sem limite de caracteres</b> — o corte
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
     * como criar nem remover (decisão do usuário em 25/09/2026 — "perícias são
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
     * {@link #PERICIAS_PADRAO} e {@link #sanitizePericias} garante que a ficha
     * sempre tenha exatamente essa lista — nem a mais, nem a menos.
     *
     * <p>Sem descrição de propósito: o usuário não pediu descrição de perícia,
     * e o nome já identifica a perícia. Se um dia quiser, é um campo a mais
     * aqui e no codec.
     */
    public record Pericia(String name, int value, Attribute attribute) {
        public static final StreamCodec<FriendlyByteBuf, Pericia> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(SKILL_MAX), Pericia::name,
                ByteBufCodecs.VAR_INT, Pericia::value,
                Attribute.STREAM_CODEC, Pericia::attribute,
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
            // Cenario defensivo: um payload antigo ou um cliente modificado
            // poderia mandar null. Sem isto, o NPE rebentaria a ficha inteira.
            attribute = attribute == null ? Attribute.DEXTERITY : attribute;
        }

        /** Total que esta pericia soma na rolagem: valor + atributo. */
        public int rollBonus(SheetData sheet) {
            return value + attributeValue(sheet, attribute);
        }

        private static int attributeValue(SheetData sheet, Attribute attr) {
            return attr == null ? 0 : sheet.getNumeric(attr.field());
        }
    }

    // ------------------------------------------------------------------
    // FÁBRICA
    // ------------------------------------------------------------------

    /**
     * <b>A LISTA FIXA DE PERÍCIAS.</b> É aqui que o sistema de RPG define
     * quais perícias existem e quais são os valores iniciais.
     *
     * <p><b>Para personalizar o sistema, edite só esta lista.</b> Acrescentar
     * uma perícia aqui faz ela aparecer na ficha de todo mundo; tirar uma
     * daqui a remove das fichas (veja {@link #sanitizePericias}). Nenhuma
     * outra parte do código precisa mudar, e nenhuma alteração de valor ou
     * atributo feita na tela é preservada para uma perícia que saiu daqui —
     * a ficha passa a recomeçar no padrão.
     *
     * <p><b>Decisões do usuario (27/09/2026):</b> a lista e exatamente as
     * <b>18 perícias básicas de D&amp;D 5e</b> em inglês, cada uma com o
     * atributo que o D&amp;D 5e define para ela, todas com valor 0 para o Mestre
     * distribuir. "Initiative" e "Melee", que o sistema tinha desde o começo,
     * <b>saíram da lista</b> — ver o aviso de perda em
     * {@code sanitizePericias}. Antes desta troca, as 16 últimas posições eram
     * espaço reservado (<code>perícia 0</code>..<code>perícia 15</code>)
     * preenchido pelo RPG.
     *
     * <p>A lista é escrita na mão, e não gerada por um laço, justamente para
     * ficar fácil de editar: dá para mudar nome, valor e atributo de uma linha
     * só, sem pensar em índice ou rotação.
     */
    public static final List<Pericia> PERICIAS_PADRAO = List.of(
            // As 18 pericias basicas de D&D 5e, em ingles, com o atributo que
            // cada uma usa. Todas comecam em 0: o bonus e preenchido pelo
            // jogador (ou pelo Mestre) na tela, nao pelo codigo.
            //
            // 27/09/2026, decisao do usuario: a lista e SO estas 18. "Initiative"
            // e "Melee", que o sistema tinha desde o inicio, sairam daqui.
            // Conferido antes da decisao: a pericia "Initiative" NAO era lida em
            // lugar nenhum de src/ -- o modo combate nao consulta a ficha para
            // ordenar turnos -- e "Melee" so duplicava "Athletics" (FOR) com um
            // nome que o D&D nao usa para teste de habilidade. Ver o aviso de
            // perda em {@code sanitizePericias}.
            new Pericia("Acrobatics", 0, Attribute.DEXTERITY),
            new Pericia("Animal Handling", 0, Attribute.WISDOM),
            new Pericia("Arcana", 0, Attribute.INTELLIGENCE),
            new Pericia("Athletics", 0, Attribute.STRENGTH),
            new Pericia("Deception", 0, Attribute.CHARISMA),
            new Pericia("History", 0, Attribute.INTELLIGENCE),
            new Pericia("Insight", 0, Attribute.WISDOM),
            new Pericia("Intimidation", 0, Attribute.CHARISMA),
            new Pericia("Investigation", 0, Attribute.INTELLIGENCE),
            new Pericia("Medicine", 0, Attribute.WISDOM),
            new Pericia("Nature", 0, Attribute.INTELLIGENCE),
            new Pericia("Perception", 0, Attribute.WISDOM),
            new Pericia("Performance", 0, Attribute.CHARISMA),
            new Pericia("Persuasion", 0, Attribute.CHARISMA),
            new Pericia("Religion", 0, Attribute.INTELLIGENCE),
            new Pericia("Stealth", 0, Attribute.DEXTERITY),
            new Pericia("Survival", 0, Attribute.WISDOM),
            new Pericia("Thievery", 0, Attribute.DEXTERITY)
    );

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
        for (Pericia padrao : PERICIAS_PADRAO) {
            List<String> aliases = legacyPericiaNames(padrao.name());
            if (!aliases.isEmpty()) {
                map.put(padrao.name().toLowerCase(Locale.ROOT), List.copyOf(aliases));
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

    /** Ficha inicial de um jogador: nome = nome da conta, resto no padrão. */
    public static SheetData defaultSheet(String playerName) {
        return new SheetData(
                new Identity(clean(playerName), "", "", ""),
                Vitals.defaults(),
                Progress.defaults(),
                Attributes.defaults(),
                List.of(),
                PERICIAS_PADRAO
        );
    }

    // ------------------------------------------------------------------
    // ESTADO
    // ------------------------------------------------------------------

    /**
     * <b>FACT (regra do usuário):</b> o personagem está <b>deitado</b> quando
     * {@code hp <= 0}. Ele não morre por isso: a vida real do Minecraft é uma
     * camada separada (e os jogadores já são imunes a dano físico — ver
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
     * inválido devolve a ficha inalterada — o servidor nunca confi no cliente.
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

            case "level" -> new SheetData(identity, vitals, new Progress(parseInt(value, progress.level()), progress.xp()), attributes, skills, pericias);
            case "xp" -> new SheetData(identity, vitals, new Progress(progress.level(), parseInt(value, progress.xp())), attributes, skills, pericias);

            case "strength" -> replaceAttributes(new Attributes(parseInt(value, attributes.strength()), attributes.dexterity(),
                    attributes.constitution(), attributes.intelligence(), attributes.wisdom(), attributes.charisma()));
            case "dexterity" -> replaceAttributes(new Attributes(attributes.strength(), parseInt(value, attributes.dexterity()),
                    attributes.constitution(), attributes.intelligence(), attributes.wisdom(), attributes.charisma()));
            case "constitution" -> replaceAttributes(new Attributes(attributes.strength(), attributes.dexterity(),
                    parseInt(value, attributes.constitution()), attributes.intelligence(), attributes.wisdom(), attributes.charisma()));
            case "intelligence" -> replaceAttributes(new Attributes(attributes.strength(), attributes.dexterity(),
                    attributes.constitution(), parseInt(value, attributes.intelligence()), attributes.wisdom(), attributes.charisma()));
            case "wisdom" -> replaceAttributes(new Attributes(attributes.strength(), attributes.dexterity(),
                    attributes.constitution(), attributes.intelligence(), parseInt(value, attributes.wisdom()), attributes.charisma()));
            case "charisma" -> replaceAttributes(new Attributes(attributes.strength(), attributes.dexterity(),
                    attributes.constitution(), attributes.intelligence(), attributes.wisdom(), parseInt(value, attributes.charisma())));

            default -> this;
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
     * jogador não teria como corrigir uma descrição errada — só poderia remover
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
                new Pericia(existing.name(), value, existing.attribute()));
    }

    /** Troca com qual atributo a perícia soma (botão de lista suspensa). */
    public SheetData withPericiaAttribute(String pericia, Attribute attribute) {
        return mutatePericia(pericia, existing ->
                new Pericia(existing.name(), existing.value(), attribute));
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
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "hp" -> vitals.hp();
            case "hpmax" -> vitals.hpMax();
            case "mana" -> vitals.mana();
            case "manamax" -> vitals.manaMax();
            case "level" -> progress.level();
            case "xp" -> progress.xp();
            case "strength" -> attributes.strength();
            case "dexterity" -> attributes.dexterity();
            case "constitution" -> attributes.constitution();
            case "intelligence" -> attributes.intelligence();
            case "wisdom" -> attributes.wisdom();
            case "charisma" -> attributes.charisma();
            default -> 0;
        };
    }

    /** Rótulo amigável de um campo, para a UI. */
    public static String labelOf(String field) {
        if (field == null) {
            return "";
        }
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "charactername" -> "Name";
            case "race" -> "Race";
            case "characterclass" -> "Class";
            case "background" -> "Background";
            case "hp" -> "HP";
            case "hpmax" -> "Max HP";
            case "mana" -> "Mana";
            case "manamax" -> "Max Mana";
            case "level" -> "Level";
            case "xp" -> "XP";
            case "strength" -> "STR";
            case "dexterity" -> "DEX";
            case "constitution" -> "CON";
            case "intelligence" -> "INT";
            case "wisdom" -> "WIS";
            case "charisma" -> "CHA";
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
     * Garante que a ficha tenha <b>exatamente</b> a lista de perícias do
     * código, nem mais nem menos.
     *
     * <p><b>Esta é a regra que faz a lista ser fixa.</b> Ela roda em todo
     * construtor de {@code SheetData}, então o resultado vale para qualquer
     * origem da ficha — inclusive um payload de rede ou o NBT do jogador:
     * <ul>
     *   <li>Perícia da lista do código que veio com valor/atributo → mantida.</li>
     *   <li>Perícia da lista do código que <b>não</b> veio (ficha nova, ou o
     *       jogador ainda não abriu a tela) → entra no padrão do código.</li>
     *   <li>Perícia que <b>não</b> está na lista do código (payload adulterado,
     *       ou uma perícia que você removeu do código depois) → descartada.</li>
     * </ul>
     *
     * <p>Ou seja: é impossível uma ficha ter uma perícia a mais, a menos, ou com
     * um nome que o sistema não conhece.
     */
    private static List<Pericia> sanitizePericias(List<Pericia> raw) {
        List<Pericia> out = new ArrayList<>(PERICIAS_PADRAO.size());
        for (Pericia padrao : PERICIAS_PADRAO) {
            Pericia achada = null;
            if (raw != null) {
                // <b>Ordem de desempate (importante):</b> quando DOIS nomes
                // salvos casam com a mesma pericia do padrao -- caso de
                // "Diplomacy" e "Diplomacia", que viraram os dois "Persuasion"
                // -- vence o <b>primeiro na lista salva</b>, nao o primeiro
                // apelido. Na pratica isso favorece o nome em ingles, porque a
                // lista padrao de 26/09/2026 gravou "Diplomacy" antes de
                // qualquer traducao para portugues existir. Quem jogou nas duas
                // epocas e editou o valor em ingles mantem o valor; quem so
                // jogou na epoca antiga tambem. Perderia o valor so quem
                // ajustou "Diplomacy" em ingles E ainda tinha "Diplomacia" salvo
                // com outro numero. Isso e aceito, porque o padrao e nao
                // somar dois numeros diferentes na mesma celula.
                for (Pericia candidate : raw) {
                    if (candidate != null && matchesPericiaName(candidate.name(), padrao.name())) {
                        achada = candidate;
                        break;
                    }
                }
            }
            // O nome vem sempre do código, nunca da ficha: assim uma perícia
            // não consegue "renomear" a si mesma.
            out.add(achada == null
                    ? padrao
                    : new Pericia(padrao.name(), achada.value(), achada.attribute()));
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
