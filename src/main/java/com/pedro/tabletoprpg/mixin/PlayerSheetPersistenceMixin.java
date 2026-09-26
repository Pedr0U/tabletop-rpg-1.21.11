package com.pedro.tabletoprpg.mixin;

import com.pedro.tabletoprpg.SessionManager;
import com.pedro.tabletoprpg.SheetData;
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
 * Persiste a ficha do personagem no <b>NBT do proprio jogador</b>, para ela
 * sobreviver a desconexao e a restart do servidor.
 *
 * <p><b>Por que mixin e nao um save proprio:</b> a ficha pertence ao jogador
 * (UUID), entao o NBT do jogador e o lugar natural -- o vanilla ja garante
 * que ele e escrito no logout/save e lido na construcao do {@code ServerPlayer}.
 * Um arquivo separado exigiria sincronizar com o ciclo de vida do servidor e
 * poderia ser salvo antes da ultima alteracao da ficha.
 *
 * <p><b>Por que esta na lista comum e nao em "server":</b> a lista
 * {@code server} do Mixin so e aplicada no servidor dedicado -- o servidor
 * integrado do singleplayer/LAN nao conta. Se este mixin estivesse em
 * "server", a ficha <b>nao persistiria no singleplayer</b>, que e justamente
 * onde o jogador mais testa. A lista comum roda nos dois, e o guard
 * {@code instanceof ServerPlayer} (igual ao {@link PlayerPoseMixin}) mantem o
 * cliente limpo.
 *
 * <p><b>Por que o guard no cliente:</b> no cliente a ficha chega por pacote e o
 * {@code SessionManager} e um cache de tela; gravar nele a partir do NBT do
 * save sobrescreveria a copia atualizada por pacotes no momento em que o mundo
 * carrega.
 *
 * <p><b>Ordem de leitura/escrita (verificado no codigo):</b>
 * {@code characterSheets} nunca e limpo e {@code removeSheet} nunca e
 * chamado, entao a ficha continua em memoria quando o vanilla grava o jogador
 * no logout. Sem isso, o save sairia vazio e a persistencia seria um no-op
 * silencioso.
 */
@Mixin(Player.class)
public abstract class PlayerSheetPersistenceMixin {

    /** Chave da ficha dentro do NBT do jogador. */
    private static final String KEY = "tabletoprpg_sheet";

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void tabletopRpg$saveSheet(ValueOutput output, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer self)) {
            return;
        }
        SheetData sheet = SessionManager.getSheet(self.getUUID());
        if (sheet == null) {
            // Ficha nunca criada (jogador entrou e saiu sem abrir a ficha).
            // Nao grava nada: o default entra na proxima carga.
            return;
        }
        output.store(KEY, SheetData.CODEC, sheet);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void tabletopRpg$loadSheet(ValueInput input, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer self)) {
            return;
        }
        input.read(KEY, SheetData.CODEC)
                .ifPresentOrElse(
                        sheet -> {
                            SessionManager.setSheet(self.getUUID(), sheet);
                            TabletopRpg.LOGGER.info(
                                    "[TabletopRPG] Ficha carregada do NBT de {} ({} skills, {} pericias)",
                                    self.getName().getString(), sheet.skills().size(),
                                    sheet.pericias().size());
                        },
                        // Sem a chave: jogador novo. Nao e erro -- o
                        // getOrCreateSheet cria o default no primeiro uso.
                        () -> TabletopRpg.LOGGER.debug(
                                "[TabletopRPG] Sem ficha salva no NBT de {}", self.getName().getString()));
    }
}
