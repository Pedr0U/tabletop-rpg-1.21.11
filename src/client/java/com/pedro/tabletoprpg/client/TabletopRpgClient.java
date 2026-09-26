package com.pedro.tabletoprpg.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TabletopRpgClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("tabletop-rpg-client");
    public static KeyMapping rpgMenuKey;

    /**
     * Tecla V: cicla o modo de câmera de espectador (3ª Pessoa -> 1ª Pessoa ->
     * TopDown -> Livre). Funciona travado ou não (o modo persiste como
     * preferência). Usado pelo {@link SpectatorCameraController}.
     */
    public static KeyMapping rpgCameraModeKey;

    /**
     * UUID do jogador ativo (turno atual) como string; vazio ("") quando não
     * há turno ativo. Atualizado via {@link RpgNetworking.ActivePlayerPayload}.
     * Usado pelo espectador para saber se o alvo espectado está no turno dele
     * (câmera 3ª pessoa = mouse do espectador em vez da órbita automática).
     */
    public static volatile String activePlayerUuid = "";

    /**
     * True quando o jogador está "travado" (modo investigação/combate e não é o
     * turno dele). Usado pelo mixin LocalPlayerMixin para impedir o movimento
     * local, evitando o efeito de "rubber-banding". Atualizado via payload
     * {@link RpgNetworking.PlayerLockPayload}.
     */
    public static volatile boolean locked = false;

    /**
     * True quando o personagem está <b>deitado</b> (HP da ficha &lt;= 0).
     *
     * <p><b>FACT (regra do usuário):</b> HP &lt;= 0 deixa o personagem deitado
     * e ele NÃO morre. Como o HP da ficha é a fonte da verdade do personagem e
     * a vida real do Minecraft é uma camada separada (o jogador já é imune a
     * dano físico — ver {@code DamageControlHandler}), este estado é
     * exatamente o inverso de "morto": é "incapaz de andar".
     *
     * <p>Atualizado via {@link RpgNetworking.DownedStatePayload}. O mixin
     * {@code LocalPlayerMixin} usa esta flag para congelar o movimento local
     * (e, de brinde, a pose local, que de outro modo o cliente recomputaria
     * para STANDING).
     */
    public static volatile boolean downed = false;

    /**
     * UUIDs de TODOS os personagens que o servidorвітou como deitados.
     *
     * <p><b>FACT (por que um conjunto e nao so a flag acima):</b> o cliente
     * recalcula a pose de todos os jogadores a cada tick
     * ({@code Player.tick()} -&gt; {@code updatePlayerPose()}), inclusive dos
     * outros jogadores que estou vendo. Se so o proprio jogador soubesse que
     * esta deitado, o cliente levantaria os caidos que aparecem na tela — e o
     * mestre, que e justamente quem precisa ver quem esta no chao, teria uma
     * visao errada. Por isso o servidor transmite o estado a todos
     * (ver {@link RpgNetworking.DownedStatePayload}).     *
     * <p>Concorrente: o receptor roda numa tarefa do cliente e o mixin de pose
     * roda no tick do render, em outra thread.
     */
    public static final Set<UUID> downedPlayers = ConcurrentHashMap.newKeySet();

    /**
     * Este personagem (o jogador local) esta deitado?
     *
     * <p>Truque importante: a verificacao e feita pelo UUID do jogador local e
     * nao pela flag {@link #downed}, porque quando a tela e aberta pela
     * primeira vez o payload pode ainda nao ter chegado.
     */
    public static boolean isDowned(Player player) {
        return player != null && downedPlayers.contains(player.getUUID());
    }

    /**
     * Estado atual do ciclo dia/noite (gamerule advance_time) conhecido pelo
     * cliente. Atualizado via {@link RpgNetworking.DayNightCycleStatePayload}
     * quando o mestre abre as Settings. Usado para o botão da tela de
     * configurações refletir o estado real do servidor.
     */
    public static volatile boolean dayNightCycleEnabled = true;

    /**
     * Se os jogadores (não-mestre) podem quebrar blocos. Atualizado via
     * {@link RpgNetworking.BlockBreakSettingStatePayload} quando o mestre abre
     * as Settings ou muda a permissão. Usado para o botão da tela de
     * configurações refletir o estado real do servidor.
     */
    public static volatile boolean playersCanBreakBlocks = false;

    /**
     * Se os jogadores (não-mestre) podem colocar blocos. Atualizado via
     * {@link RpgNetworking.PlaceBlockSettingStatePayload} quando o mestre abre
     * as Settings ou muda a permissão. Usado para o botão da tela de
     * configurações refletir o estado real do servidor.
     */
    public static volatile boolean playersCanPlaceBlocks = false;

    /**
     * Clima atual do mundo conhecido pelo cliente (0=sol, 1=chuva,
     * 2=tempestade). Atualizado via {@link RpgNetworking.WeatherStatePayload}
     * quando o mestre abre as Settings ou muda o clima. Usado para o botão da
     * tela de configurações refletir o clima real do servidor.
     */
    public static volatile int weatherState = 0;

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
     * ID da entidade que o JOGADOR LOCAL está mirando (ou -1). Usado pelo
     * {@code EntityRendererMixin} para renderizar o contorno do highlight
     * apenas na tela de quem está com o mouse em cima do mob — o efeito
     * Glowing vanilla foi removido porque era global (todos viam o contorno
     * do mob hoverado por qualquer jogador).
     */
    public static int getHoveredEntityId() {
        return lastHoveredId;
    }

    /**
     * Distância máxima (em blocos) do highlight para este jogador. O mestre
     * recebe um valor alto (ilimitado na prática); os demais recebem a
     * distância configurada pelo mestre (/rpg hoverdistance). Atualizado via
     * {@link RpgNetworking.HoverConfigPayload}.
     */
    public static volatile int hoverMaxDistance = 32;

    /**
     * Limpa o estado que sobrevive entre conexoes.
     *
     * <p>{@link #downedPlayers} e estatico e guarda UUIDs, entao sem isto ele
     * cresceria a cada entrada/saida e, pior, poderia conter o UUID de um
     * jogador que ja saiu: numa reconexao em outro mundo, o
     * {@code ClientPlayerPoseMixin} prenderia a pose de alguem que nao esta
     * mais deitado. O JOIN reenvia o retrato do servidor, entao limpar aqui
     * apenas garante que o estado comeca zerado.
     */
    private static void registerConnectionCleanup() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            downedPlayers.clear();
            downed = false;
        });
    }

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

            rpgCameraModeKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.tabletoprpg.camera_mode",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyMapping.Category.MISC
            ));
            LOGGER.info("[TabletopRPG-Client] Camera mode keybinding registered successfully: {}", rpgCameraModeKey.getName());
        } catch (Throwable t) {
            LOGGER.error("[TabletopRPG-Client] FAILED to register keybinding!", t);
        }

        registerNetworking();
        registerConnectionCleanup();
        AuraRenderer.register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Atualiza a câmera de espectador (carrossel + modos de câmera).
            SpectatorCameraController.tick(client);

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

            // Tecla V: cicla o modo de câmera de espectador (funciona travado
            // ou não — o modo persiste como preferência).
            if (rpgCameraModeKey != null) {
                while (rpgCameraModeKey.consumeClick()) {
                    SpectatorCameraController.cycleMode();
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

        // Estado da permissão de quebra de blocos -> atualiza o campo e a tela de Settings aberta.
        ClientPlayNetworking.registerGlobalReceiver(RpgNetworking.BlockBreakSettingStatePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                playersCanBreakBlocks = payload.enabled();
                if (context.client().screen instanceof RpgSettingsScreen settings) {
                    settings.onBlockBreakSettingReceived(payload.enabled());
                }
            });
        });

        // Estado da permissão de colocação de blocos -> atualiza o campo e a tela de Settings aberta.
        ClientPlayNetworking.registerGlobalReceiver(RpgNetworking.PlaceBlockSettingStatePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                playersCanPlaceBlocks = payload.enabled();
                if (context.client().screen instanceof RpgSettingsScreen settings) {
                    settings.onPlaceBlockSettingReceived(payload.enabled());
                }
            });
        });

        // Clima atual do mundo -> atualiza o campo e a tela de Settings aberta.
        ClientPlayNetworking.registerGlobalReceiver(RpgNetworking.WeatherStatePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                weatherState = payload.weather();
                if (context.client().screen instanceof RpgSettingsScreen settings) {
                    settings.onWeatherStateReceived(payload.weather());
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

        // Lista de alvos do carrossel de espectador -> atualiza o carrossel.
        ClientPlayNetworking.registerGlobalReceiver(RpgNetworking.SpectatorTargetsPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                if (context.client().player != null) {
                    SpectatorCameraController.setServerTargets(payload.targets(), context.client().player.getId());
                }
            });
        });

        // Jogador ativo (turno) -> usado pela regra "espectado no turno =
        // mouse do espectador" na câmera 3ª pessoa.
        ClientPlayNetworking.registerGlobalReceiver(RpgNetworking.ActivePlayerPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                activePlayerUuid = payload.activePlayerUuid();
            });
        });

        // Estado "deitado" (HP da ficha <= 0).
        // Chega para TODOS os clientes (ver DownedStatePayload), entao o
        // receptor mantem um conjunto com quem esta deitado -- e nao so uma
        // flag local, que so serviria para o proprio jogador.
        ClientPlayNetworking.registerGlobalReceiver(RpgNetworking.DownedStatePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                UUID id = payload.playerId();
                if (id == null) {
                    return;
                }
                if (payload.downed()) {
                    downedPlayers.add(id);
                } else {
                    downedPlayers.remove(id);
                }
                // A flag local continua existindo porque o bloqueio de
                // movimento (LocalPlayerMixin) so interessa ao proprio jogador.
                Minecraft client = context.client();
                if (client.player != null && id.equals(client.player.getUUID())) {
                    downed = payload.downed();
                }
            });        });

        // Ficha do personagem -> preenche/atualiza a tela de ficha, se estiver
        // aberta. É por aqui que a edição fica "ao vivo": quando o mestre altera
        // a ficha de um jogador, o cliente daquele jogador recebe este payload e
        // a tela dele se atualiza sozinha (e vice-versa).
        ClientPlayNetworking.registerGlobalReceiver(RpgNetworking.SheetStatePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                if (context.client().screen instanceof CharacterSheetScreen sheet) {
                    sheet.onSheetState(payload);
                }
            });
        });
    }

    /**
     * Pede ao servidor a ficha de um jogador. {@code targetName} vazio pede a
     * própria ficha; o nome de outro jogador só funciona para o mestre (a
     * permissão é conferida no servidor).
     */
    public static void requestSheet(String targetName) {
        if (Minecraft.getInstance().getConnection() == null) {
            return; // fora de um mundo (menu principal/loading)
        }
        String name = targetName == null ? "" : targetName.trim();
        // O codec aceita no máximo 64 caracteres (stringUtf8(64)); um nome
        // maior derrubaria a codificação do pacote, então corta aqui.
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }
        ClientPlayNetworking.send(new RpgNetworking.SheetQueryPayload(name));
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
        // No modo espectador o highlight (Glowing) é desnecessário: limpa o
        // hover anterior (envia -1) e não envia novos (feedback do usuário).
        if (SpectatorCameraController.isActive()) {
            if (lastHoveredId != -1) {
                lastHoveredId = -1;
                ClientPlayNetworking.send(new RpgNetworking.HoverPayload(-1));
            }
            return;
        }
        hoverTickCounter++;

        Camera camera = client.gameRenderer.getMainCamera();
        Vec3 start = camera.position();
        Vec3 look = new Vec3(camera.forwardVector());
        double maxDist = hoverMaxDistance;
        Vec3 end = start.add(look.scale(maxDist));
        // Caixa do raycast a partir da CÂMERA (não do corpo do jogador): sem
        // isso, ao espectar outro alvo à distância (FASE 2) o highlight não
        // funcionaria, pois a caixa ficava ancorada no jogador congelado.
        AABB box = new AABB(start, end).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(client.player, start, end, box,
                e -> e instanceof Mob, maxDist * maxDist);

        // Linha de visão: o mob só é destacado se NENHUM bloco estiver entre a
        // câmera e ele (nada de highlight através de paredes). O raycast de
        // blocos vai até o ponto atingido na entidade; se um bloco estiver no
        // caminho, a entidade está atrás de uma parede e não é destacada.
        int hoveredId = -1;
        if (hit != null) {
            Vec3 hitPoint = hit.getLocation();
            BlockHitResult blockHit = client.level.clip(new ClipContext(
                    start, hitPoint, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
            if (blockHit.getType() == HitResult.Type.MISS) {
                hoveredId = hit.getEntity().getId();
            }
        }

        if (hoveredId != lastHoveredId || hoverTickCounter % 20 == 0) {
            lastHoveredId = hoveredId;
            ClientPlayNetworking.send(new RpgNetworking.HoverPayload(hoveredId));
        }
    }
}