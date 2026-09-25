package com.pedro.tabletoprpg.client.mixin;

import com.pedro.tabletoprpg.client.SpectatorCameraController;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Esconde o HUD do jogador no modo espectador (FASE 2, feedback do usuário).
 *
 * <p>O vanilla renderiza o HUD em métodos separados do {@link Gui}:
 * <ul>
 *   <li>{@code renderCrosshair} — mira;</li>
 *   <li>{@code renderHotbarAndDecorations} — hotbar, vida, fome, armadura,
 *       ar, experiência, nome do item, barra de montaria;</li>
 *   <li>{@code renderEffects} — ícones de efeitos de poção.</li>
 * </ul>
 *
 * <p>Enquanto a câmera de espectador está ativa, esses elementos são pulados:
 * o jogador travado não deve ver o HUD vanilla (a barra de experiência, a
 * hotbar etc.), apenas os HUDs customizados que o mod adicionar no futuro
 * (ex.: iniciativa). Chat, scoreboard, boss bar, títulos e overlays de câmera
 * continuam sendo renderizados normalmente.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void tabletopRpg$hideCrosshairWhenSpectating(GuiGraphics guiGraphics,
                                                         DeltaTracker deltaTracker,
                                                         CallbackInfo ci) {
        if (SpectatorCameraController.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderHotbarAndDecorations", at = @At("HEAD"), cancellable = true)
    private void tabletopRpg$hideHotbarWhenSpectating(GuiGraphics guiGraphics,
                                                      DeltaTracker deltaTracker,
                                                      CallbackInfo ci) {
        if (SpectatorCameraController.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
    private void tabletopRpg$hideEffectsWhenSpectating(GuiGraphics guiGraphics,
                                                       DeltaTracker deltaTracker,
                                                       CallbackInfo ci) {
        if (SpectatorCameraController.isActive()) {
            ci.cancel();
        }
    }
}