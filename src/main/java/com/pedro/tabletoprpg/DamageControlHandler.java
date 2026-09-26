package com.pedro.tabletoprpg;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Regras de dano da sess├úo (FASE 0.2).
 *
 * <p>Jogadores (incluindo o mestre) e mobs NUNCA tomam dano f├¡sico
 * (queda, lava, mobs, explos├Áes, fogo). A ├║nica forma de perder/ganhar
 * HP ser├í o futuro menu de status do personagem/mob: o mestre e o
 * jogador ajustam a vida por l├í, e o mestre ajusta o HP dos mobs.
 *
 * <p>Exce├º├Áes de seguran├ºa (n├úo s├úo "dano f├¡sico" de mesa):
 * <ul>
 *   <li>{@link DamageTypes#FELL_OUT_OF_WORLD} (queda no vazio): sem essa
 *       exce├º├úo, um jogador que cai no void cai para sempre ÔÇö softlock.</li>
 *   <li>{@link DamageTypes#GENERIC_KILL} (/kill): o mestre precisa poder
 *       matar entidades para desfazer erros de sess├úo.</li>
 * </ul>
 */
public final class DamageControlHandler {

    private DamageControlHandler() {
    }

    public static void register() {
        // Retornar false no ALLOW_DAMAGE cancela o dano antes de ser aplicado.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer || entity instanceof Mob) {
                if (source.is(DamageTypes.FELL_OUT_OF_WORLD) || source.is(DamageTypes.GENERIC_KILL)) {
                    return true; // exce├º├Áes de seguran├ºa (void e /kill)
                }
                return false; // imune: jogadores e mobs n├úo tomam dano f├¡sico
            }
            return true; // demais entidades seguem o comportamento vanilla
        });

        // Personagem deitado (HP da ficha <= 0): aplicado no fim de cada tick
        // do servidor. O estado so e enviado ao cliente quando MUDA, nunca a
        // cada tick.
        ServerTickEvents.END_SERVER_TICK.register(DamageControlHandler::tickDownedPlayers);
    }

    // ------------------------------------------------------------------
    // PERSONAGEM DEITADO (HP <= 0 = deitado, NAO morto)
    // ------------------------------------------------------------------

    /**
     * Ultimo estado "deitado" enviado por jogador. Evita reenviar o payload a
     * cada tick; tambem da um valor anterior (previous) para saber se o
     * jogador acabou de levantar.
     */
    private static final Map<UUID, Boolean> lastDownedState = new HashMap<>();

    /**
     * Regra do usuario: quando o HP da ficha chega a 0 (ou fica negativo) o
     * personagem DEITA e NAO MORRE. Com HP > 0 ele levanta sozinho.
     *
     * <p><b>Decisao (evidencia):</b> nao registramos um ALLOW_DEATH global.
     * A API existe (verificada com javap em fabric-entity-events-v1), mas
     * cancelar toda morte quebraria as duas valvulas de seguranca ja
     * documentadas nesta classe: o void (senao o jogador cai para sempre) e o
     * /kill (o mestre precisa desfazer erros de sessao). Como o jogador ja e
     * imune a todo o resto (ALLOW_DAMAGE acima), nao existe outra fonte de
     * morte em jogo -- o que resta e exatamente o estado deitado.
     *
     * <p><b>Nota sobre a pose:</b> o vanilla nao tem pose "deitado de costas"
     * (javap Entity$Pose: STANDING, SLEEPING, SWIMMING, CROUCHING, ...). Usamos
     * SWIMMING, que e a unica pose vanilla que deixa o personagem no chao sem
     * exigir cama. O visual e "deitado de brucos".
     */
    private static void tickDownedPlayers(MinecraftServer server) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            tickDownedPlayer(player);
        }
        // Esquece quem saiu: a ficha continua no SessionManager, mas nao ha
        // cliente para avisar.
        lastDownedState.keySet().removeIf(uuid -> !online.contains(uuid));
    }

    /** Aplica o estado deitado/levantado de um jogador. */
    private static void tickDownedPlayer(ServerPlayer player) {
        SheetData sheet = SessionManager.getSheet(player.getUUID());
        boolean downed = sheet != null && sheet.isDowned();
        Boolean previous = lastDownedState.get(player.getUUID());

        if (previous == null || previous != downed) {
            lastDownedState.put(player.getUUID(), downed);
            RpgNetworking.sendDownedState(player, downed);
        }

        if (downed) {
            if (player.getPose() != Pose.SWIMMING) {
                player.setPose(Pose.SWIMMING);
            }
            player.setSprinting(false);
            // Zera SO o movimento horizontal. Zerar o eixo Y prenderia o
            // jogador parado no ar caso ele estivesse caindo.
            Vec3 motion = player.getDeltaMovement();
            if (motion.x != 0.0 || motion.z != 0.0) {
                player.setDeltaMovement(0.0, motion.y, 0.0);
            }
        } else if (Boolean.TRUE.equals(previous) && player.getPose() == Pose.SWIMMING) {
            // Acabou o estado deitado: levanta. So tocamos na pose se ela ainda
            // for a que nos mesmos aplicamos, para nao sobrescrever sono/nado.
            player.setPose(Pose.STANDING);
        }
    }

    /**
     * O personagem deste jogador esta deitado? Usado pelo mixin de movimento
     * do servidor para bloquear o deslocamento (a rotacao continua liberada).
     */
    public static boolean isDowned(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        SheetData sheet = SessionManager.getSheet(player.getUUID());
        return sheet != null && sheet.isDowned();
    }
}
