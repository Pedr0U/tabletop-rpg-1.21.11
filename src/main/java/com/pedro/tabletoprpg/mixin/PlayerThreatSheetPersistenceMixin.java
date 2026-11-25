package com.pedro.tabletoprpg.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Antigo ponto de persistencia das Fichas de Ameaca no NBT do Mestre.
 *
 * <p><b>Fonte atual:</b> as fichas sao compartilhadas por sessao e ficam no
 * {@link com.pedro.tabletoprpg.SessionStore}, gravadas no SavedData da sessao ativa.
 * O NBT antigo nao e lido nem migrado: a sessao comeca vazia.
 *
 * <p>Os dois injectors ficam sem corpo de proposito, mantidos apenas para nao alterar
 * o arquivo de registro de mixins.
 */
@Mixin(Player.class)
public abstract class PlayerThreatSheetPersistenceMixin {

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void tabletopRpg$saveThreatSheets(ValueOutput output, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer)) {
            return;
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void tabletopRpg$loadThreatSheets(ValueInput input, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer)) {
            return;
        }
    }
}
