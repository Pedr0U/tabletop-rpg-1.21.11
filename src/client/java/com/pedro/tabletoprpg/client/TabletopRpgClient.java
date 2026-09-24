package com.pedro.tabletoprpg.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TabletopRpgClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("tabletop-rpg-client");
    public static KeyMapping rpgMenuKey;

    /**
     * True quando o jogador está "travado" (modo investigação/combate e não é o
     * turno dele). Usado pelo mixin LocalPlayerMixin para impedir o movimento
     * local, evitando o efeito de "rubber-banding". Atualizado via payload
     * {@link RpgNetworking.PlayerLockPayload}.
     */
    public static volatile boolean locked = false;

    /**
     * Estado atual do ciclo dia/noite (gamerule advance_time) conhecido pelo
     * cliente. Atualizado via {@link RpgNetworking.DayNightCycleStatePayload}
     * quando o mestre abre as Settings. Usado para o botão da tela de
     * configurações refletir o estado real do servidor.
     */
    public static volatile boolean dayNightCycleEnabled = true;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[TabletopRPG-Client] onInitializeClient() started.");

        try {
            rpgMenuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.tabletoprpg.menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                KeyMapping.Category.MISC
            ));
            LOGGER.info("[TabletopRPG-Client] Keybinding registered successfully: {}", rpgMenuKey.getName());
        } catch (Throwable t) {
            LOGGER.error("[TabletopRPG-Client] FAILED to register keybinding!", t);
        }

        registerNetworking();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Atualiza a câmera cinematográfica (órbita do jogador congelado).
            CinematicCameraController.tick(client);

            if (rpgMenuKey == null) {
                return;
            }
            while (rpgMenuKey.consumeClick()) {
                // Pede os dados da sessão ao servidor; quando o servidor responder
                // com MenuDataPayload, o menu é aberto com as informações corretas.
                if (client.player != null && client.getConnection() != null) {
                    LOGGER.info("[TabletopRPG] Tecla R pressionada -> enviando MenuRequestPayload.");
                    ClientPlayNetworking.send(new RpgNetworking.MenuRequestPayload());
                } else {
                    LOGGER.warn("[TabletopRPG] Tecla R pressionada, mas sem jogador/conexão válida.");
                }
            }
        });

        LOGGER.info("[TabletopRPG-Client] onInitializeClient() finished.");
    }

    private void registerNetworking() {
        // Resposta do servidor com os dados da sessão -> abre o menu.
        ClientPlayNetworking.registerGlobalReceiver(RpgNetworking.MenuDataPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                if (context.client().player == null) {
                    LOGGER.warn("[TabletopRPG] MenuDataPayload recebido, mas sem jogador no cliente.");
                    return;
                }
                LOGGER.info("[TabletopRPG] MenuDataPayload recebido -> abrindo RpgMenuScreen.");
                RpgMenuScreen screen = new RpgMenuScreen(
                    payload.isMaster(),
                    payload.isMyTurn(),
                    payload.sessionName(),
                    payload.modeName(),
                    payload.activePlayerName(),
                    payload.playerNames()
                );
                context.client().setScreen(screen);
            });
        });

        // Estado de "trava" do jogador -> atualiza o campo locked.
        ClientPlayNetworking.registerGlobalReceiver(RpgNetworking.PlayerLockPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                locked = payload.locked();
            });
        });

        // Estado do ciclo dia/noite -> atualiza o campo e a tela de Settings aberta.
        ClientPlayNetworking.registerGlobalReceiver(RpgNetworking.DayNightCycleStatePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                dayNightCycleEnabled = payload.enabled();
                if (context.client().screen instanceof RpgSettingsScreen settings) {
                    settings.onCycleStateReceived(payload.enabled());
                }
            });
        });
    }
}