package com.pedro.tabletoprpg.mixin;

import com.pedro.tabletoprpg.CombatController;
import com.pedro.tabletoprpg.DamageControlHandler;
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
 * Intercepta ServerboundMovePlayerPacket (movimenta├º├úo/rota├º├úo enviada
 * pelo cliente) antes do processamento. Se o jogador n├úo tiver permiss├úo
 * para agir (SessionManager.canPlayerAct), o pacote ├® cancelado e o
 * jogador permanece travado na posi├º├úo atual do servidor.
 *
 * <p>Al├®m disso, o jogador ativo n├úo pode sair da aura de limite dele
 * (15 blocos da ├óncora onde come├ºou). O mestre nunca ├® limitado.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    // "Espelha" o campo p├║blico player j├í existente na classe original
    @Shadow public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void tabletopRpg$cancelMoveIfLocked(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!SessionManager.canPlayerAct(this.player)) {
            // Ignora completamente o pacote: nada de posi├º├úo/rota├º├úo ├®
            // atualizado no servidor, ent├úo o cliente ├® corrigido de volta
            // na posi├º├úo travada no pr├│ximo pacote de sincroniza├º├úo.
            ci.cancel();
            return;
        }
        // Personagem deitado (HP da ficha <= 0): bloqueia o DESLOCAMENTO, mas
        // deixa os pacotes de rota├º├úo passarem (um personagem deitado ainda
        // olha para os lados). O cliente tambem congela o movimento local
        // (LocalPlayerMixin), ent├úo n├úo h├¡ rubber-banding.
        if (DamageControlHandler.isDowned(this.player) && packet.hasPosition()) {
            ci.cancel();
            return;
        }
        // Limite de aura: o jogador ativo n├úo pode sair do c├¡rculo de 15
        // blocos da ├óncora dele (o mestre nunca ├® limitado). S├│ cancelar o
        // pacote deixaria o cliente andando por predi├º├úo sem corre├º├úo; ent├úo
        // projetamos a posi├º├úo de volta para a borda da aura e avisamos o
        // cliente (teleport), "barrando" o jogador na borda.
        if (packet.hasPosition() && CombatController.isPlayerBeyondAura(this.player,
                packet.getX(this.player.getX()), packet.getZ(this.player.getZ()))) {
            // S├│ teleporta se N├âO houver teleporte pendente. Sem essa checagem,
            // segurar W na borda gerava um ClientboundPlayerPositionPacket por
            // tick com o anterior ainda pendente: o cliente nunca conseguia
            // aceitar um antes de chegar o pr├│ximo (o accept chegava com ID
            // defasado e era ignorado) e ficava preso em desync irrevers├¡vel
            // (bug documentado). Com a checagem, cada teleporte ├® aceito antes
            // do pr├│ximo ÔÇö o jogador ├® segurado na borda sem travar.
            if (((ServerGamePacketListenerImplAccessor) this).awaitingPositionFromClient() == null) {
                Vec3 clamped = CombatController.clampToAura(this.player,
                        packet.getX(this.player.getX()), packet.getZ(this.player.getZ()));
                // connection.teleport envia ClientboundPlayerPositionPacket (mesmo
                // mecanismo da corre├º├úo de desync do vanilla): o cliente volta para
                // a borda da aura e o servidor atualiza a posi├º├úo quando o cliente
                // aceita o teleporte.
                this.player.connection.teleport(clamped.x, clamped.y, clamped.z, this.player.getYRot(), this.player.getXRot());
            }
            ci.cancel();
        }
    }
}
