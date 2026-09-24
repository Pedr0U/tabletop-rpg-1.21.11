package com.pedro.tabletoprpg;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Mob;

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
    }
}
