package com.pedro.tabletoprpg;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Mob;

/**
 * Regras de dano da sessão (FASE 0.2).
 *
 * <p>Jogadores (incluindo o mestre) e mobs NUNCA tomam dano físico
 * (queda, lava, mobs, explosões, fogo). A única forma de perder/ganhar
 * HP será o futuro menu de status do personagem/mob: o mestre e o
 * jogador ajustam a vida por lá, e o mestre ajusta o HP dos mobs.
 *
 * <p>Exceções de segurança (não são "dano físico" de mesa):
 * <ul>
 *   <li>{@link DamageTypes#FELL_OUT_OF_WORLD} (queda no vazio): sem essa
 *       exceção, um jogador que cai no void cai para sempre — softlock.</li>
 *   <li>{@link DamageTypes#GENERIC_KILL} (/kill): o mestre precisa poder
 *       matar entidades para desfazer erros de sessão.</li>
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
                    return true; // exceções de segurança (void e /kill)
                }
                return false; // imune: jogadores e mobs não tomam dano físico
            }
            return true; // demais entidades seguem o comportamento vanilla
        });
    }
}