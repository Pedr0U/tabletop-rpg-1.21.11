package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetModel;
import com.pedro.tabletoprpg.SheetModelHolder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tela do Sheet Editor, aberta pelo item {@code Sheet Editor}.
 *
 * <p>27/09/2026. O Mestre edita aqui o <b>molde</b> da ficha: os rotulos dos
 * campos, o que aparece, o modo do XP, e as listas de atributos e pericias. Os
 * <b>valores</b> continuam sendo de cada jogador e sao preservados: o que muda
 * aqui e o nome das coisas, nao o que o jogador preencheu.
 *
 * <p><b>Estrutura (decisao do usuario, 27/09/2026):</b> uma coluna so, com tudo
 * rolando junto. Uma coluna e mais facil de acertar do que duas areas de rolagem,
 * e o conteudo cabe em uma lista unica e previsivel.
 *
 * <p><b>Edicao em memoria, gravacao no botao Salvar (decisao do usuario):</b> a
 * tela mexe numa copia ({@code staged}) e so envia quando o Mestre aperta Salvar.
 * Descartar joga fora a copia e volta ao que o servidor tem. O preco e que nao
 * existe "desfazer" para uma edicao ja gravada; o preco da alternativa (gravar a
 * cada tecla) seria reescrever o modelo do mundo a cada tecla.
 *
 * <p><b>Fechar sem salvar nao apaga a edicao (decisao do usuario,
 * 27/09/2026):</b> sair da tela sem gravar -- pelo ESC, pelo botao Close ou por
 * qualquer outra troca de tela -- guarda um {@code rascunho} (ver
 * {@link #draft}) e a proxima abertura comeca por ele. Ele <b>nao entra em
 * vigor</b>: o modelo do servidor continua sendo o de {@code baseline} e nada e
 * enviado. So o Descartar volta ao salvo e so o Salvar grava.
 *
 * <p><b>Mas o rascunho e por sessao de mundo, nao por tela (27/09/2026):</b>
 * ele e estatico, e por isso sobreviveria a uma saida do mundo. Entrar em outro
 * mundo com o rascunho do primeiro faria a proxima abertura comecar pelo modelo
 * errado ({@code baseline} seria o do mundo novo) e um Salvar gravaria o modelo
 * do mundo antigo no {@code SheetModelStore} do novo. E o motivo de existir
 * {@link #discardTransientState()}, chamado no cleanup de desconexao.
 *
 * <p><b>So o Mestre chega aqui:</b> o servidor so envia o pacote de abrir depois
 * de {@code SessionManager.isMaster}. Esta tela nao refaz a checagem de proposito.
 * Se ela aparecesse para um jogador comum, o problema seria de permissao no
 * servidor, e nao de UI; revezar a checagem aqui esconderia o bug.
 *
 * <p><b>O cliente nao e autoridade.</b> A tela manda o modelo inteiro, mas o
 * servidor reconstroi o {@code SheetModel} na desserializacao, o que dispara o
 * construtor compacto e saneia rotulos, duplicatas e os tetos de 10/30. Um
 * pacote forjado chega limpo, e o servidor ainda exige Mestre antes de gravar.
 */
public class SheetEditorScreen extends Screen {

    // ------------------------------------------------------------------
    // LAYOUT
    // ------------------------------------------------------------------

    private static final int TITLE_Y = 10;
    private static final int CONTENT_TOP = 28;
    private static final int FOOTER_H = 26;
    /** Largura maxima da coluna; acima disso a linha fica desconfortavel de ler. */
    private static final int MAX_COL_W = 460;
    private static final int COL_PADDING = 12;
    /** Altura de uma linha de controle. */
    private static final int ROW_H = 20;
    /** Altura de um cabecalho de secao. */
    private static final int HEADER_H = 24;
    /** Espaco reservado ao texto do campo, a esquerda do controle. */
    private static final int CAP_W = 104;
    private static final int GAP = 4;
    private static final int BTN_W = 24;
    /** Largura do botao que cicla o atributo padrao de uma pericia. */
    private static final int ATTR_BTN_W = 56;

    private static final int COL_TITLE = 0xFFFFFFFF;
    private static final int COL_HEADER = 0xFFFFC44D;
    private static final int COL_CAPTION = 0xFFB8B8C4;
    private static final int COL_HINT = 0xFF8A8A94;

    // ------------------------------------------------------------------
    // ESTADO
    // ------------------------------------------------------------------

    /** A copia em edicao. Nunca null. */
    private SheetModel staged = takeDraft();
    /** O que o servidor tinha quando a tela montou, ou o ultimo que foi salvo. */
    private SheetModel baseline = SheetModelHolder.current();

    /**
     * O que o Mestre digitou numa linha de pericia e que o modelo ainda nao
     * aceitou, por <b>posicao</b> na lista: {@code posicao -> texto digitado}.
     *
     * <p><b>O nome vazio nao entra no modelo, e a tela nao finge que entrou.</b>
     * {@code SheetModel.sanitizePericias} <b>descarta</b> a pericia de nome
     * vazio e, se a lista toda sobrar vazia, restaura as 18 padrao: deixar o
     * vazio no modelo deslocaria todas as linhas seguintes, e o botao de atributo
     * e o {@code X} passariam a operar na pericia errada: a identidade da linha
     * aqui e a posicao ({@link #periciaAt}). O modelo guarda o ultimo nome valido
     * e o texto digitado fica aqui; enquanto o mapa nao estiver vazio o Salvar
     * fica desligado ({@link #hasPendingName}).
     *
     * <p><b>E estatico de proposito:</b> a tela e montada de novo a cada
     * abertura do item ({@code TabletopRpgClient} faz {@code new
     * SheetEditorScreen()}), e o texto digitado precisa continuar na caixa depois
     * de um ESC, que monta outra instancia. Vive so na memoria do cliente.
     */
    private static final Map<Integer, String> pendingNames = new LinkedHashMap<>();

    /**
     * A copia que o Mestre deixou na tela e fechou sem salvar.
     *
     * <p><b>Fecha sem salvar nao e cancelar.</b> Reabrir o item comeca por ele
     * ({@link #takeDraft}) e ele <b>nao entra em vigor</b>: o modelo do
     * servidor continua sendo o de {@code baseline} e nada e enviado. Descartar
     * volta ao salvo e Salvar grava; os dois apagam este campo.
     *
     * <p><b>E estatico pela mesma razao de {@link #pendingNames}:</b> um campo
     * de instancia desapareceria no ESC, que e justamente o caminho que precisa
     * guardar. Nao vai para disco, nem para o {@code SheetModel}, nem para o
     * {@code SheetData}, nem para pacote: dura ate o fim da sessao.
     */
    private static SheetModel draft;

    private int scrollPx;

    /**
     * Impede o eco ao reescrever o texto de uma caixa por codigo.
     *
     * <p>Precisa porque {@code setValue} dispara o responder, e sem esta guarda a
     * correcao do texto (ver {@link #periciaRow}) chamaria o modelo de novo, que
     * recusaria de novo, e o resultado seria um laco.
     */
    private boolean suppressNotify;

    /**
     * Botoes do rodape cujo estado e conferido a cada frame (ver {@link #render}).
     *
     * <p>Sao campos, e nao variaveis locais de {@code buildFooter}, porque o
     * estado deles e ajustado a cada frame: so com o retorno de
     * {@code addRenderableWidget} nao haveria como ligar e desligar depois que a
     * tela ja foi montada. O Salvar segue {@link #isDirty()} e tambem
     * {@link #hasPendingName()}; o Descartar segue os dois
     * ({@link #canDiscard()}); o Restaurar segue o padrao do modelo, e nao o que
     * foi salvo.
     */
    private Button saveButton;
    private Button discardButton;
    private Button resetButton;

    /** Controles de conteudo, com a posicao que teriam sem rolagem. */
    private final List<Slot> slots = new ArrayList<>();
    /** Textos desenhados a mao (cabecalhos e contadores), tambem sem rolagem. */
    private final List<Caption> captions = new ArrayList<>();
    private int contentHeight;

    private record Slot(AbstractWidget widget, int layoutY) {
    }

    private record Caption(int layoutY, Component text, int color) {
    }

    public SheetEditorScreen() {
        super(tr("title"));
    }

    // ------------------------------------------------------------------
    // GEOMETRIA DA COLUNA
    // ------------------------------------------------------------------

    private int colX() {
        return (this.width - colW()) / 2;
    }

    private int colW() {
        return Math.max(260, Math.min(MAX_COL_W, this.width - 2 * COL_PADDING));
    }

    /** X onde comecam os controles (a direita do texto do campo). */
    private int ctrlX() {
        return colX() + CAP_W + GAP;
    }

    /** Largura util dos controles, ate a borda direita da coluna. */
    private int ctrlW() {
        return colW() - CAP_W - GAP;
    }

    private int contentBottom() {
        return this.height - FOOTER_H;
    }

    // ------------------------------------------------------------------
    // MONTAGEM
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        slots.clear();
        captions.clear();
        buildContent();
        buildFooter();
        applyScroll();
    }

    /** Constroi a coluna unica, na ordem em que a ficha desenha os campos. */
    private void buildContent() {
        int x = ctrlX();
        int w = ctrlW();
        int y = 0;

        y = header(y, "fields");
        y = labelField(y, "field_name", staged.nameLabel(),
                v -> staged = staged.withLabel("characterName", v));
        y = labelField(y, "field_race", staged.raceLabel(),
                v -> staged = staged.withLabel("race", v));
        y = toggleField(y, "field_race", staged.raceEnabled(),
                v -> staged = staged.withEnabled("race", v));
        y = labelField(y, "field_class", staged.classLabel(),
                v -> staged = staged.withLabel("characterClass", v));
        y = labelField(y, "field_background", staged.backgroundLabel(),
                v -> staged = staged.withLabel("background", v));
        y = labelField(y, "field_hp", staged.hpLabel(),
                v -> staged = staged.withLabel("hp", v));
        y = labelField(y, "field_mana", staged.manaLabel(),
                v -> staged = staged.withLabel("mana", v));
        y = toggleField(y, "field_mana", staged.manaEnabled(),
                v -> staged = staged.withEnabled("mana", v));
        y = labelField(y, "field_level", staged.levelLabel(),
                v -> staged = staged.withLabel("level", v));
        y = labelField(y, "field_xp", staged.xpLabel(),
                v -> staged = staged.withLabel("xp", v));
        // 01/10/2026: o rotulo do CA, que o Mestre edita como os outros. Na ficha
        // o CA divide a linha com o Level, entao ele precisa de rotulo proprio --
        // sem este campo o Mestre nao teria como trocar "CA" por "Armadura" sem
        // editar o NBT na mao.
        y = labelField(y, "field_ca", staged.caLabel(),
                v -> staged = staged.withLabel("ca", v));
        y = xpModeField(y);
        y = header(y, "value_limits");
        y = limitField(y, "attribute_min", staged.attributeValueMin(), true,
                v -> staged = staged.withValueLimits(v,
                        staged.attributeValueMax(), staged.periciaValueMax()));
        y = limitField(y, "attribute_max", staged.attributeValueMax(), false,
                v -> staged = staged.withValueLimits(
                        staged.attributeValueMin(), v, staged.periciaValueMax()));
        y = limitField(y, "pericia_max", staged.periciaValueMax(), false,
                v -> staged = staged.withValueLimits(
                        staged.attributeValueMin(), staged.attributeValueMax(), v));

        y = header(y, "attributes");
        for (SheetModel.AttributeDef def : staged.attributes()) {
            y = attributeRow(y, def);
        }
        y = addButton(y, "add_attribute", staged.attributeCount() < SheetModel.MAX_ATTRIBUTES,
                staged.attributeCount(), SheetModel.MAX_ATTRIBUTES,
                () -> {
                    staged = staged.addAttribute();
                    rebuildWidgets();
                });

        y = header(y, "pericias");
        List<SheetModel.PericiaDef> pericias = staged.pericias();
        for (int i = 0; i < pericias.size(); i++) {
            y = periciaRow(y, pericias.get(i), i);
        }
        y = addButton(y, "add_pericia", staged.periciaCount() < SheetModel.MAX_PERICIAS,
                staged.periciaCount(), SheetModel.MAX_PERICIAS,
                () -> {
                    staged = staged.addPericia();
                    rebuildWidgets();
                });

        contentHeight = y;
    }

    private int header(int y, String key) {
        captions.add(new Caption(y + 6, tr(key), COL_HEADER));
        return y + HEADER_H;
    }

    /** Rotulo de um campo de sistema: texto a esquerda, caixa a direita. */
    private int labelField(int y, String key, String current, Consumer<String> apply) {
        captions.add(new Caption(y + 6, tr(key), COL_CAPTION));
        EditBox box = new EditBox(this.font, ctrlX(), y, ctrlW(), ROW_H - 4, tr(key));
        box.setMaxLength(SheetModel.LABEL_MAX);
        box.setValue(current);
        box.setResponder(value -> {
            if (!suppressNotify) {
                apply.accept(value);
            }
        });
        addRenderableWidget(box);
        slots.add(new Slot(box, y));
        return y + ROW_H;
    }

    /**
     * Um interruptor de dois estados, com o rotulo do campo a esquerda.
     *
     * <p><b>O estado mora num holder, e nao no parametro {@code current}.</b> O
     * parametro e congelado por valor quando o botao e montado, entao
     * {@code !current} seria uma constante e todo clique enviaria o mesmo valor --
     * o botao so mudaria na primeira vez. O holder e lido e escrito a cada
     * clique, como em {@link #xpModeField}.
     */
    private int toggleField(int y, String key, boolean current, Consumer<Boolean> apply) {
        captions.add(new Caption(y + 6, tr(key), COL_CAPTION));
        boolean[] on = {current};
        Button button = Button.builder(toggleText(on[0]), b -> {
            on[0] = !on[0];
            apply.accept(on[0]);
            b.setMessage(toggleText(on[0]));
        }).bounds(ctrlX(), y, 60, ROW_H - 4).build();
        addRenderableWidget(button);
        slots.add(new Slot(button, y));
        return y + ROW_H;
    }

    /** XP e um modo de tres estados, nao um interruptor de dois. */
    private int xpModeField(int y) {
        captions.add(new Caption(y + 6, tr("field_xp_mode"), COL_CAPTION));
        SheetModel.XpMode[] mode = {staged.xp()};
        Button button = Button.builder(xpModeText(mode[0]), b -> {
            mode[0] = mode[0] == SheetModel.XpMode.NUMBER
                    ? SheetModel.XpMode.TEXT
                    : mode[0] == SheetModel.XpMode.TEXT
                            ? SheetModel.XpMode.HIDDEN
                            : SheetModel.XpMode.NUMBER;
            staged = staged.withXpMode(mode[0]);
            b.setMessage(xpModeText(mode[0]));
        }).bounds(ctrlX(), y, ctrlW(), ROW_H - 4).build();
        addRenderableWidget(button);
        slots.add(new Slot(button, y));
        return y + ROW_H;
    }

    /**
     * Uma linha de limite de valor: rotulo a esquerda, caixa numerica a direita.
     *
     * <p><b>O que entra em {@code staged} e so um numero completo.</b> Cada tecla
     * dispara o {@code responder}, e um "-", um "3" ou uma string vazia sao
     * estados intermediarios, nao limites: entrar com eles deixaria a regra do
     * modelo com teto "-3" por um instante, e o {@code withValueLimits} ja
     * corrige teto para piso nesse caso. Como nao ha nada a enviar enquanto a
     * digitacao nao fecha (o save so acontece no botao), o que fica e o ultimo
     * numero completo no {@code staged}, e a caixa mostra o texto digitado.
     * Sem isso, digitar "30" para trocar "-30" por "30" passaria por "-3" e o
     * modelo gravaria teto 0 no meio da digitacao.
     *
     * <p><b>{@code signed} so no piso do atributo.</b> E o unico dos tres que
     * aceita negativo, e por um motivo de regra: o valor da pericia tem piso 0
     * fixo (o bonus soma, e negativo ali viraria penalidade) e um teto de
     * atributo negativo nao tem sentido util. O filtro e o mesmo
     * {@code -?\d{0,9}} da ficha ({@code createFieldBox}), entao o sinal pode
     * ser digitado em qualquer posicao e o numero fica longe do overflow.
     *
     * <p><b>O que acontece com um intervalo todo negativo, ja gravado:</b> o
     * Mestre pode digitar piso -5 e teto 0, o que deixa a pericia e o atributo
     * num intervalo que so aceita valores de -5 a 0. A caixa do teto mostra
     * "0" e aceita nao menos que 0, entao ele nao consegue digitar "-5" la -- e
     * nao precisa: o piso -5 ja faz o teto efetivo ser -5 pelo clamp do
     * {@code SheetModel}, e o valor e o mesmo. Um NBT editado a mao com teto
     * negativo ainda e lido sem erro, porque quem corrige e o construtor
     * compacto, e nao o filtro da tela.
     *
     * <p><b>Sem {@code rebuildWidgets()}, de proposito:</b> as outras linhas
     * guardam o texto pendente em {@code pendingNames} justamente para nao ter
     * que remontar. Aqui nao ha texto a preservar -- a caixa e a fonte da
     * verdade enquanto o campo esta em foco -- e remontar a cada tecla jogaria
     * fora o foco e o cursor. O {@code staged} atualizado por tecla e o que o
     * botao Salvar envia, entao a edicao ja vale sem remontar.
     */
    private int limitField(int y, String key, int current, boolean signed, Consumer<Integer> apply) {
        captions.add(new Caption(y + 6, tr(key), COL_CAPTION));
        EditBox box = new EditBox(this.font, ctrlX(), y, ctrlW(), ROW_H - 4, tr(key));
        box.setFilter(s -> s.matches(signed ? "-?\\d{0,9}" : "\\d{0,9}"));
        box.setMaxLength(10);
        box.setValue(Integer.toString(current));
        box.setResponder(value -> {
            if (suppressNotify) {
                return;
            }
            Integer parsed = parseCompleteNumber(value);
            if (parsed != null) {
                apply.accept(parsed);
            }
        });
        addRenderableWidget(box);
        slots.add(new Slot(box, y));
        return y + ROW_H;
    }

    /**
     * O texto da caixa como numero, ou {@code null} enquanto nao for um numero
     * completo.
     *
     * <p>{@code null} cobre as tres formas incompletas: texto vazio (o Mestre
     * apagou tudo), so o sinal ("-") e um numero que estourou o {@code int}. O
     * filtro ja impede as outras, mas o guarda e o que impede o
     * {@code NumberFormatException} de chegar ao {@code apply}.
     */
    private static Integer parseCompleteNumber(String value) {
        if (value == null || value.isEmpty() || "-".equals(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Uma linha por atributo: sigla editavel, nome editavel e o botao de tirar.
     *
     * <p><b>O id nao e editavel, de proposito.</b> O id e a identidade do
     * atributo: e por ele que o valor guardado na ficha do jogador e reencontrado.
     * Se o Mestre pudesse trocar o id, renomear a sigla de um atributo apagaria o
     * valor dele em todas as fichas ja salvas. Os ids gerados sao {@code attr_1},
     * {@code attr_2} e assim por diante.
     */
    private int attributeRow(int y, SheetModel.AttributeDef def) {
        String id = def.id();
        int half = (ctrlW() - GAP - BTN_W - GAP) / 2;
        int rightX = colX() + colW() - BTN_W;

        EditBox label = new EditBox(this.font, ctrlX(), y, half, ROW_H - 4, tr("abbreviation"));
        label.setMaxLength(SheetModel.LABEL_MAX);
        label.setValue(def.label());
        label.setResponder(value -> {
            if (!suppressNotify) {
                // Le o nome de {@code staged} no momento da tecla, e nao a copia
                // congelada quando a linha foi montada: assim editar a sigla e o
                // nome na mesma sessao nao faz um sobrescrever o outro.
                SheetModel.AttributeDef live = staged.attribute(id);
                staged = staged.withAttributeText(id, value, live == null ? def.name() : live.name());
            }
        });

        EditBox name = new EditBox(this.font, ctrlX() + half + GAP, y, half, ROW_H - 4, tr("name"));
        name.setMaxLength(SheetModel.LABEL_MAX);
        name.setValue(def.name());
        name.setResponder(value -> {
            if (!suppressNotify) {
                SheetModel.AttributeDef live = staged.attribute(id);
                staged = staged.withAttributeText(id, live == null ? def.label() : live.label(), value);
            }
        });

        Button remove = Button.builder(Component.literal("X"), b -> {
            staged = staged.removeAttribute(id);
            rebuildWidgets();
        }).bounds(rightX, y, BTN_W, ROW_H - 4).build();
        remove.active = staged.attributeCount() > SheetModel.MIN_ATTRIBUTES;

        addRenderableWidget(label);
        addRenderableWidget(name);
        addRenderableWidget(remove);
        slots.add(new Slot(label, y));
        slots.add(new Slot(name, y));
        slots.add(new Slot(remove, y));
        return y + ROW_H;
    }

    /**
     * Uma linha por pericia: nome editavel, atributo padrao e o botao de tirar.
     *
     * <p><b>A linha e identificada pela posicao, nunca pelo nome.</b> O nome e o
     * que o Mestre esta digitando, entao usar o nome capturado na montagem da
     * linha faz a busca errar a partir da segunda tecla: o modelo ja nao tem
     * mais aquela pericia com aquele nome, a busca volta {@code null} e a tela
     * cai no ramo de reversao, devolvendo o texto antigo na caixa e deixando no
     * modelo so a primeira letra. A posicao na lista e estavel durante a vida da
     * linha porque {@code withPericiaText} substitui a pericia no lugar e
     * qualquer mudanca na quantidade de pericias passa por
     * {@code rebuildWidgets()}, que remonta as linhas.
     *
     * <p><b>Nome vazio fica na caixa e nao entra no modelo.</b> Antes,
     * {@code withPericiaText} recusava o vazio, a tela comparava por identidade
     * ({@code next == staged}) e devolvia o texto antigo na caixa, e o
     * {@code setValue} leva o cursor para o fim, entao o Mestre apertava Backspace,
     * a letra voltava e o cursor pulava para o fim. Agora a caixa mostra o que foi
     * digitado, o modelo continua com o ultimo nome valido
     * ({@link #pendingNames}) e so volta a ser possivel gravar quando o nome
     * voltar a ser valido (decisao do Mestre em 27/09/2026). So espacos contam
     * como vazio porque o modelo faz {@code trim()}.
     *
     * <p><b>Nome ja usado por outra pericia fica na caixa e no rascunho
     * (28/09/2026).</b> Antes esse caso voltava para a caixa, e recusa no modelo
     * e reversao na tela eram a mesma coisa: o Mestre nao conseguia nem digitar o
     * nome. Agora o nome repetido entra em {@code staged} como o valido, a caixa
     * mostra o que foi digitado e quem barra e o <b>Salvar</b>, desligado por
     * {@link #hasDuplicateName()}, com o aviso de nome repetido no lugar do de
     * "unsaved changes". O Descartar continua sendo a saida
     * ({@link #canDiscard()}).
     *
     * <p><b>O {@code X} nao depende do nome.</b> Ele remove por
     * {@code periciaAt(pos)}, a posicao, e nao pelo nome: e por isso que continua
     * sendo a saida de um nome invalido, e o unico caminho, ja que o Salvar fica
     * desligado enquanto houver um.
     */
    private int periciaRow(int y, SheetModel.PericiaDef def, int pos) {
        int rightX = colX() + colW() - BTN_W;
        int nameW = ctrlW() - GAP - ATTR_BTN_W - GAP - BTN_W;

        EditBox name = new EditBox(this.font, ctrlX(), y, nameW, ROW_H - 4, tr("name"));
        name.setMaxLength(SheetModel.LABEL_MAX);
        // rebuildWidgets() remonta a linha: a caixa volta com o texto que o Mestre
        // deixou e nao com o nome valido que esta por tras dele.
        name.setValue(pendingNames.containsKey(pos) ? pendingNames.get(pos) : def.name());
        name.setResponder(value -> {
            if (suppressNotify) {
                return;
            }
            // Le a pericia de {@code staged} no momento da tecla: o texto da caixa
            // ja e o nome novo, e o modelo precisa ser consultado pelo id que a
            // linha tem agora, e nao pelo nome que ela tinha quando foi montada.
            // 28/09/2026: a chave e' o {@code id} da pericia (o nome e' o que o
            // Mestre esta digitando, e por isso muda a cada tecla).
            SheetModel.PericiaDef live = periciaAt(pos);
            if (live == null) {
                return;
            }
            if (value == null || value.trim().isEmpty()) {
                pendingNames.put(pos, value == null ? "" : value);
                return;
            }
            SheetModel next = staged.withPericiaText(live.id(), value, live.attributeId());
            pendingNames.remove(pos);
            staged = next;
        });

        Button attr = Button.builder(Component.literal(staged.attributeLabel(def.attributeId())), b -> {
            SheetModel.PericiaDef live = periciaAt(pos);
            if (live == null || staged.attributes().isEmpty()) {
                return;
            }
            int index = attributeIndexOf(live.attributeId());
            int next = ((index < 0 ? 0 : index) + 1) % staged.attributes().size();
            String id = staged.attributes().get(next).id();
            staged = staged.withPericiaText(live.id(), live.name(), id);
            b.setMessage(Component.literal(staged.attributeLabel(id)));
        }).bounds(ctrlX() + nameW + GAP, y, ATTR_BTN_W, ROW_H - 4).build();
        attr.active = attributeIndexOf(def.attributeId()) >= 0;

        Button remove = Button.builder(Component.literal("X"), b -> {
            SheetModel.PericiaDef live = periciaAt(pos);
            if (live == null) {
                return;
            }
            staged = staged.removePericia(live.id());
            shiftPendingAbove(pos);
            rebuildWidgets();
        }).bounds(rightX, y, BTN_W, ROW_H - 4).build();
        remove.active = staged.periciaCount() > SheetModel.MIN_PERICIAS;

        addRenderableWidget(name);
        addRenderableWidget(attr);
        addRenderableWidget(remove);
        slots.add(new Slot(name, y));
        slots.add(new Slot(attr, y));
        slots.add(new Slot(remove, y));
        return y + ROW_H;
    }

    private int addButton(int y, String key, boolean canAdd, int count, int max, Runnable action) {
        Button button = Button.builder(tr(key), b -> action.run())
                .bounds(ctrlX(), y, 120, ROW_H - 4).build();
        button.active = canAdd;
        addRenderableWidget(button);
        slots.add(new Slot(button, y));
        captions.add(new Caption(y + 6, tr("count", count, max), COL_HINT));
        return y + ROW_H;
    }

    /**
     * Monta o rodape: Salvar, Descartar, Reset e Close.
     *
     * <p><b>A largura do rodape acompanha a janela.</b> A conta vem de
     * {@link #colW()}, que ja respeita a largura real, em vez de um total fixo de
     * 418px: em 1600x900 com escala 4 a tela tem 400px, o {@code x} ficava
     * negativo e o primeiro botao saia cortado. Agora a folga e a largura de cada
     * botao encolhem junto e os quatro cabem entre 0 e {@code this.width}. A
     * folga nunca cai abaixo de 2px, e na menor janela possivel (320px de tela,
     * ja com escala 4) cada botao ainda fica com 72px, o que comporta o rotulo
     * mais longo do rodape.
     */
    private void buildFooter() {
        int y = this.height - FOOTER_H + 4;
        int span = Math.min(colW(), this.width);
        int gap = Math.max(2, span / 100);
        int bw = (span - 3 * gap) / 4;
        int x = Math.max(0, (this.width - (4 * bw + 3 * gap)) / 2);

        saveButton = addRenderableWidget(Button.builder(tr("save"), b -> save())
                .bounds(x, y, bw, ROW_H - 2).build());
        discardButton = addRenderableWidget(Button.builder(tr("discard"), b -> discard())
                .bounds(x + (bw + gap), y, bw, ROW_H - 2).build());
        resetButton = addRenderableWidget(Button.builder(tr("reset"), b -> {
            staged = SheetModel.defaults();
            // O modelo inteiro foi trocado: as marcas de nome pendente eram
            // posicoes da lista antiga, e o rascunho era de outra edicao.
            clearDraft();
            rebuildWidgets();
        }).bounds(x + 2 * (bw + gap), y, bw, ROW_H - 2).build());
        addRenderableWidget(Button.builder(tr("close"), b -> onClose())
                .bounds(x + 3 * (bw + gap), y, bw, ROW_H - 2).build());

        // Descartar so faz sentido com algo para descartar. O estado dos tres
        // botoes de edicao e conferido de novo a cada frame, no render, porque
        // digitar numa caixa muda o modelo sem passar por init(): definido so
        // aqui, eles ficariam desatualizados durante a digitacao. O Descartar
        // entra tambem com um nome invalido pendente (ver o render): sem isso,
        // apagar um nome e nao mexer em mais nada deixaria o Descartar e o
        // Restaurar desligados e o Salvar tambem, sem saida para o texto valido.
        discardButton.active = canDiscard();
        resetButton.active = !staged.equals(SheetModel.defaults());
    }

    // ------------------------------------------------------------------
    // ROLAGEM
    // ------------------------------------------------------------------

    private int maxScroll() {
        return Math.max(0, contentHeight - (contentBottom() - CONTENT_TOP));
    }

    /**
     * Reposiciona os controles conforme a rolagem e esconde os que ficaram fora.
     *
     * <p><b>Esconder em vez de recortar:</b> recortar exigiria scissor e entails
     * desenhar os filhos a mao, porque o {@code super.render()} do vanilla desenha
     * todos de uma vez, incluindo o rodape. Marcar {@code visible = false} usa o
     * caminho ja testado do vanilla e deixa o rodape sempre visivel.
     *
     * <p><b>So entra o que cabe inteiro (27/09/2026).</b> O teste antigo era
     * {@code y + ROW_H > top}, e {@code ROW_H} (20) e a altura da <i>linha</i>,
     * nao a do widget ({@code ROW_H - 4}, 16): com a rolagem em uma linha o
     * primeiro controle ia parar em {@code y = 12}, dentro da faixa do titulo
     * ({@code TITLE_Y = 10}) e acima do painel, que comeca em {@code CONTENT_TOP
     * - 2}. O titulo era desenhado depois de {@code super.render()}, entao a caixa
     * sumia debaixo dele. {@link #fitsInPanel} mede a altura real do widget e usa
     * a mesma regra para controle e {@link Caption}, o que resolve as duas metades
     * do sintoma: nada na faixa do titulo, nada fora do painel, e cabecalho e
     * controle somem juntos na borda.
     */
    private void applyScroll() {
        scrollPx = Math.max(0, Math.min(scrollPx, maxScroll()));
        int top = CONTENT_TOP;
        int bottom = contentBottom();
        for (Slot slot : slots) {
            int y = top + slot.layoutY() - scrollPx;
            slot.widget().setY(y);
            slot.widget().visible = fitsInPanel(y, slot.widget().getHeight(), top, bottom);
        }
    }

    /**
     * Se uma caixa de altura {@code h} cabe inteira na janela de conteudo.
     *
     * <p><b>Um criterio so para controle e {@link Caption}.</b> Os dois ocupam a
     * mesma faixa horizontal e nao podem aparecer e sumir em bordas diferentes: o
     * cabecalho sumindo com a caixa ainda na tela (e o inverso) era a assimetria
     * que denunciava o teste com {@code ROW_H} em {@link #applyScroll}. Caber
     * inteiro tambem garante que nada seja desenhado sobre a borda do painel nem
     * por cima do rodape.
     */
    private static boolean fitsInPanel(int y, int h, int top, int bottom) {
        return y >= top && y + h <= bottom;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= CONTENT_TOP && mouseY < contentBottom() && maxScroll() > 0) {
            int before = scrollPx;
            scrollPx -= (int) Math.signum(scrollY) * ROW_H;
            if (scrollPx != before) {
                applyScroll();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------
    // ACOES
    // ------------------------------------------------------------------

    private boolean isDirty() {
        return !staged.equals(baseline);
    }

    /**
     * Ha alguma linha de pericia sem nome valido.
     *
     * <p>E o que desliga o Salvar: o {@code SheetModel} recusa o nome vazio, e
     * mandar o modelo com a pericia pendurada nao gravaria o que o Mestre ve na
     * tela.
     */
    private static boolean hasPendingName() {
        return !pendingNames.isEmpty();
    }

    /**
     * Ha duas ou mais pericias com o mesmo nome na copia em edicao.
     *
     * <p><b>28/09/2026:</b> o modelo passou a aceitar o nome repetido (ver
     * {@link SheetModel#withPericiaText}), porque recusar la fazia a caixa
     * devolver sozinha o texto antigo e o Mestre nao conseguia digitar. O que
     * segura a duplicata e a tela: o Salvar fica desligado e o aviso de nome
     * repetido entra na fila do {@code render()}, entre o de nome vazio e o de
     * "unsaved changes".
     *
     * <p>A comparacao ignora caixa porque o nome e o que o Mestre le na linha, e
     * porque e assim que o resto do projeto compara nome de pericia (ver
     * {@link SheetModel#periciaByName(String)}).
     */
    private boolean hasDuplicateName() {
        List<SheetModel.PericiaDef> pericias = staged.pericias();
        for (int i = 0; i < pericias.size(); i++) {
            for (int j = i + 1; j < pericias.size(); j++) {
                if (pericias.get(i).name().equalsIgnoreCase(pericias.get(j).name())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Se ha o que descartar: a copia editada, ou um nome pendente que o modelo
     * recusou, ou um nome repetido que o Salvar nao aceita.
     *
     * <p>O nome pendente conta porque ele e estado da tela, e o Descartar e o
     * caminho de volta ao salvo: sem ele, apagar um nome e nao mexer em mais nada
     * deixaria o Descartar desligado (a copia esta igual a {@code baseline}) e o
     * Salvar tambem, e o Mestre nao teria como voltar ao texto valido. O nome
     * repetido entra pelo mesmo motivo: com ele o Salvar esta desligado, e sem
     * o Descartar ligado o Mestre ficaria preso num rascunho que so ele mesmo
     * desfez.
     */
    private boolean canDiscard() {
        return isDirty() || hasPendingName() || hasDuplicateName();
    }

    /**
     * Apaga o rascunho e as marcas de nome pendente.
     *
     * <p>Chamado pelo Salvar, pelo Descartar e pelo Restaurar: nos tres o que
     * estava na tela deixa de valer, entao nenhuma sobra para a proxima sessao de
     * edicao.
     */
    private static void clearDraft() {
        draft = null;
        pendingNames.clear();
    }

    /**
     * Zera o estado de edicao que pertence a <b>sessao de mundo</b>, e nao a uma
     * tela. E o par {@link #draft} + {@link #pendingNames}, nada mais.
     *
     * <p><b>Por que os dois campos precisam deste reset (27/09/2026):</b> eles
     * sao estaticos porque a tela e remontada a cada abertura do item
     * ({@code TabletopRpgClient} faz {@code new SheetEditorScreen()}) e o texto
     * digitado precisa sobreviver a uma troca de tela. O preco e que eles duram
     * mais que o mundo. O cleanup de desconexao ja zera o
     * {@link SheetModelHolder}, entao ao entrar no mundo B o JOIN publica o
     * modelo de B; sem isto, o {@code draft} continuava com o modelo de A, e
     * {@code takeDraft()} devolveria A como {@code staged} com B como
     * {@code baseline}: a tela abriria com "unsaved changes" e rotulos de outro
     * mundo, e o Salvar mandaria o modelo de A para o {@code SheetModelStore}
     * de B. O agravante e que {@code pendingNames} e chaveado por posicao: uma
     * marca de A cairia numa linha que talvez nem exista em B, deixando o Salvar
     * desligado com um aviso que nomeia uma linha inexistente.
     *
     * <p>Chamado de {@code TabletopRpgClient.registerConnectionCleanup()}, no
     * mesmo {@code DISCONNECT} que zera o holder. Mesmo caminho de
     * {@link #clearDraft()}: os dois apagam o rascunho e as marcas juntos, porque
     * eles viajam juntos (ver {@link #captureDraft()}).
     */
    public static void discardTransientState() {
        clearDraft();
    }

    /**
     * O rascunho de um fechamento sem salvar, e o apaga em seguida.
     *
     * <p>Apagar aqui (e nao so no Salvar/Descartar) mantem uma unica copia: a
     * proxima abertura comeca pelo rascunho uma vez so, e fechar de novo sem
     * salvar reescreve o campo.
     */
    private static SheetModel takeDraft() {
        SheetModel saved = draft;
        draft = null;
        return saved == null ? SheetModelHolder.current() : saved;
    }

    /**
     * Perde a marca da posicao {@code removed} e baixa em uma as das posicoes
     * seguintes.
     *
     * <p>Remover uma pericia tira um elemento do meio da lista, e a chave de
     * {@link #pendingNames} e a posicao: sem remapear, o nome vazio que o Mestre
     * digitou marcaria a pericia vizinha.
     */
    private static void shiftPendingAbove(int removed) {
        Map<Integer, String> moved = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : pendingNames.entrySet()) {
            int pos = entry.getKey();
            if (pos != removed) {
                moved.put(pos > removed ? pos - 1 : pos, entry.getValue());
            }
        }
        pendingNames.clear();
        pendingNames.putAll(moved);
    }

    /**
     * Envia o modelo inteiro.
     *
     * <p>Depois de enviar, {@code baseline} passa a ser o que foi enviado: assim o
     * botao Descartar para de oferecer voltar a um estado que ja nao existe. O
     * modelo oficial so volta pelo payload que o servidor reenvia depois de
     * gravar.
     */
    private void save() {
        ClientPlayNetworking.send(new RpgNetworking.SheetModelSavePayload(staged));
        baseline = staged;
        // O que foi gravado e o novo ponto de partida: nem o rascunho nem as
        // marcas de nome pendente valem para a proxima edicao.
        clearDraft();
        rebuildWidgets();
    }

    private void discard() {
        staged = baseline;
        clearDraft();
        rebuildWidgets();
    }

    /**
     * O servidor trocou o modelo: ele gravou o nosso, ou outro Mestre editou.
     *
     * <p>Se nao ha nada em edicao, o que chegou substitui a copia. Se ha, a tela
     * <b>nao</b> sobrescreve, porque isso perderia o que o Mestre estava
     * digitando; o aviso de "nao salvo" continua e o proximo Salvar manda a copia
     * local por cima do modelo novo.
     */
    public void onModelChanged() {
        if (!isDirty()) {
            staged = SheetModelHolder.current();
            baseline = staged;
            // O modelo que chegou pode ter outra quantidade de pericias, e a chave
            // de pendingNames e a posicao: as marcas valem para a lista que saiu,
            // nao para a que acabou de chegar.
            pendingNames.clear();
        }
        rebuildWidgets();
    }

    // ------------------------------------------------------------------
    // DESENHO
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // O que liga e desliga o Salvar e o Descartar e o texto digitado depois do
        // ultimo save; o Salvar ainda exige que nenhuma pericia esteja sem nome
        // nem com o nome repetido de outra, porque o nome vazio nao entra no
        // modelo e o repetido deixaria duas linhas iguais no molde. O que liga e
        // desliga o Restaurar e a copia em edicao contra o padrao do modelo. Digitar
        // numa caixa nao passa por init(), por isso o estado e lido por frame, no
        // mesmo caminho que o aviso de "unsaved" logo abaixo. Fica antes do
        // super.render() para o botao sair ja desenhado no estado certo, sem o
        // atraso de um frame do padrao do StatusScreen.
        if (saveButton != null) {
            saveButton.active = isDirty() && !hasPendingName() && !hasDuplicateName();
        }
        if (discardButton != null) {
            discardButton.active = canDiscard();
        }
        if (resetButton != null) {
            resetButton.active = !staged.equals(SheetModel.defaults());
        }

        // O titulo entra antes dos widgets: o vanilla desenha o
        // renderBackground antes do render (renderWithTooltipAndSubtitles), entao
        // o titulo nao fica sob o fundo da tela, e assim, se alguma vez um
        // controle encostar na faixa dele, e a caixa que aparece por cima do
        // texto, e nao o texto por cima da caixa.
        graphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, COL_TITLE);

        super.render(graphics, mouseX, mouseY, partialTick);

        int top = CONTENT_TOP;
        int bottom = contentBottom();
        for (Caption caption : captions) {
            int y = top + caption.layoutY() - scrollPx;
            if (!fitsInPanel(y, this.font.lineHeight, top, bottom)) {
                continue;
            }
            graphics.drawString(this.font, caption.text(), colX(), y, caption.color(), false);
        }

        if (maxScroll() > 0) {
            graphics.drawString(this.font, tr("scroll_hint"), COL_PADDING, TITLE_Y + 2, COL_HINT, false);
        }
        if (hasPendingName()) {
            // Mesmo lugar do aviso de "unsaved", e com prioridade: e ele que diz
            // por que o Salvar esta desligado, e nessa faixa so cabe uma linha.
            Component warn = tr("pericia_no_name");
            graphics.drawString(this.font, warn, this.width - COL_PADDING - this.font.width(warn),
                    TITLE_Y, COL_HEADER, false);
        } else if (hasDuplicateName()) {
            // Abaixo do de nome vazio e acima do de "unsaved": o vazio e o mais
            // grave porque impede a pericia de existir, e o repetido so impede de
            // gravar o rascunho, que ainda e descartavel.
            Component warn = tr("pericia_duplicate_name");
            graphics.drawString(this.font, warn, this.width - COL_PADDING - this.font.width(warn),
                    TITLE_Y, COL_HEADER, false);
        } else if (isDirty()) {
            Component warn = tr("unsaved");
            graphics.drawString(this.font, warn, this.width - COL_PADDING - this.font.width(warn),
                    TITLE_Y, COL_HEADER, false);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int x = colX();
        graphics.fill(x - 4, CONTENT_TOP - 2, x + colW() + 4, contentBottom(), 0xC0000000);
    }

    /**
     * O ESC e o botao Close passam por aqui, mas a captura mora no
     * {@link #removed()}.
     *
     * <p><b>27/09/2026 (bug):</b> a captura vivia so aqui, e aqui e so o caminho
     * do ESC e do botao Close -- nao o de <b>toda</b> saida de tela. Abrir o
     * inventario (tecla E), o menu, ou qualquer outra tela com o editor aberto e
     * sujo trocava a tela sem registrar o rascunho, e a edicao ia embora.
     * {@code Minecraft.setScreen} chama {@code removed()} em qualquer troca, entao
     * o rascunho passa a ser registado pela saida, e nao pelo botao.
     *
     * <p>Aqui sobra so o fecho do ESC: {@code shouldCloseOnEsc()} e verdadeiro,
     * o vanilla so chama {@code onClose} nesse caso, e ele e quem faz
     * {@code setScreen(null)} -- o que dispara {@link #removed()}. Chamar
     * {@link #captureDraft()} nos dois e idempotente.
     */
    @Override
    public void onClose() {
        captureDraft();
        if (this.minecraft != null) {
            this.minecraft.setScreen(null);
        }
    }

    /**
     * Qualquer saida de tela passa por aqui: ESC, botao Close, inventario,
     * outra tela do mod, e a queda da conexao.
     *
     * <p>27/09/2026: este e o gancho certo porque {@code Minecraft.setScreen} o
     * chama em <b>qualquer</b> troca de tela, e nao so no caminho do ESC.
     */
    @Override
    public void removed() {
        captureDraft();
    }

    /**
     * Registra a edicao nao salva para a proxima abertura. E so isso: nada e
     * enviado, e o {@code baseline} nao muda.
     *
     * <p><b>O rascunho e as marcas de nome pendente viajam juntos, e o "com nada
     * em edicao nao ha o que preservar" e uma leitura errada (27/09/2026).</b>
     * {@code pendingNames} guarda o texto que o Mestre digitou numa linha de
     * pericia e que o modelo ainda nao aceitou, e a chave e a <b>posicao</b> na
     * lista: ele nao e derivavel de {@code staged}, que so guarda o ultimo nome
     * valido, e a proxima abertura repopula as caixas dele
     * ({@code periciaRow}). Apagar as marcas junto com o rascunho perderia o que
     * foi digitado; guarda-las quando {@code staged} esta igual a
     * {@code baseline} tambem: o texto continua na caixa, o Salvar continua
     * desligado ({@link #hasPendingName}) e o aviso de "falta um nome valido"
     * continua explicando por que. Por isso este metodo so mexe em
     * {@code draft}, e as marcas ficam como estao.
     *
     * <p><b>Por que os botoes nao recriam rascunho:</b> {@link #save()},
     * {@link #discard()} e o Restaurar chamam {@link #clearDraft()} e nao trocam
     * de tela ({@code rebuildWidgets()} so refaz o {@code init()}), entao
     * {@code removed()} so alcanca um deles depois. O Salvar faz
     * {@code baseline = staged} e o Descartar faz {@code staged = baseline}, e
     * os dois deixam {@link #isDirty()} falso: nada e recriado.
     * <b>O Restaurar e a excecao, e e o comportamento certo:</b> ele poe
     * {@link SheetModel#defaults()} em {@code staged} sem mexer em
     * {@code baseline}, entao fechar depois dele <b>deve</b> guardar rascunho --
     * o padrao ainda nao esta gravado no servidor e e o que o Mestre esta vendo.
     *
     * <p><b>Por que o teste de conexao:</b> a saida de tela que coincide com a
     * queda da conexao e a saida do <b>mundo</b>, e o rascunho e estado da sessao
     * de mundo ({@link #discardTransientState()}), nao da tela. Sem este teste a
     * ordem entre este metodo e o evento de DISCONNECT decidiria sozinha se o
     * rascunho do mundo que esta saindo sobrevive para o proximo -- e o pior
     * desfecho seria justamente o que {@code discardTransientState()} existe
     * para impedir. Ja sem conexao nao ha rascunho a preservar: o proximo
     * {@code takeDraft()} so rodaria depois de um JOIN, com o modelo novo.
     */
    private void captureDraft() {
        if (this.minecraft == null || this.minecraft.getConnection() == null) {
            return;
        }
        draft = isDirty() ? staged : null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    // ------------------------------------------------------------------
    // TEXTOS
    // ------------------------------------------------------------------

    private static Component tr(String key) {
        return Component.translatable("screen.tabletoprpg.sheet_editor." + key);
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("screen.tabletoprpg.sheet_editor." + key, args);
    }

    private static Component toggleText(boolean on) {
        return tr(on ? "on" : "off");
    }

    private static Component xpModeText(SheetModel.XpMode mode) {
        return tr("xp_mode_" + mode.name().toLowerCase(Locale.ROOT));
    }

    private int attributeIndexOf(String attributeId) {
        for (int i = 0; i < staged.attributes().size(); i++) {
            if (staged.attributes().get(i).id().equals(attributeId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A pericia na posicao {@code pos} da copia em edicao, ou {@code null} se a
     * lista encolheu depois que a linha foi montada.
     *
     * <p><b>28/09/2026:</b> a {@code PericiaDef} tem {@code id} (o
     * {@code pericia_N} que o modelo gera e congela), e as edicoes desta tela o
     * usam. A <b>linha</b> continua identificada pela posicao, e nao pelo id nem
     * pelo nome: o rascunho do texto digitado ({@code pendingNames}) e' por
     * posicao, e qualquer mudanca na quantidade de pericias remonta as linhas
     * ({@code rebuildWidgets()}).
     */
    private SheetModel.PericiaDef periciaAt(int pos) {
        List<SheetModel.PericiaDef> pericias = staged.pericias();
        return pos >= 0 && pos < pericias.size() ? pericias.get(pos) : null;
    }
}
