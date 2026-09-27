package com.pedro.tabletoprpg.item;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SessionManager;
import com.pedro.tabletoprpg.TabletopRpg;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Itens e aba criativa do mod.
 *
 * <p>27/09/2026, primeiro pedaco do Sheet Editor. O escopo desta fatia e
 * <b>somente registro e acesso</b>: o item existe, aparece na aba do mod e abre a
 * tela de edicao. A tela ainda e <b>somente leitura</b> — ela mostra as 18
 * pericias de {@code SheetData.PERICIAS_PADRAO} e nao edita nada.
 *
 * <p><b>Por que ainda nao edita:</b> editar exige um modelo salvo no mundo
 * ({@code SavedData}) e dois payloads novos. Esse e o pedaco com risco de perder
 * dado, entao fica para a proxima fatia, depois que o item estiver provado em
 * jogo. Ver {@code agent/reports/2026-09-27_sheet-editor-item.md}.
 *
 * <p><b>Por que {@link UseItemCallback} e nao {@code Item#use}:</b> a assinatura
 * de {@code use} mudou varias vezes entre versoes do Minecraft, e o projeto ja
 * usa o evento do Fabric API para uso de item e de bloco
 * ({@code PlayerControlHandler}). Seguir o mesmo caminho mantem este arquivo sem
 * dependencia de assinatura versionada, e a verificacao de Mestre fica num lugar
 * so.
 */
public final class ModItems {

    /** Chave do registro do item. Precisa existir antes de construir o {@link Item}. */
    public static final ResourceKey<Item> SHEET_EDITOR_KEY =
            ResourceKey.create(Registries.ITEM, TabletopRpg.id("sheet_editor"));

    /**
     * Chave da aba criativa.
     *
     * <p>Fica no <b>comum</b>, e nao no client: {@code CreativeModeTabs.bootstrap}
     * roda no comum nesta versao, e a aba e usada pelo servidor para saber o que
     * existe. So a <i>aba</i> da interface e montada pelo client.
     */
    public static final ResourceKey<CreativeModeTab> TAB_KEY =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, TabletopRpg.id("main"));

    private static Item sheetEditor;

    private ModItems() {
    }

    /**
     * Registra item, aba e o comportamento de uso. Chamar de
     * {@code TabletopRpg.onInitialize()}, antes de qualquer rede.
     */
    public static void register() {
        // O `setId` nao e opcional: `Item.Properties.effectiveDescriptionId()`
        // faz `Objects.requireNonNull(id, "Item id not set")`. Sem ele o jogo
        // quebra ao abrir o inventario, com "Item id not set".
        sheetEditor = new Item(new Item.Properties()
                .setId(SHEET_EDITOR_KEY)
                .stacksTo(1));

        Registry.register(BuiltInRegistries.ITEM, SHEET_EDITOR_KEY, sheetEditor);

        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB_KEY, CreativeModeTab.builder(
                        CreativeModeTab.Row.TOP, 0)
                .title(Component.translatable("itemGroup.tabletoprpg.main"))
                .icon(() -> new ItemStack(sheetEditor))
                .displayItems((parameters, output) -> output.accept(sheetEditor))
                .build());

        UseItemCallback.EVENT.register(ModItems::onUseItem);
    }

    /** O item pronto, para testes e para a aba. */
    public static Item sheetEditor() {
        return sheetEditor;
    }

    /**
     * Clique com o botao direito no Sheet Editor.
     *
     * <p>A tela e aberta pelo <b>servidor</b>, que e quem sabe quem e o Mestre. O
     * cliente devolve {@code SUCCESS} sem fazer nada, so para o braco do jogador
     * nao animar duas vezes; quem decide se abre e o servidor.
     *
     * <p>Decisao do usuario (27/09/2026): o item <b>existe para todo mundo</b> e
     * aparece na aba do mod para todos. Quem nao e Mestre recebe um aviso e a tela
     * nao abre. Filtrar a aba ou o drop por papel deixaria o item invisivel para o
     * jogador comum, e o Mestre teria dificuldade para explicar o item.
     *
     * <p><b>Assinatura do evento nesta versao:</b> {@code (Player, Level,
     * InteractionHand)} — o {@code Level} e o segundo parametro e
     * <b>nao ha {@code ItemStack}</b>. O stack vem de
     * {@code player.getItemInHand(hand)}. Assinatura com 3 params e o stack no
     * lugar do {@code Level} da para o erro "Level cannot be converted to
     * InteractionHand".
     */
    private static InteractionResult onUseItem(net.minecraft.world.entity.player.Player player,
                                               Level level,
                                               net.minecraft.world.InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.is(sheetEditor)) {
            // Cliente: consome o clique e nao decide nada.
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.SUCCESS;
            }
            if (!SessionManager.isMaster(serverPlayer)) {
                serverPlayer.displayClientMessage(
                        Component.translatable("item.tabletoprpg.sheet_editor.denied"), false);
                return InteractionResult.FAIL;
            }
            RpgNetworking.sendOpenSheetEditor(serverPlayer);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}
