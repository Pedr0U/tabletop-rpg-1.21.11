package com.pedro.tabletoprpg;

import com.pedro.tabletoprpg.item.ModItems;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Highlight da Ficha de Ameaca na mao: contorno dos mobs vinculados, so para quem
 * segura a ficha (subtask do Bug C, 16/10/2026).
 *
 * <p><b>O que faz:</b> enquanto um jogador segura o item {@code threat_sheet} cujo
 * id de ficha esta vinculado a um mob, o servidor manda para ESSE jogador a lista
 * de ids das entidades a destacar ({@link RpgNetworking.SheetHighlightPayload}). O
 * cliente desenha o contorno apenas para quem recebeu a lista -- os outros
 * jogadores nao veem nada. Ficha na mao principal OU na secundaria vale.
 *
 * <p><b>Substitui o highlight de mira:</b> com a ficha na mao, o contorno de
 * crosshair (hover) some no cliente, para nao haver dois contornos diferentes na
 * tela ao mesmo tempo. Ver {@code TabletopRpgClient.tickHover}.
 *
 * <p><b>Contorno "sempre visivel":</b> o desenho do contorno atravessa paredes e
 * nao depende de distancia, mas o mob precisa EXISTIR no cliente: fora do
 * {@code clientTrackingRange} do vanilla (8 chunks para creeper/golem, ~128
 * blocos) a entidade nem chegou no cliente e nao ha o que desenhar. Assim, so
 * entidades do MESMO mundo do jogador entram na lista.
 *
 * <p><b>So o Mestre:</b> a lista so e calculada para o jogador que ocupa o posto de
 * Mestre ({@link SessionManager#isMaster}); qualquer outro jogador recebe lista
 * vazia e nao ve contorno nenhum. O contorno de MIRA, porem, some no cliente de
 * quem segura a ficha (a checagem de "esta segurando" e local, ver
 * {@code TabletopRpgClient.tickHover}), independente do posto.
 */
public final class SheetHighlightManager {

    /**
     * De quantos em quantos ticks recalcular o vinculo mesmo sem a mao mudar.
     *
     * <p><b>Por que existe:</b> a mao sozinha nao cobre tudo. Se a ficha for
     * amarrada a um mob DEPOIS de ja estar na mao, ou o mob morrer e o vinculo
     * mudar, o proximo recalculo pega a mudanca em ate 1 segundo. Fichas nao
     * vinculadas custam uma varredura de entidades no {@code selfHeal} do
     * {@link ThreatSheetBinding}; por isso o recalculo e periodico e nao a cada
     * tick.
     */
    private static final int REFRESH_INTERVAL = 20;

    /** Estado por jogador: o que foi enviado e as entradas (posto + mao) que o geraram. */
    private static final Map<UUID, Sent> STATE = new HashMap<>();

    private static long tickCount;

    /** O que ja foi enviado a um jogador, com as entradas que produziram isso. */
    private record Sent(boolean master, List<Integer> ids, String signature) {
    }

    private SheetHighlightManager() {
    }

    /** Liga o calculo por tick e a limpeza na desconexao. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SheetHighlightManager::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                STATE.remove(handler.player.getUUID()));
    }

    /**
     * Recalcula o destaque de cada jogador e envia SO quando muda.
     *
     * <p><b>Por que "so quando muda":</b> o payload e pequeno, mas o envio a cada
     * tick de 20 Hz entupiria o canal com um pacote que o cliente ja conhece. A
     * comparacao tambem e o que limpa a tela: ao guardar a ficha (ou perder o
     * posto de Mestre), a lista desejada vira vazia, difere da ultima enviada e o
     * cliente recebe o "nao destaque nada".
     */
    private static void tick(MinecraftServer server) {
        tickCount++;
        boolean refresh = tickCount % REFRESH_INTERVAL == 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            Sent previous = STATE.get(uuid);
            boolean master = SessionManager.isMaster(player);
            String signature = heldSheetSignature(player);
            // O posto de Mestre faz parte do recalculo: quem JA estava com a ficha
            // na mao e ganha o posto (ou o perde) precisa de resposta imediata, e
            // nao de esperar o refresh periodico -- a assinatura da mao nao muda
            // nesse instante.
            boolean changedInput = previous == null
                    || master != previous.master()
                    || !signature.equals(previous.signature());

            List<Integer> desired;
            if (!master || signature.isEmpty()) {
                desired = List.of();
            } else if (refresh || changedInput) {
                desired = resolveTargets(player);
            } else {
                desired = previous.ids();
            }

            if (previous == null) {
                // Primeiro tick deste jogador: guarda o estado mesmo sem nada a
                // destacar (senao o vinculo seria recalculado a cada tick) e
                // evita um pacote vazio por jogador a cada entrada.
                STATE.put(uuid, new Sent(master, desired, signature));
                if (!desired.isEmpty()) {
                    sendHighlight(player, desired);
                }
            } else if (!previous.ids().equals(desired)) {
                STATE.put(uuid, new Sent(master, desired, signature));
                sendHighlight(player, desired);
            } else if (changedInput) {
                // Mesmo resultado, entrada diferente (mao ou posto): guarda para
                // nao recalcular o vinculo a cada tick ate o proximo refresh.
                STATE.put(uuid, new Sent(master, desired, signature));
            }
        }
    }

    /**
     * Envia a lista e registra no log do servidor.
     *
     * <p><b>Por que logar:</b> e o unico lado em que da para provar, sem um
     * segundo cliente, que o destaque foi (ou deixou de ser) enviado -- o log do
     * cliente so mostra o que chegou. O log sai SO na mudanca, entao nao polui.
     */
    private static void sendHighlight(ServerPlayer player, List<Integer> ids) {
        TabletopRpg.LOGGER.info("[TabletopRPG] SheetHighlight para {}: {} entidade(s) {}",
                player.getName().getString(), ids.size(), ids);
        ServerPlayNetworking.send(player, new RpgNetworking.SheetHighlightPayload(ids));
    }

    /**
     * Os ids das fichas nas duas maos, como {@code "principal|secundaria"} -- ou
     * {@code ""} se nenhuma mao tiver ficha.
     *
     * <p><b>Por que uma assinatura de texto e nao os stacks:</b> stack muda de
     * contagem e de dano sem trocar de ficha, e comparar o objeto faria recalcular
     * o vinculo (e varrer entidades) por nada.
     */
    private static String heldSheetSignature(ServerPlayer player) {
        String main = ModItems.threatSheetId(player.getMainHandItem());
        String off = ModItems.threatSheetId(player.getOffhandItem());
        if (main.isEmpty() && off.isEmpty()) {
            return "";
        }
        return main + "|" + off;
    }

    /** Ids das entidades vinculadas as fichas que o jogador segura. */
    private static List<Integer> resolveTargets(ServerPlayer player) {
        List<Integer> ids = new ArrayList<>();
        collectTarget(player, player.getMainHandItem(), ids);
        collectTarget(player, player.getOffhandItem(), ids);
        return ids;
    }

    private static void collectTarget(ServerPlayer player, ItemStack stack, List<Integer> out) {
        String sheetId = ModItems.threatSheetId(stack);
        if (sheetId.isEmpty()) {
            return;
        }
        // Modo Horda (22/10/2026): a ficha pode valer para varios monstros, e TODOS
        // eles entram no destaque.
        for (UUID mobUuid : ThreatSheetBinding.mobsOf(player.level().getServer(), sheetId)) {
            // So o mob DESTE mundo: o cliente do jogador so tem entidades do proprio
            // nivel, entao um id de outro mundo nao desenharia nada (e ocuparia a
            // comparacao de "mudou" sem necessidade).
            Entity mob = player.level().getEntity(mobUuid);
            if (mob == null) {
                continue;
            }
            int id = mob.getId();
            if (!out.contains(id)) {
                out.add(id);
            }
        }
    }
}
