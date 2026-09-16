package com.pedro.tabletoprpg;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

public class PlayerControlHandler {

    public static void register() {

        // Bloqueia quebrar/bater em blocos com clique esquerdo (só o mestre pode quebrar)
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!canBreakBlocks(player)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // Bloqueia atacar entidades
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!canInteract(player)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // Bloqueia interagir com blocos (baús, portas, alavancas)
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!canInteract(player)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // Bloqueia interagir com entidades (clique direito em NPCs, etc.)
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!canInteract(player)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // Bloqueia uso de itens (no MC 1.21 o callback retorna InteractionResult diretamente)
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!canInteract(player)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // Camada extra para prevenir quebra de bloco em modo criativo
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> canBreakBlocks(player));
    }

    /**
     * Jogadores (não-mestre) NUNCA podem quebrar blocos, em qualquer modo.
     * Apenas o Mestre pode quebrar/colocar blocos (útil para montar cenas).
     */
    private static boolean canBreakBlocks(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        return SessionManager.isMaster(serverPlayer);
    }

    /**
     * Interações gerais (usar itens/blocos/entidades, atacar criaturas).
     * Segue a regra de turno do SessionManager:
     *  - Livre: todos podem.
     *  - Investigação/Combate: só o Mestre ou o jogador com turno ativo.
     */
    private static boolean canInteract(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        return SessionManager.canPlayerAct(serverPlayer);
    }
}