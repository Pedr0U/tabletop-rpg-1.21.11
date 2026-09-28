package com.pedro.tabletoprpg.client;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.BooleanSupplier;

/**
 * Botao de {@code +}/{@code -} que repete o passo enquanto o mouse estiver
 * pressionado, com aceleracao.
 *
 * <p><b>Por que existe (pedido do usuario):</b> sem isto, subir um atributo de
 * 0 a 30 exigia 30 cliques. Agora segurar o botao faz o valor andar sozinho.
 *
 * <p><b>Perfil de aceleracao</b> (decisao do usuario, 28/09/2026):
 * <ul>
 *   <li>na pressionada: <b>1 passo</b> imediato, e comeca a segurar;</li>
 *   <li><b>400 ms de silencio</b> ({@link #HOLD_DELAY_TICKS} ticks a 20 Hz)
 *       antes de repetir, para um clique com intencao nao acelerar sem querer;</li>
 *   <li>depois do atraso: comeca em {@link #STEPS_PER_SECOND_MIN} passos/s e
 *       <b>rampa linear</b> ate {@link #STEPS_PER_SECOND_MAX} ao longo de
 *       {@link #RAMP_TICKS} ticks (2 s);</li>
 *   <li>ao soltar: para na hora e zera o estado, entao o proximo atraso volta a
 *       ser 400 ms;</li>
 *   <li>ao chegar no limite: <b>para sozinho</b> (ver {@code canStep}), sem
 *       deixar a rajada presa ligada.</li>
 * </ul>
 *
 * <p><b>Por que o som sai so na primeira pressionada:</b> {@code playDownSound}
 * e chamado por {@code AbstractWidget.mouseClicked}, e nao pelo
 * {@code OnPress}. As repeticoes deste botao sao disparadas por
 * {@link #tick()}, que chama o {@code OnPress} direto -- elas nao passam por
 * {@code mouseClicked}, entao nao tocam som nenhum. O caminho normal do clique
 * (que toca o som) continua intocado.
 *
 * <p><b>Por que {@code renderContents} e o unico metodo de desenho:</b> em
 * 1.21.11 {@code renderWidget} e {@code protected final} em
 * {@code AbstractButton} e chama {@code renderContents} + {@code handleCursor}.
 * Como nao da para sobrescrever {@code renderWidget}, o unico ponto de
 * entrada do desenho e {@code renderContents}, que aqui repete o corpo de
 * {@code Button$Plain}.
 *
 * <p><b>Por que o estado de segurada mora AQUI, e nao na tela:</b>
 * {@code rebuildWidgets()} (scroll da coluna de pericias, troca de modelo pelo
 * Mestre, resize) chama {@code clearWidgets()} + {@code clearFocus()}: o
 * release do mouse se perde e o estado "estou segurando" nunca mais chega
 * ninguem. Se ele morasse na tela, a tela continuaria repetindo para sempre sem
 * ninguem para parar. Morto aqui junto com o widget, o pior caso e a rajada
 * morrer em silencio -- que e o desfecho aceitavel. E o caminho contrario
 * (rajada presa, sem ninguem para parar) fica coberto pelo watchdog do botao
 * fisico em {@link #tick()}.
 *
 * <p><b>{@code canStep} responde pelo valor OTIMISTA</b>, e nao pelo
 * {@code active} do widget: {@code applyStepButtons} so roda no render (e
 * depois de {@code super.render()}), entao o {@code active = false} do limite
      * so chega no frame seguinte. Se o loop confiasse nele, estouraria 1 ou 2 passos
      * alem do teto, e o numero piscaria enquanto o valor otimista estivesse a
      * frente do eco do servidor ({@code CharacterSheetScreen.keepPending}).
      * Consultando o valor otimista antes de cada passo, o botao
      * para sozinho no limite.
 */

final class HoldStepButton extends Button {

