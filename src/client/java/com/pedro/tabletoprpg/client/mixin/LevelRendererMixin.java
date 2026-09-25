package com.pedro.tabletoprpg.client.mixin;

import com.pedro.tabletoprpg.client.SpectatorCameraController;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mantém o corpo do jogador visível quando o espectador está vendo outro alvo
 * (FASE 2, feedback do usuário).
 *
 * <p>No vanilla, {@code LevelRenderer.extractVisibleEntities} pula a
 * renderização do {@code LocalPlayer} quando a entidade da câmera não é o
 * próprio jogador (comportamento de espectador vanilla: o corpo "some"). Como
 * o TableTop troca a entidade da câmera para o alvo espectado
 * ({@code Minecraft.setCameraEntity}), o corpo do jogador travado sumia da
 * cena ao espectar outro jogador/mob.
 *
 * <p>Este mixin redireciona a 4ª chamada a {@code Camera.entity()} dentro de
 * {@code extractVisibleEntities} (a comparação {@code camera.entity() ==
 * entity} do check {@code instanceof LocalPlayer}): enquanto a câmera de
 * espectador está ativa, devolve o jogador local — a comparação vira
 * {@code player == entity}, que só é verdadeira para o próprio jogador, então
 * o corpo dele é renderizado e os demais alvos continuam normais.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Redirect(method = "extractVisibleEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;entity()Lnet/minecraft/world/entity/Entity;",
                    ordinal = 3))
    private Entity tabletopRpg$renderPlayerBodyWhenSpectating(Camera camera) {
        if (SpectatorCameraController.isActive()) {
            Entity player = Minecraft.getInstance().player;
            if (player != null) {
                return player;
            }
        }
        return camera.entity();
    }
}