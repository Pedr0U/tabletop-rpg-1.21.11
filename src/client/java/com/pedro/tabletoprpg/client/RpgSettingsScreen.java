package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Tela de configurações do TableTop RPG.
 *
 * <p>Mostra o painel e os controles que o mestre usa:
 * <ul>
 *   <li>Slider de horário do mundo (TimeSlider).</li>
 *   <li>Botão para pausar ({@code false}) ou retomar ({@code true}) o ciclo
 *       dia/noite, enviando um {@code DayNightCycleSetPayload} ao servidor.</li>
 *   <li>Botão para liberar/bloquear a quebra de blocos pelos players,
 *       enviando um {@code BlockBreakSettingPayload} ao servidor.</li>
 *   <li>Botão para liberar/bloquear a colocação de blocos pelos players,
 *       enviando um {@code PlaceBlockSettingPayload} ao servidor.</li>
 *   <li>Botão de clima que cicla Sol -> Chuva -> Tempestade, enviando um
 *       {@code WeatherSetPayload} ao servidor. O estado do botão é
 *       sincronizado com o clima real do mundo (query + broadcast).</li>
 *   <li>Botão "Voltar" para retornar ao menu principal.</li>
 * </ul>
 *
 * <p>Para jogadores NÃO-mestres, a tela também mostra as opções da câmera de
 * espectador (FASE 2):
 * <ul>
 *   <li>"Câmera Orbital: Sim/Não" — liga/desliga a órbita automática da
 *       câmera 3ª pessoa quando o alvo espectado está fora do turno.</li>
 *   <li>"Modo de Câmera: ..." — cicla 3ª Pessoa -> 1ª Pessoa -> TopDown ->
 *       Livre (mesma ação da tecla V).</li>
 *   <li>"Zoom TopDown" (slider -100..100, padrão 0) — aproxima/afasta a
 *       câmera TopDown (altura 15 blocos + zoom × 0.1).</li>
 *   <li>"Velocidade de Transição" (slider 0..100, padrão 50) — velocidade da
 *       transição ao trocar de personagem espectado (50 = 50 ticks atual,
 *       100 = instantâneo).</li>
 * </ul>
 *
 * <p>O mestre NÃO vê as opções de câmera: ele nunca fica travado (o carrossel
 * de espectador só ativa para jogadores fora do turno), então os botões seriam
 * inúteis para ele.
 *
 * <p>Jogadores não-mestres não veem os controles de horário/ciclo/quebra/
 * colocação/clima, apenas as opções de câmera e o botão "Voltar".
 */
public class RpgSettingsScreen extends Screen {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;

    private final Screen parent;
    private final boolean isMaster;
    private boolean cycleEnabled = true;
    private boolean breakBlocksEnabled = false;
    private boolean placeBlocksEnabled = false;
    private int weatherState = 0; // 0=sol, 1=chuva, 2=tempestade

    private Button cycleButton;
    private Button breakBlocksButton;
    private Button placeBlocksButton;
    private Button weatherButton;
    private Button orbitalButton;
    private Button cameraModeButton;
    private RpgSlider topDownZoomSlider;
    private RpgSlider transitionSpeedSlider;
    private TimeSlider timeSlider;

