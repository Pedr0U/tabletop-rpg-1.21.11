package com.pedro.tabletoprpg.mixin;

import com.pedro.tabletoprpg.DiaryStore;
import com.pedro.tabletoprpg.TabletopRpg;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Persiste o Diario no <b>NBT do proprio jogador</b>.
 *
 * <p><b>02/10/2026.</b> Copia exata do padrao de
 * {@link PlayerRollPresetPersistenceMixin} (presets), e pelo mesmo motivo: o diario pertence
 * ao jogador (UUID) e o vanilla ja garante que o NBT dele e escrito no logout/save.
 *
 * <p><b>Por que o cache NAO e limpo na desconexao:</b> o vanilla grava o jogador no logout; se
 * o cache fosse esvaziado antes disso, o save sairia vazio e a persistencia viraria um no-op
 * silencioso. {@link DiaryStore#forget} existe para teste e limpeza explicita, mas o
 * desligamento NAO a chama.
 *
 * <p><b>Lista comum, e nao "server":</b> a lista {@code server} do Mixin so roda no servidor
 * dedicado; no singleplayer/LAN integrado ela nao e aplicada e o diario nao persistiria onde a
 * jogadora mais testa. O guard {@code instanceof ServerPlayer} mantem o cliente limpo.
 *
 * <p><b>O que NAO vai para o NBT:</b> o apagado do botao Reverter. Ele fica so no cache em
 * memoria ({@code DiaryStore.UNDO}) porque a jogadora pediu desfazer "no uso atual da
 * interface"; gravar sobreviveria a sessao e devolveria uma subarvore de outro dia.
 */
@Mixin(Player.class)
public abstract class PlayerDiaryPersistenceMixin {

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void tabletopRpg$saveDiary(ValueOutput output, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer self)) {
            return;
        }
        DiaryStore.Diary diary = DiaryStore.diary(self.getUUID());
        if (diary.entries().isEmpty()) {
            // Jogadora nunca criou anotação (ou apagou todas): nao grava nada, e o default
            // entra na proxima carga.
            return;
        }
        output.store(DiaryStore.NBT_KEY, DiaryStore.Diary.CODEC, diary);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void tabletopRpg$loadDiary(ValueInput input, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer self)) {
            return;
        }
        input.read(DiaryStore.NBT_KEY, DiaryStore.Diary.CODEC)
                .ifPresentOrElse(
                        loaded -> {
                            DiaryStore.replaceAll(self.getUUID(), loaded);
                            TabletopRpg.LOGGER.info("[TabletopRPG] {} anotação(ões) de diário carregadas do NBT de {}",
                                    loaded.entries().size(), self.getName().getString());
                        },
                        // Sem a chave: jogadora que nunca criou anotação. Nao e erro.
                        () -> DiaryStore.forget(self.getUUID()));
    }
}