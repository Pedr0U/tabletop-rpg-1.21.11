package com.pedro.tabletoprpg.client.mixin;

import com.pedro.tabletoprpg.client.TabletopRpgClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Torna o contorno do hover visível APENAS para o próprio jogador que está
 * com o mouse em cima do mob (FASE 2, feedback do usuário).
 *
 * <p><b>Contexto:</b> antes, o servidor aplicava o efeito vanilla
 * {@code Glowing} {MobEffects.GLOWING} no mob sob o crosshair. Esse efeito é
 * GLOBAL: o contorno ia para a tela de TODOS os jogadores do servidor (o
 * melhor exemplo: o mestre colocou o mouse no creeper e OUTROS jogadores viram
 * o highlight dele). O vanilla também tem esta mesma regra global de
 * "destacar membro do time para o time inteiro".
 *
 * <p><b>Como funciona agora (apenas local):</b> no frame de renderização, o
 * vanilla pergunta a cada entidade se ela deve "aparecer brilhando"
 * ({@code Minecraft#shouldEntityAppearGlowing(Entity)}) — se sim, o contorno
 * é desenhado. Este mixin redireciona essa pergunta: uma entidade só passa a
 * "aparecer brilhando" quando ela é EXATAMENTE a que o JOGADOR LOCAL está
 * mirando ({@link TabletopRpgClient#getHoveredEntityId()}).
 *
 * <p>Ou seja: o contorno do destaque é renderizado no cliente de quem
 * hoverou — ninguém mais vê (nem o efeito Glowing vanilla é mais aplicado).
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    /**
     * Marca como "brilhante" apenas a entidade que o jogador local está
     * mirando. Todos os outros mobs retornam {@code false} (sem contorno)
     * mesmo que o efeito GLOWING não exista mais no servidor — para um
     * jogador que NÃO está mirando nada, nada fica destacado.
     */
    @Redirect(
            method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;shouldEntityAppearGlowing(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean tabletopRpg$glowOnlyForHover(Minecraft minecraft, Entity entity) {
        return entity.getId() == TabletopRpgClient.getHoveredEntityId();
    }
}