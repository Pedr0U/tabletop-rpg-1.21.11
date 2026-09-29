package com.pedro.tabletoprpg.client.mixin;

import com.pedro.tabletoprpg.client.TabletopRpgClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Congela o movimento do jogador no LADO DO CLIENTE quando ele está "travado"
 * (modo investigação/combate e não é o turno dele) ou quando está CAÍDO.
 *
 * <p>O servidor já é autoritativo (o mixin ServerGamePacketListenerImplMixin
 * ignora os pacotes de movimento), mas sem este bloqueio local o cliente ainda
 * tentaria se mover e sofreria "rubber-banding". Ao cancelar {@code aiStep()},
 * o input de movimento nunca é aplicado localmente, então o jogador fica
 * completamente parado tanto para si quanto para os outros.
 *
 * <p><b>A câmera livre NÃO cancela mais o {@code aiStep()}.</b> Com a câmera
 * livre ligada o WASD move a câmera, não o personagem, e o "corpo parado" é
 * garantido agora em {@code ClientInputMixin}, zerando o vetor de movimento do
 * jogador local. Cancelar o {@code aiStep()} aqui era a solução errada e trazia
 * dois defeitos: em 1.21.11 é o {@code LivingEntity.aiStep()} que chama o
 * {@code travel()} (gravidade e colisão) e o {@code calculateEntityAnimation()},
 * então o corpo ficava congelado no ar e as pernas em pose de corrida parada.
 * Sem o cancelamento a gravidade, a colisão e a animação voltam a ser vanilla,
 * e o jogador apenas assenta no chão enquanto a câmera voa.
 *
 * <p>As duas condições que restam são o estado do jogador LOCAL: {@code locked}
 * (não é o turno dele) e {@code downed} (HP da ficha &lt;= 0, deitado e não
 * morto). Nos dois casos o personagem fica parado de verdade, e aí o cancelamento
 * é o que impede a queda e o rolamento.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void tabletopRpg$freezeWhenLocked(CallbackInfo ci) {
        // "locked" = nao e o turno dele. "downed" = HP da ficha <= 0 (deitado,
        // nao morto). Nos dois casos o personagem fica parado.
        //
        // <p><b>Nao e este mixin que segura a pose deitado.</b> A pose e
        // recalculada em Player.tick() -> updatePlayerPose(), que NAO passa por
        // aiStep(); por isso o comentario antigo deste metodo (afirmando o
        // contrario) estava errado e o personagem levantava assim que ficava
        // deitado. A pose agora e mantida em ClientPlayerPoseMixin. Aqui so
        // importa bloquear o movimento.
        if (TabletopRpgClient.locked || TabletopRpgClient.downed) {
            ci.cancel();
        }
    }
}