    /** Tick do cliente por segundo: a base de todas as contas de tempo aqui. */
    private static final int TICKS_PER_SECOND = 20;
    /** Atraso inicial da rajada: 8 ticks = 400 ms sem repetir. */
    private static final int HOLD_DELAY_TICKS = 8;
    /** Janela da rampa de aceleracao: 40 ticks = 2 s. */
    private static final int RAMP_TICKS = 40;
    /** Ritmo logo depois do atraso inicial (passos por segundo). */
    private static final double STEPS_PER_SECOND_MIN = 10.0;
    /** Ritmo no fim da rampa (passos por segundo). */
    private static final double STEPS_PER_SECOND_MAX = 25.0;

    /**
     * Ainda da para andar neste botao? Vem do valor <b>otimista</b> da tela
     * ({@code pendingNumeric} / {@code pendingPericiaValue}) e dos limites,
     * nunca do {@code active} deste widget.
     *
     * <p>E o que segura o botao no limite, e o que garante que uma ficha
     * somente-leitura ({@code canEdit == false}) nao envie nada.
     */
    private final BooleanSupplier canStep;

    /** Ter o que este botao esta segurado. Vive no widget, ver a Javadoc. */
    private boolean holding;
    /** Ticks desde a pressionada (0 no instante da pressionada). */
    private int heldTicks;
    /** Passos fracionarios acumulados, para suportar ritmo nao inteiro. */
    private double pendingSteps;

