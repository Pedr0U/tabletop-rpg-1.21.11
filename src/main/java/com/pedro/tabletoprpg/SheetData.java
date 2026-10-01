package com.pedro.tabletoprpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Ficha do personagem de um jogador (FASE 3).
 *
 * <p>Estrutura agrupada em sub-records para manter cada
 * {@code StreamCodec.composite} com no máximo 6 campos (limite da API):
     * {@code identity} (7), {@code vitals} (4), {@code progress} (2),
 * {@code attributes} (6) e {@code skills} (lista).
 *
 * <p><b>Invariante de faixa:</b> os construtores compactos limitam o que
 * <b>precisa</b> ser limitado - vida (com piso {@link #MAX_HP_FLOOR} e teto
 * {@link #MAX_RESOURCE}), mana, nível, textos (nome/descrição), o valor do
 * atributo e o valor da perícia. A validação acontece <b>por construção</b> e
 * não depende do chamador lembrar de clamp. O servidor continua sendo a
 * autoridade (a edição chega por payload e é convertida com
 * {@link #withField}), mas mesmo um cliente malicioso não consegue gravar um
 * valor fora de faixa.
 *
 * <p><b>O limite de atributo e de pericia vem do Mestre (28/09/2026).</b> Sao
 * os tres {@code int} do fim do record, que o {@link SheetModel} preenche: piso
 * e teto do atributo, teto da pericia (padrao -30, 30 e 30). Eles moram na
 * ficha, e nao so no modelo, para que qualquer caminho que monte uma ficha ja
 * saia limitado (inclusive a leitura do NBT) e para que a tela e o servidor
 * leiam o mesmo numero sem consultar o modelo. <b>O clamp autoritativo e o
 * construtor compacto desta classe</b>, e nao cada call site: e ele que corta o
 * valor do atributo no intervalo {@code [attributeValueMin, attributeValueMax]}
 * e o da pericia em {@code [0, periciaValueMax]}. O piso 0 da pericia continua
 * fixo: o valor dela e o <b>investimento</b> do jogador e o que <b>soma</b> na
 * rolagem ({@code 1d20 + valor + atributo}), entao negativo ali significaria
 * penalidade, e penalidade pertence ao atributo, que aceita negativo. Os limites
 * internos de {@link Attributes} e {@link Pericia} sao apenas um <b>teto
 * absoluto largo</b> (rede de seguranca do protocolo, como
 * {@link #MAX_RESOURCE}); a regra e do modelo. A aritmetica das rolagens usa
 * {@code long} para nao estourar.
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
 *
 * <p><b>30/09/2026 (FASE 2B): o inventario e o ultimo componente do record</b>,
 * depois de {@code periciaValueMax} e fora dos seis grupos do {@link
 * #STREAM_CODEC}: ele entra no {@link #ENCODER} e no {@link #DECODER} na
 * <b>mesma posicao</b> (a ultima), porque as duas lambdas nao se amarram em
 * tempo de compilacao. O peso e <b>visual</b>: {@link Inventory#overweight()}
 * so pinta a linha de vermelho, e o excesso nunca bloqueia a gravacao.
 */
public record SheetData(
        Identity identity,
        Vitals vitals,
        Progress progress,
        Attributes attributes,
        List<Skill> skills,
        List<Pericia> pericias,
        int attributeValueMin,
        int attributeValueMax,
        int periciaValueMax,
        Inventory inventory,
        Spellbook spellbook
    ) {

    /** Teto de caracteres dos textos livres (nome, raça, classe). */
    public static final int MAX_NAME = 32;
    /**
     * Teto de caracteres dos textos longos (aparência e história do personagem).
     *
     * <p><b>30/09/2026:</b> os dois quadros grandes da aba {@code Info/Inventory}.
     * O teto e separado do {@link #MAX_NAME} porque sao coisas diferentes: 32 e
     * o nome do personagem, que precisa caber num rotulo; 2.000 e um texto livre
     * que o jogador rola dentro da caixa. Se os dois usassem {@code MAX_NAME}, o
     * Appearance seria cortado em 32 pelo servidor (ver {@link Identity}) e a
     * rolagem mostraria sempre o mesmo comeco.
     */
    public static final int MAX_TEXT = 2_000;
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

    /**
     * Campos de texto livre (editaveis), mais o XP quando o modelo esta em modo
     * TEXT.
     *
     * <p><b>27/09/2026:</b> "xptext" entrou aqui porque e assim que a ficha
     * decide ler um campo por {@link #getText} em vez de {@link #getNumeric}
     * (ver {@code CharacterSheetScreen.applySheetToWidgets}). Sem ele, a caixa
     * do XP em modo TEXT recebia {@code getNumeric("xptext")}, que caia no
     * ramo de atributo e devolvia {@code 0}: o Mestre via a caixa vazia depois
     * de digitar. Esta lista <b>nao</b> e usada em NBT, codec nem migracao - o
     * texto do XP ja e gravado em {@code Progress.xpText}, e a edicao dele ja
     * tinha caso proprio em {@link #withField}.
     */
    public static final List<String> TEXT_FIELDS =
            List.of("playerName", "characterName", "race", "characterClass", "background",
                    "appearance", "backstory", "xptext");
    /**
     * Campos com rotulo <b>desenhado na ficha</b>: os de texto, os numericos
     * com rotulo proprio e o XP em modo TEXT.
     *
     * <p><b>27/09/2026:</b> e a lista que a tela de Status usa para reservar a
     * largura do rotulo ({@code StatusScreen.fieldLabelWidth}). Antes ela
     * aceitava {@link #TEXT_FIELDS} e uma lista fixa de rotulos literais, o
     * que deixava de fora o {@code "xptext"} e qualquer rotulo que o Mestre
     * tivesse renomeado no editor. {@code LABELLED_NUMERIC_FIELDS} do
     * {@link SheetModel} e a lista dos numericos com rotulo; aqui estao todos.
     */
    public static final List<String> LABELLED_FIELDS = List.of(
            "playerName", "characterName", "race", "characterClass", "background",
            "hp", "mana", "level", "xp", "xptext", "ca"
    );
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

    private static final StreamCodec<FriendlyByteBuf, List<Skill>> SKILLS_STREAM_CODEC =
            Skill.STREAM_CODEC.apply(ByteBufCodecs.list());
    private static final StreamCodec<FriendlyByteBuf, List<Pericia>> PERICIAS_STREAM_CODEC =
            Pericia.STREAM_CODEC.apply(ByteBufCodecs.list());
    private static final StreamCodec<FriendlyByteBuf, List<Spell>> SPELLS_STREAM_CODEC =
            Spell.STREAM_CODEC.apply(ByteBufCodecs.list());

    /**
     * Codec da ficha. Cada grupo tem seu próprio codec; a lista de skills é
     * limitada por item ({@code stringUtf8(SKILL_MAX)}) e o
     * {@code ByteBufCodecs.list()} já impõe um teto de quantidade.
     *
     * <p>São 6 grupos, que é exatamente o limite de {@link
     * StreamCodec#composite} - por isso {@code skills} e {@code pericias} são
     * listas de records separados em vez de um record único.
     *
     * <p><b>28/09/2026: o {@code composite} foi trocado por encoder e decoder
     * proprios.</b> Os tres limites de valor do Mestre (piso e teto do atributo,
     * teto da pericia) levaram o record a 9 campos, e o
     * {@link StreamCodec#composite} para em 6: nao havia como acrescenta-los
     * sem inventar mais um sub-record so para carregar tres inteiros. O
     * {@code StreamCodec.of} nao tem esse teto, e e o mesmo caminho que o
     * {@link SheetModel} ja usa (ver o Javadoc do {@code ENCODER} dele).
     *
     * <p><b>A ORDEM DO ENCODER TEM DE SER IGUAL A DO DECODER.</b> Os dois sao
     * lambdas independentes e nada os amarra em tempo de compilacao: acrescentar
     * um campo so num dos lados continua compilando e falha so em runtime. O
     * round-trip de {@code SheetModelCodecTest} e o que pega esse erro.
     */
    private static final StreamEncoder<FriendlyByteBuf, SheetData> ENCODER = (buf, sheet) -> {
        Identity.STREAM_CODEC.encode(buf, sheet.identity());
        Vitals.STREAM_CODEC.encode(buf, sheet.vitals());
        Progress.STREAM_CODEC.encode(buf, sheet.progress());
        Attributes.STREAM_CODEC.encode(buf, sheet.attributes());
        SKILLS_STREAM_CODEC.encode(buf, sheet.skills());
        PERICIAS_STREAM_CODEC.encode(buf, sheet.pericias());
        // Os tres limites por ultimo, como no record. Sem eles no pacote, o
        // cliente receberia a ficha com o padrao do record e as setas de +/- do
        // Status desligariam no limite errado quando o Mestre configurasse outro.
        ByteBufCodecs.VAR_INT.encode(buf, sheet.attributeValueMin());
        ByteBufCodecs.VAR_INT.encode(buf, sheet.attributeValueMax());
        ByteBufCodecs.VAR_INT.encode(buf, sheet.periciaValueMax());
        // 30/09/2026 (FASE 2B): o inventario vai por ultimo, como no record, e
        // na mesma posicao do DECODER abaixo. Ler e escrever em ordem diferente
        // continua compilando e quebra so em runtime -- e o round-trip de
        // SheetModelCodecTest que pega isso.
        Inventory.STREAM_CODEC.encode(buf, sheet.inventory());
        // 01/10/2026 (pagina 3 da ficha): o grimorio entra por ultimo, como no
        // record, e na MESMA posicao do DECODER abaixo. Mesmo alerta do
        // inventario: ler e escrever em ordem diferente continua compilando e
        // quebra so em runtime, e o round-trip do SheetModelCodecTest e o que pega.
        Spellbook.STREAM_CODEC.encode(buf, sheet.spellbook());
    };

    private static final StreamDecoder<FriendlyByteBuf, SheetData> DECODER = buf -> new SheetData(
            Identity.STREAM_CODEC.decode(buf),
            Vitals.STREAM_CODEC.decode(buf),
            Progress.STREAM_CODEC.decode(buf),
            Attributes.STREAM_CODEC.decode(buf),
            SKILLS_STREAM_CODEC.decode(buf),
            PERICIAS_STREAM_CODEC.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            Inventory.STREAM_CODEC.decode(buf),
            Spellbook.STREAM_CODEC.decode(buf)
    );

    public static final StreamCodec<FriendlyByteBuf, SheetData> STREAM_CODEC =
            StreamCodec.of(ENCODER, DECODER);

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
            Codec.STRING.optionalFieldOf("background", "").forGetter(Identity::background),
            // 29/09/2026: nome do jogador dono da ficha, pedido do usuario.
            // Opcional com "" para uma ficha de mundo ja salva continuar abrindo.
            Codec.STRING.optionalFieldOf("playerName", "").forGetter(Identity::playerName),
            // 30/09/2026: os dois textos longos da aba Info/Inventory. O padrao ""
            // e obrigatorio pelo mesmo motivo do playerName acima: sao chaves que
            // NAO existem em nenhuma ficha ja salva, e sem o padrao o
            // RecordCodecBuilder derrubaria o NBT inteiro no primeiro mundo que
            // abrir o log com uma ficha de antes desta mudanca.
            Codec.STRING.optionalFieldOf("appearance", "").forGetter(Identity::appearance),
            Codec.STRING.optionalFieldOf("backstory", "").forGetter(Identity::backstory)
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
            Codec.STRING.optionalFieldOf("xpText", "").forGetter(Progress::xpText),
            // 01/10/2026: o CA entra como optionalFieldOf com padrao 0, entao uma
            // ficha salva ANTES do CA existir continua abrindo -- e abre com CA 0,
            // que e o mesmo que uma ficha recem-criada.
            Codec.INT.optionalFieldOf("ca", 0).forGetter(Progress::ca)
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

    /**
     * Codec de NBT de UMA pericia: {@code {id, name, value, attribute}}.
     *
     * <p><b>28/09/2026 - por que o {@code id} e {@code optionalFieldOf} e nao
     * {@code fieldOf} (e por que aqui e diferente do {@code id} do atributo):</b>
     * o {@code fieldOf} obrigatorio existe em
     * {@link Attributes.AttributeValue#CODEC} porque ele e a <b>chave de
     * selecao</b> da migracao dos seis inteiros soltos: sem ele, um save antigo
     * seria lido com sucesso e a migracao nunca rodaria (ver o
     * {@link #ATTRIBUTES_CODEC} e o porque acima dele). Aqui nao existe migracao
     * alternativa para a lista de pericias, e um {@code fieldOf} seria
     * <b>pior</b>: uma lista antiga sem {@code id} derrubaria o
     * {@code Codec.list} inteiro, o erro subiria para {@link #CODEC} e o
     * jogador perderia a <b>ficha completa</b> - vida, mana, nivel, atributos e
     * skills - e nao so as pericias. Como opcional, o id chega vazio e
     * {@link #sanitizePericias} o preenche, que e o mesmo caminho do
     * preenchimento por posicao usado por qualquer outra lista deste arquivo.
     */
    private static final Codec<Pericia> PERICIA_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("id", "").forGetter(Pericia::id),
            Codec.STRING.optionalFieldOf("name", "").forGetter(Pericia::name),
            Codec.INT.optionalFieldOf("value", 0).forGetter(Pericia::value),
            Codec.STRING.optionalFieldOf("attribute", "").forGetter(Pericia::attributeId)
    ).apply(i, Pericia::new));

    /**
     * Codec de NBT de UM item do inventario (30/09/2026, FASE 2B).
     *
     * <p>Todos os campos opcionais: e a mesma regra dos outros grupos deste
     * arquivo (ver o {@link #CODEC}) -- um item gravado por outra versao, ou
     * editado a mao, nao pode derrubar a ficha inteira. O construtor compacto
     * de {@link InventoryItem} corta o que passou do teto depois.
     */
    private static final Codec<InventoryItem> INVENTORY_ITEM_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("name", "").forGetter(InventoryItem::name),
            Codec.STRING.optionalFieldOf("type", "").forGetter(InventoryItem::type),
            Codec.FLOAT.optionalFieldOf("weight", 0f).forGetter(InventoryItem::weight),
            Codec.STRING.optionalFieldOf("description", "").forGetter(InventoryItem::description)
    ).apply(i, InventoryItem::new));

    /**
     * Codec de NBT do inventario inteiro (30/09/2026, FASE 2B).
     *
     * <p>A lista de itens e opcional com o padrao vazio e o {@code maxWeight}
     * opcional com {@code 0}: sao chaves que <b>nao existem em nenhuma ficha ja
     * salva</b>, e sem o padrao o {@link #CODEC} derrubaria o NBT inteiro no
     * primeiro mundo que abrir o log com uma ficha de antes desta fase.
     */
    private static final Codec<Inventory> INVENTORY_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.list(INVENTORY_ITEM_CODEC).optionalFieldOf("items", List.of()).forGetter(Inventory::items),
            Codec.FLOAT.optionalFieldOf("maxWeight", 0f).forGetter(Inventory::maxWeight)
    ).apply(i, Inventory::new));

    /**
     * Codec de NBT de UMA magia (01/10/2026, pagina 3 da ficha).
     *
     * <p>Mesmo desenho do {@link #INVENTORY_ITEM_CODEC}: tudo opcional com padrao,
     * e o construtor compacto do {@link Spell} corta o que passou do teto depois.
     * O {@code circle} e {@code optionalFieldOf} com padrao {@code 1} porque e a
     * chave de ordenacao da lista (ver {@link Spellbook#sorted()}): uma magia
     * gravada sem ele tem de cair no 1o circulo, nao ser descartada.
     */
    private static final Codec<Spell> SPELL_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("name", "").forGetter(Spell::name),
            Codec.INT.optionalFieldOf("circle", 1).forGetter(Spell::circle),
            Codec.STRING.optionalFieldOf("execution", "").forGetter(Spell::execution),
            Codec.STRING.optionalFieldOf("range", "").forGetter(Spell::range),
            Codec.STRING.optionalFieldOf("target", "").forGetter(Spell::target),
            Codec.STRING.optionalFieldOf("duration", "").forGetter(Spell::duration),
            Codec.STRING.optionalFieldOf("cost", "").forGetter(Spell::cost)
    ).apply(i, Spell::new));

    /**
     * Codec de NBT do grimorio inteiro (01/10/2026, pagina 3 da ficha).
     *
     * <p>Sao tres chaves que <b>nao existem em nenhuma ficha ja salva</b>, entao
     * todas precisam de padrao: sem ele o {@link #CODEC} derrubaria o NBT inteiro
     * no primeiro mundo que abrir o log com uma ficha de antes da pagina 3.
     * O padrao e escrito aqui em vez de lido de {@link Spellbook#EMPTY}, pelo
     * mesmo motivo do inventario (ver o comentario do {@link #CODEC}).
     */
    private static final Codec<Spellbook> SPELLBOOK_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.list(SPELL_CODEC).optionalFieldOf("spells", List.of()).forGetter(Spellbook::spells),
            Codec.STRING.optionalFieldOf("castingAttribute", "").forGetter(Spellbook::castingAttribute),
            Codec.INT.optionalFieldOf("cd", 0).forGetter(Spellbook::cd)
    ).apply(i, Spellbook::new));

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
            IDENTITY_CODEC.optionalFieldOf("identity", new Identity("", "", "", "", "", "", "")).forGetter(SheetData::identity),
            VITALS_CODEC.optionalFieldOf("vitals", Vitals.defaults()).forGetter(SheetData::vitals),
            PROGRESS_CODEC.optionalFieldOf("progress", Progress.defaults()).forGetter(SheetData::progress),
            ATTRIBUTES_CODEC.optionalFieldOf("attributes", Attributes.defaults()).forGetter(SheetData::attributes),
            Codec.list(SKILL_CODEC).optionalFieldOf("skills", List.of()).forGetter(SheetData::skills),
            Codec.list(PERICIA_CODEC).optionalFieldOf("pericias", List.of()).forGetter(SheetData::pericias),
            // Os tres limites de valor (28/09/2026). Seguem a mesma regra dos
            // outros campos: opcionais, com o padrao do SheetModel quando
            // ausentes, para que um save de antes desta mudanca abra igual. Um
            // save antigo nasce com -30/30/30 e e re-limitado pelo
            // SheetModel.align assim que o Mestre salva o modelo.
            Codec.INT.optionalFieldOf("attributeValueMin", SheetModel.DEFAULT_ATTRIBUTE_VALUE_MIN).forGetter(SheetData::attributeValueMin),
            Codec.INT.optionalFieldOf("attributeValueMax", SheetModel.DEFAULT_ATTRIBUTE_VALUE_MAX).forGetter(SheetData::attributeValueMax),
            Codec.INT.optionalFieldOf("periciaValueMax", SheetModel.DEFAULT_PERICIA_VALUE_MAX).forGetter(SheetData::periciaValueMax),
            // 30/09/2026 (FASE 2B): o inventario e o setimo grupo deste codec.
            // O RecordCodecBuilder aceita mais de 6 grupos (o limite de 6 e do
            // StreamCodec.composite, e o topo da ficha ja usa encoder/decoder
            // proprios), entao ele entra aqui como um grupo so, opcional: um
            // save anterior a Fase 2B abre com Inventory.EMPTY, nunca quebrado.
            // O padrao e construido aqui em vez de lido de Inventory.EMPTY: um
            // save antigo pode chegar enquanto o <clinit> de Inventory ainda
            // roda (InventoryItem abre a ficha ao montar o EMPTY dele), e nesse
            // instante o EMPTY ainda vale null -- o que faria este CODEC devolver
            // null no lugar de um inventario vazio e derrubar o login.
            INVENTORY_CODEC.optionalFieldOf("inventory", new Inventory(List.of(), 0f))
                    .forGetter(SheetData::inventory),
            // 01/10/2026 (pagina 3 da ficha): o grimorio e o ultimo grupo deste
            // codec, pela mesma raza do inventario. Um save anterior a pagina 3
            // abre com Spellbook vazio, nunca quebrado.
            SPELLBOOK_CODEC.optionalFieldOf("spellbook", new Spellbook(List.of(), "", 0))
                    .forGetter(SheetData::spellbook)
    ).apply(i, SheetData::new));

    /**
     * Normaliza nulos, a lista de skills, a lista de perícias e <b>corta os
     * valores no intervalo do Mestre</b>.
     *
     * <p><b>28/09/2026 - o clamp autoritativo do limite de valor mora AQUI, e
     * nao em cada call site.</b> O piso/teto do atributo e o teto da pericia
     * sao os tres {@code int} do fim do record, e este construtor e o unico lugar
     * que os le para cortar. E por isso que eles acompanham a ficha: com o clamp
     * aqui, {@link #withField}, {@link #withPericiaValue},
     * {@link #mutatePericia}, {@link SheetModel#align} e a leitura do NBT ficam
     * limitados de uma vez so, sem ninguem ter de lembrar de limitar.
     *
     * <p><b>Por que os limites sao saneados tambem:</b> eles chegam de um NBT
     * editado a mao e de um pacote de rede, entao o mesmo teto absoluto do
     * {@link SheetModel} se aplica ({@code -999..999} no atributo, {@code 0..999}
     * na pericia), e um teto abaixo do piso vira o proprio piso — sem isso, o
     * intervalo viraria vazio e o clamp de baixo cortaria <b>todos</b> os valores
     * para o piso, ate os que estavam certos.
     */
    public SheetData {
        identity = identity == null ? new Identity("", "", "", "", "", "", "") : identity;
        vitals = vitals == null ? Vitals.defaults() : vitals;
        progress = progress == null ? Progress.defaults() : progress;
        attributeValueMin = clamp(attributeValueMin, SheetModel.VALUE_LIMIT_MIN, SheetModel.VALUE_LIMIT_MAX);
        attributeValueMax = clamp(attributeValueMax, SheetModel.VALUE_LIMIT_MIN, SheetModel.VALUE_LIMIT_MAX);
        if (attributeValueMax < attributeValueMin) {
            attributeValueMax = attributeValueMin;
        }
        periciaValueMax = clamp(periciaValueMax, 0, SheetModel.VALUE_LIMIT_MAX);
        attributes = attributes == null ? Attributes.defaults() : attributes;
        skills = sanitizeSkills(skills);
        pericias = sanitizePericias(pericias);
        attributes = clampAttributes(attributes, attributeValueMin, attributeValueMax);
        pericias = clampPericiaValues(pericias, periciaValueMax);
        // 30/09/2026 (FASE 2B): o inventario entra por ultimo, normalizado pelo
        // construtor de Inventory, que corta a lista em Inventory.MAX_ITEMS e o
        // maxWeight em [0, WEIGHT_MAX].
        inventory = inventory == null ? Inventory.EMPTY : inventory;
        // 01/10/2026 (pagina 3): o grimorio entra por ultimo. O
        // castingAttribute passa por cleanId e o cd e cortado em
        // [Spellbook.CD_MIN, Spellbook.CD_MAX]; a lista e normalizada pelo
        // construtor de Spellbook.
        spellbook = spellbook == null ? new Spellbook(List.of(), "", 0) : spellbook;
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
     * fora, e um record de 5 campos cabe folgadamente.
     *
     * <p>Ordem importa no codec de rede ({@code StreamCodec.composite} é
     * posicional) mas nao no de NBT, que é por nome. <b>Não há negociação de
     * versão entre cliente e servidor</b>, então acrescentar o 4o campo quebra
     * cliente antigo de qualquer jeito: ele leria a 4a string como se fosse o
     * próximo campo do record. Por isso {@code background} foi posto no fim
     * apenas por convencao de leitura - a posição é indiferente para a
     * segurança, e a compatibilidade real vem de o mod ir junto com o cliente.
     *
     * <p><b>{@code playerName} (29/09/2026):</b> quinto campo, pedido
     * do usuario como "campo Player na frente de Identity, com uma caixinha
     * pequena para o nome do dono da ficha". Ele e <b>independente</b> do
     * {@code characterName}: aquele e o nome do personagem, este e o nome de
     * quem joga. Texto vazio e <b>legitimo</b> (caixa vazia = nao preenchido),
     * e por isso ele passa pelo {@link SheetData#clean(String)} de um argumento,
     * que devolve {@code ""} em vez de um fallback.
     *
     * <p><b>{@code appearance} e {@code backstory} (30/09/2026):</b> sexto e
     * setimo campo, os dois quadros de texto livre com rolagem da aba
     * {@code Info/Inventory}. <b>Por que entram aqui e nao como componentes
     * novos do {@link SheetData}:</b> {@code Identity} ja e o bloco de texto do
     * personagem e ja esta no NBT com {@code optionalFieldOf} por chave, entao
     * os dois campos novos nao mexem no {@link SheetData#CODEC} (que tem ordem
     * sensivel e ~8 call sites de construtor dentro do {@link #withField}) nem
     * no {@link SheetData#STREAM_CODEC}. O preco e um {@link
     * #STREAM_CODEC} deste record com 7 campos, que e o motivo de ele ter sido
     * trocado por {@link StreamCodec#of} logo abaixo.
     *
     * <p>Os dois tetos sao DIFERENTES e nao se cruzam: os cinco campos antigos
     * continuam em {@link #MAX_NAME} (32) e estes dois em {@link #MAX_TEXT}
     * (2.000). E o construtor compacto que aplica o teto de cada um, entao o
     * Appearance nao volta cortado em 32 pelo servidor -- o que aconteceria se os
     * dois usassem o mesmo {@code clean(String)}.
     */
    public record Identity(String characterName, String race, String characterClass, String background,
                           String playerName, String appearance, String backstory) {
        /**
         * <b>Por que {@link StreamCodec#of} e nao {@code composite} (30/09/2026):</b>
         * o record foi de 5 para 7 campos. O {@code composite} tem sobrecarga de 7
         * pares (conferido com {@code javap} no jar do 1.21.11), entao nao era
         * obrigatorio trocar -- mas o {@code of} deixa as duas listas de campos
         * visiveis lado a lado, sem depender de o numero caber numa sobrecarga, e
         * ja e o caminho do {@link SheetData#STREAM_CODEC} (ver o Javadoc do
         * {@code ENCODER} de la).
         *
         * <p><b>A ORDEM DO ENCODER TEM DE SER IGUAL A DO DECODER</b> (7 linhas
         * em cada lado, nesta ordem: characterName, race, characterClass,
         * background, playerName, appearance, backstory). Os dois sao lambdas
         * independentes e nada os amarra em tempo de compilacao: um campo so num
         * dos lados continua compilando e falha so em runtime, com o dado
         * trocado. O {@code Identity} de um cliente antigo nao teria o 6o e o 7o
         * campo, mas nao ha como evitar: o mod vai junto com o cliente.
         */
        public static final StreamCodec<FriendlyByteBuf, Identity> STREAM_CODEC = StreamCodec.of(
                (buf, id) -> {
                    ByteBufCodecs.stringUtf8(MAX_NAME).encode(buf, id.characterName());
                    ByteBufCodecs.stringUtf8(MAX_NAME).encode(buf, id.race());
                    ByteBufCodecs.stringUtf8(MAX_NAME).encode(buf, id.characterClass());
                    ByteBufCodecs.stringUtf8(MAX_NAME).encode(buf, id.background());
                    ByteBufCodecs.stringUtf8(MAX_NAME).encode(buf, id.playerName());
                    ByteBufCodecs.stringUtf8(MAX_TEXT).encode(buf, id.appearance());
                    ByteBufCodecs.stringUtf8(MAX_TEXT).encode(buf, id.backstory());
                },
                buf -> new Identity(
                        ByteBufCodecs.stringUtf8(MAX_NAME).decode(buf),
                        ByteBufCodecs.stringUtf8(MAX_NAME).decode(buf),
                        ByteBufCodecs.stringUtf8(MAX_NAME).decode(buf),
                        ByteBufCodecs.stringUtf8(MAX_NAME).decode(buf),
                        ByteBufCodecs.stringUtf8(MAX_NAME).decode(buf),
                        ByteBufCodecs.stringUtf8(MAX_TEXT).decode(buf),
                        ByteBufCodecs.stringUtf8(MAX_TEXT).decode(buf)
                )
        );

        public Identity {
            characterName = clean(characterName);
            race = clean(race);
            characterClass = clean(characterClass);
            background = clean(background);
            // clean(String) de um argumento: trim + teto MAX_NAME, e devolve ""
            // para null/"" em vez de cair em um fallback.
            playerName = clean(playerName);
            // Os dois textos longos NAO podem usar o clean de cima: ele corta em
            // MAX_NAME (32) e o Appearance seria truncado pelo servidor sempre no
            // mesmo ponto. cleanText e o mesmo trim com o teto MAX_TEXT.
            appearance = cleanText(appearance);
            backstory = cleanText(backstory);
        }

        /** Cópia com o nome do personagem trocado. */
        public Identity withCharacterName(String value) {
            return new Identity(value, race, characterClass, background, playerName, appearance, backstory);
        }

        /** Cópia com a raça trocada. */
        public Identity withRace(String value) {
            return new Identity(characterName, value, characterClass, background, playerName, appearance, backstory);
        }

        /** Cópia com a classe trocada. */
        public Identity withCharacterClass(String value) {
            return new Identity(characterName, race, value, background, playerName, appearance, backstory);
        }

        /** Cópia com a origem trocada. */
        public Identity withBackground(String value) {
            return new Identity(characterName, race, characterClass, value, playerName, appearance, backstory);
        }

        /** Cópia com o nome do jogador dono da ficha trocado. */
        public Identity withPlayerName(String value) {
            return new Identity(characterName, race, characterClass, background, value, appearance, backstory);
        }

        /** Cópia com a aparência do personagem trocada. */
        public Identity withAppearance(String value) {
            return new Identity(characterName, race, characterClass, background, playerName, value, backstory);
        }

        /** Cópia com a história do personagem trocada. */
        public Identity withBackstory(String value) {
            return new Identity(characterName, race, characterClass, background, playerName, appearance, value);
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
    /**
     * O progresso do personagem: nivel, XP e o CA (Classe de Armadura).
     *
     * <p><b>01/10/2026, o campo {@code ca}:</b> o usuario pediu um campo CA na
     * mesma linha do Level, com caixa numerica propria. Ele mora AQUI e nao na
     * {@link Attributes} porque ele nao e um atributo: nao entra em rolagem, nao
     * tem piso/teto do Mestre e nao e uma das pericias. E um valor absoluto do
     * personagem, como o Level e o XP.
     *
     * <p><b>Por que o limite e {@code 0..MAX_RESOURCE}:</b> e o mesmo teto que o
     * HP e a Mana ja usam ({@link #MAX_RESOURCE}), por decision do usuario. O
     * piso 0 significa que CA nao aceita negativo: um CA abaixo de zero nao tem
     * leitura em regra de RPG, e o campo serve para mostrar a classe de armadura,
     * nao um modificador (modificadores vivem nos atributos, que vao a -30).
     */
    public record Progress(int level, int xp, String xpText, int ca) {
        public static final StreamCodec<FriendlyByteBuf, Progress> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Progress::level,
                ByteBufCodecs.VAR_INT, Progress::xp,
                ByteBufCodecs.stringUtf8(MAX_NAME), Progress::xpText,
                ByteBufCodecs.VAR_INT, Progress::ca,
                Progress::new
        );

        public Progress {
            level = clamp(level, 1, MAX_LEVEL);
            xp = clamp(xp, 0, MAX_XP);
            xpText = clean(xpText);
            ca = clamp(ca, 0, MAX_RESOURCE);
        }

        /**
         * Construtor de 3 campos, kept para os chamadores antigos.
         *
         * <p><b>Por que ele existe:</b> sem ele, adicionar o {@code ca} obrigaria
         * a editar as sete chamadas de {@code new Progress(...)} espalhadas por
         * {@link #withField} e pelo resto do arquivo, e um delas esquecida seria
         * um erro de compilacao em um lugar que nao tem nada a ver com CA. Com o
         * construtor antigo, o CA entra no lugar certo ({@code 0}) por padrao e
         * so o codigo que realmente mexe no CA precisa falar dele.
         */
        public Progress(int level, int xp, String xpText) {
            this(level, xp, xpText, 0);
        }

        public static Progress defaults() {
            return new Progress(1, 0, "", 0);
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

        /**
         * Teto <b>absoluto</b> do valor de um atributo.
         *
         * <p><b>28/09/2026: este nao e mais o teto de regra.</b> O Mestre escolhe
         * o teto e o piso da ficha no Sheet Editor, e quem corta o valor nesse
         * intervalo e o construtor compacto de {@link SheetData}
         * ({@code [attributeValueMin, attributeValueMax]}). Estas duas constantes
         * sobraram como <b>rede de seguranca do protocolo</b>, o mesmo papel que
         * {@link SheetData#MAX_RESOURCE} tem para o HP: um cliente modificado pode
         * mandar {@code Integer.MAX_VALUE}, e sem teto nenhum o numero viraria
         * {@code -2147483648} na tela por causa do overflow. Por isso o valor e
         * largo o bastante para nenhum Mestre alcancar e curto o bastante para
         * nao estourar nada.
         */
        public static final int VALUE_MAX = 999;
        /** Piso absoluto do valor de um atributo; ver {@link #VALUE_MAX}. */
        public static final int VALUE_MIN = -999;

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
                // Ceil e piso ABSOLUTOS, nao o intervalo da ficha: o intervalo
                // que o Mestre definiu e aplicado pela SheetData logo acima. Aqui
                // so o numero absurdo da rede/ do NBT editado a mao.
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
         * Troca nome e descricao da skill que esta no <b>indice</b> do payload,
         * sem mudar a ordem da lista (decisao do usuario em 29/09/2026).
         *
         * <p>E o que o botao Edit da tela de Skills faz: carrega a skill
         * selecionada nas caixas do rodape e o "Add" vira "Save".
         *
         * <p><b>Por que o indice e nao so o nome:</b> enquanto o jogador edita,
         * outra edicao (inclusive a seta de reordenar da propria tela) pode
         * trocar a skill de posicao. O nome em diante no payload e' a trava: o
         * servidor so grava se a skill que estiver NAQUele indice ainda tiver
         * aquele nome. Sem esse teste, um "Save" atrasado sobrescreveria a skill
         * que o jogador nunca editou.
         */
        UPDATE,
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
     * (limitado pelo Mestre, ver {@link Pericia#VALUE_MAX}) e o atributo que ela
     * soma.
     */
    public enum PericiaOp {
        /** Muda só o valor da perícia. */
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
     * Uma <b>perícia</b> da ficha: {@code id} + {@code name} + {@code value} +
     * {@code attribute}.
     *
     * <p><b>Por que a lista é fixa:</b> o usuário decide (25/09/2026) que as
     * perícias são definidas em código e personalizadas por sistema de RPG; o
     * jogador não pode criar nem apagar, só ajustar o <b>valor</b> e o
     * <b>atributo</b> que a perícia soma. A lista que vale é a do
     * {@link SheetModel}, e quem garante que a ficha tenha exatamente essa lista
     * é {@link SheetModel#align} (chamado no login e a cada edição do modelo) -
     * {@link #sanitizePericias} <b>não</b>: ele só normaliza, preenchendo o id
     * que faltar e descartando entrada sem nome ou com id repetido.
     *
     * <p><b>28/09/2026: o {@code id} e a identidade, e o nome e o rotulo.</b>
     * O id vem do {@link SheetModel.PericiaDef#id()} e e o que casa a ficha com
     * o modelo no {@link SheetModel#align}: renomear a pericia preserva valor e
     * atributo. Antes o NOME era a identidade, e o rename zerava o valor em
     * silencio.
     *
     * <p>Sem descrição de propósito: o usuário não pediu descrição de perícia,
     * e o nome já identifica a perícia na tela. Se um dia quiser, é um campo a
     * mais aqui e no codec.
     */
    public record Pericia(String id, String name, int value, String attributeId) {
        public static final StreamCodec<FriendlyByteBuf, Pericia> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), Pericia::id,
                ByteBufCodecs.stringUtf8(SKILL_MAX), Pericia::name,
                ByteBufCodecs.VAR_INT, Pericia::value,
                ByteBufCodecs.stringUtf8(32), Pericia::attributeId,
                Pericia::new
        );

        /**
         * Piso e teto <b>absolutos</b> do valor da pericia.
         *
         * <p><b>28/09/2026: o teto deixou de ser a regra.</b> O teto de verdade
         * e o {@code periciaValueMax} que o Mestre escolhe no Sheet Editor, e quem
         * corta o valor nele e o construtor compacto de {@link SheetData}. O piso 0
         * continua fixo, e por um motivo de regra: o bonus de pericia e o que
         * <b>soma</b> na rolagem ({@code 1d20 + valor + atributo}), entao negativo
         * aqui significaria penalidade em vez de bonus -- e penalidade pertence ao
         * atributo, que aceita negativo.
         *
         * <p>Estas duas constantes ficaram como <b>rede de seguranca do
         * protocolo</b> (o mesmo papel de {@link Attributes#VALUE_MAX}): cortam o
         * numero absurdamente grande que um cliente modificado ou um NBT editado a
         * mao trariam, e nao limitam o uso. Quem le o teto de regra na tela e o
         * {@code sheet.periciaValueMax()}, e o
         * {@code MasterCommands.rollSkill} nao le nenhum dos dois: confia em
         * {@code pericia.value()}, que ja passou pelo construtor da ficha.
         */
        public static final int VALUE_MIN = 0;
        public static final int VALUE_MAX = 999;

        public Pericia {
            // Mesmo teto de 32 e mesmo trim do id de um AttributeValue: e o
            // mesmo papel, a chave com que a ficha liga esta pericia ao modelo.
            id = cleanId(id);
            name = cleanSkill(name);
            // Ceil e piso ABSOLUTOS, nao o intervalo da ficha: o teto que o
            // Mestre definiu e aplicado pela SheetData logo acima. Aqui so o
            // numero absurdo da rede/ do NBT editado a mao.
            value = clamp(value, VALUE_MIN, VALUE_MAX);
            // Cenario defensivo: um payload antigo, um save editado a mao ou um
            // cliente modificado poderiam mandar null. Sem isto, o NPE rebentaria
            // a ficha inteira. String vazia significa "ainda nao sabe com qual
            // atributo soma" e e resolvido por SheetModel.align.
            attributeId = cleanId(attributeId);
        }

        /** Total que esta pericia soma na rolagem: valor + atributo. */
        public int rollBonus(SheetData sheet) {
            return value + (sheet == null ? 0 : sheet.attributeValue(attributeId));
        }
    }

    // ------------------------------------------------------------------
    // INVENTARIO (30/09/2026, FASE 2B)
    // ------------------------------------------------------------------

    /**
     * O que um {@code SheetItemPayload} quer fazer com um item do inventario.
     *
     * <p>As mesmas tres operacoes de {@link SkillOp}, pelo mesmo motivo: sao as
     * tres coisas que um item pode sofrer na tela -- criar, editar, apagar.
     */
    public enum ItemOp {
        /** Cria um item novo. O {@code index} do payload e {@code -1}. */
        ADD,
        /**
         * Troca o item do <b>indice</b> do payload, no mesmo lugar da lista.
         *
         * <p>E o que o botao Edit da coluna Inventory faz. O indice trava a
         * posicao: enquanto o jogador edita, outra edicao pode ter apagado esse
         * item, e um UPDATE atrasado com indice fora da lista e descartado pelo
         * servidor em vez de recriar o item.
         */
        UPDATE,
        /** Apaga o item do indice. O {@code item} do payload nao e lido. */
        REMOVE,
        /**
         * Valor invalido vindo da rede. <b>Nao fazer nada com ele.</b>
         *
         * <p>Mesma razao do {@link SkillOp#INVALID}: um indice corrompido nao
         * pode virar uma operacao valida por acidente.
         */
        INVALID;

        public static final StreamCodec<io.netty.buffer.ByteBuf, ItemOp> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public ItemOp decode(io.netty.buffer.ByteBuf buffer) {
                        int index = ByteBufCodecs.VAR_INT.decode(buffer);
                        ItemOp[] values = ItemOp.values();
                        return index >= 0 && index < values.length ? values[index] : INVALID;
                    }

                    @Override
                    public void encode(io.netty.buffer.ByteBuf buffer, ItemOp op) {
                        ByteBufCodecs.VAR_INT.encode(buffer, op == null ? 0 : op.ordinal());
                    }
                };
    }

    /**
     * As operacoes de uma magia (01/10/2026, pagina 3).
     *
     * <p><b>Sao as mesmas tres de {@link ItemOp}, e nao as quatro de
     * {@link SkillOp}:</b> falta o {@code MOVE} de proposito, porque a ordem
     * exibida das magias e sempre automatica (circulo crescente, depois
     * alfabetica, por {@link Spellbook#visible(int)}) e o jogador nao tem o que
     * mover. O {@code INVALID} e pelo mesmo motivo dos outros enums: um indice
     * corrompido nao pode virar uma operacao valida por acidente.
     */
    public enum SpellOp {
        /** Cria uma magia nova. O {@code index} do payload e {@code -1}. */
        ADD,
        /**
         * Troca a magia do <b>indice</b> do payload, no mesmo lugar da lista.
         *
         * <p>E o que o botao Edit da coluna de magias faz. O indice e o indice
         * <b>guardado</b>, nao a posicao na tela filtrada: e por isso que
         * {@link Spellbook#visible(int)} devolve indices da lista e nao das
         * posicoes exibidas.
         */
        UPDATE,
        /** Apaga a magia do indice. O {@code spell} do payload nao e lido. */
        REMOVE,
        /** Valor invalido vindo da rede. <b>Nao fazer nada com ele.</b> */
        INVALID;

        public static final StreamCodec<io.netty.buffer.ByteBuf, SpellOp> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public SpellOp decode(io.netty.buffer.ByteBuf buffer) {
                        int index = ByteBufCodecs.VAR_INT.decode(buffer);
                        SpellOp[] values = SpellOp.values();
                        return index >= 0 && index < values.length ? values[index] : INVALID;
                    }

                    @Override
                    public void encode(io.netty.buffer.ByteBuf buffer, SpellOp op) {
                        ByteBufCodecs.VAR_INT.encode(buffer, op == null ? 0 : op.ordinal());
                    }
                };
    }

    /**
     * Um <b>item</b> do inventario da ficha: {@code name} + {@code type} +
     * {@code weight} + {@code description} (30/09/2026, FASE 2B).
     *
     * <p><b>O peso nao e uma regra, e um numero que o jogador ve.</b> O
     * {@code type} ("arma", "poção", "armadura") e o que o jogador classifica;
     * o {@code weight} e o quanto aquilo pesa para a regra de carga, e a soma de
     * todos os itens aparece na linha {@code Weight: X.XX / Y.YY}. Passar do
     * limite pinta a linha de vermelho ({@link Inventory#overweight()}) e
     * <b>nao bloqueia nada</b>: quem decide se o personagem pode carregar o que
     * carrega e o Mestre, em jogo.
     *
     * <p>Por que {@code name} e {@code type} tem teto {@link #MAX_NAME} (32) e
     * a descricao tem {@link #MAX_TEXT}: sao coisas diferentes. O nome e o tipo
     * precisam caber num rotulo e numa linha da lista; a descricao e texto livre
     * e o jogador rola dentro da caixa.
     *
     * <p><b>{@code WEIGHT_PATTERN} e a regra de digito do peso</b>, e ela mora
     * aqui, e nao em cada tela, porque tem de concordar com o teto de 2 casas
     * que {@link #WEIGHT_MAX} e o arredondamento de {@link #roundWeight}
     * implementam: um peso escrito na tela nao pode ser recusado pelo construtor
     * logo depois.
     */
    public record InventoryItem(String name, String type, float weight, String description) {

        /**
         * Item vazio. E o que o payload de {@link ItemOp#REMOVE} leva: apagar nao
         * precisa carregar item nenhum, e assim o servidor tem um campo valido
         * para ler mesmo quando o item sumiu da lista.
         */
        public static final InventoryItem EMPTY = new InventoryItem("", "", 0f, "");

        /** Teto do peso de um item, e o mesmo do {@code maxWeight}. */
        public static final float WEIGHT_MAX = 9999f;

        /**
         * Digitos do peso, com <b>um</b> ponto e ate 2 casas decimais.
         *
         * <p>E o filtro que as duas telas usam na caixa do peso: e o que impede
         * "1.2.3" (que nao parseia e cairia em 0 ao salvar) e "99999" (que o
         * construtor cortaria em {@link #WEIGHT_MAX} sem o jogador ver).
         */
        public static final String WEIGHT_PATTERN = "\\d{0,4}(\\.\\d{0,2})?";

        public static final StreamCodec<FriendlyByteBuf, InventoryItem> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(MAX_NAME), InventoryItem::name,
                ByteBufCodecs.stringUtf8(MAX_NAME), InventoryItem::type,
                ByteBufCodecs.FLOAT, InventoryItem::weight,
                ByteBufCodecs.stringUtf8(MAX_TEXT), InventoryItem::description,
                InventoryItem::new
        );

        public InventoryItem {
            name = clean(name);
            type = clean(type);
            // 30/09/2026: o peso e arredondado NA ENTRADA (duas casas), e nao
            // so na soma. Sem isso, dois itens de peso 1.005 somariam 2.01 e
            // cada um continuaria exibindo um numero que a soma nao tem.
            weight = roundWeight(clampWeight(weight));
            description = cleanText(description);
        }

        /** Peso Formatado com 2 casas, para a lista e para o resumo. */
        public String weightText() {
            return formatWeight(weight);
        }
    }

    /**
     * A lista de itens da ficha e o limite de peso escolhido pelo jogador
     * (30/09/2026, FASE 2B).
     *
     * <p>Vem por ultimo no {@link SheetData} e e o unico componente que o
     * {@link SheetModel#align} <b>copia sem mexer</b>: a lista de itens pertence
     * ao jogador e nao ao modelo do sistema, entao trocar de modelo nao pode
     * apagar o que ele carrega.
     *
     * <p><b>O peso e sempre visual.</b> {@link #totalWeight()} e a soma
     * arredondada de todos os itens e {@link #overweight()} decide a cor da
     * linha do resumo; nenhum dos dois bloqueia gravacao, edicao ou uso do
     * inventario.
     */
    public record Inventory(List<InventoryItem> items, float maxWeight) {

        /** Numero maximo de itens por ficha. */
        public static final int MAX_ITEMS = 50;

        /** Teto do peso maximo, o mesmo de {@link InventoryItem#WEIGHT_MAX}. */
        public static final float WEIGHT_MAX = InventoryItem.WEIGHT_MAX;

        /** Inventario vazio: e o que um save anterior a Fase 2B decodifica. */
        public static final Inventory EMPTY = new Inventory(List.of(), 0f);

        public static final StreamCodec<FriendlyByteBuf, Inventory> STREAM_CODEC = StreamCodec.composite(
                InventoryItem.STREAM_CODEC.apply(ByteBufCodecs.list()), Inventory::items,
                ByteBufCodecs.FLOAT, Inventory::maxWeight,
                Inventory::new
        );

        public Inventory {
            items = sanitizeItems(items);
            maxWeight = roundWeight(clampWeight(maxWeight));
        }

        /**
         * Soma dos pesos de todos os itens, <b>arredondada uma vez no final</b>.
         *
         * <p>Cada item ja chega arredondado por {@link InventoryItem}, mas a
         * soma de dois numeros de 2 casas ainda pode dar uma terceira casa
         * (0.33 + 0.33 = 0.66 exato, mas 12.34 + 0.66 = 13.00 tambem fecha;
         * 0.01 repetido 50 vezes fecha em 0.50, e com itens de 9999 o total
         * passa de 49 mil). Arredondar so aqui evita que a linha do resumo
         * mostre um numero que a soma nao tem.
         */
        public float totalWeight() {
            float sum = 0f;
            for (InventoryItem item : items) {
                sum += item.weight();
            }
            return roundWeight(sum);
        }

        /** O total passa do limite escolhido. Decide so a cor do resumo. */
        public boolean overweight() {
            return totalWeight() > maxWeight;
        }

        /**
         * Acrescenta um item no fim da lista.
         *
         * <p><b>Com a lista cheia (50) devolve a propria instancia</b>: o
         * servidor ve {@code updated == current} e nem transmite nada, em vez de
         * aceitar o 51o item e perder o primeiro. E o mesmo caminho do
         * {@link #withSkill} com {@link #MAX_SKILLS}.
         */
        public Inventory addItem(InventoryItem item) {
            if (item == null || items.size() >= MAX_ITEMS) {
                return this;
            }
            List<InventoryItem> next = new ArrayList<>(items);
            next.add(item);
            return new Inventory(next, maxWeight);
        }

        /**
         * Troca o item do indice dado, no mesmo lugar da lista.
         *
         * <p>Indice fora da lista devolve a propria instancia: um UPDATE atrasado
         * (o item foi apagado entre o clique e o pacote) nao pode recriar o item
         * como se fosse um ADD.
         */
        public Inventory withItem(int index, InventoryItem item) {
            if (item == null || index < 0 || index >= items.size()) {
                return this;
            }
            if (items.get(index).equals(item)) {
                return this;
            }
            List<InventoryItem> next = new ArrayList<>(items);
            next.set(index, item);
            return new Inventory(next, maxWeight);
        }

        /** Apaga o item do indice; indice fora da lista devolve a propria instancia. */
        public Inventory removeItem(int index) {
            if (index < 0 || index >= items.size()) {
                return this;
            }
            List<InventoryItem> next = new ArrayList<>(items);
            next.remove(index);
            return new Inventory(next, maxWeight);
        }

        /**
         * Novo limite de peso. Valor ja cortado e arredondado por quem chama
         * (o construtor), e igual ao atual devolve a propria instancia, para que
         * o eco do servidor nao dispare uma gravacao e um broadcast a toa.
         */
        public Inventory withMaxWeight(float next) {
            if (next == maxWeight) {
                return this;
            }
            return new Inventory(items, next);
        }
    }

    /**
     * Uma magia da ficha (01/10/2026, pagina 3).
     *
     * <p>Campos por decisao do usuario: nome, circulo (1 a 5) e os cinco campos
     * do formulario de Tormenta 20 - execucao, alcance, alvo, duracao e custo.
     * Os cinco sao <b>texto livre</b>, e nao lista fechada: o pedido foi "campo",
     * e e o mesmo tratamento que o inventario da aba Info/Inventory ja usa.
     *
     * <p><b>Por que o {@link #STREAM_CODEC} e manual:</b> sao 7 campos e o
     * {@link StreamCodec#composite} para em 6. Nao da para criar um sub-record
     * so para caber no limite sem mudar o formulario, entao o par
     * {@link StreamCodec#of} e o mesmo caminho que o topo da ficha ja usa (ver
     * o Javadoc do {@link #ENCODER}).
     *
     * <p><b>A ordem do encoder tem de ser igual a do decoder</b>, pelas mesmas
     * razoes do topo da ficha: sao duas lambdas independentes e nada as amarra em
     * tempo de compilacao.
     */
    public record Spell(String name, int circle, String execution, String range,
                        String target, String duration, String cost) {

        /** Menor circulo de Tormenta 20 que o mod aceita. */
        public static final int CIRCLE_MIN = 1;
        /** Maior circulo de Tormenta 20 que o mod aceita. */
        public static final int CIRCLE_MAX = 5;
        /** Circulo usado quando a magia nao tem circulo definido. */
        public static final int CIRCLE_DEFAULT = 1;

        /** Teto de caracteres do nome de uma magia: o mesmo do {@link #MAX_NAME}. */
        public static final int SPELL_NAME_MAX = 48;
        /**
         * Teto de caracteres de cada um dos cinco campos de texto curto.
         *
         * <p>Menor que o {@link #MAX_TEXT} (2.000) porque "Acao", "1 por turno" e
         * "Ate 1 hora" sao curtos: o que o jogador rola nesses cinco campos e o
         * valor da tabela dele, e 200 casas deixariam a caixa abrindo tres
         * linhas de nada.
         */
        public static final int SPELL_FIELD_MAX = 64;
        /** Teto de caracteres do custo, que pode trazer calculo ("3 PM + 2 accoes"). */
        public static final int SPELL_COST_MAX = 120;

        /** Magia vazia: e o que um item de lista sem nome decodifica. */
        public static final Spell EMPTY = new Spell("", 1, "", "", "", "", "");

        /**
         * Encodificador manual, no mesmo par do {@link SheetData#ENCODER}.
         * Ordem: nome, circulo, execucao, alcance, alvo, duracao, custo.
         */
        private static final StreamEncoder<FriendlyByteBuf, Spell> ENCODER = (buf, spell) -> {
            ByteBufCodecs.stringUtf8(SPELL_NAME_MAX).encode(buf, spell.name());
            ByteBufCodecs.VAR_INT.encode(buf, spell.circle());
            ByteBufCodecs.stringUtf8(SPELL_FIELD_MAX).encode(buf, spell.execution());
            ByteBufCodecs.stringUtf8(SPELL_FIELD_MAX).encode(buf, spell.range());
            ByteBufCodecs.stringUtf8(SPELL_FIELD_MAX).encode(buf, spell.target());
            ByteBufCodecs.stringUtf8(SPELL_FIELD_MAX).encode(buf, spell.duration());
            ByteBufCodecs.stringUtf8(SPELL_COST_MAX).encode(buf, spell.cost());
        };

        private static final StreamDecoder<FriendlyByteBuf, Spell> DECODER = buf -> new Spell(
                ByteBufCodecs.stringUtf8(SPELL_NAME_MAX).decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.stringUtf8(SPELL_FIELD_MAX).decode(buf),
                ByteBufCodecs.stringUtf8(SPELL_FIELD_MAX).decode(buf),
                ByteBufCodecs.stringUtf8(SPELL_FIELD_MAX).decode(buf),
                ByteBufCodecs.stringUtf8(SPELL_FIELD_MAX).decode(buf),
                ByteBufCodecs.stringUtf8(SPELL_COST_MAX).decode(buf)
        );

        public static final StreamCodec<FriendlyByteBuf, Spell> STREAM_CODEC =
                StreamCodec.of(ENCODER, DECODER);

        public Spell {
            name = cleanSpellName(name);
            circle = clamp(circle, CIRCLE_MIN, CIRCLE_MAX);
            execution = cleanSpellField(execution);
            range = cleanSpellField(range);
            target = cleanSpellField(target);
            duration = cleanSpellField(duration);
            cost = cleanSpellCost(cost);
        }

        /**
         * Rotulo do circulo, do jeito que a lista mostra na propria linha da magia.
         *
         * <p><b>Por que o nome por extenso entra na lista (01/10/2026):</b> o
         * jogador pediu "1º círculo" em vez do ordinal sozinho: com a linha so
         * embaixo do nome e nada mais na tela, "3º" nao dizia o que era, e ele
         * lia como numero solto. O nome por extenso cabe -- a coluna mostra o
         * circulo sozinho na linha, sem texto do lado.
         *
         * <p><b>Por que minusculo:</b> e a linha da lista, nao um titulo; o
         * formulario e o filtro continuam capitalizados em {@link #circleLabel}.
         */
        public String circleText() {
            return circle + "º círculo";
        }

        /**
         * O circulo na sua forma por extenso, para o formulario.
         *
         * <p><b>O indicador ordinal (01/10/2026):</b> e {@code º} (U+00BA) e nao
         * a letra "o" -- pedido do usuario, que le "1o" como letra solta e nao
         * como ordinal. O {@code default} cobre o circulo 0 e o 6, que o
         * construtor ja cortou; devolve o 1º porque e o circulo mais provavel de um
         * formulario recem-aberto.
         */
        public String circleLabel() {
            return switch (circle) {
                case 1 -> "1º Círculo";
                case 2 -> "2º Círculo";
                case 3 -> "3º Círculo";
                case 4 -> "4º Círculo";
                case 5 -> "5º Círculo";
                default -> "1º Círculo";
            };
        }
    }

    /**
     * A lista de magias, o atributo de conjuracao e a CD da pagina 3
     * (01/10/2026).
     *
     * <p><b>Por que o atributo e a CD sao globais da pagina e nao por magia</b>:
     * decisao do usuario. O "Modificador" exibido ao lado do atributo e o valor
     * daquele atributo na pagina 1 ({@link #attributeValue}), e a CD e um alvo
     * de rolagem da pagina, nao uma propriedade da magia.
     *
     * <p>Vem por ultimo no {@link SheetData}, depois de {@code inventory}, e
     * assim como ele nao e copiado nem apagado pelo {@link SheetModel#align}: a
     * lista de magias pertence ao jogador, nao ao modelo do sistema.
     *
     * <p><b>A ordem exibida nao e a ordem da lista guardada.</b> A lista e
     * mantida na ordem em que o jogador digitou, porque um UPDATE chega por
     * indice e reordenar no caminho gravar trocaria o alvo do proximo UPDATE.
     * O filtro e a ordenacao sao aplicados so na exibicao, por
     * {@link #visible(int)}.
     */
    public record Spellbook(List<Spell> spells, String castingAttribute, int cd) {

        /** Numero maximo de magias por ficha, decidido pelo usuario. */
        public static final int MAX_SPELLS = 60;

        /** Menor CD aceito. CD negativa nao tem significado de jogo. */
        public static final int CD_MIN = 0;
        /**
         * Maior CD aceito (decisao do usuario em 01/10/2026).
         *
         * <p><b>E o teto de 4 digitos do {@code SheetFieldPayload}, nao um numero
         * de jogo:</b> o jogador pediu para manter o teto de 2048 que o payload ja
         * tinha, entao a CD e livre ate 4 algarismos. O {@code EditBox} da tela
         * filtra em {@code \d{0,4}} pelo mesmo motivo, e por isso os dois tetos
         * precisam concordar -- se a caixa aceitasse mais, o servidor cortaria o
         * que o jogador digitou sem ele ver.
         */
        public static final int CD_MAX = 2048;

        /** Filtro que mostra todas as magias, sem cortar por circulo. */
        public static final int FILTER_ALL = 0;

        /** Grimorio vazio: e o que um save anterior a pagina 3 decodifica. */
        public static final Spellbook EMPTY = new Spellbook(List.of(), "", 0);

        public static final StreamCodec<FriendlyByteBuf, Spellbook> STREAM_CODEC = StreamCodec.composite(
                SPELLS_STREAM_CODEC, Spellbook::spells,
                ByteBufCodecs.stringUtf8(32), Spellbook::castingAttribute,
                ByteBufCodecs.VAR_INT, Spellbook::cd,
                Spellbook::new
        );

        public Spellbook {
            spells = sanitizeSpells(spells);
            castingAttribute = cleanId(castingAttribute);
            cd = clamp(cd, CD_MIN, CD_MAX);
        }

        /**
         * Acrescenta uma magia no fim da lista.
         *
         * <p><b>Com a lista cheia devolve a propria instancia</b>: o servidor ve
         * {@code updated == current} e nem transmite nada, em vez de aceitar a
         * 61a magia e perder a primeira. Mesmo caminho do
         * {@link Inventory#addItem} e do {@link #withSkill}.
         */
        public Spellbook addSpell(Spell spell) {
            if (spell == null || spells.size() >= MAX_SPELLS) {
                return this;
            }
            List<Spell> next = new ArrayList<>(spells);
            next.add(spell);
            return new Spellbook(next, castingAttribute, cd);
        }

        /**
         * Troca a magia do indice dado, no mesmo lugar da lista.
         *
         * <p>Indice fora da lista devolve a propria instancia: um UPDATE atrasado
         * (a magia foi apagada entre o clique e o pacote) nao pode recriá-la como
         * se fosse um ADD. Mesmo desenho do {@link Inventory#withItem}.
         */
        public Spellbook withSpell(int index, Spell spell) {
            if (spell == null || index < 0 || index >= spells.size()) {
                return this;
            }
            if (spells.get(index).equals(spell)) {
                return this;
            }
            List<Spell> next = new ArrayList<>(spells);
            next.set(index, spell);
            return new Spellbook(next, castingAttribute, cd);
        }

        /** Apaga a magia do indice; indice fora da lista devolve a propria instancia. */
        public Spellbook removeSpell(int index) {
            if (index < 0 || index >= spells.size()) {
                return this;
            }
            List<Spell> next = new ArrayList<>(spells);
            next.remove(index);
            return new Spellbook(next, castingAttribute, cd);
        }

        /** Indice da magia com esse nome, ou {@code -1}. */
        public int indexOfSpell(String name) {
            if (name == null || name.isEmpty()) {
                return -1;
            }
            for (int i = 0; i < spells.size(); i++) {
                if (spells.get(i).name().equalsIgnoreCase(name)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Novo atributo de conjuracao. Id igual ao atual devolve a propria
         * instancia, para que o eco do servidor nao dispare gravacao e broadcast
         * a toa.
         */
        public Spellbook withCastingAttribute(String attributeId) {
            if (Objects.equals(castingAttribute, attributeId)) {
                return this;
            }
            return new Spellbook(spells, attributeId, cd);
        }

        /** Nova CD. Valor ja cortado pelo construtor. */
        public Spellbook withCd(int next) {
            if (next == cd) {
                return this;
            }
            return new Spellbook(spells, castingAttribute, next);
        }

        /**
         * O filtro de circulo que o jogador escolheu no botao do topo.
         *
         * <p><b>Ordem do ciclo, pedida pelo usuario:</b> Todas -&gt; 1o -&gt; 2o -&gt;
         * 3o -&gt; 4o -&gt; 5o -&gt; Todas. O valor e um {@code int} de tela, nao um
         * campo da ficha: por isso nao entra em codec, payload nem NBT. O que a
         * ficha guarda sao as magias; como elas aparecem e escolha de cada tela.
         */
        public static int nextFilter(int current) {
            if (current < FILTER_ALL || current >= Spell.CIRCLE_MAX) {
                return FILTER_ALL;
            }
            return current + 1;
        }

        /** Rotulo do filtro, para o botao do topo. */
        public static String filterText(int filter) {
            // 01/10/2026: "1º" com o indicador ordinal (U+00BA) e nao "1o", por
            // pedido do usuario -- e o mesmo rotulo que a ficha usa nos outros
            // circulos. O Minecraft traz esse caractere na fonte padrao, entao nao
            // vira caixa especial.
            return switch (filter) {
                case 1 -> "1º Círculo";
                case 2 -> "2º Círculo";
                case 3 -> "3º Círculo";
                case 4 -> "4º Círculo";
                case 5 -> "5º Círculo";
                default -> "Todas";
            };
        }

        /**
         * As magias que o filtro atual deve mostrar, ja ordenadas.
         *
         * <p><b>Todas:</b> circulo crescente, e dentro do mesmo circulo em ordem
         * alfabetica. <b>Um circulo so:</b> o mesmo criterio, com o filtro
         * custando nada porque as magias ja vem em ordem de circulo.
         *
         * <p>O indice devolvido e o indice na <b>lista guardada</b>, e nao na
         * lista exibida: e ele que volta no UPDATE quando o jogador edita a
         * magia em questao. Por isso a comparacao e o sort sao feitos sobre o
         * indice original.
         */
        public List<Integer> visible(int filter) {
            List<Integer> idx = new ArrayList<>(spells.size());
            for (int i = 0; i < spells.size(); i++) {
                if (filter <= FILTER_ALL || spells.get(i).circle() == filter) {
                    idx.add(i);
                }
            }
            idx.sort((a, b) -> {
                Spell first = spells.get(a);
                Spell second = spells.get(b);
                int byCircle = Integer.compare(first.circle(), second.circle());
                if (byCircle != 0) {
                    return byCircle;
                }
                // Sem acento e sem diferenciar maiuscula: a lista e em portugues
                // e "Agua" precisa ficar junto de "Abrigo", nao depois de "Z".
                return first.name().compareToIgnoreCase(second.name());
            });
            return List.copyOf(idx);
        }

        /** A magia do indice exibido, ou {@code null} se o filtro a escondeu. */
        public Spell at(int index) {
            return index < 0 || index >= spells.size() ? null : spells.get(index);
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
            out.add(new Pericia(def.id(), def.name(), 0, def.attributeId()));
        }
        return List.copyOf(out);
    }

    /**
     * apelidos ANTIGOS de cada pericia do padrao, indexados pelo nome ATUAL.
     * Um nome novo pode ter VARIOS apelidos velhos.
     *
     * <p><b>Por que existe:</b> ate 28/09/2026 a identidade de uma pericia no
     * NBT era o NOME (ver {@link #PERICIA_CODEC}), entao o apelido antigo era o
     * que segurava o valor de uma ficha salva antes da troca da lista.
     *
     * <p><b>Hoje este mapa nao e o que preserva valor nenhum</b>, e honesto
     * dizer: ele so e alcancado por {@link #periciaByNameOrLegacy}, que o
     * {@link SheetModel#align} chama apenas quando {@link #hasPericiaIds} e
     * {@code false} - e isso so acontece com a lista de pericias <b>VAZIA</b>
     * (ver o Javadoc de la), lista que nao tem nome para casar. O valor de uma
     * ficha pre-migration sobrevive pelo <b>preenchimento posicional</b> do
     * {@code sanitizePericias}, nao por este mapa. Ele fica porque sao
     * utilitarios publicos e podem ser reativados; se algum dia o {@code align}
     * voltar a precisar do apelido, ele esta aqui.
     *
     * <p><b>27/09/2026:</b> a lista trocou as 16 pericias de espaco reservado
     * ("skill 0".."skill 15") pelas 18 basicas de D&amp;D 5e. Duas situacoes
     * diferentes acontecem aqui. <b>Atencao: o que cada apelido faz hoje e
     * nenhum</b> (ver o paragrafo acima) - o texto abaixo descreve o que eles
     * faziam quando o nome ainda era a identidade, e esta aqui por historico:
     * <ul>
     *   <li><b>Mesmo nome, apelido so em portugues.</b> "Luta" e "Acrobacia"
     *       viraram "Melee" e "Acrobatics".</li>
     *   <li><b>Nome mudado de verdade.</b> "Diplomacy"/"Diplomacia" nao existe
     *       mais em D&amp;D 5e: o equivalente e "Persuasion", mesmo atributo
     *       (CHA).</li>
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
     * <p><b>28/09/2026: so o fallback de save pre-migration chama isto - e ele
     * hoje nao tem efeito.</b> O {@link SheetModel#align} chama este metodo
     * <b>somente</b> quando {@link #hasPericiaIds} e {@code false}, e isso so
     * acontece com a lista de pericias <b>VAZIA</b> (ver o Javadoc de la): com
     * lista vazia este metodo devolve {@code null} para qualquer nome. Portanto
     * <b>esta busca nao e o que preserva o valor de uma ficha salva ANTES da
     * troca da lista</b>: isso e o preenchimento posicional do
     * {@code sanitizePericias}. Fica como utilitario publico, pronto para o dia
     * em que um alinhamento precisar dele.
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

    /**
     * Ficha inicial de um jogador: nome = nome da conta, resto no padrão.
     *
     * <p><b>Os tres limites de valor vem do modelo (28/09/2026).</b> A ficha
     * nova ja nasce com o intervalo que o Mestre configurou, e nao com o padrao
     * do record: sem isto, o primeiro jogador a entrar depois de uma mudanca de
     * limite teria a ficha limitada pelo valor antigo ate o proximo
     * {@link #aligned()}, e a tela dele mostraria as setas travadas no teto
     * errado.
     */
    public static SheetData defaultSheet(String playerName) {
        SheetModel model = SheetModelHolder.current();
        return new SheetData(
                // 29/09/2026: o campo novo "playerName" nasce VAZIO de
                // proposito. O nome do jogador continua indo no campo do
                // personagem, como antes - o usuario nao pediu para mudar esse
                // campo, so pediu a caixinha nova em cima dele.
                new Identity(clean(playerName), "", "", "", "", "", ""),
                Vitals.defaults(),
                Progress.defaults(),
                Attributes.defaults(),
                List.of(),
                defaultPericias(),
                model.attributeValueMin(),
                model.attributeValueMax(),
                model.periciaValueMax(),
                // 30/09/2026 (FASE 2B): a ficha nova nasce com inventario vazio
                // e sem limite de peso. O jogador escolhe o proprio limite na
                // coluna Inventory; um valor aqui viria do modelo, que nao tem
                // nada a dizer sobre a carga de cada um.
                Inventory.EMPTY,
                // 01/10/2026: a ficha nova nasce sem magias, sem atributo
                // de conjuracao e com CD 0.
                new Spellbook(List.of(), "", 0)
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
            case "charactername" -> new SheetData(identity.withCharacterName(value), vitals, progress,
                    attributes, skills, pericias, attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);
            case "race" -> new SheetData(identity.withRace(value), vitals, progress,
                    attributes, skills, pericias, attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);
            case "characterclass" -> new SheetData(identity.withCharacterClass(value), vitals, progress,
                    attributes, skills, pericias, attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);
            case "background" -> new SheetData(identity.withBackground(value), vitals, progress,
                    attributes, skills, pericias, attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);
            // 29/09/2026: nome do jogador dono da ficha. A chave chega como
            // "playerName" e o switch compara em minuscula (key), igual aos
            // casos acima. A permissao e a mesma de todos os outros campos de
            // texto: quem aplica este metodo ja passou por canEditSheet no
            // servidor, que libera o dono da ficha e o Mestre.
            case "playername" -> new SheetData(identity.withPlayerName(value), vitals, progress,
                    attributes, skills, pericias, attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);
            // 30/09/2026: os dois textos longos da aba Info/Inventory. Mesmo
            // caminho dos casos acima (chave em minuscula no `key`, permissao ja
            // liberada por canEditSheet no servidor, teto por cleanText no
            // Identity) -- e o que sobra depois e o `default`, o ramo de
            // atributo: sem estes casos o Appearance cairia la e viraria um
            // atributo inexistente.
            case "appearance" -> new SheetData(identity.withAppearance(value), vitals, progress,
                    attributes, skills, pericias, attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);
            case "backstory" -> new SheetData(identity.withBackstory(value), vitals, progress,
                    attributes, skills, pericias, attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);

            // 30/09/2026 (FASE 2B): o limite de peso do inventario e um texto
            // que o jogador digita, entao vem pelo mesmo caminho dos outros
            // campos em withField (o SheetFieldPayload ja trada a chave
            // "maxWeight"). Valor nao numerico mantem o anterior, e um valor que
            // nao muda devolve a propria ficha -- assim o eco do servidor nao
            // reescreve a caixa que esta com o foco.
            case "maxweight" -> {
                Inventory next = inventory.withMaxWeight(parseFloat(value, inventory.maxWeight()));
                yield next == inventory ? this : withInventory(next);
            }

            // 01/10/2026 (pagina 3): os dois campos globais da area de magias.
            // "cd" e' o numero que o jogador digita na caixa; "castingAttribute"
            // e' o id do atributo escolhido no seletor. Valor nao numerico mantem
            // o anterior, e um valor que nao muda devolve a propria ficha -- assim
            // o eco do servidor nao reescreve a caixa que esta com o foco.
            case "cd" -> {
                Spellbook next = spellbook.withCd(parseInt(value, spellbook.cd()));
                yield next == spellbook ? this : withSpellbook(next);
            }
            case "castingattribute" -> {
                // Id fora do modelo e' recusado, pelo mesmo motivo do
                // withPericiaAttribute: o "Modificador" mostrado ao lado busca o
                // valor desse id na pagina 1, e um id que nao existe mostraria 0
                // sem o jogador perceber que escolheu nada.
                if (SheetModelHolder.current().attribute(value) == null) {
                    yield this;
                }
                Spellbook next = spellbook.withCastingAttribute(value);
                yield next == spellbook ? this : withSpellbook(next);
            }

            case "hp" -> replaceVitals(new Vitals(parseInt(value, vitals.hp()), vitals.hpMax(), vitals.mana(), vitals.manaMax()));
            case "hpmax" -> replaceVitals(new Vitals(vitals.hp(), parseInt(value, vitals.hpMax()), vitals.mana(), vitals.manaMax()));
            case "mana" -> replaceVitals(new Vitals(vitals.hp(), vitals.hpMax(), parseInt(value, vitals.mana()), vitals.manaMax()));
            case "manamax" -> replaceVitals(new Vitals(vitals.hp(), vitals.hpMax(), vitals.mana(), parseInt(value, vitals.manaMax())));

            // <b>01/10/2026: o {@code progress.ca()} e repassado aqui e nos
            // ramos de XP abaixo.</b> Estes ramos reconstroem o Progress inteiro a
            // partir do antigo para trocar UM campo. Usar o construtor de 3 campos
            // (que deixa o CA em 0) faria o Level voltar a 0 sempre que o Mestre
            // mexesse no XP, e vice-versa -- o CA e a mesma linha do Level, entao
            // as duas edicoes estao a uma tecla de distancia e o sumico seria
            // facil de encontrar e impossivel de explicar.
            case "level" -> new SheetData(identity, vitals,
                    new Progress(parseInt(value, progress.level()), progress.xp(), progress.xpText(),
                            progress.ca()),
                    attributes, skills, pericias, attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);
            // 01/10/2026: o CA (Classe de Armadura), na mesma linha do Level na
            // ficha. Fica ANTES do `default` pelo mesmo motivo do "level": sem
            // esta linha "ca" cairia em attributes.withValue, que devolveria a
            // propria ficha -- a caixa aceitaria a tecla e o valor sumiria no
            // proximo eco do servidor.
            case "ca" -> new SheetData(identity, vitals,
                    new Progress(progress.level(), progress.xp(), progress.xpText(),
                            parseInt(value, progress.ca())),
                    attributes, skills, pericias, attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);
            // Com o modelo em modo TEXT, o campo da barra mostra o texto que o
            // Mestre digitou (ex.: "Fiel aogrupo"). O numero continua guardado
            // para quando o modelo voltar para NUMBER.
            case "xp" -> SheetModelHolder.current().xp() == SheetModel.XpMode.TEXT
                    ? new SheetData(identity, vitals, new Progress(progress.level(), progress.xp(), value,
                            progress.ca()),
                            attributes, skills, pericias,
                            attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook)
                    : new SheetData(identity, vitals,
                            new Progress(progress.level(), parseInt(value, progress.xp()), progress.xpText(),
                                    progress.ca()),
                            attributes, skills, pericias,
                            attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);

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
                    ? new SheetData(identity, vitals, new Progress(progress.level(), progress.xp(), value,
                            progress.ca()),
                            attributes, skills, pericias,
                            attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook)
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
        return new SheetData(identity, newVitals, progress, attributes, skills, pericias,
                attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);
    }

    private SheetData replaceAttributes(Attributes newAttributes) {
        return new SheetData(identity, vitals, progress, newAttributes, skills, pericias,
                attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);
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
            return new SheetData(identity, vitals, progress, attributes, next, pericias,
                    attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);
        }
        if (skills.size() >= MAX_SKILLS) {
            return this; // lista cheia
        }
        next.add(new Skill(cleanName, cleanDesc));
        return new SheetData(identity, vitals, progress, attributes, next, pericias,
                attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);
    }

    /**
     * Troca nome e descricao da skill que esta no <b>indice</b> dado, no mesmo
     * lugar da lista (decisao do usuario em 29/09/2026: o botao Edit da tela de
     * Skills aproveita as caixas do rodape e o botao "Add" vira "Save").
     *
     * <p><b>Por que o indice e nao o nome:</b> o nome e' o que a tela lembra da
     * selecao, mas ele nao sobrevive a uma reordenacao -- quem mexe nas setas
     * entre a selecao e o "Save" troca a skill de posicao, e uma atualizacao por
     * nome cairia na skill errada. O indice trava a posicao, e o servidor (que
     * e' quem valida) exige que o nome em diante seja o da skill que esta la.
     *
     * <p><b>Por que a duplicata e' recusada aqui:</b> {@link
     * #sanitizeSkills} descarta a skill repetida e mantem a <b>primeira</b> da
     * lista, entao salvar com o nome de outra skill teria um resultado
     * dependente da posicao: ou o Save seria engolido em silencio, ou a skill
     * vizinha seria apagada. Devolver a ficha intacta faz o servidor recusar o
     * pedido (nada muda, sem erro) em vez de deixar o sanitizeSkills escolher
     * qual das duas fica.
     *
     * <p>Indice fora da faixa e nome vazio devolvem a ficha intacta, como os
     * outros {@code with...} deste arquivo.
     */
    public SheetData withSkill(int index, String name, String description) {
        String cleanName = cleanSkill(name);
        if (cleanName.isEmpty() || index < 0 || index >= skills.size()) {
            return this;
        }
        List<Skill> next = new ArrayList<>(skills.size());
        for (int i = 0; i < skills.size(); i++) {
            Skill existing = skills.get(i);
            if (i == index) {
                next.add(new Skill(cleanName, cleanDescription(description)));
            } else if (existing.name().equalsIgnoreCase(cleanName)) {
                // Nome ja usado por OUTRA skill: recusar e melhor do que deixar o
                // sanitizeSkills escolher qual das duas fica.
                return this;
            } else {
                next.add(existing);
            }
        }
        return new SheetData(identity, vitals, progress, attributes, next, pericias,
                attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);
    }

    /**
     * Muda SÓ o valor de uma perícia, preservando o atributo.
     *
     * <p><b>28/09/2026: o parametro e o {@code id} da pericia</b> (antes era o
     * nome), porque e o id que o cliente recebe no
     * {@code SheetPericiaPayload} e o que o modelo casa. O id e o nome fazem o
     * papel inverso: o nome e o que a pessoa le.
     *
     * <p>Usado pelas setas da lista de perícias na aba Status. Se a perícia não
     * existir na ficha, devolve a ficha intacta: a lista é fixa e não aceita
     * entrada nova.
     */
    public SheetData withPericiaValue(String periciaId, int value) {
        return mutatePericia(periciaId, existing ->
                new Pericia(existing.id(), existing.name(), value, existing.attributeId()));
    }

    /**
     * Troca com qual atributo a pericia soma (botao de lista suspensa).
     *
     * <p>27/09/2026: o parametro do atributo virou o <b>id</b> do atributo
     * (String) em vez do enum {@code Attribute}, que nao existe mais. O id
     * precisa existir no modelo: um id desconhecido e ignorado, e a pericia fica
     * com o atributo que ela ja tinha. 28/09/2026: o primeiro parametro tambem
     * passou a ser o <b>id da pericia</b>.
     */
    public SheetData withPericiaAttribute(String periciaId, String attributeId) {
        if (attributeId == null || SheetModelHolder.current().attribute(attributeId) == null) {
            return this;
        }
        return mutatePericia(periciaId, existing ->
                new Pericia(existing.id(), existing.name(), existing.value(), attributeId));
    }

    /** Altera UM campo de uma perícia existente; nunca cria nem remove. */
    private SheetData mutatePericia(String periciaId, java.util.function.UnaryOperator<Pericia> mutator) {
        Pericia target = periciaById(periciaId);
        if (target == null) {
            return this;
        }
        List<Pericia> next = new ArrayList<>(pericias.size());
        boolean changed = false;
        for (Pericia existing : pericias) {
            if (existing.id().equals(target.id())) {
                Pericia updated = mutator.apply(existing);
                next.add(updated);
                changed = changed || !updated.equals(existing);
            } else {
                next.add(existing);
            }
        }
        return changed ? new SheetData(identity, vitals, progress, attributes, skills, next,
                attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook) : this;
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
        return removed ? new SheetData(identity, vitals, progress, attributes, next, pericias,
                attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook) : this;
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
        return new SheetData(identity, vitals, progress, attributes, next, pericias,
                attributeValueMin, attributeValueMax, periciaValueMax, inventory, spellbook);
    }

    /**
     * Devolve uma ficha nova com o inventario trocado (30/09/2026, FASE 2B).
     *
     * <p>E o unico caminho que o servidor usa para gravar um item, e ele
     * <b>devolve a propria ficha</b> quando o inventario nao mudou (indice fora
     * da lista, item igual, lista cheia). O handler do servidor compara
     * {@code updated == current} e nesse caso nem grava nem transmite: e o que
     * faz um UPDATE atrasado, cujo item ja foi apagado, ser descartado em
     * silencio em vez de recriar o item.
     */
    public SheetData withInventory(Inventory next) {
        if (next == null || next.equals(inventory)) {
            return this;
        }
        return new SheetData(identity, vitals, progress, attributes, skills, pericias,
                attributeValueMin, attributeValueMax, periciaValueMax, next, spellbook);
    }

    /**
     * Devolve uma ficha nova com o grimorio trocado (01/10/2026, pagina 3).
     *
     * <p>E o unico caminho que o servidor usa para gravar uma magia, o atributo
     * de conjuracao ou a CD. Devolve a <b>propria ficha</b> quando nada mudou
     * (mesma regra do {@link #withInventory}), para que o handler do servidor
     * veja {@code updated == current} e nem grave nem transmita.
     */
    public SheetData withSpellbook(Spellbook next) {
        if (next == null || next.equals(spellbook)) {
            return this;
        }
        return new SheetData(identity, vitals, progress, attributes, skills, pericias,
                attributeValueMin, attributeValueMax, periciaValueMax, inventory, next);
    }

    /** Acrescenta uma magia no fim. Com a lista cheia devolve a propria ficha. */
    public SheetData withSpellAdded(Spell spell) {
        Spellbook next = spellbook.addSpell(spell);
        return next == spellbook ? this : withSpellbook(next);
    }

    /**
     * Troca a magia do indice dado.
     *
     * <p>Indice fora da lista devolve a propria ficha: um UPDATE atrasado (a magia
     * foi apagada entre o clique e o pacote) nao pode recriá-la como se fosse um
     * ADD. Mesmo desenho do {@link #withSkill(int, String, String)}.
     */
    public SheetData withSpellUpdated(int index, Spell spell) {
        if (spell == null || spell.name().isEmpty()) {
            return this;
        }
        Spellbook next = spellbook.withSpell(index, spell);
        return next == spellbook ? this : withSpellbook(next);
    }

    /** Apaga a magia do indice; indice fora da lista devolve a propria ficha. */
    public SheetData withSpellRemoved(int index) {
        Spellbook next = spellbook.removeSpell(index);
        return next == spellbook ? this : withSpellbook(next);
    }

    /** Novo atributo de conjuracao da pagina 3. */
    public SheetData withCastingAttribute(String attributeId) {
        Spellbook next = spellbook.withCastingAttribute(attributeId);
        return next == spellbook ? this : withSpellbook(next);
    }

    /** Nova CD da pagina 3. */
    public SheetData withCd(int next) {
        Spellbook updated = spellbook.withCd(next);
        return updated == spellbook ? this : withSpellbook(updated);
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
            case "playername" -> identity.playerName();
            case "charactername" -> identity.characterName();
            case "race" -> identity.race();
            case "characterclass" -> identity.characterClass();
            case "background" -> identity.background();
            // 30/09/2026: os dois textos longos da aba Info/Inventory.
            case "appearance" -> identity.appearance();
            case "backstory" -> identity.backstory();
            // 01/10/2026 (pagina 3): o id do atributo de conjuracao. A tela mostra
            // o NOME do atributo (o rotulo do Mestre), e nao o id, porque e o que
            // o jogador le; o id continua sendo a chave que o servidor valida.
            case "castingattribute" -> castingAttributeName();
            // XP em modo TEXT e um campo de texto, e nao um numero. Sem esta
            // linha, a caixa de texto da tela de Status cairia no `default` e o
            // Mestre nao veria o que digitou.
            case "xptext" -> progress.xpText();
            // 30/09/2026 (FASE 2B): o limite de peso e lido como texto (e nao
            // por getNumeric, que devolve int) justamente porque tem 2 casas
            // decimais. O formato e o mesmo da linha de resumo do inventario.
            case "maxweight" -> formatWeight(inventory.maxWeight());
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
            // 01/10/2026: o CA da mesma linha do Level. Entra antes do `default`
            // pelo mesmo motivo de level/xp: sem ele, "ca" seria lido como id de
            // atributo e devolveria 0 sempre.
            case "ca" -> progress.ca();
            // 01/10/2026 (pagina 3): a CD das magias. Entra no switch antes do
            // `default` pelo mesmo motivo de hp/level: sem esta linha, "cd" cairia
            // em `attributes.valueOf("cd")` e a caixa mostraria 0 sempre.
            case "cd" -> spellbook.cd();
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
     * O NOME do atributo de conjuracao escolhido na pagina 3, ou {@code ""}.
     *
     * <p>E o rotulo do Mestre ({@link SheetModel.AttributeDef#label()}), e nao o
     * id: o id e a chave que o servidor valida, e o rotulo e o que o jogador le
     * no botao. Um id que saiu do modelo devolve {@code ""}, que e o que a tela
     * mostra como "sem atributo escolhido".
     */
    public String castingAttributeName() {
        String id = spellbook.castingAttribute();
        if (id.isEmpty()) {
            return "";
        }
        SheetModel.AttributeDef def = SheetModelHolder.current().attribute(id);
        return def == null ? "" : def.label();
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

    /**
     * A pericia deste <b>id</b>, ou {@code null}. Busca exata, e id vazio
     * devolvendo {@code null}.
     *
     * <p>E o espelho de {@link #periciaByName}: o id e a identidade (o que o
     * {@link SheetModel#align} casa e o que o payload traz), o nome e o rotulo
     * que o jogador le. Id desconhecido (payload forjado, pericia removida do
     * modelo) tem de falhar, e nao casar com a linha que estiver no lugar.
     */
    public Pericia periciaById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Pericia pericia : pericias) {
            if (pericia.id().equals(id)) {
                return pericia;
            }
        }
        return null;
    }

    /**
     * Esta ficha ainda nao tem id de pericia.
     *
     * <p>Com pelo menos um id presente, a lista ja passou por
     * {@link #sanitizePericias}, e uma pericia que nao casar por id e outra
     * pericia (ou um bug), nao um save antigo: o {@link SheetModel#align} nao usa
     * o nome nesse caso.
     *
     * <p><b>Na pratica, so a lista vazia devolve {@code false}.</b> O
     * {@code sanitizePericias} preenche o id de toda entrada que ele retida, e
     * nao ha como construir uma ficha com a lista mista (alguns com id, outros
     * sem) passando pelo construtor. Por isso o fallback por nome do
     * {@link SheetModel#align} <b>nao produz efeito hoje</b>: ele exige a lista
     * vazia, e sobre uma lista vazia {@link #periciaByNameOrLegacy} devolve
     * {@code null} para tudo. O que carrega o valor de um save pre-migration e o
     * <b>preenchimento posicional</b> do {@code sanitizePericias} (ver o
     * Javadoc de {@link SheetModel#align}).
     */
    public boolean hasPericiaIds() {
        for (Pericia pericia : pericias) {
            if (!pericia.id().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A pericia deste nome, ou {@code null}. Busca ignora maiusculas.
     *
     * <p><b>28/09/2026: nao e mais a identidade</b> (ver
     * {@link #periciaById}). Continua existindo para o {@code MasterCommands}
     * ({@code /rpg roll <nome>}), para a tela mostrar o nome e para o editor
     * recusar um nome ja usado.
     */
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
     *
     * <p><b>30/09/2026 - os dois textos longos tem o rotulo resolvido AQUI, e nao
     * no {@link SheetModel}:</b> o {@code default} do modelo devolve a propria
     * chave, e "appearance" nao e vazio, entao o {@code switch} abaixo nunca era
     * alcancado e a ficha desenharia "appearance" cru no lugar do titulo. Como o
     * usuario nao pediu para editar estes dois nomes no Sheet Editor (ao contrario
     * de {@code characterName}, que tem {@code nameLabel}), o nome e um literal
     * dos dois, igual ao {@code case "playername" -> "Player"} do modelo. Sao os
     * unicos dois campos com este tratamento: nenhum outro rotulo foi inventado.
     */
    public static String labelOf(String field) {
        if (field == null) {
            return "";
        }
        String key = field.toLowerCase(Locale.ROOT);
        if (key.equals("appearance")) {
            return "Character Appearance";
        }
        if (key.equals("backstory")) {
            return "Character Backstory";
        }
        String label = SheetModelHolder.current().labelOf(field);
        if (!label.isEmpty()) {
            return label;
        }
        return switch (key) {
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

    /**
     * Texto longo (aparencia, historia): o mesmo trim de {@link #clean(String)},
     * mas com o teto {@link #MAX_TEXT} em vez de {@link #MAX_NAME}.
     *
     * <p><b>30/09/2026:</b> existe para o teto nao ser unico. Se os dois campos
     * novos usassem o {@code clean} de um argumento, o servidor cortaria o
     * Appearance em 32 caracteres e a rolagem mostraria sempre o mesmo comeco --
     * o jogador digitaria o resto e ele nunca chegaria ao servidor. O
     * {@code stringUtf8(MAX_TEXT)} do {@link Identity#STREAM_CODEC} e o
     * {@code stringUtf8(2048)} do {@code SheetFieldPayload} sao o mesmo numero
     * com folga, porque o codec lanca excecao acima do teto, nao corta.
     */
    private static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > MAX_TEXT ? trimmed.substring(0, MAX_TEXT) : trimmed;
    }

    private static String cleanSkill(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > SKILL_MAX ? trimmed.substring(0, SKILL_MAX) : trimmed;
    }

    /** Nome de uma magia: trim e teto {@link Spell#SPELL_NAME_MAX}. */
    private static String cleanSpellName(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > Spell.SPELL_NAME_MAX
                ? trimmed.substring(0, Spell.SPELL_NAME_MAX) : trimmed;
    }

    /** Um dos cinco campos curtos de uma magia: teto {@link Spell#SPELL_FIELD_MAX}. */
    private static String cleanSpellField(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > Spell.SPELL_FIELD_MAX
                ? trimmed.substring(0, Spell.SPELL_FIELD_MAX) : trimmed;
    }

    /** Custo de uma magia: o mesmo trim, com o teto maior dele. */
    private static String cleanSpellCost(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > Spell.SPELL_COST_MAX
                ? trimmed.substring(0, Spell.SPELL_COST_MAX) : trimmed;
    }

    /**
     * Higiene da lista de magias (01/10/2026, pagina 3).
     *
     * <p>Mesmo desenho do {@link #sanitizeItems}: descarta nulo e magia sem nome,
     * reconstroi as que ficam (o construtor do record normaliza de novo, porque a
     * lista pode vir de um payload sem passar por {@link Spellbook#addSpell}) e
     * corta em {@link Spellbook#MAX_SPELLS}.
     *
     * <p><b>Nao deduplica por nome</b>, ao contrario das skills: o jogador e quem
     * decide o que e duplicado, e duas magias de mesmo nome em circulos
     * diferentes sao legítimas.
     */
    private static List<Spell> sanitizeSpells(List<Spell> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Spell> out = new ArrayList<>(Math.min(raw.size(), Spellbook.MAX_SPELLS));
        for (Spell spell : raw) {
            if (spell == null) {
                continue;
            }
            Spell clean = new Spell(spell.name(), spell.circle(), spell.execution(),
                    spell.range(), spell.target(), spell.duration(), spell.cost());
            if (clean.name().isEmpty()) {
                continue;
            }
            out.add(clean);
            if (out.size() >= Spellbook.MAX_SPELLS) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /**
     * Limpa um <b>id</b> (de atributo ou de pericia): nunca {@code null}, {@code trim}
     * e teto de 32 - o mesmo teto do {@code ByteBufCodecs.stringUtf8(32)} do codec
     * de rede e do {@link Attributes.AttributeValue}. Id vazio significa "ainda
     * nao tem id", e quem preenche e {@link #sanitizePericias}.
     */
    private static String cleanId(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
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
     * Higiene estrutural da lista de pericias: o id primeiro, a deduplicacao
     * depois.
     *
     * <p><b>27/09/2026 (Sheet Editor) - a lista deixou de ser imposta.</b> Este
     * metodo JA NAO prende a ficha a lista do codigo. Antes ele reescrevia a
     * lista inteira para casar com {@code PERICIAS_PADRAO}, o que tornava
     * impossivel ao Mestre adicionar, renomear ou remover uma pericia em tempo de
     * jogo. Agora quem decide a lista e o {@link SheetModel}, e o metodo que
     * casa a ficha com ela e {@link SheetModel#align}. A separacao e
     * deliberada:
     * <ul>
     *   <li>Aqui: a ficha nao pode vir com lixo (nulo, vazio, duplicata) de
     *       nenhuma origem, inclusive um payload adulterado.</li>
     *   <li>Em {@code align}: a ficha ganha as pericias novas, perde as
     *       removidas e mantem o valor das que sobreviveram pelo <b>id</b>.</li>
     * </ul>
     *
     * <p>Por que nao foi tudo deixado em {@code align}: {@code align} roda no
     * construtor de {@link SheetModel#align} e o construtor de {@code SheetData}
     * chama este metodo. Se o {@code align} fosse chamado aqui, o construtor
     * chamaria o align, que constroi uma ficha, que chama este metodo, que
     * chamaria o align outra vez. A limpeza fica estrutural para o loop
     * terminar.
     *
     * <p><b>28/09/2026 - a ordem das duas etapas e o que impede a perda da
     * ficha inteira.</b> A deduplicacao e por {@code id}, e uma ficha gravada
     * antes dos ids chega aqui com 18 pericias, todas sem id. Se a deduplicacao
     * rodasse antes do preenchimento, as 18 teriam o mesmo id {@code ""}, uma
     * venceria e a lista <b>colapsaria para 1</b>: silencioso, sem erro e sem
     * log. Por isso o id e preenchido ANTES do {@code seen.add}, e um id vazio
     * nunca entra no {@code seen}.
     *
     * <p>Se duas entradas disputarem o mesmo id - o que so acontece com um save
     * editado a mao, porque {@link #freshPericiaId} nunca devolve um id que ja
     * esta na lista -, quem entra primeiro vence e a outra e descartada por
     * {@code id} repetido. O id gerado e sempre o primeiro {@code pericia_N}
     * livre, o que torna o preenchimento deterministico e reproduzivel.
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
            if (pericia.name().isEmpty()) {
                continue;
            }
            // Ver o Javadoc: preenche ANTES da deduplicacao, senao uma ficha
            // antiga (18 pericias, todas sem id) colapsa para uma.
            String id = pericia.id().isEmpty() ? freshPericiaId(out) : pericia.id();
            if (seen.add(id.toLowerCase(Locale.ROOT))) {
                out.add(new Pericia(id, pericia.name(), pericia.value(), pericia.attributeId()));
            }
        }
        return List.copyOf(out);
    }

    /**
     * O primeiro {@code pericia_N} (N &gt;= 1) cujo id nao esta em uso na lista
     * dada - o mesmo formato e a mesma regra do {@code SheetModel}, para que uma
     * ficha e o modelo nunca inventem ids com a mesma cara.
     *
     * <p>Contador, e nao UUID, pelas mesmas duas razoes do {@code SheetModel}: o
     * id gerado vai para o NBT e o mesmo save tem de produzir sempre o mesmo
     * id, e um id reusado faria a pericia nova herdar, no {@link SheetModel#align},
     * o valor da antiga. O {@code omitempty} do {@code Codec} do DataFixerUpper
     * nao e a razao: com um id nao deterministico o campo seria sempre escrito e
     * o arquivo fecharia igual.
     */
    private static String freshPericiaId(List<Pericia> current) {
        Set<String> used = new LinkedHashSet<>();
        for (Pericia pericia : current) {
            if (pericia.id().isEmpty()) {
                continue;
            }
            used.add(pericia.id().toLowerCase(Locale.ROOT));
        }
        int n = 1;
        while (used.contains(("pericia_" + n).toLowerCase(Locale.ROOT))) {
            n++;
        }
        return "pericia_" + n;
    }

    /**
     * Corta o valor de cada atributo no intervalo que o Mestre definiu.
     *
     * <p>Devolve a <b>mesma instancia</b> quando nada precisou ser cortado, para
     * que o caminho comum (uma edicao de nome, uma skill) nao aloque uma lista e
     * uma ficha nova a cada gravacao do NBT.
     */
    private static Attributes clampAttributes(Attributes source, int min, int max) {
        List<Attributes.AttributeValue> out = new ArrayList<>(source.values().size());
        boolean changed = false;
        for (Attributes.AttributeValue value : source.values()) {
            int limited = clamp(value.value(), min, max);
            changed = changed || limited != value.value();
            out.add(limited == value.value() ? value
                    : new Attributes.AttributeValue(value.id(), limited));
        }
        return changed ? new Attributes(out) : source;
    }

    /**
     * Corta o valor de cada pericia em {@code 0..max}.
     *
     * <p>O piso 0 e fixo e nao vem do modelo (ver o Javadoc da classe), entao o
     * intervalo e montado aqui em vez de vir em dois inteiros. Mesma regra de
     * devolver a mesma instancia quando nada mudou.
     */
    private static List<Pericia> clampPericiaValues(List<Pericia> source, int max) {
        List<Pericia> out = new ArrayList<>(source.size());
        boolean changed = false;
        for (Pericia pericia : source) {
            int limited = clamp(pericia.value(), 0, max);
            changed = changed || limited != pericia.value();
            out.add(limited == pericia.value() ? pericia
                    : new Pericia(pericia.id(), pericia.name(), limited, pericia.attributeId()));
        }
        return changed ? List.copyOf(out) : source;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    /**
     * Corta o peso em {@code 0..WEIGHT_MAX} (30/09/2026, FASE 2B).
     *
     * <p><b>NaN e infinito viram 0</b>, e nao passam reto: eles chegam de um
     * {@code ByteBufCodecs.FLOAT} adulterado ou de um NBT editado a mao, e
     * {@code Math.min(NaN, x)} devolve {@code NaN} -- que contaminaria a soma de
     * {@link Inventory#totalWeight()} e a comparacao de {@link
     * Inventory#overweight()} para sempre, sem erro nenhum.
     */
    private static float clampWeight(float value) {
        if (!Float.isFinite(value)) {
            return 0f;
        }
        return Math.max(0f, Math.min(value, InventoryItem.WEIGHT_MAX));
    }

    /**
     * Arredonda o peso para 2 casas decimais (decisao do usuario em 30/09/2026).
     *
     * <p>E o {@code Math.round(w * 100f) / 100f} do enunciado: o
     * {@code Math.round(float)} devolve {@code int}, e o total de 50 itens de
     * peso {@link InventoryItem#WEIGHT_MAX} multiplicado por 100 cabe folgado
     * num {@code int}.
     */
    private static float roundWeight(float value) {
        return Math.round(value * 100f) / 100f;
    }

    /**
     * Peso como texto, sempre com 2 casas (decisao do usuario em 30/09/2026).
     *
     * <p>{@link Locale#ROOT} e obrigatorio: com a locale padrao do sistema (pt-BR
     * no Windows) o separador viraria virgula, e o eco do servidor apareceria
     * como {@code 12,34} na caixa, que so aceita digito e ponto.
     */
    public static String formatWeight(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * Higiene da lista de itens (30/09/2026, FASE 2B).
     *
     * <p>Mesmo desenho do {@link #sanitizeSkills}: descarta nulo e item sem
     * nome, reconstroi os que ficam (o construtor do record normaliza de novo,
     * porque a lista pode vir de um payload sem passar por {@link
     * Inventory#addItem}) e corta em {@link Inventory#MAX_ITEMS}.
     *
     * <p><b>Nao deduplica por nome</b>, ao contrario das skills: dois itens com o
     * mesmo nome sao legitimos ("Corda" em duas mochilas), e o jogador e quem
     * decide o que e duplicado.
     */
    private static List<InventoryItem> sanitizeItems(List<InventoryItem> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<InventoryItem> out = new ArrayList<>(Math.min(raw.size(), Inventory.MAX_ITEMS));
        for (InventoryItem item : raw) {
            if (item == null) {
                continue;
            }
            InventoryItem clean = new InventoryItem(item.name(), item.type(), item.weight(), item.description());
            if (clean.name().isEmpty()) {
                continue;
            }
            out.add(clean);
            if (out.size() >= Inventory.MAX_ITEMS) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback; // texto não numérico: mantém o valor anterior
        }
    }

    /**
     * Le o peso que o cliente mandou como texto (30/09/2026, FASE 2B).
     *
     * <p><b>Texto incompleto mantem o valor anterior</b> em vez de virar 0: e o
     * que acontece com o texto "12." que o jogador esta digitando quando o
     * pacote sai, e zerar o limite nele seria pior do que nao gravar nada. O
     * corte e o arredondamento ficam com {@link Inventory#withMaxWeight}.
     */
    private static float parseFloat(String value, float fallback) {
        if (value.endsWith(".")) {
            return fallback; // texto incompleto: mantem o valor anterior
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return fallback; // texto nao numerico: mantem o valor anterior
        }
    }
}
