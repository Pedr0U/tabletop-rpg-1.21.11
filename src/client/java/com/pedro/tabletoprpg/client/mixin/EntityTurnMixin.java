package com.pedro.tabletoprpg.client.mixin;

import com.pedro.tabletoprpg.client.SpectatorCameraController;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Trava a rotação do jogador no modo espectador (FASE 2, feedback do usuário).
 *
 * <p>O vanilla aplica a rotação do mouse via {@code MouseHandler.turnPlayer} →
 * {@code LocalPlayer.turn} (herdado de {@link Entity}), que roda FORA do
 * {@code aiStep} cancelado pelo {@code LocalPlayerMixin} — por isso o jogador
 * travado ainda girava a cabeça (pitch) e o corpo (yaw) ao mexer o mouse, em
 * qualquer modo de câmera (1ª pessoa, 3ª pessoa, orbital, etc.).
 *
 * <p>Este mixin cancela {@code Entity.turn} para o jogador local enquanto a
 * câmera de espectador está ativa: o corpo fica completamente estático. Os
 * deltas do mouse são repassados ao controlador
 * ({@link SpectatorCameraController#onMouseLook}) para a câmera Livre olhar
 * com o mouse sem girar o jogador (a câmera Livre tem yaw/pitch próprios).
 */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void tabletopRpg$freezePlayerLookWhenSpectating(double yawDelta, double pitchDelta, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer && SpectatorCameraController.isActive()) {
            SpectatorCameraController.onMouseLook(yawDelta, pitchDelta);
            ci.cancel();
        }
    }
}