    /**
     * @param onPress  o passo de um clique (o mesmo {@code OnPress} de um
     *                 {@code Button} normal: 1 passo por chamada)
     * @param canStep  responde se ainda ha o que andar, pelo valor otimista
     */
    HoldStepButton(int x, int y, int width, int height, Component message,
                   OnPress onPress, BooleanSupplier canStep) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.canStep = canStep;
    }

    /**
     * Um clique: o passo imediato (com o som do {@code mouseClicked}) e o
     * inicio da rajada.
     *
     * <p>A rajada so comeca se ainda houver o que andar depois do passo. Assim
     * um clique que acabou de chegar no limite nao abre uma contagem que so
     * terminaria 400 ms depois.
     *
     * <p>So o <b>mouse</b> abre rajada. {@code AbstractButton.keyPressed} tambem
     * chama {@code onPress} (Enter/Espaco no botao focado) e, em 1.21.11, nao ha
     * rota de release por teclado — {@code keyReleased} nao chega no botao. Sem
     * esta guarda, um Enter solto ramparia ate o limite e pararia ali.
     */
    @Override
    public void onPress(InputWithModifiers input) {
        super.onPress(input);
        if (!(input instanceof MouseButtonEvent) || !canStep.getAsBoolean()) {
            return;
        }
        holding = true;
        heldTicks = 0;
        pendingSteps = 0.0;
    }

    /** Soltar: para imediatamente e zera a rajada. */
    @Override
    public void onRelease(MouseButtonEvent event) {
        stopHold();
        super.onRelease(event);
    }

    /**
     * Um tick da tela ({@code Screen.tick()} chama via
     * {@code CharacterSheetScreen.tick()}).
     *
     * <p>Antes do {@link #HOLD_DELAY_TICKS} nao repete nada. Depois disso
     * acumula passos no ritmo da rampa e dispara <b>somente</b> os passos
     * inteiros, sempre perguntando {@code canStep} antes de cada um. No limite,
     * a rajada para sozinha.
     *
     * <p>Nao depende de {@code isMouseOver} de proposito: os botoes tem 9 a 13
     * px de lado, e o vanilla entrega o release ao widget FOCADO (sem teste de
     * posicao), entao a repeticao continua mesmo com o cursor fora.
     *
     * <p><b>28/09/2026, watchdog do botao fisico:</b> o release so chega por
     * {@code onRelease}, e o vanilla so o entrega ao widget focado com
     * {@code button == 0 && isDragging()}. Se o evento <b>nao chegar</b> --
     * soltar fora da janela do jogo, {@code alt+tab} no meio da rajada, perda do
     * grab -- o {@code holding} ficava {@code true} e a rajada continuava
     * mandando passos sozinha, ate o teto, gravando no servidor sem o usuario
     * ter pedido. Por isso o primeiro teste deste metodo e o botao esquerdo
     * (ver {@link #isLeftMouseDown}): o estado real do mouse nao depende de o
     * evento ter chegado ate aqui.
     */
    void tick() {
        if (!holding) {
            return;
        }
        if (!isLeftMouseDown()) {
            // O release se perdeu (ou o usuario ja soltou antes do proximo tick):
            // a rajada para aqui em vez de correr sozinha ate o teto.
            stopHold();
            return;
        }
        heldTicks++;
        if (heldTicks <= HOLD_DELAY_TICKS) {
            return;
        }
        pendingSteps += stepsPerSecondAt(heldTicks - HOLD_DELAY_TICKS) / TICKS_PER_SECOND;
        while (pendingSteps >= 1.0) {
            if (!canStep.getAsBoolean()) {
                // Chegou no limite (ou a ficha ficou somente-leitura): a rajada
                // para aqui, sem ficar presa ligada esperando um release que
                // pode nem vir.
                stopHold();
                return;
            }
            pendingSteps -= 1.0;
            this.onPress.onPress(this);
        }
    }

    /**
     * O botao esquerdo do mouse esta pressionado agora?
     *
     * <p><b>Por que o GLFW e nao o {@code MouseHandler}:</b> em 1.21.11
     * {@code MouseHandler.isLeftPressed()} existe, mas e campo morto para isto:
     * o bytecode do {@code onButton} so escreve o campo quando
     * {@code screen == null && getOverlay() == null} (confirmado no jar
     * {@code minecraft-clientonly-1.21.11}), ou seja, com uma ficha ABERTA ele
     * nunca e atualizado e a rajada morreria no primeiro tick. E
     * {@code Window.isMouseButtonDown(int)} nao existe nesta versao. O que
     * sobrou e a fonte da verdade de verdade, a mesma que o GLFW usa:
     * {@code glfwGetMouseButton} na janela do jogo. Como {@code Screen.tick()}
     * roda na thread principal (a que criou a janela), a chamada e segura.
     *
     * <p>Limitacao conhecida: se o SO nao entregar o release de jeito nenhum
     * (a janela perde foco sem o evento), o GLFW continua respondendo
     * "pressionado" e a rajada vai ate o teto -- que e o pior caso de antes, e
     * ainda para sozinha pelo {@code canStep}.
     */
    private static boolean isLeftMouseDown() {
        Window window = Minecraft.getInstance().getWindow();
        return window != null
                && GLFW.glfwGetMouseButton(window.handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT)
                == GLFW.GLFW_PRESS;
    }

    /**
     * Ritmo da rajada, em passos por segundo, {@code ticksAfterDelay} ticks
     * depois do fim do atraso inicial.
     *
     * <p>Rampa <b>linear</b> de {@link #STEPS_PER_SECOND_MIN} ate
     * {@link #STEPS_PER_SECOND_MAX}, e depois constante no maximo.
     *
     * <p>Metodo puro e isolado de proposito: e a unica conta de tempo do
     * botao, sem dependencia de tela, de tick ou de estado, entao da para
     * conferir a rampa sozinha.
     *
     * @param ticksAfterDelay ticks ja passados depois do atraso inicial
     */
    static double stepsPerSecondAt(int ticksAfterDelay) {
        double ramp = Math.min(1.0, Math.max(0, ticksAfterDelay) / (double) RAMP_TICKS);
        return STEPS_PER_SECOND_MIN + (STEPS_PER_SECOND_MAX - STEPS_PER_SECOND_MIN) * ramp;
    }

    /** Zera a rajada: o proximo atraso volta a ser o inicial. */
    private void stopHold() {
        holding = false;
        heldTicks = 0;
        pendingSteps = 0.0;
    }

    /**
     * Corpo de {@code Button$Plain.renderContents}, verbatim.
     *
     * <p>{@code renderWidget} e {@code final} em {@code AbstractButton}, entao
     * este e o unico ponto de extensao do desenho.
     */
    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderDefaultSprite(graphics);
        renderDefaultLabel(graphics.textRendererForWidget(this, GuiGraphics.HoveredTextEffects.NONE));
    }
}