    public RpgSettingsScreen(Screen parent, boolean isMaster) {
        super(Component.literal("Configurações do RPG"));
        this.parent = parent;
        this.isMaster = isMaster;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int startY = this.height / 4;

        if (this.isMaster) {
            // Slider para ajustar o horário do dia (0 - 24000)
            int initialTime = 6000;
            if (this.minecraft != null && this.minecraft.level != null) {
                initialTime = (int) (this.minecraft.level.getDayTime() % 24000L);
            }

            this.timeSlider = new TimeSlider(
                    centerX - (BUTTON_WIDTH / 2),
                    startY,
                    BUTTON_WIDTH,
                    BUTTON_HEIGHT,
                    initialTime,
                    time -> ClientPlayNetworking.send(new RpgNetworking.TimeSetPayload(time))
            );
            this.addRenderableWidget(this.timeSlider);

            // Estado inicial do ciclo: último valor conhecido pelo cliente.
            // A query abaixo atualiza com o valor real do servidor.
            this.cycleEnabled = TabletopRpgClient.dayNightCycleEnabled;

            // Botão para pausar / retomar o ciclo dia/noite
            this.cycleButton = Button.builder(
                            Component.literal(cycleLabel()),
                            btn -> {
                                this.cycleEnabled = !this.cycleEnabled;
                                ClientPlayNetworking.send(new RpgNetworking.DayNightCycleSetPayload(this.cycleEnabled));
                                btn.setMessage(Component.literal(cycleLabel()));
                            }
                    )
                    .bounds(centerX - (BUTTON_WIDTH / 2), startY + 28, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build();

            this.addRenderableWidget(this.cycleButton);

            // Estado inicial da permissão de quebra: último valor conhecido pelo cliente.
            // A query abaixo atualiza com o valor real do servidor.
            this.breakBlocksEnabled = TabletopRpgClient.playersCanBreakBlocks;

            // Botão para liberar/bloquear a quebra de blocos pelos players
            this.breakBlocksButton = Button.builder(
                            Component.literal(breakBlocksLabel()),
                            btn -> {
                                this.breakBlocksEnabled = !this.breakBlocksEnabled;
                                ClientPlayNetworking.send(new RpgNetworking.BlockBreakSettingPayload(this.breakBlocksEnabled));
                                btn.setMessage(Component.literal(breakBlocksLabel()));
                            }
                    )
                    .bounds(centerX - (BUTTON_WIDTH / 2), startY + 56, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build();

            this.addRenderableWidget(this.breakBlocksButton);

            // Estado inicial da permissão de colocação: último valor conhecido pelo cliente.
            // A query abaixo atualiza com o valor real do servidor.
            this.placeBlocksEnabled = TabletopRpgClient.playersCanPlaceBlocks;

            // Botão para liberar/bloquear a colocação de blocos pelos players
            this.placeBlocksButton = Button.builder(
                            Component.literal(placeBlocksLabel()),
                            btn -> {
                                this.placeBlocksEnabled = !this.placeBlocksEnabled;
                                ClientPlayNetworking.send(new RpgNetworking.PlaceBlockSettingPayload(this.placeBlocksEnabled));
                                btn.setMessage(Component.literal(placeBlocksLabel()));
                            }
                    )
                    .bounds(centerX - (BUTTON_WIDTH / 2), startY + 84, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build();

            this.addRenderableWidget(this.placeBlocksButton);

            // Estado inicial do clima: último valor conhecido pelo cliente.
            // A query abaixo atualiza com o clima real do servidor.
            this.weatherState = TabletopRpgClient.weatherState;

            // Botão de clima: um único botão que cicla sol -> chuva -> tempestade.
            // O estado do botão é sincronizado com o clima real do mundo: ao
            // abrir as Settings o cliente pergunta o clima atual (query) e o
            // servidor responde com o estado real (isRaining/isThundering).
            this.weatherButton = Button.builder(
                            Component.literal(weatherLabel()),
                            btn -> {
                                this.weatherState = (this.weatherState + 1) % 3;
                                ClientPlayNetworking.send(new RpgNetworking.WeatherSetPayload(this.weatherState));
                                btn.setMessage(Component.literal(weatherLabel()));
                            }
                    )
                    .bounds(centerX - (BUTTON_WIDTH / 2), startY + 112, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build();

            this.addRenderableWidget(this.weatherButton);

            // Pede o estado real do ciclo ao servidor; a resposta chega via
            // DayNightCycleStatePayload e atualiza o botão (onCycleStateReceived).
            ClientPlayNetworking.send(new RpgNetworking.DayNightCycleQueryPayload());

            // Pede o estado real da permissão de quebra ao servidor; a resposta chega via
            // BlockBreakSettingStatePayload e atualiza o botão (onBlockBreakSettingReceived).
            ClientPlayNetworking.send(new RpgNetworking.BlockBreakSettingQueryPayload());

            // Pede o estado real da permissão de colocação ao servidor; a resposta chega via
            // PlaceBlockSettingStatePayload e atualiza o botão (onPlaceBlockSettingReceived).
            ClientPlayNetworking.send(new RpgNetworking.PlaceBlockSettingQueryPayload());

            // Pede o clima real do mundo ao servidor; a resposta chega via
            // WeatherStatePayload e atualiza o botão (onWeatherStateReceived).
            ClientPlayNetworking.send(new RpgNetworking.WeatherQueryPayload());
        }

        // Opções da câmera de espectador (FASE 2) — apenas para NÃO-mestres.
        // O mestre nunca fica travado (o carrossel só ativa para jogadores
        // fora do turno), então os botões seriam inúteis para ele.
        if (!this.isMaster) {
            // Botão "Câmera Orbital: Sim/Não" (órbita automática da 3ª pessoa).
            this.orbitalButton = Button.builder(
                            Component.literal(orbitalLabel()),
                            btn -> {
                                SpectatorCameraController.setOrbitalEnabled(!SpectatorCameraController.isOrbitalEnabled());
                                btn.setMessage(Component.literal(orbitalLabel()));
                            }
                    )
                    .bounds(centerX - (BUTTON_WIDTH / 2), startY, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build();

            this.addRenderableWidget(this.orbitalButton);

            // Botão "Modo de Câmera: ..." (cicla 3ª -> 1ª -> TopDown -> Livre).
            this.cameraModeButton = Button.builder(
                            Component.literal(cameraModeLabel()),
                            btn -> {
                                SpectatorCameraController.cycleMode();
                                btn.setMessage(Component.literal(cameraModeLabel()));
                            }
                    )
                    .bounds(centerX - (BUTTON_WIDTH / 2), startY + 28, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build();

            this.addRenderableWidget(this.cameraModeButton);

            // Slider de zoom da câmera TopDown (-100..100, padrão 0).
            // Cada unidade = 0.1 bloco de altura: -100 → 5 blocos,
            // +100 → 25 blocos (base 15).
            this.topDownZoomSlider = new RpgSlider(
                    centerX - (BUTTON_WIDTH / 2),
                    startY + 56,
                    BUTTON_WIDTH,
                    BUTTON_HEIGHT,
                    -100,
                    100,
                    SpectatorCameraController.getTopDownZoom(),
                    SpectatorCameraController::setTopDownZoom,
                    v -> "Zoom TopDown: " + (v > 0 ? "+" : "") + v
            );
            this.addRenderableWidget(this.topDownZoomSlider);

            // Slider de velocidade de transição (0..100, padrão 50).
            // Duração em ticks = 100 - valor: 50 → 50 ticks (atual),
            // 100 → 1 tick (instantâneo), 0 → 100 ticks (lento).
            this.transitionSpeedSlider = new RpgSlider(
                    centerX - (BUTTON_WIDTH / 2),
                    startY + 84,
                    BUTTON_WIDTH,
                    BUTTON_HEIGHT,
                    0,
                    100,
                    SpectatorCameraController.getTransitionSpeed(),
                    SpectatorCameraController::setTransitionSpeed,
                    v -> "Velocidade de Transição: " + v
            );
            this.addRenderableWidget(this.transitionSpeedSlider);
        }

        // Botão Voltar para o menu anterior
        int backY = this.isMaster ? startY + 140 : startY + 112;
        Button backButton = Button.builder(
                        Component.literal("Voltar"),
                        btn -> {
                            if (this.minecraft != null) {
                                this.minecraft.setScreen(this.parent);
                            }
                        }
                )
                .bounds(centerX - (BUTTON_WIDTH / 2), backY, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();

        this.addRenderableWidget(backButton);
    }

    /** Rótulo do botão de ciclo conforme o estado atual. */
    private String cycleLabel() {
        return this.cycleEnabled ? "Ciclo Dia/Noite: Ligado" : "Ciclo Dia/Noite: Pausado";
    }

    /** Atualiza o estado do ciclo quando a resposta do servidor chega (S2C). */
    public void onCycleStateReceived(boolean enabled) {
        this.cycleEnabled = enabled;
        if (this.cycleButton != null) {
            this.cycleButton.setMessage(Component.literal(cycleLabel()));
        }
    }

    /** Rótulo do botão de permissão de quebra conforme o estado atual. */
    private String breakBlocksLabel() {
        return this.breakBlocksEnabled ? "Players quebram blocos: Sim" : "Players quebram blocos: Não";
    }

    /** Rótulo do botão de permissão de colocação conforme o estado atual. */
    private String placeBlocksLabel() {
        return this.placeBlocksEnabled ? "Players colocam blocos: Sim" : "Players colocam blocos: Não";
    }

    /** Rótulo do botão de clima conforme o estado atual (0=sol, 1=chuva, 2=tempestade). */
    private String weatherLabel() {
        return switch (this.weatherState) {
            case 0 -> "Clima: Sol";
            case 1 -> "Clima: Chuva";
            default -> "Clima: Tempestade";
        };
    }

    /** Rótulo do botão de órbita automática da câmera 3ª pessoa. */
    private String orbitalLabel() {
        return SpectatorCameraController.isOrbitalEnabled() ? "Câmera Orbital: Sim" : "Câmera Orbital: Não";
    }

    /** Rótulo do botão de modo de câmera de espectador. */
    private String cameraModeLabel() {
        return "Modo de Câmera: " + SpectatorCameraController.getMode().getDisplayName();
    }

    /** Atualiza a permissão de quebra quando a resposta do servidor chega (S2C). */
    public void onBlockBreakSettingReceived(boolean enabled) {
        this.breakBlocksEnabled = enabled;
        if (this.breakBlocksButton != null) {
            this.breakBlocksButton.setMessage(Component.literal(breakBlocksLabel()));
        }
    }

    /** Atualiza a permissão de colocação quando a resposta do servidor chega (S2C). */
    public void onPlaceBlockSettingReceived(boolean enabled) {
        this.placeBlocksEnabled = enabled;
        if (this.placeBlocksButton != null) {
            this.placeBlocksButton.setMessage(Component.literal(placeBlocksLabel()));
        }
    }

    /** Atualiza o clima quando a resposta do servidor chega (S2C). */
    public void onWeatherStateReceived(int weather) {
        this.weatherState = weather;
        if (this.weatherButton != null) {
            this.weatherButton.setMessage(Component.literal(weatherLabel()));
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Escurece o fundo
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Título centralizado
        guiGraphics.drawCenteredString(
                this.font,
                this.title,
                this.width / 2,
                (this.height / 4) - 25,
                0xFFFFFF
        );

        if (!this.isMaster) {
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.literal("Apenas o mestre pode alterar as configurações do mundo."),
                    this.width / 2,
                    (this.height / 4) + 140,
                    0xAAAAAA
            );
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}