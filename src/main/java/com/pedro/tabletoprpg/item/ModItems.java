package com.pedro.tabletoprpg.item;

import com.pedro.tabletoprpg.BlockLockManager;
import com.pedro.tabletoprpg.MasterCommands;
import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.RollPreset;
import com.pedro.tabletoprpg.RollPresetColor;
import com.pedro.tabletoprpg.RollPresetStore;
import com.pedro.tabletoprpg.SessionManager;
import com.pedro.tabletoprpg.TabletopRpg;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

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
     * Chave do registro do Block Locker.
     *
     * <p><b>Sprite provisorio:</b> a textura {@code minecraft:item/trial_key}
     * (verificada no jar do cliente 1.21.11). O usuario pediu esse sprite
     * "por enquanto"; trocar depois e so sobrescrever o {@code layer0} de
     * {@code models/item/block_lock.json}, sem tocar em codigo.
     */
    public static final ResourceKey<Item> BLOCK_LOCK_KEY =
            ResourceKey.create(Registries.ITEM, TabletopRpg.id("block_lock"));

    /**
     * Chave do registro do Camera Tool.
     *
     * <p><b>Sprite:</b> copia da luneta do proprio jogo
     * ({@code minecraft:textures/item/spyglass.png}, 16x16) extraida do jar do cliente
     * 1.21.11 para {@code assets/tabletop-rpg/textures/item/camera_tool.png}.
     *
     * <p><b>Por que copia, e nao referencia direta</b> (decisao do usuario em
     * 01/10/2026): o sprite e <b>temporario</b>. Referenciar {@code minecraft:item/spyglass}
     * deixaria o item preso a textura do jogo -- trocar depois exigiria mexer em codigo, e
     * uma mudanca de textura do vanilla no futuro mudaria o item sem ninguem pedir. Com a
     * copia, trocar o visual e sobrescrever {@code camera_tool.png}, sem tocar em codigo.
     */
    public static final ResourceKey<Item> CAMERA_TOOL_KEY =
            ResourceKey.create(Registries.ITEM, TabletopRpg.id("camera_tool"));

    /**
     * Chave do registro do Preset de Rolagem (01/10/2026).
     *
     * <p><b>Sprite provisório:</b> o Bundle do proprio jogo
     * ({@code minecraft:textures/item/bundle.png}, 16x16) foi copiado do jar do cliente
     * para {@code assets/tabletop-rpg/textures/item/roll_preset.png}, igual ao Camera
     * Tool copiou o da luneta.
     *
     * <p><b>Por que a cópia foi convertida para máscara em escala de cinza:</b> a cor do
     * item entra pelo tint {@code minecraft:dye}, que MULTIPLICA a textura pela cor. Num
     * sprite marrom como o do Bundle, o verde daria marrom-lodo e o preto daria buraco
     * preto. A máscara (cinza 170..255) é o mesmo truque das armaduras de couro do
     * vanilla, cuja textura também é uma máscara — aí o multiplicado dá a cor pedida.
     *
     * <p><b>FATO (01/10/2026, conferido no mappings e nos assets do jar):</b> o Bundle
     * vanilla <b>não</b> usa componente de cor. São 17 itens registrados
     * ({@code bundle}, {@code white_bundle}...), cada um com sua textura pintada à mão.
     * O que este item faz é diferente de propósito: 1 item + 1 componente, o que mantém
     * 17 cores com uma única textura.
     */
    public static final ResourceKey<Item> ROLL_PRESET_KEY =
            ResourceKey.create(Registries.ITEM, TabletopRpg.id("roll_preset"));

    /** Chaves dentro do {@code CUSTOM_DATA} do item. */
    private static final String NBT_PRESET = "preset";
    private static final String NBT_FORMULA = "formula";
    private static final String NBT_COLOR = "color";

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

    private static Item blockLock;

    private static Item cameraTool;

    private static Item rollPreset;

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

        // stacksTo(1): e uma ferramenta de Mesa, nao um consumivel. O
        // `setId` nao e opcional pelo mesmo motivo do Sheet Editor
        // ("Item id not set" ao abrir o inventario).
        blockLock = new Item(new Item.Properties()
                .setId(BLOCK_LOCK_KEY)
                .stacksTo(1));

        Registry.register(BuiltInRegistries.ITEM, BLOCK_LOCK_KEY, blockLock);

        // Camera Tool: mesma forma do Block Locker (ferramenta de Mesa, nao
        // consumivel, um por stack). O sprite e so no JSON do modelo.
        cameraTool = new Item(new Item.Properties()
                .setId(CAMERA_TOOL_KEY)
                .stacksTo(1));

        Registry.register(BuiltInRegistries.ITEM, CAMERA_TOOL_KEY, cameraTool);

        // Preset de Rolagem (01/10/2026). Classe anonima porque o item carrega a
        // dica (formula e cor) no tooltip -- e a dica que permite a jogadora
        // distinguir os itens quando tem varios na mochila.
        //
        // NAO entra na aba criativa, ao contrario dos outros tres itens: um item
        // solto na aba nao tem preset nenhum e usaria-lo so serviria para mostrar
        // "preset nao existe". Quem cria preset e o comando ou o botao da tela.
        rollPreset = new Item(new Item.Properties()
                .setId(ROLL_PRESET_KEY)
                .stacksTo(1)) {
            @Override
            public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                        TooltipDisplay display, Consumer<Component> tooltip,
                                        TooltipFlag flag) {
                super.appendHoverText(stack, context, display, tooltip, flag);
                CompoundTag tag = presetTag(stack);
                if (tag == null) {
                    // Item sem preset nenhum (item virgem). A dica honesta e dizer isso,
                    // e nao fingir que existe uma formula.
                    tooltip.accept(Component.translatable("item.tabletop-rpg.roll_preset.no_preset"));
                    return;
                }
                tooltip.accept(Component.translatable("item.tabletop-rpg.roll_preset.formula",
                        tag.getString(NBT_FORMULA).orElse("")));
                tooltip.accept(Component.translatable("item.tabletop-rpg.roll_preset.color",
                        RollPresetColor.idOrDefault(tag.getString(NBT_COLOR).orElse("")).displayName()));
            }
        };

        Registry.register(BuiltInRegistries.ITEM, ROLL_PRESET_KEY, rollPreset);

        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB_KEY, CreativeModeTab.builder(
                        CreativeModeTab.Row.TOP, 0)
                .title(Component.translatable("itemGroup.tabletoprpg.main"))
                .icon(() -> new ItemStack(sheetEditor))
                .displayItems((parameters, output) -> {
                    output.accept(sheetEditor);
                    output.accept(blockLock);
                    output.accept(cameraTool);
                })
                .build());

        UseItemCallback.EVENT.register(ModItems::onUseItem);
    }

    /** O item pronto, para testes e para a aba. */
    public static Item sheetEditor() {
        return sheetEditor;
    }

    /** O Block Locker pronto. */
    public static Item blockLock() {
        return blockLock;
    }

    /**
     * O stack na mao e o Block Locker?
     *
     * <p>Usado pelo {@code BlockLockManager} para decidir se o clique do mestre
     * deve alternar a tranca. Fica aqui, e nao la, porque a identidade do item
     * e responsabilidade deste arquivo: e o lugar onde o item e registrado.
     */
    public static boolean isBlockLock(ItemStack stack) {
        return blockLock != null && stack != null && stack.is(blockLock);
    }

    /** O Camera Tool pronto, para a aba. */
    public static Item cameraTool() {
        return cameraTool;
    }

    /**
     * O stack na mao e o Camera Tool?
     *
     * <p>Usado pelo {@code CameraToolManager} para decidir se o clique numa entidade
     * vira camera. Fica aqui pelo mesmo motivo do {@link #isBlockLock}: a identidade do
     * item e responsabilidade de quem o registra.
     */
    public static boolean isCameraTool(ItemStack stack) {
        return cameraTool != null && stack != null && stack.is(cameraTool);
    }

    // --- Preset de Rolagem (01/10/2026) ---

    /** O Preset de Rolagem pronto. */
    public static Item rollPreset() {
        return rollPreset;
    }

    /** O stack e um Preset de Rolagem? */
    public static boolean isRollPreset(ItemStack stack) {
        return rollPreset != null && stack != null && stack.is(rollPreset);
    }

    /**
     * O {@code CUSTOM_DATA} do item, ou {@code null} se nao for um item de preset
     * valido.
     *
     * <p><b>Por que devolver a tag e nao o preset:</b> o item guarda NOME, FORMULA e
     * COR da <i>entrega</i>, que podem ser diferentes do preset salvo agora. O cliente
     * so consegue ler a tag (ele nao tem o {@link RollPresetStore}), entao e a tag que
     * alimenta a dica.
     */
    private static CompoundTag presetTag(ItemStack stack) {
        if (!isRollPreset(stack)) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.getString(NBT_PRESET).orElse("").isEmpty() ? null : tag;
    }

    /** Monta o stack de um preset: nome, cor, formula e a chave que o liga ao preset salvo. */
    public static ItemStack buildRollPresetStack(RollPreset preset) {
        ItemStack stack = new ItemStack(rollPreset);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.translatable("item.tabletop-rpg.roll_preset.named", preset.name()));

        CompoundTag tag = new CompoundTag();
        tag.putString(NBT_PRESET, preset.key());
        tag.putString(NBT_FORMULA, preset.formula());
        tag.putString(NBT_COLOR, preset.colorId());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        // DEFAULT nao pinta o componente: sem ele o tint `minecraft:dye` do
        // items/roll_preset.json usa o "default" do proprio JSON, que e o marrom
        // do Bundle sem tingir. Os 16 ids pintam.
        RollPresetColor color = preset.color();
        if (color != RollPresetColor.DEFAULT) {
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color.argb()));
        }
        return stack;
    }

    /**
     * Entrega o item de um preset no inventario.
     *
     * @return o stack entregue, ou {@code null} se o inventario estava cheio
     */
    public static ItemStack giveRollPreset(ServerPlayer player, RollPreset preset) {
        ItemStack stack = buildRollPresetStack(preset);
        return player.getInventory().add(stack) ? stack : null;
    }

    /**
     * Reaplica nome, cor e dica nos itens de um preset que a jogadora ja carrega.
     *
     * <p><b>Por que isso existe (01/10/2026):</b> o {@code /rpg preset edit} muda o
     * preset sem entregar item novo. Sem esta passada, o item antigo continuaria
     * mostrando a formula antiga na dica e com a cor antiga, e a jogadora veria duas
     * informacoes diferentes para o mesmo preset.
     */
    public static void refreshRollPresetItems(ServerPlayer player, RollPreset preset) {
        ItemStack updated = buildRollPresetStack(preset);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack existing = player.getInventory().getItem(slot);
            if (!isRollPreset(existing)) {
                continue;
            }
            CompoundTag tag = presetTag(existing);
            if (tag == null || !preset.key().equals(tag.getString(NBT_PRESET).orElse(""))) {
                continue;
            }
            existing.set(DataComponents.CUSTOM_NAME, updated.get(DataComponents.CUSTOM_NAME));
            existing.set(DataComponents.CUSTOM_DATA, updated.get(DataComponents.CUSTOM_DATA));
            if (updated.has(DataComponents.DYED_COLOR)) {
                existing.set(DataComponents.DYED_COLOR, updated.get(DataComponents.DYED_COLOR));
            } else {
                existing.remove(DataComponents.DYED_COLOR);
            }
        }
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

        // Block Locker: este e o caminho do clique NO AR (no chao e sem alvo de
        // bloco), porque o clique num bloco e interceptedado antes, pelo
        // BlockLockManager, e nunca chega aqui.
        if (isBlockLock(stack)) {
            // Cliente: consome o clique e nao decide nada.
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.SUCCESS;
            }
            if (!SessionManager.isMaster(serverPlayer)) {
                serverPlayer.displayClientMessage(
                        Component.translatable("item.tabletop-rpg.block_lock.denied"), false);
                return InteractionResult.FAIL;
            }
            // Nao repetir a dica quando o clique ja foi num bloco: nesse caso o
            // BlockLockManager respondeu (trancou, destrancou ou recusou), e o
            // cliente ainda envia este segundo pacote.
            if (!BlockLockManager.justHandledBlockClick(level)) {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.tabletop-rpg.block_lock_pick_block"), true);
            }
            return InteractionResult.PASS;
        }

        // Camera Tool: tambem e o caminho do clique NO AR. Numa entidade quem responde
        // primeiro e o CameraToolManager (pelo UseEntityCallback), entao se o codigo
        // chegou aqui o Mestre clicou em nada e so precisa da dica.
        //
        // O item NAO arma pedido nenhum: quem arma e o `/rpg insert camera`. Enquanto o
        // item esta na mao ele ja basta (o CameraToolManager checa a mao), e armar aqui
        // faria o pedido sobreviver a troca de item, aplicando camera na proxima criatura
        // que o Mestre tocasse com outra coisa na mao -- surpresa que ninguem pediu.
        if (isCameraTool(stack)) {
            // Cliente: consome o clique e nao decide nada.
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.SUCCESS;
            }
            if (!SessionManager.isMaster(serverPlayer)) {
                serverPlayer.displayClientMessage(
                        Component.translatable("item.tabletop-rpg.camera_tool.denied"), false);
                return InteractionResult.FAIL;
            }
            serverPlayer.displayClientMessage(
                    Component.translatable("message.tabletop-rpg.camera_tool_pick_entity"), true);
            return InteractionResult.PASS;
        }

        // Preset de Rolagem: clique com o botao direito ROLA. Nao ha checagem de Mestre
        // porque o preset e pessoal (decisao do usuario em 01/10/2026).
        if (isRollPreset(stack)) {
            // Cliente: consome o clique e nao decide nada.
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.SUCCESS;
            }
            CompoundTag tag = presetTag(stack);
            if (tag == null) {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.tabletoprpg.preset_no_preset_on_item"), true);
                return InteractionResult.FAIL;
            }

            // O preset pode ter sido deletado com `/rpg preset delete` depois que o
            // item foi entregue: o item fica na mochila de proposito. Aqui e o aviso
            // de que o preset nao existe mais.
            RollPreset preset = RollPresetStore.find(serverPlayer.getUUID(),
                    tag.getString(NBT_PRESET).orElse("")).orElse(null);
            if (preset == null) {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.tabletoprpg.preset_not_found",
                                tag.getString(NBT_PRESET).orElse("")), true);
                return InteractionResult.FAIL;
            }

            // SUCCESS (e nao PASS): o item nao tem outro uso, e PASS deixaria o jogador
            // colocar no mundo o item inteiro a cada rolagem.
            MasterCommands.rollForPlayer(serverPlayer, preset.formula());
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }
}
