package com.pedro.tabletoprpg.mixin;

import com.pedro.tabletoprpg.SessionManager;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepta ServerboundMovePlayerPacket (movimentação/rotação enviada
 * pelo cliente) antes do processamento. Se o jogador não tiver permissão
 * para agir (SessionManager.canPlayerAct), o pacote é cancelado e o
 * jogador permanece travado na posição atual do servidor.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    // "Espelha" o campo público player já existente na classe original
    @Shadow public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void tabletopRpg$cancelMoveIfLocked(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!SessionManager.canPlayerAct(this.player)) {
            // Ignora completamente o pacote: nada de posição/rotação é
            // atualizado no servidor, então o cliente é corrigido de volta
            // na posição travada no próximo pacote de sincronização.
            ci.cancel();
        }
    }
}