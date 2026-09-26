package com.pedro.tabletoprpg.mixin;

import com.pedro.tabletoprpg.DamageControlHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Impede o vanilla de tirar a pose "deitado" do personagem.
 *
 * <p><b>FACT (bug do "flickering"):</b> {@code Player.updatePlayerPose()} roda a
 * cada tick e decide a pose pelas condicoes do jogo (nadando, dormindo,
 * agachado); como o jogador deitado nao esta nadando, ele volta para
 * STANDING todo tick. Aplicar a pose no fim do tick (END_SERVER_TICK) faz o
 * mod e o vanilla brigarem: um tick deitado, o seguinte em pe, e o cliente
 * recebe a mudanca de pose a cada tick -- dai o personagem "tenta levantar e
 * deita" so.
 *
 * <p><b>Correcao na origem:</b> cancelamos o metodo enquanto o personagem
 * estiver deitado, entao a pose nunca e sobrescrita e nao ha mais alternancia.
 * Sem isso, a pose so ficaria estavel se fosse reaplicada antes do tick do
 * jogador -- e o jogo continua capaz de reescreve-la.
 *
 * <p>So afeta o servidor: o guard exige {@code ServerPlayer}, entao em um
 * cliente puro este mixin nunca cancela nada. No cliente,
 * {@code LocalPlayerMixin} ja cancela o {@code aiStep} inteiro do jogador
 * deitado, o que tambem impede a recalculacao local da pose.
 *
 * <p><b>Por que esta na lista "mixins" e nao "server":</b> no Fabric a lista
 * {@code server} so e aplicada no servidor fisico dedicado. O servidor
 * integrado do singleplayer/LAN nao conta como ambiente "server" para o
 * Mixin, entao um mixin so em "server" NAO rodaria no singleplayer -- e o
 * flickering voltaria la. Na lista comum ele roda nos dois, e o guard
 * {@code instanceof ServerPlayer} mantem o cliente limpo.
 */
@Mixin(Player.class)
public abstract class PlayerPoseMixin {

    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void tabletopRpg$keepDownedPose(CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer serverPlayer
                && DamageControlHandler.isDowned(serverPlayer)) {
            ci.cancel();
        }
    }
}
