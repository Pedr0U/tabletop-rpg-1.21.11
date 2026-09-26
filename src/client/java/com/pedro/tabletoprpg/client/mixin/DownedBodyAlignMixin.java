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

import java.util.Locale;

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

    // ==== DIAG_TEMP (26/09/2026) =============================================
    // INSTRUMENTACAO TEMPORARIA. Duas hipoteses anteriores falharam; esta
    // rodada nao chuta mais, mede. Ligar/desligar com DIAG_ENABLED sem mexer no
    // resto do arquivo.
    //
    // Uma linha a cada DIAG_EVERY chamadas de Player. Cada linha responde:
    //  spec  -> SpectatorCameraController.isActive()
    //  down  -> TabletopRpgClient.isDowned(player)
    //  rig   -> CinematicCameraRig.isActive()
    //  pT    -> partialTick recebido no render da entidade
    //  camYaw-> yaw que calculamos
    //  bRotB -> bodyRot ANTES da nossa atribuicao (valor do vanilla)
    //  bRotW -> bodyRot que NOS escrevemos no frame anterior
    //  camAw -> yaw que a camera REALMENTE aplicou neste frame
    //  camPT -> partialTick que a camera REALMENTE usou neste frame
    //
    // O estado do cameras vive em CinematicCameraRig (classe normal): campo e
    // metodo estatico de mixin tem que ser private, com `public` o jogo morre
    // em "InvalidMixinException: contains non-private static method/field".
    //
    // Leitura:
    //  - "SKIP" com down=1 e spec=1  -> nunca escrevemos (hipotese (a) variante)
    //  - bRotB != bRotW              -> outro codigo sobrescreve DEPOIS do nosso
    //                                  TAIL (hipotese (b)) CONFIRMADA
    //  - pT != camPT                 -> o render de entidade usa um partialTick
    //                                  diferente do da camera (hipotese (c))
    //                                  CONFIRMADA
    //  - WROTE com bRotB == bRotW == camAw e o corpo ainda nao virar -> o problema
    //    NAO e este mixin; e o que consome bodyRot adiante.
    // FATO (26/09/2026): com `public static final`, o runClient morre em
    // "InvalidMixinException: contains non-private static field". Campo de
    // mixin e mesclado no alvo, entao tem que ser private. Vale para metodo
    // estatico tambem.
    private static final boolean DIAG_ENABLED = true;
    private static final int DIAG_EVERY = 10;
    private static int diagCounter = 0;
    private static float diagLastWrittenBodyRot = Float.NaN;
    // ==== /DIAG_TEMP ==========================================================

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
     * renderizada <b>por frame</b> com interpolacao. A correcao usada foi
     * {@code CinematicCameraRig.getInterpolatedYaw(partialTick)} - que NAO
     * resolveu o sintoma. Por isso a medicao.
     */
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void tabletopRpg$alignDownedBodyToCamera(LivingEntity entity,
                                                      LivingEntityRenderState living,
                                                      float partialTick,
                                                      CallbackInfo ci) {
        // Reorganizado apenas para caber a instrumentacao: a ordem das condicoes
        // e a mesma da versao anterior (nao-Player sai; nao-espectador sai;
        // nao-caido sai) e nada e tocado no render state nesses casos.
        if (!(entity instanceof Player player)) {
            return;
        }
        boolean spectator = SpectatorCameraController.isActive();
        boolean downed = TabletopRpgClient.isDowned(player);
        if (!spectator || !downed) {
            if (DIAG_ENABLED) {
                tabletopRpg$diag(player, partialTick, living, spectator, downed,
                        CinematicCameraRig.isActive(), Float.NaN, false);
            }
            return;
        }
        // Mesmo valor do CameraMixin. O fallback e necessario porque em
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
        if (DIAG_ENABLED) {
            tabletopRpg$diag(player, partialTick, living, spectator, downed,
                    CinematicCameraRig.isActive(), camYaw, true);
        }
    }

    // ==== DIAG_TEMP (26/09/2026) =============================================
    private static void tabletopRpg$diag(Player player, float partialTick,
                                         LivingEntityRenderState living,
                                         boolean spectator, boolean downed,
                                         boolean rigActive, float camYaw,
                                         boolean wrote) {
        if (++diagCounter % DIAG_EVERY != 0) {
            return;
        }
        float bodyRotBefore = living.bodyRot;
        float bodyRotPrevWritten = diagLastWrittenBodyRot;
        if (wrote) {
            diagLastWrittenBodyRot = camYaw;
        }
        TabletopRpgClient.LOGGER.info(String.format(Locale.ROOT,
                "[DownAlign] f=%d ent=%s local=%s spec=%s down=%s rig=%s "
                        + "pT=%.3f camYaw=%.2f bRotB=%.2f bRotW=%.2f yRotB=%.2f "
                        + "camAw=%.2f camPT=%.3f -> %s",
                diagCounter,
                player.getName().getString(),
                player.isLocalPlayer(),
                spectator,
                downed,
                rigActive,
                partialTick,
                camYaw,
                bodyRotBefore,
                bodyRotPrevWritten,
                living.yRot,
                CinematicCameraRig.tabletopRpg$lastAppliedYaw(),
                CinematicCameraRig.tabletopRpg$lastAppliedPartialTick(),
                wrote ? "WROTE" : "SKIP"));
    }
    // ==== /DIAG_TEMP ==========================================================
}
