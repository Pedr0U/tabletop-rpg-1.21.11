package com.pedro.tabletoprpg;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;

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
            // Players (não-mestre) só podem colocar blocos se o mestre liberou
            // no menu de configurações (playersCanPlaceBlocks) E estiver no
            // turno dele. A checagem é por BlockItem na mão: bloqueia a
            // colocação sem impedir interações com blocos (baús, portas,
            // alavancas).
            if (!canPlaceBlocks(player) && isBlockItemInHand(player, hand)) {
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
     * Quem pode quebrar blocos:
     *  - O Mestre sempre pode (útil para montar cenas).
     *  - Jogadores (não-mestre) só se o mestre liberou no menu de configurações
     *    (playersCanBreakBlocks) E estiver no turno dele (canPlayerAct).
     *
     * <p>No lado do cliente o player não é um {@link ServerPlayer}, então
     * retornamos true (deixa passar): o servidor é quem decide. Se retornássemos
     * false no cliente, o FAIL cancelaria o clique ANTES de enviar o pacote ao
     * servidor, impedindo até o mestre de interagir com entidades/blocos.
     */
    private static boolean canBreakBlocks(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return true; // cliente: deixa passar, o servidor decide
        }
        return SessionManager.isMaster(serverPlayer)
                || (SessionManager.canPlayersBreakBlocks() && SessionManager.canPlayerAct(serverPlayer));
    }

    /**
     * Quem pode colocar blocos:
     *  - O Mestre sempre pode (útil para montar cenas).
     *  - Jogadores (não-mestre) só se o mestre liberou no menu de configurações
     *    (playersCanPlaceBlocks) E estiver no turno dele (canPlayerAct).
     *
     * <p>No lado do cliente o player não é um {@link ServerPlayer}, então
     * retornamos true (deixa passar): o servidor é quem decide.
     */
    private static boolean canPlaceBlocks(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return true; // cliente: deixa passar, o servidor decide
        }
        return SessionManager.isMaster(serverPlayer)
                || (SessionManager.canPlayersPlaceBlocks() && SessionManager.canPlayerAct(serverPlayer));
    }

    /** True se o item na mão é um bloco (BlockItem) — clique direito tentaria colocar um bloco. */
    private static boolean isBlockItemInHand(Player player, InteractionHand hand) {
        return player.getItemInHand(hand).getItem() instanceof BlockItem;
    }

    /**
     * Interações gerais (usar itens/blocos/entidades, atacar criaturas).
     * Segue a regra de turno do SessionManager:
     *  - Livre: todos podem.
     *  - Investigação/Combate: só o Mestre ou o jogador com turno ativo.
     *
     * <p>No lado do cliente retornamos true (deixa passar): o servidor é quem
     * decide. Se retornássemos false no cliente, o FAIL cancelaria o clique
     * ANTES de enviar o pacote ao servidor, impedindo até o mestre de
     * interagir (ex: selecionar monstro com o botão direito).
     */
    private static boolean canInteract(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return true; // cliente: deixa passar, o servidor decide
        }
        return SessionManager.canPlayerAct(serverPlayer);
    }
}