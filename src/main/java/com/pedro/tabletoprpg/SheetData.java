package com.pedro.tabletoprpg;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ficha do personagem de um jogador (FASE 3).
 *
 * <p>Estrutura agrupada em sub-records para manter cada
 * {@code StreamCodec.composite} com no máximo 6 campos (limite da API):
 * {@code identity} (3), {@code vitals} (4), {@code progress} (2),
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
 * <p><b>Exceção: atributos não têm faixa</b> (decisão do usuário). Eles viraram
 * modificadores somados às rolagens, podem ser negativos e o usuário pediu
 * "sem limitação" — ver {@link Attributes}. O único teto é o do próprio
 * {@code int} do {@code VAR_INT}, e a aritmética das rolagens usa {@code long}
 * para não estourar.
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
        List<Skill> skills
    ) {

    /** Teto de caracteres dos textos livres (nome, raça, classe). */
    public static final int MAX_NAME = 32;
    /** Teto de caracteres de cada habilidade. */
    public static final int SKILL_MAX = 48;
    /**
     * Teto de caracteres da descrição de cada habilidade. Aparece no tooltip
     * ao passar o mouse sobre o botão da skill. O teto existe porque
     * {@code ByteBufCodecs.stringUtf8(n)} lança exceção ao decodificar uma
     * string maior que {@code n}.
     */
    public static final int SKILL_DESC_MAX = 120;
    /** Número máximo de habilidades por ficha. */
    public static final int MAX_SKILLS = 24;
    /** Teto de nível. */
    public static final int MAX_LEVEL = 99;
    /**
     * <b>DESUSADO.</b> Era o teto dos atributos (0..30). O usuário pediu
     * atributos SEM limite e QUE PODEM SER NEGATIVOS (eles viraram
     * modificadores das rolagens de perícia), então o clamp foi removido.
     * A constante fica só para não quebrar referências antigas; não é usada.
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
    public static final List<String> TEXT_FIELDS = List.of("characterName", "race", "characterClass");
    /** Campos numéricos (editáveis). */
    public static final List<String> NUMERIC_FIELDS = List.of(
            "hp", "hpMax", "mana", "manaMax",
            "level", "xp",
            "strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma"
    );

    /**
     * Codec da ficha. Cada grupo tem seu próprio codec; a lista de habilidades
     * é limitada por item ({@code stringUtf8(SKILL_MAX)}) e o
     * {@code ByteBufCodecs.list()} já impõe um teto de quantidade.
     */
    public static final StreamCodec<FriendlyByteBuf, SheetData> STREAM_CODEC = StreamCodec.composite(
            Identity.STREAM_CODEC, SheetData::identity,
            Vitals.STREAM_CODEC, SheetData::vitals,
            Progress.STREAM_CODEC, SheetData::progress,
            Attributes.STREAM_CODEC, SheetData::attributes,
            Skill.STREAM_CODEC.apply(ByteBufCodecs.list()), SheetData::skills,
            SheetData::new
    );

    /** Normaliza nulos e a lista de habilidades. */
    public SheetData {
        identity = identity == null ? new Identity("", "", "") : identity;
        vitals = vitals == null ? Vitals.defaults() : vitals;
        progress = progress == null ? Progress.defaults() : progress;
        attributes = attributes == null ? Attributes.defaults() : attributes;
        skills = sanitizeSkills(skills);
    }

    // ------------------------------------------------------------------
    // SUB-RECORDS
    // ------------------------------------------------------------------

    /** Nome do personagem, raça e classe. Textos livres. */
    public record Identity(String characterName, String race, String characterClass) {
        public static final StreamCodec<FriendlyByteBuf, Identity> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(MAX_NAME), Identity::characterName,
                ByteBufCodecs.stringUtf8(MAX_NAME), Identity::race,
                ByteBufCodecs.stringUtf8(MAX_NAME), Identity::characterClass,
                Identity::new
        );

        public Identity {
            characterName = clean(characterName);
            race = clean(race);
            characterClass = clean(characterClass);
        }

        /** Cópia com o nome do personagem trocado. */
        public Identity withCharacterName(String value) {
            return new Identity(value, race, characterClass);
        }

        /** Cópia com a raça trocada. */
        public Identity withRace(String value) {
            return new Identity(characterName, value, characterClass);
        }

        /** Cópia com a classe trocada. */
        public Identity withCharacterClass(String value) {
            return new Identity(characterName, race, value);
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
         * <b>Sem clamp, por decisao do usuario</b> ("nao coloque limitacao"):
         * o atributo virou modificador das rolagens de pericia, entao pode ser
         * NEGATIVO e nao tem teto. O unico limite e o proprio int do
         * {@code VAR_INT}, que ja e seguro no protocolo; a aritmetica das
         * rolagens (ver {@code RpgSkillRoll}) usa {@code long} para um
         * atributo absurdo nao estourar o resultado.
         *
         * <p>Consequencia: o antigo {@code clampStat} (0..30) foi removido
         * daqui. Ele era a causa de "digito 32 e volta 30, e nao sai mais".
         */
        public Attributes {
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
        STRENGTH("strength", "FOR", "Forca"),
        DEXTERITY("dexterity", "DES", "Destreza"),
        CONSTITUTION("constitution", "CON", "Constituicao"),
        INTELLIGENCE("intelligence", "INT", "Inteligencia"),
        WISDOM("wisdom", "SAB", "Sabedoria"),
        CHARISMA("charisma", "CAR", "Carisma");

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
        /** Abreviacao de 3 letras mostrada nos botoes. */
        private final String abbr;
        /** Nome por extenso, usado no texto de ajuda. */
        private final String fullName;

        Attribute(String field, String abbr, String fullName) {
            this.field = field;
            this.abbr = abbr;
            this.fullName = fullName;
        }

        public String field() {
            return field;
        }

        public String abbr() {
            return abbr;
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
     * O que um {@code SheetSkillPayload} quer fazer com a pericia.
     *
     * <p>Existe um enum (e nao um {@code boolean add}) porque a tela de Skills
     * passou a ter tres acoes alem de criar/remover: as setas alteram so o
     * valor e o botao de lista suspensa altera so o atributo. Sem separar as
     * acoes, um clique de seta enviaria a descricao de volta e poderia
     * sobrescrever o que o usuario digitou.
     *
     * <p>O codec e escrito a mao em vez de usar ordinal direto porque um
     * cliente modificado pode mandar qualquer inteiro: o valor e validado e
     * cai em {@link #ADD} em vez de estourar {@code values()[i]}.
     */
    public enum SkillOp {
        /** Cria a pericia, ou atualiza todos os campos se o nome ja existir. */
        ADD,
        /** Remove a pericia pelo nome. */
        REMOVE,
        /** Muda so o valor (0-3). */
        SET_VALUE,
        /** Muda so o atributo que a pericia soma. */
        SET_ATTRIBUTE,
        /**
         * Valor invalido vindo da rede. <b>Nao fazer nada com ele.</b>
         *
         * <p>Existe para que um indice corrompido seja descartado: sem esta
         * opcao, o decode cairia em {@link #ADD}, e como {@code withSkill}
         * atualiza os 4 campos, um {@code SET_VALUE} adulterado viraria
         * "renomear, apagar a descricao, valor 0 e DES" -- corrupcao silenciosa
         * em vez de uma operacao ignorada.
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
     * Uma pericia da ficha: {@code name} + {@code description} + {@code value}
     * + {@code attribute}.
     *
     * <p><b>Por que um record e nao uma String solta:</b> o pedido do usuario
     * foi exibir a descricao ao passar o mouse sobre o botao da pericia, e
     * depois acrescentar valor (0-3) e o atributo que a pericia soma. Tudo isso
     * precisa viajar junto do nome no protocolo e sobreviver ao round-trip
     * servidor -&gt; cliente.
     *
     * <p>A descricao e opcional (pode ser vazia: a pericia aparece, so nao
     * abre tooltip).
     */
    public record Skill(String name, String description, int value, Attribute attribute) {
        public static final StreamCodec<FriendlyByteBuf, Skill> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(SKILL_MAX), Skill::name,
                ByteBufCodecs.stringUtf8(SKILL_DESC_MAX), Skill::description,
                ByteBufCodecs.VAR_INT, Skill::value,
                Attribute.STREAM_CODEC, Skill::attribute,
                Skill::new
        );

        /** Valor minimo e maximo da pericia (0 a 3, pedido do usuario). */
        public static final int VALUE_MIN = 0;
        public static final int VALUE_MAX = 3;

        public Skill {
            name = cleanSkill(name);
            description = cleanDescription(description);
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
     * As 4 pericias nomeadas que o usuario pediu explicitamente.
     *
     * <p>Valores e atributos informed pelo usuario:
     * Luta 2 + FOR, Acrobacia 3 + DES, Diplomacia 1 + CAR, Iniciativa 2 + DES.
     * <b>Iniciativa e a mais importante</b>: sera usada na FASE de iniciativa
     * para definir a ordem dos turnos no modo combate.
     */
    public static final List<Skill> NAMED_SKILLS = List.of(
            new Skill("Luta", "", 2, Attribute.STRENGTH),
            new Skill("Acrobacia", "", 3, Attribute.DEXTERITY),
            new Skill("Diplomacia", "", 1, Attribute.CHARISMA),
            new Skill("Iniciativa", "", 2, Attribute.DEXTERITY)
    );

    /** Quantas pericias "padrao" (perícia 0, perícia 1, ...) sao criadas. */
    public static final int DEFAULT_SKILL_COUNT = 16;

    /**
     * As 16 pericias padrao: nome "perícia 0" .. "perícia 15", todas com valor
     * 0 e com o atributo alternando entre os 6.
     *
     * <p><b>Decisoes do usuario:</b> os valores "todos vao comecar com 0" e o
     * atributo "alternando os 6 atributos" (FOR, DES, CON, INT, SAB, CAR,
     * repetindo). Como o valor comeca em 0, elas somam apenas o atributo ate
     * o mestre ajustar com as setas.
     *
     * <p><b>Numeracao 0-based:</b> o usuario escreveu "perícia 0, perícia 1,
     * perícia 2 e assim por diante". A versao antiga do .docx falava em
     * "perícia 1" ate "perícia 20"; prevalecceu a mensagem mais recente.
     */
    public static List<Skill> defaultSkills() {
        List<Skill> out = new ArrayList<>(NAMED_SKILLS);
        List<Attribute> rotation = Attribute.VALUES;
        for (int i = 0; i < DEFAULT_SKILL_COUNT; i++) {
            out.add(new Skill("perícia " + i, "", 0, rotation.get(i % rotation.size())));
        }
        return List.copyOf(out);
    }

    /** Ficha inicial de um jogador: nome = nome da conta, resto no padrão. */
    public static SheetData defaultSheet(String playerName) {
        return new SheetData(
                new Identity(clean(playerName), "", ""),
                Vitals.defaults(),
                Progress.defaults(),
                Attributes.defaults(),
                defaultSkills()
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
            case "charactername" -> new SheetData(identity.withCharacterName(value), vitals, progress, attributes, skills);
            case "race" -> new SheetData(identity.withRace(value), vitals, progress, attributes, skills);
            case "characterclass" -> new SheetData(identity.withCharacterClass(value), vitals, progress, attributes, skills);

            case "hp" -> replaceVitals(new Vitals(parseInt(value, vitals.hp()), vitals.hpMax(), vitals.mana(), vitals.manaMax()));
            case "hpmax" -> replaceVitals(new Vitals(vitals.hp(), parseInt(value, vitals.hpMax()), vitals.mana(), vitals.manaMax()));
            case "mana" -> replaceVitals(new Vitals(vitals.hp(), vitals.hpMax(), parseInt(value, vitals.mana()), vitals.manaMax()));
            case "manamax" -> replaceVitals(new Vitals(vitals.hp(), vitals.hpMax(), vitals.mana(), parseInt(value, vitals.manaMax())));

            case "level" -> new SheetData(identity, vitals, new Progress(parseInt(value, progress.level()), progress.xp()), attributes, skills);
            case "xp" -> new SheetData(identity, vitals, new Progress(progress.level(), parseInt(value, progress.xp())), attributes, skills);

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
        return new SheetData(identity, newVitals, progress, attributes, skills);
    }

    private SheetData replaceAttributes(Attributes newAttributes) {
        return new SheetData(identity, vitals, progress, newAttributes, skills);
    }

    /**
     * Adiciona uma pericia com descricao, valor e atributo.
     *
     * <p>Se o nome JA existe, os demais campos sao ATUALIZADOS no lugar
     * (preservando a posicao) em vez de a entrada ser ignorada.
     * <b>Decisao:</b> sem isso o mestre nao teria como corrigir uma
     * descricao, um valor ou um atributo errado -- so poderia remover e digitar
     * tudo de novo. Nomes duplicados com outra capitalizacao continuam casando.
     */
    public SheetData withSkill(String skill, String description, int value, Attribute attribute) {
        String cleanName = cleanSkill(skill);
        String cleanDesc = cleanDescription(description);
        if (cleanName.isEmpty()) {
            return this;
        }
        List<Skill> next = new ArrayList<>(skills.size() + 1);
        boolean updated = false;
        for (Skill existing : skills) {
            if (existing.name().equalsIgnoreCase(cleanName)) {
                next.add(new Skill(existing.name(), cleanDesc, value, attribute));
                updated = true;
            } else {
                next.add(existing);
            }
        }
        if (updated) {
            return new SheetData(identity, vitals, progress, attributes, next);
        }
        if (skills.size() >= MAX_SKILLS) {
            return this; // lista cheia
        }
        next.add(new Skill(cleanName, cleanDesc, value, attribute));
        return new SheetData(identity, vitals, progress, attributes, next);
    }

    /**
     * Altera UM campo de uma pericia ja existente, sem mexer nos outros.
     *
     * <p>Usado pelas setas de valor e pelo botao de atributo da tela de Skills:
     * mexer so no que o usuario tocou evita que um clique de seta apague a
     * descricao. Se a pericia nao existir, devolve a ficha intacta.
     */
    public SheetData withSkillValue(String skill, int value) {
        return mutateSkill(skill, existing ->
                new Skill(existing.name(), existing.description(), value, existing.attribute()));
    }

    /** Troca com qual atributo a pericia soma (botao de lista suspensa). */
    public SheetData withSkillAttribute(String skill, Attribute attribute) {
        return mutateSkill(skill, existing ->
                new Skill(existing.name(), existing.description(), existing.value(), attribute));
    }

    private SheetData mutateSkill(String skill, java.util.function.UnaryOperator<Skill> mutator) {
        String clean = cleanSkill(skill);
        if (clean.isEmpty()) {
            return this;
        }
        List<Skill> next = new ArrayList<>(skills.size());
        boolean changed = false;
        for (Skill existing : skills) {
            if (existing.name().equalsIgnoreCase(clean)) {
                Skill updated = mutator.apply(existing);
                next.add(updated);
                changed = changed || !updated.equals(existing);
            } else {
                next.add(existing);
            }
        }
        return changed ? new SheetData(identity, vitals, progress, attributes, next) : this;
    }

    /** Remove uma habilidade pelo nome (comparacao sem diferenciar maiusculas). */
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
        return removed ? new SheetData(identity, vitals, progress, attributes, next) : this;
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
            // O construtor do record normaliza nome/descricao/valor/atributo de
            // novo: a lista pode vir de uma fonte externa (payload) sem passar
            // por withSkill.
            Skill clean = new Skill(skill.name(), skill.description(), skill.value(), skill.attribute());
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
