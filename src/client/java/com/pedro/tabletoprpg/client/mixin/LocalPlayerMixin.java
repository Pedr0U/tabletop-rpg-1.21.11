package com.pedro.tabletoprpg.client.mixin;

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
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void tabletopRpg$freezeWhenLocked(CallbackInfo ci) {
        if (TabletopRpgClient.locked) {
            ci.cancel();
        }
    }
}