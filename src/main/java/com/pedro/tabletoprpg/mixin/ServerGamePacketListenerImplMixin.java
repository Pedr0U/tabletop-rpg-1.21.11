package com.pedro.tabletoprpg.mixin;

import com.pedro.tabletoprpg.CombatController;
import com.pedro.tabletoprpg.SessionManager;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
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
 *
 * <p>Além disso, o jogador ativo não pode sair da aura de limite dele
 * (15 blocos da âncora onde começou). O mestre nunca é limitado.
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
            return;
        }
        // Limite de aura: o jogador ativo não pode sair do círculo de 15
        // blocos da âncora dele (o mestre nunca é limitado). Só cancelar o
        // pacote deixaria o cliente andando por predição sem correção; então
        // projetamos a posição de volta para a borda da aura e avisamos o
        // cliente (teleport), "barrando" o jogador na borda.
        if (packet.hasPosition() && CombatController.isPlayerBeyondAura(this.player,
                packet.getX(this.player.getX()), packet.getZ(this.player.getZ()))) {
            // Só teleporta se NÃO houver teleporte pendente. Sem essa checagem,
            // segurar W na borda gerava um ClientboundPlayerPositionPacket por
            // tick com o anterior ainda pendente: o cliente nunca conseguia
            // aceitar um antes de chegar o próximo (o accept chegava com ID
            // defasado e era ignorado) e ficava preso em desync irreversível
            // (bug documentado). Com a checagem, cada teleporte é aceito antes
            // do próximo — o jogador é segurado na borda sem travar.
            if (((ServerGamePacketListenerImplAccessor) this).awaitingPositionFromClient() == null) {
                Vec3 clamped = CombatController.clampToAura(this.player,
                        packet.getX(this.player.getX()), packet.getZ(this.player.getZ()));
                // connection.teleport envia ClientboundPlayerPositionPacket (mesmo
                // mecanismo da correção de desync do vanilla): o cliente volta para
                // a borda da aura e o servidor atualiza a posição quando o cliente
                // aceita o teleporte.
                this.player.connection.teleport(clamped.x, clamped.y, clamped.z, this.player.getYRot(), this.player.getXRot());
            }
            ci.cancel();
        }
    }
}