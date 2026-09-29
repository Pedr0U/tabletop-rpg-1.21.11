package com.pedro.tabletoprpg.client.mixin;

import com.pedro.tabletoprpg.client.SpectatorCameraController;
import com.pedro.tabletoprpg.client.TabletopRpgClient;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ajustes de renderização do espectador (FASE 2).
 *
 * <p><b>1. Mão/arma escondida na 1ª pessoa</b> enquanto o espectador está
 * ativo — inclusive ao espectar a SI MESMO (feedback do usuário: a mão
 * aparecia no canto da tela na 1ª pessoa do próprio jogador). O vanilla só
 * pula a renderização da mão em modo espectador real, dormindo ou panorama —
 * jogadores normais sempre veem a própria mão. Sem este mixin, o espectador
 * veria a própria mão flutuando na visão do alvo espectado (ou na própria
 * visão congelada).
 *
 * <p><b>1b. Mão/arma escondida também com o jogador LOCAL CAÍDO</b> (0 HP na
 * ficha, deitado mas vivo). Aqui a câmera de espectador está desligada, ou
 * seja {@code isActive()} é falso e a condição acima não disparava — e a mão
 * em 1ª pessoa do vanilla continuava sendo desenhada sobre um corpo deitado
 * na pose {@code SWIMMING} forçada pelo {@code ClientPlayerPoseMixin}:
 * symptomatizado pelo usuário como "a mão do personagem sai para fora da tela
 * ao virar com o mouse". Não há braço para desenhar nessa pose, então a mão
 * é suprimida. {@code TabletopRpgClient.downed} é justamente o estado do
 * jogador LOCAL, que é o dono desta visão em 1ª pessoa.
 *
 * <p><b>2. Filtros de visão dos mobs removidos</b> ao espectar. O vanilla
 * aplica post-effects de visão ("creeper", "spider", "invert" do EnderMan)
 * via {@code GameRenderer.checkEntityPostEffect} quando a entidade da câmera
 * é um desses mobs (chamado por {@code Minecraft.setCameraEntity} e pelo F5).
 * Como o espectador troca a entidade da câmera para o mob, o filtro era
 * aplicado — agora é pulado enquanto a câmera de espectador estiver ativa.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Shadow
    public abstract void clearPostEffect();

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void tabletopRpg$hideHandWhenSpectating(float partialTick, boolean notFirstPerson,
                                                    Matrix4f matrix, CallbackInfo ci) {
        if (notFirstPerson) {
            return; // terceira pessoa: o vanilla não renderiza a mão aqui
        }
        if (SpectatorCameraController.isActive() || TabletopRpgClient.downed) {
            ci.cancel();
        }
    }

    @Inject(method = "checkEntityPostEffect", at = @At("HEAD"), cancellable = true)
    private void tabletopRpg$skipMobVisionWhenSpectating(Entity entity, CallbackInfo ci) {
        if (SpectatorCameraController.isActive()) {
            this.clearPostEffect();
            ci.cancel();
        }
    }
}