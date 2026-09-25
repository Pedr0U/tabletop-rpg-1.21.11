package com.pedro.tabletoprpg.client.mixin;

import com.pedro.tabletoprpg.client.CinematicCameraRig;
import com.pedro.tabletoprpg.client.SpectatorCameraController;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sobrescreve a câmera quando a cinematic está ativa.
 *
 * <p>Injeta no final de {@code Camera.setup(...)} e, se o jogador estiver
 * congelado com a cinematic ligada, força a posição e a rotação da câmera
 * para os valores interpolados da órbita ({@link CinematicCameraRig}).
 * Assim o jogador congelado vê a câmera girando ao redor dele, mostrando os
 * arredores, mesmo sem controlar o mouse.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void tabletopRpg$applyCinematicCamera(Level level, Entity entity,
                                                  boolean detached, boolean thirdPersonReverse,
                                                  float partialTick, CallbackInfo ci) {
        if (SpectatorCameraController.isActive() && CinematicCameraRig.isActive()) {
            var pos = CinematicCameraRig.getInterpolatedPosition(partialTick);
            this.setPosition(pos.x, pos.y, pos.z);
            this.setRotation(CinematicCameraRig.getInterpolatedYaw(partialTick),
                             CinematicCameraRig.getInterpolatedPitch(partialTick));
        }
    }
}