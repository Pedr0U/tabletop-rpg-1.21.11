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
 *   <li>Botão de clima que cicla Sol -> Chuva -> Tempestade, enviando um
 *       {@code WeatherSetPayload} ao servidor. O clima persiste até o mestre
 *       mudar de novo.</li>
 *   <li>Botão "Voltar" para retornar ao menu principal.</li>
 * </ul>
 *
 * <p>Jogadores não-mestres não veem os controles de horário/ciclo/quebra/clima,
 * apenas o botão "Voltar".
 */
public class RpgSettingsScreen extends Screen {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;

    private final Screen parent;
    private final boolean isMaster;
    private boolean cycleEnabled = true;
    private boolean breakBlocksEnabled = false;
    private int weatherState = 0; // 0=sol, 1=chuva, 2=tempestade

    private Button cycleButton;
    private Button breakBlocksButton;
    private Button weatherButton;
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

            // Botão de clima: um único botão que cicla sol -> chuva -> tempestade.
            // O clima aplicado persiste no servidor até o mestre mudar de novo.
            this.weatherButton = Button.builder(
                            Component.literal(weatherLabel()),
                            btn -> {
                                this.weatherState = (this.weatherState + 1) % 3;
                                ClientPlayNetworking.send(new RpgNetworking.WeatherSetPayload(this.weatherState));
                                btn.setMessage(Component.literal(weatherLabel()));
                            }
                    )
                    .bounds(centerX - (BUTTON_WIDTH / 2), startY + 84, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build();

            this.addRenderableWidget(this.weatherButton);

            // Pede o estado real do ciclo ao servidor; a resposta chega via
            // DayNightCycleStatePayload e atualiza o botão (onCycleStateReceived).
            ClientPlayNetworking.send(new RpgNetworking.DayNightCycleQueryPayload());

            // Pede o estado real da permissão de quebra ao servidor; a resposta chega via
            // BlockBreakSettingStatePayload e atualiza o botão (onBlockBreakSettingReceived).
            ClientPlayNetworking.send(new RpgNetworking.BlockBreakSettingQueryPayload());
        }

        // Botão Voltar para o menu anterior
        int backY = this.isMaster ? startY + 112 : startY + 20;
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

    /** Rótulo do botão de clima conforme o estado atual (0=sol, 1=chuva, 2=tempestade). */
    private String weatherLabel() {
        return switch (this.weatherState) {
            case 0 -> "Clima: Sol";
            case 1 -> "Clima: Chuva";
            default -> "Clima: Tempestade";
        };
    }

    /** Atualiza a permissão de quebra quando a resposta do servidor chega (S2C). */
    public void onBlockBreakSettingReceived(boolean enabled) {
        this.breakBlocksEnabled = enabled;
        if (this.breakBlocksButton != null) {
            this.breakBlocksButton.setMessage(Component.literal(breakBlocksLabel()));
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
                    (this.height / 4),
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