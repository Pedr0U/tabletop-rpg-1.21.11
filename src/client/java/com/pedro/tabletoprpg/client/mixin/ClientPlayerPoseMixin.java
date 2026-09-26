package com.pedro.tabletoprpg.client.mixin;

import com.pedro.tabletoprpg.client.TabletopRpgClient;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mantem o personagem DEITADO no lado do cliente.
 *
 * <p><b>FACT (causa raiz do bug "deita e levanta em milésimos"):</b> a pose e
 * recalculada em {@code Player.tick()} -&gt; {@code updatePlayerPose()}, e nao
 * em {@code aiStep()}. O {@code LocalPlayerMixin} cancela o {@code aiStep},
 * entao ele <b>nao</b> impede a recalculacao — o comentario antigo ali dizia o
 * contrario e estava errado.
 *
 * <p>Como o vanilla chama {@code updatePlayerPose()} a cada tick para
 * <b>todos</b> os jogadores, o cliente sobrescrevia a pose de SWIMMING que o
 * servidor tinha aplicado, voltando para STANDING. Como o servidor nao mudava
 * mais a pose, nao reenviava metadata: o resultado era o personagem em pe com
 * HP 0, de forma permanente (e na FASE 3c chegou a virar "flicker", porque a
 * pose do servidor ainda chegava a tempo de ser vista por um frame).
 *
 * <p><b>Correcao na origem:</b> cancelamos {@code updatePlayerPose()} e
 * aplicamos SWIMMING localmente enquanto o servidor tiver marcado este
 * jogador como deitado. Vale para o jogador local E para os outros que
 * aparecem na tela, porque o vanilla recalcula a pose de todos — sem isso o
 * mestre veria os caidos em pe (ver
 * {@link TabletopRpgClient#downedPlayers}).
 *
 * <p>Quando o HP volta a positivo o servidor remove o jogador do conjunto e
 * o vanilla volta a decidir a pose normalmente.
 */
@Mixin(Player.class)
public abstract class ClientPlayerPoseMixin {

    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void tabletopRpg$holdDownedPose(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (TabletopRpgClient.isDowned(self)) {
            self.setPose(Pose.SWIMMING);
            ci.cancel();
        }
    }
}
