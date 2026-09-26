package com.pedro.tabletoprpg.client.mixin;

import com.pedro.tabletoprpg.client.SpectatorCameraController;
import com.pedro.tabletoprpg.client.TabletopRpgClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Congela o movimento do jogador no LADO DO CLIENTE quando ele está "travado"
 * (modo investigação/combate e não é o turno dele).
 *
 * <p>O servidor já é autoritativo (o mixin ServerGamePacketListenerImplMixin
 * ignora os pacotes de movimento), mas sem este bloqueio local o cliente ainda
 * tentaria se mover e sofreria "rubber-banding". Ao cancelar {@code aiStep()},
 * o input de movimento nunca é aplicado localmente, então o jogador fica
 * completamente parado tanto para si quanto para os outros.
 *
 * <p><b>Terceira condição: a câmera livre (decisão do usuário em 25/09/2026 —
 * "corpo parado").</b> Com a câmera livre ligada, o WASD move a câmera, não o
 * personagem. Como no modo Livre ninguém fica travado, a trava não cobre este
 * caso: sem a terceira condição o jogador andaria ao mesmo tempo que a câmera
 * voa, e o corpo não ficaria parado.
 *
 * <p><b>Por que a câmera precisa do {@code isActive()}:</b> a condição usa
 * "câmera livre <b>ligada</b>", e não "câmera livre selecionada". Se o jogador
 * tem a câmera Livre escolhida mas acaba de desligá-la com V, ele volta a
 * poder andar normalmente; com a versão sem {@code isActive()} ele ficaria
 * preso sem se mover.
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
        if (TabletopRpgClient.locked || TabletopRpgClient.downed
                || SpectatorCameraController.isFreeCameraActive()) {
            ci.cancel();
        }
    }
}