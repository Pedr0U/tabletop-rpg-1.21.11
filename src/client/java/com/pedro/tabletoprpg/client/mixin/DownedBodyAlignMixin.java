package com.pedro.tabletoprpg.client.mixin;

import com.pedro.tabletoprpg.client.CinematicCameraRig;
import com.pedro.tabletoprpg.client.SpectatorCameraController;
import com.pedro.tabletoprpg.client.TabletopRpgClient;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Alinha o corpo do jogador CAIDO com a camera de espectador.
 *
 * <p>Feedback do usuario: ao girar a camera para a direita, a mao do caido vai
 * ficando para a esquerda e nao segue a visao; o inverso acontece ao girar
 * para a esquerda.
 *
 * <p><b>Por que aqui, e nao no tick da camera:</b> a solucao anterior setava
 * {@code yBodyRot}/{@code yHeadRot} na entidade durante o tick da camera. Isso
 * nao resolve por dois motivos:
 * <ul>
 *   <li>{@code Entity.turn} esta cancelado no modo espectador, entao a rotacao
 *       do jogador fica congelada no instante em que a camera foi ativada. Com o
 *       corpo congelado e a camera orbitando, o corpo parece girar para o lado
 *       contrario ao movimento da camera - e o corpo inteiro, nao so a mao, o
 *       que aponta para o problema estar na rotacao base e nao em uma animacao
 *       de braco;</li>
 *   <li>para um caido que NAO e o jogador local, o {@code LivingEntity.tick()}
 *       do cliente reescreve {@code yBodyRot} a cada tick, anulando o valor
 *       setado.</li>
 * </ul>
 *
 * <p>{@code extractRenderState} e onde o vanilla decide o que sera desenhado,
 * logo antes da renderizacao. Nenhum tick roda depois, entao o valor aqui nao
 * pode ser sobrescrito.
 *
 * <p>Aplicados: {@code yRot} (yaw do corpo, que o {@code HumanoidRenderer} usa
 * no torso e nos bracos) e {@code bodyRot}. {@code xRot} (pitch) fica intacto,
 * para a cabeca nao acompanhar o pitch da camera.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class DownedBodyAlignMixin {

    /**
     * <p><b>FACT (crash de 26/09/2026):</b> a primeira versao injetava em
     * {@code (Entity, EntityRenderState, float)} e o jogo crashava na abertura
     * com {@code InvalidInjectionException: Invalid descriptor ... Expected
     * (LivingEntity, LivingEntityRenderState, float) but found (Entity,
     * EntityRenderState, float)}. A ponte sintetica existe no bytecode, mas o
     * Mixin resolve para o metodo GENERICO e exige os tipos genericos
     * concretos. Por isso a assinatura abaixo e
     * {@code (LivingEntity, LivingEntityRenderState, float)}.
     *
     * <p><b>FACT (feedback de 26/09/2026):</b> a versao seguinte usava
     * {@code SpectatorCameraController.getCurrentYaw()}, e o usuario reclamou
     * que a mao "nao segue na mesma velocidade junto com a camera, fica para
     * tras". Causa: {@code currentYaw} e reamostrado <b>uma vez por tick</b>
     * (20 Hz), dentro de {@code ensureRigActive}, enquanto a camera e
     * renderizada <b>por frame</b> com interpolacao. O corpo virava um degrau e
     * a camera uma rampa, entao o corpo arrastava meio tick. A correcao e usar
     * exatamente o valor que o {@code CameraMixin} passa para a camera:
     * {@code CinematicCameraRig.getInterpolatedYaw(partialTick)}.
     */
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void tabletopRpg$alignDownedBodyToCamera(LivingEntity entity,
                                                      LivingEntityRenderState living,
                                                      float partialTick,
                                                      CallbackInfo ci) {
        if (!SpectatorCameraController.isActive()) {
            return;
        }
        if (!(entity instanceof Player player) || !TabletopRpgClient.isDowned(player)) {
            return;
        }
        // Mesmo valor do CameraMixin:39. O fallback e necessario porque em
        // FIRST_PERSON do proprio jogador o rig e desativado enquanto
        // isActive() continua true.
        float camYaw = CinematicCameraRig.isActive()
                ? CinematicCameraRig.getInterpolatedYaw(partialTick)
                : SpectatorCameraController.getCurrentYaw();
        // bodyRot gira a RAIZ do modelo (LivingEntityRenderer.setupRotations:
        // mulPose(Axis.YP.rotationDegrees(180 - bodyRot))), entao torso e
        // bracos acompanham. yRot e offset da CABECA relativo ao corpo: o
        // vanilla define yRot = wrapDegrees(headYaw - bodyRot), entao para a
        // cabeca acompanhar o corpo zeramos yRot.
        living.bodyRot = camYaw;
        living.yRot = 0f;
    }
}
