package com.pedro.tabletoprpg.mixin;

import com.pedro.tabletoprpg.TabletopRpg;
import com.pedro.tabletoprpg.ThreatSheet;
import com.pedro.tabletoprpg.ThreatSheetStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Persiste as Fichas de Ameaca no <b>NBT do proprio Mestre</b>.
 *
 * <p><b>02/10/2026.</b> Copia exata do padrao do
 * {@link PlayerRollPresetPersistenceMixin}, e pelos mesmos dois motivos: a ficha
 * pertence ao jogador (UUID) e o vanilla ja escreve o NBT dele no logout/save; e a
 * lista do mixin tem de ser a comum, porque a {@code server} so roda no servidor
 * dedicado e a ficha sumiria justamente onde o Mestre mais testa.
 *
 * <p><b>Por que o cache NAO e limpo na desconexao:</b> o vanilla grava o jogador no
 * logout. Esvaziar o cache antes disso produziria um save vazio e a persistencia
 * viraria um no-op silencioso. {@link ThreatSheetStore#forget} existe para teste e
 * limpeza explicita, e o desligamento nao a chama.
 */
@Mixin(Player.class)
public abstract class PlayerThreatSheetPersistenceMixin {

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void tabletopRpg$saveThreatSheets(ValueOutput output, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer self)) {
            return;
        }
        List<ThreatSheet> snapshot = ThreatSheetStore.snapshot(self.getUUID());
        if (snapshot.isEmpty()) {
            // Mestre que nunca criou ficha (ou apagou todas): nao grava nada, e o
            // default entra na proxima carga.
            return;
        }
        output.store(ThreatSheetStore.NBT_KEY, ThreatSheetStore.CODEC, snapshot);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void tabletopRpg$loadThreatSheets(ValueInput input, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer self)) {
            return;
        }
        input.read(ThreatSheetStore.NBT_KEY, ThreatSheetStore.CODEC)
                .ifPresentOrElse(
                        loaded -> {
                            ThreatSheetStore.replaceAll(self.getUUID(), loaded);
                            TabletopRpg.LOGGER.info("[TabletopRPG] {} ficha(s) de ameaça carregadas do NBT de {}",
                                    loaded.size(), self.getName().getString());
                        },
                        // Sem a chave: Mestre que nunca criou ficha. Nao e erro.
                        () -> TabletopRpg.LOGGER.debug(
                                "[TabletopRPG] Sem fichas de ameaça no NBT de {}", self.getName().getString()));
    }
}