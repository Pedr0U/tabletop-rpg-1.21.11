package com.pedro.tabletoprpg;

import com.pedro.tabletoprpg.item.ModItems;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Camera Tool: poe (e tira) uma entidade do carrossel de cameras do Mestre.
 *
 * <p><b>Para que serve (pedido de 01/10/2026):</b> o caminho antigo de camera era o
 * {@code cam_perm=true} do {@code /rpg insert enemy}, que so funciona no mob que o
 * proprio comando acabou de criar. Nao havia como dar camera a um mob que ja estava no
 * mundo -- spawnado naturalmente, invocado com {@code /summon}, ou criatura de mod cujo
 * tipo nao resolve pelo nosso comando. Este arquivo cobre esse buraco, por dois caminhos:
 *
 * <ul>
 *   <li><b>Item na mao</b>: o Mestre clica com o botao direito na criatura.</li>
 *   <li><b>Comando armado</b>: {@code /rpg insert camera} e o proximo clique numa
 *       criatura aplica. Serve para quando ele nao quer largar o que esta segurando.</li>
 * </ul>
 *
 * <p><b>Alterna.</b> Decisao do usuario em 01/10/2026: o mesmo clique que adiciona
 * tambem remove, para corrigir engano sem precisar de outro comando.
 *
 * <p><b>So Mobs.</b> Tambem decidido em 01/10/2026. O carrossel
 * ({@code RpgNetworking.getSpectatorTargets}) filtra {@code instanceof Mob}, entao
 * aceitar barco ou armadura aqui daria uma camera que some em silencio na hora de montar
 * a lista. Recusar na entrada e dizer o motivo e melhor que aceitar e nao funcionar.
 *
 * <p><b>Por que a decisao mora aqui e nao num callback proprio:</b> o
 * {@code UseEntityCallback} do {@link CombatController} ja e o ponto unico de decisao do
 * clique em entidade. Dois callbacks independentes dariam selecao <b>e</b> camera no
 * mesmo clique. O {@code CombatController} consulta esta classe antes de tudo e, se ela
 * consumir o clique, nao seleciona.
 */
public final class CameraToolManager {

    /**
     * Marca NBT da camera. Gravada na entidade para o carrossel sobreviver ao reinicio
     * do servidor: {@code CombatController.selfHealCameraMobs} procura exatamente por
     * esta tag. As tags vanilla vao no NBT ("Tags"), sem {@code getPersistentData()} no
     * 1.21.11.
     */
    public static final String CAMERA_TAG = "tabletoprpg_camera";

    /**
     * Janela do debounce, em milissegundos.
     *
     * <p>Mesmo valor usado pela selecao de monstros no {@code CombatController}, de
     * proposito: os dois caminhos disputam o mesmo gesto e devem concordar sobre o que
     * e "o mesmo clique".
     */
    private static final long RECENT_MS = 400L;

    /**
     * Mestres com {@code /rpg insert camera} armado: proximo clique numa criatura
     * aplica.
     *
     * <p><b>Sem prazo, igual ao {@link BlockLockManager}.</b> O pedido vale ate ser
     * consumido por um clique valido ou pela desconexao. A mensagem de armacao fica no
     * chat para o Mestre ver o que esta pendente.
     */
    private static final Set<UUID> armedMasters = ConcurrentHashMap.newKeySet();

    /**
     * Debounce: UUID do Mestre -> ultimo alvo alternado e quando.
     *
     * <p>Pelo mesmo motivo do {@link CombatController}: o vanilla re-dispara o uso com o
     * botao segurado apos ~200ms, e sem isto um unico clique adiciona e remove em
     * seguida. A chave inclui a entidade para que clicar em duas criaturas diferentes
     * depressa funcione.
     */
    private static final Map<UUID, Stamp> lastToggles = new HashMap<>();

    private record Stamp(int entityId, long time) {
    }

    private CameraToolManager() {
    }

