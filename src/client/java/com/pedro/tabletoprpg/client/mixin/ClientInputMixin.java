package com.pedro.tabletoprpg.client.mixin;

import com.pedro.tabletoprpg.client.SpectatorCameraController;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zera o movimento do jogador LOCAL enquanto a câmera livre esta voando, sem
 * tocar em mais nada do personagem.
 *
 * <p><b>Por que zerar o input em vez de cancelar o {@code aiStep()}:</b> em
 * 1.21.11 e o {@code LivingEntity.aiStep()} que chama o {@code travel()}
 * (movimento, gravidade e colisao) E o {@code calculateEntityAnimation()} (a
 * animacao das pernas). A solucao antiga, cancelar o {@code aiStep()} quando a
 * camera livre estava ligada (ver {@code LocalPlayerMixin}), travava o
 * personagem no ar e deixava as pernas em pose de corrida parada. Cancelando
 * aqui, nao cancelamos nada: apenas o corpo nao anda, enquanto a gravacao faz
 * o personagem assentar no chao e a animacao das pernas volta ao idle, que e o
 * comportamento pedido pelo usuario em 28/09/2026.
 *
 * <p><b>Por que o alvo e {@link KeyboardInput} e nao {@link ClientInput}:</b>
 * {@code LocalPlayer.aiStep()} chama {@code invokevirtual ClientInput.tick()},
 * mas o objeto colocado em {@code LocalPlayer.input} e sempre um
 * {@code KeyboardInput} (criado em {@code ClientPacketListener}), que
 * SOBRESCREVE {@code tick()}. Um inject no corpo de {@code ClientInput.tick}
 * (que esta vazio) seria codigo morto: a chamada e virtual e resolve sempre
 * para a subclasse.
 *
 * <p><b>Por que este mixin estende {@link ClientInput}:</b> os campos
 * {@code moveVector} (protected) e {@code keyPresses} (public) sao declarados
 * no {@code ClientInput}, e o Mixin procura o alvo do {@code @Shadow} so no
 * proprio alvo: um {@code @Shadow} de campo herdado estoura
 * {@code InvalidMixinException: @Shadow field ... was not located in the target
 * class} na abertura. A forma suportada de alcançar membro da classe-pai e o
 * mixin estender a classe-pai direta do alvo, e dai o acesso normal do Java.
 *
 * <p><b>Por que nao zera o {@code keyPresses} inteiro:</b> o jogador pode
 * atacar e usar itens com a camera livre ligada, e esses sinais vivem no mesmo
 * record. So o pulo e o agachar sao zerados, junto do vetor de movimento. O
 * {@code Input} e um record imutavel, entao o caminho e reconstruir o record
 * preservando o resto das teclas - exatamente o que o proprio vanilla faz em
 * {@code ClientInput.makeJump()}.
 */
@Mixin(KeyboardInput.class)
public abstract class ClientInputMixin extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void tabletopRpg$neutralizeMoveWhenFreeCamera(CallbackInfo ci) {
        if (!SpectatorCameraController.isFreeCameraActive()) {
            return;
        }
        // Sem isto o WASD andaria junto com a camera: e daqui que o
        // LocalPlayer.aiStep() le xxa/zza.
        this.moveVector = Vec2.ZERO;
        Input pressed = this.keyPresses;
        this.keyPresses = new Input(pressed.forward(), pressed.backward(),
                pressed.left(), pressed.right(), false, false, pressed.sprint());
    }
}
