package com.pedro.tabletoprpg.client.mixin;

import com.pedro.tabletoprpg.client.SpectatorCameraController;
import com.pedro.tabletoprpg.client.TabletopRpgClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Controles de espectador enquanto o jogador está "travado" (FASE 2):
 * <ul>
 *   <li><b>Carrossel de alvos</b>: clique esquerdo = próximo alvo, clique
 *       direito = alvo anterior. Os cliques são "engolidos" no início de
 *       {@code handleKeybinds()}, antes do processamento padrão do jogo, então
 *       o jogador travado não ataca nem usa itens (o servidor já bloqueia, mas
 *       isso evita a animação de braço no cliente).</li>
 *   <li><b>F5 bloqueado</b>: a perspectiva é controlada pelos modos de câmera
 *       (tecla V), não pela troca vanilla de perspectiva.</li>
 * </ul>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow
    public Options options;

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void tabletopRpg$spectatorControls(CallbackInfo ci) {
        if (TabletopRpgClient.locked) {
            // Carrossel de espectador: clique esquerdo = próximo, direito = anterior.
            while (this.options.keyAttack.consumeClick()) {
                SpectatorCameraController.cycleNext();
            }
            while (this.options.keyUse.consumeClick()) {
                SpectatorCameraController.cyclePrev();
            }
            // Consome o clique da tecla de perspectiva (F5) para que o
            // processamento padrão do jogo nunca veja o clique.
            while (this.options.keyTogglePerspective.consumeClick()) {
                // Intencionalmente vazio: engole o clique.
            }
        }
    }
}