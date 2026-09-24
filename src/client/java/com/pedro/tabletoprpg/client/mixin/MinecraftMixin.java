package com.pedro.tabletoprpg.client.mixin;

import com.pedro.tabletoprpg.client.TabletopRpgClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bloqueia a troca de perspectiva (tecla F5) enquanto o jogador está
 * "travado" com a câmera cinematográfica ativa.
 *
 * <p>Sem este bloqueio, o jogador congelado poderia apertar F5 e ver o HUD
 * em primeira pessoa (mira central + mão do personagem) enquanto a câmera
 * continua orbitando ao redor dele — quebrando a imersão da cinematic.
 *
 * <p>O clique da tecla é "engolido" no início de {@code handleKeybinds()},
 * antes do processamento padrão do jogo, então nenhuma outra tecla é afetada.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow
    public Options options;

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void tabletopRpg$blockPerspectiveToggle(CallbackInfo ci) {
        if (TabletopRpgClient.locked) {
            // Consome o clique da tecla de perspectiva (F5) para que o
            // processamento padrão do jogo nunca veja o clique.
            while (this.options.keyTogglePerspective.consumeClick()) {
                // Intencionalmente vazio: engole o clique.
            }
        }
    }
}