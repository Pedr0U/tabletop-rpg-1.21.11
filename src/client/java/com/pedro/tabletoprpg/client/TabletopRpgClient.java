package com.pedro.tabletoprpg.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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

    /**
     * Auras de limite de movimentação ativas (círculos azuis no chão).
     * Atualizado via {@link RpgNetworking.AuraStatePayload} quando o mestre
     * seleciona um monstro ou o modo/turno muda. Renderizado pelo
     * {@link AuraRenderer}.
     */
    public static volatile List<RpgNetworking.AuraStatePayload.AuraData> auras = List.of();

    /** Contador para enviar o hover (highlight) periodicamente. */
    private static int hoverTickCounter = 0;

    /** Última entidade sob o crosshair enviada (para enviar só na mudança). */
    private static int lastHoveredId = -1;

    /**
     * Distância máxima (em blocos) do highlight para este jogador. O mestre
     * recebe um valor alto (ilimitado na prática); os demais recebem a
     * distância configurada pelo mestre (/rpg hoverdistance). Atualizado via
     * {@link RpgNetworking.HoverConfigPayload}.
     */
    public static volatile int hoverMaxDistance = 32;

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
        AuraRenderer.register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Atualiza a câmera cinematográfica (órbita do jogador congelado).
            CinematicCameraController.tick(client);

            // Envia o hover (entidade sob o crosshair) para o highlight Glowing.
            tickHover(client);

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

        // Estado das auras de limite -> atualiza os círculos renderizados.
        ClientPlayNetworking.registerGlobalReceiver(RpgNetworking.AuraStatePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                auras = payload.auras();
                LOGGER.info("[TabletopRPG] AuraStatePayload recebido: {} aura(s) -> {}", auras.size(), auras);
            });
        });

        // Distância máxima do highlight -> atualiza o raycast do hover.
        ClientPlayNetworking.registerGlobalReceiver(RpgNetworking.HoverConfigPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                hoverMaxDistance = payload.maxDistance();
            });
        });
    }

    /**
     * Detecta a entidade sob o crosshair (raycast a partir da câmera) e envia
     * o hover ao servidor. O envio acontece imediatamente quando a entidade
     * muda (sem delay perceptível) e a cada 20 ticks como refresh para renovar
     * o efeito Glowing. A distância máxima é a configurada pelo servidor
     * (mestre: ilimitado; jogador: /rpg hoverdistance).
     */
    private static void tickHover(Minecraft client) {
        if (client.getConnection() == null || client.level == null || client.player == null) {
            return; // não está em um mundo (menu/loading) -> não pode enviar pacotes
        }
        hoverTickCounter++;

        Camera camera = client.gameRenderer.getMainCamera();
        Vec3 start = camera.position();
        Vec3 look = new Vec3(camera.forwardVector());
        double maxDist = hoverMaxDistance;
        Vec3 end = start.add(look.scale(maxDist));
        AABB box = client.player.getBoundingBox().expandTowards(look.scale(maxDist)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(client.player, start, end, box,
                e -> e instanceof Mob, maxDist * maxDist);
        int hoveredId = hit != null ? hit.getEntity().getId() : -1;

        if (hoveredId != lastHoveredId || hoverTickCounter % 20 == 0) {
            lastHoveredId = hoveredId;
            ClientPlayNetworking.send(new RpgNetworking.HoverPayload(hoveredId));
        }
    }
}