    /** Liga a limpeza do pedido armado na desconexao. O clique e tratado pelo CombatController. */
    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                armedMasters.remove(handler.player.getUUID()));
    }

    /**
     * Arma o pedido de {@code /rpg insert camera}. O proximo clique do Mestre numa
     * criatura aplica.
     */
    public static void arm(ServerPlayer master) {
        armedMasters.add(master.getUUID());
    }

    /** Cancela o pedido armado. */
    public static void clear(ServerPlayer master) {
        armedMasters.remove(master.getUUID());
    }

    /** O Mestre tem um pedido armado pelo comando? */
    public static boolean isArmed(ServerPlayer master) {
        return armedMasters.contains(master.getUUID());
    }

    /**
     * Trata o clique do Mestre numa entidade, se for assunto da Camera Tool.
     *
     * <p>Chamado pelo {@code CombatController} <b>antes</b> da checagem de Mestre, de
     * proposito: um jogador comum com o item na mao precisa receber a recusa, senao o
     * item vira um botao morto e silencioso (mesma licao do Block Locker).
     *
     * @param player o jogador que clicou, ja sabido {@code ServerPlayer}
     * @param level o nivel do clique
     * @param hand a mao usada
     * @param clicked a entidade crua do clique -- pode ser uma <b>parte</b>, como no
     *                dragao do Fim; ver {@link EntityTargets}
     * @param primaryPacket {@code true} no pacote principal do clique. O cliente manda
     *                dois por clique (interactAt + interact) e o segundo vem sem
     *                posicao; alternar nos dois inverteria o resultado de um clique so
     * @return {@code true} se a Camera Tool consumiu o clique e a selecao normal nao
     *         deve rodar
     */
    public static boolean handleEntityClick(ServerPlayer player, ServerLevel level,
                                            InteractionHand hand, Entity clicked,
                                            boolean primaryPacket) {
        boolean hasTool = ModItems.isCameraTool(player.getItemInHand(hand));
        boolean armed = isArmed(player);

        // Desembrulha cedo: o id tem de ser o do PAI, porque e o pai que entra no
        // carrossel (o dragao do Fim e alcancado por partes). Ver EntityTargets.
        Entity target = EntityTargets.resolve(clicked);
        int targetId = target == null ? Integer.MIN_VALUE : target.getId();

        long now = System.currentTimeMillis();
        Stamp stamp = lastToggles.get(player.getUUID());
        boolean recent = stamp != null && stamp.entityId() == targetId
                && now - stamp.time() < RECENT_MS;

        // A camera esta no comando deste clique se o item esta na mao, se o pedido foi
        // armado pelo comando, OU se um toggle recente ja aconteceu nesta mesma
        // criatura. O ultimo caso e o que impede um defeito real: o vanilla re-dispara
        // o uso com o botao SEGURADO, e nesse re-disparo o pedido armado ja foi
        // consumido. Sem esta condicao, segurar o botao aplicaria a camera E
        // selecionaria o mob para mover, no mesmo gesto.
        if (!hasTool && !armed && !recent) {
            return false;
        }

        // Jogador comum com o item: recusa nomeada, e o pedido armado nao faz sentido
        // para quem nao e Mestre.
        if (!SessionManager.isMaster(player)) {
            if (primaryPacket) {
                player.displayClientMessage(
                        Component.translatable("item.tabletop-rpg.camera_tool.denied"), false);
            }
            clear(player);
            return true;
        }

        // Segundo pacote do mesmo clique (o cliente manda interactAt + interact, e o
        // segundo vem sem posicao): consome sem alternar.
        if (!primaryPacket) {
            return true;
        }

        // Re-disparo do mesmo clique (botao segurado) ou clique repetido depressa na
        // MESMA criatura: consome sem alternar, e RENOVA o carimbo. Renovar e o que
        // mantem a camera no comando enquanto o botao continua pressionado; sem isto o
        // carimbo envelheceria com o botao ainda apertado, a condicao `recent` cairia e
        // a selecao assumiria o clique no meio do gesto.
        if (recent) {
            lastToggles.put(player.getUUID(), new Stamp(targetId, now));
            return true;
        }

        if (!(target instanceof Mob mob)) {
            player.displayClientMessage(
                    Component.translatable("message.tabletop-rpg.camera_tool_not_mob"), true);
            // O pedido armado CONTINUA valido: o Mestre pode ter errado o alvo e vai
            // tentar de novo. Consumir aqui o obrigaria a repetir o comando.
            TabletopRpg.LOGGER.info("[TabletopRPG] Camera recusada, alvo nao e criatura: {}",
                    EntityTargets.describe(clicked, target));
            return true;
        }

        lastToggles.put(player.getUUID(), new Stamp(targetId, now));
        toggle(player, level, mob);
        clear(player);
        return true;
    }

    /**
     * Adiciona ou remove a camera, conforme o estado atual.
     *
     * <p>Adicionar marca a tag NBT e persiste a entidade. A persistencia e necessaria
     * porque uma camera que despawna some do carrossel em silencio, e o Mestre so
     * descobre no meio da sessao; e o mesmo que o caminho do {@code cam_perm=true} ja
     * faz. Nao congela a criatura ({@code setNoAi}): o pedido foi de camera num mob que
     * se move, e congelar mudaria o comportamento do mundo sem ter sido pedido.
     */
    private static void toggle(ServerPlayer master, ServerLevel level, Mob mob) {
        String name = mob.getName().getString();

        if (CombatController.getCameraMobs().contains(mob.getUUID())) {
            CombatController.removeCameraMob(mob.getUUID());
            mob.removeTag(CAMERA_TAG);
            master.displayClientMessage(
                    Component.translatable("message.tabletop-rpg.camera_removed", name), false);
            TabletopRpg.LOGGER.info("[TabletopRPG] Camera removida de {} por {}",
                    name, master.getName().getString());
        } else {
            CombatController.addCameraMob(mob.getUUID());
            mob.addTag(CAMERA_TAG);
            mob.setPersistenceRequired();
            master.displayClientMessage(
                    Component.translatable("message.tabletop-rpg.camera_added", name), false);
            TabletopRpg.LOGGER.info("[TabletopRPG] Camera em {} por {}",
                    name, master.getName().getString());
        }

        // O carrossel e um retrato enviado a todos: sem isto os jogadores travados so
        // veriam a mudanca no proximo evento que reenvia a lista.
        RpgNetworking.sendSpectatorTargetsToAll(level.getServer());
    }
}