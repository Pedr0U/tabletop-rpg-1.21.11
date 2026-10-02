package com.pedro.tabletoprpg.mixin;

import com.pedro.tabletoprpg.RollPreset;
import com.pedro.tabletoprpg.RollPresetStore;
import com.pedro.tabletoprpg.TabletopRpg;
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
 * Persiste os Presets de Rolagem no <b>NBT do proprio jogador</b>.
 *
 * <p><b>01/10/2026.</b> Copia exata do padrao de
 * {@link PlayerSheetPersistenceMixin} (ficha), e pelo mesmo motivo: o preset pertence
 * ao jogador (UUID) e o vanilla ja garante que o NBT dele e escrito no logout/save.
 *
 * <p><b>Por que o cache NAO e limpo na desconexao:</b> ver a nota de ordem de
 * leitura/escrita do mixin da ficha. O vanilla grava o jogador no logout; se o cache
 * fosse esvaziado antes disso, o save sairia vazio e a persistencia viraria um no-op
 * silencioso. {@link RollPresetStore#forget} existe para teste e para uma limpeza
 * explicita, mas o desligamento NAO a chama.
 *
 * <p><b>Lista comum, e nao "server":</b> a lista {@code server} do Mixin so roda no
 * servidor dedicado; no singleplayer/LAN integrado ela nao e aplicada e o preset nao
 * persistiria onde o jogador mais testa. O guard {@code instanceof ServerPlayer} mantem
 * o cliente limpo.
 */
@Mixin(Player.class)
public abstract class PlayerRollPresetPersistenceMixin {

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void tabletopRpg$saveRollPresets(ValueOutput output, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer self)) {
            return;
        }
        List<RollPreset> snapshot = RollPresetStore.snapshot(self.getUUID());
        if (snapshot.isEmpty()) {
            // Jogador nunca criou preset (ou deletou todos): nao grava nada, o default
            // entra na proxima carga.
            return;
        }
        output.store(RollPresetStore.NBT_KEY, RollPresetStore.CODEC, snapshot);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void tabletopRpg$loadRollPresets(ValueInput input, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer self)) {
            return;
        }
        input.read(RollPresetStore.NBT_KEY, RollPresetStore.CODEC)
                .ifPresentOrElse(
                        loaded -> {
                            RollPresetStore.replaceAll(self.getUUID(), loaded);
                            TabletopRpg.LOGGER.info("[TabletopRPG] {} preset(s) de rolagem carregados do NBT de {}",
                                    loaded.size(), self.getName().getString());
                        },
                        // Sem a chave: jogador que nunca criou preset. Nao e erro.
                        () -> TabletopRpg.LOGGER.debug(
                                "[TabletopRPG] Sem presets de rolagem no NBT de {}", self.getName().getString()));
    }
}