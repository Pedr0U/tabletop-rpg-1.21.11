package com.pedro.tabletoprpg;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do inventario da ficha (30/09/2026, FASE 2B).
 *
 * <p><b>O que estes testes seguram:</b> tres regras do enunciado que o build
 * sozinho nao pega, porque sao todas de <b>valor</b>, e nao de sintaxe.
 * <ol>
 *   <li><b>Aritmetica do peso:</b> o arredondamento e na ENTRADA do item (uma
 *       vez), e a soma e arredondada outra vez no fim. Um item que continua
 *       exibindo uma terceira casa faria a linha de resumo mostrar um total que a
 *       soma dos itens nao tem.</li>
 *   <li><b>Limites:</b> 50 itens e o teto de 9999 sao cortados <b>por
 *       construcao</b>, inclusive quando chegam de um payload forjado, e o
 *       excedente nao bloqueia a gravacao (devolve a propria instancia, e o
 *       receptor nem transmite).</li>
 *   <li><b>Round-trip de rede:</b> o inventario e o ultimo campo do
 *       {@link SheetData#STREAM_CODEC}, e encoder e decoder sao duas lambdas
 *       independentes. Este e o teste que pega um inventario gravado em ordem
 *       diferente da que e lida -- o mod inteiro compila nesse caso.</li>
 * </ol>
 */
class SheetDataInventoryTest {

    private static SheetData.InventoryItem item(String name, float weight) {
        return new SheetData.InventoryItem(name, "gear", weight, "");
    }

    private static SheetData sheetWith(SheetData.Inventory inventory) {
        SheetData base = SheetData.defaultSheet("Tester");
        return base.withInventory(inventory);
    }

    @Test
    @DisplayName("O peso e arredondado na entrada, e a soma arredonda so no final")
    void weightArithmetic() {
        SheetData.InventoryItem rounded = new SheetData.InventoryItem("Corda", "gear", 12.3456f, "");
        assertEquals(12.35f, rounded.weight(), 0.0001f, "o peso do item ja nasce com 2 casas");
        // 0.004 arredonda para 0.00, e nao sobe para 0.01: o item nao pesa nada.
        assertEquals(0f, new SheetData.InventoryItem("Corda", "gear", 0.004f, "").weight(), 0.0001f);

        SheetData.Inventory inventory = new SheetData.Inventory(
                List.of(new SheetData.InventoryItem("A", "gear", 12.34f, ""),
                        new SheetData.InventoryItem("B", "gear", 0.66f, "")),
                100f);
        assertEquals(13.0f, inventory.totalWeight(), 0.0001f);
        // A soma de 50 itens de 0.01 daria 0.5 em ponto flutuante com erro de
        // arrasto se o arredondamento fosse feito a cada parcela.
        List<SheetData.InventoryItem> tiny = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tiny.add(new SheetData.InventoryItem("i" + i, "gear", 0.01f, ""));
        }
        assertEquals(0.5f, new SheetData.Inventory(tiny, 100f).totalWeight(), 0.0001f);
    }

    @Test
    @DisplayName("O peso e cortado em [0, 9999] e NaN/Infinito viram 0")
    void weightClamp() {
        assertEquals(9999f, new SheetData.InventoryItem("A", "", 1e9f, "").weight(), 0.0001f);
        assertEquals(0f, new SheetData.InventoryItem("A", "", -5f, "").weight(), 0.0001f);
        assertEquals(0f, new SheetData.InventoryItem("A", "", Float.NaN, "").weight(), 0.0001f);
        assertEquals(0f, new SheetData.InventoryItem("A", "", Float.POSITIVE_INFINITY, "").weight(), 0.0001f);
        // maxWeight segue a mesma regra: um limite negativo viraria um intervalo
        // em que TODO item estivesse acima, e o vermelho apareceria sem motivo.
        assertEquals(0f, new SheetData.Inventory(List.of(), -3f).maxWeight(), 0.0001f);
    }

    @Test
    @DisplayName("A lista corta em 50 itens e o 51o devolve a propria instancia")
    void itemLimit() {
        List<SheetData.InventoryItem> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(item("item" + i, 1f));
        }
        SheetData.Inventory full = new SheetData.Inventory(many, 500f);
        assertEquals(SheetData.Inventory.MAX_ITEMS, full.items().size());
        // Mesma instancia = o receptor ve `updated == current` e nao transmite.
        assertSame(full, full.addItem(item("outro", 1f)));
    }

    @Test
    @DisplayName("overweight() e so cor: o excesso nunca bloqueia a gravacao")
    void overweightDoesNotBlock() {
        SheetData.Inventory inventory = new SheetData.Inventory(
                List.of(item("A", 10f), item("B", 5f)), 10f);
        assertEquals(15f, inventory.totalWeight(), 0.0001f);
        assertTrue(inventory.overweight());
        // O item que estourou o limite continua na ficha: quem decide se o
        // personagem carrega o que carrega e o Mestre, em jogo.
        assertEquals(2, inventory.items().size());
        assertEquals(15f, inventory.totalWeight(), 0.0001f);
        assertFalse(new SheetData.Inventory(List.of(item("A", 10f)), 10f).overweight());
    }

    @Test
    @DisplayName("UPDATE/REMOVE com indice fora da lista nao mudam nada")
    void indexOutOfRange() {
        SheetData.Inventory inventory = new SheetData.Inventory(List.of(item("A", 1f)), 10f);
        assertSame(inventory, inventory.withItem(1, item("B", 2f)));
        assertSame(inventory, inventory.removeItem(-1));
        assertSame(inventory, inventory.removeItem(1));
        // Item igual no mesmo indice tambem nao gera ficha nova: evita gravar e
        // transmitir a cada eco.
        assertSame(inventory, inventory.withItem(0, item("A", 1f)));
        assertEquals(1, inventory.withItem(0, item("A", 2f)).items().size());
        assertEquals(2f, inventory.withItem(0, item("A", 2f)).items().get(0).weight(), 0.0001f);
    }

    @Test
    @DisplayName("Item sem nome e descartado, e o limite de peso vai por withField")
    void sanitizeAndMaxWeightField() {
        SheetData.Inventory inventory = new SheetData.Inventory(
                List.of(item("A", 1f), new SheetData.InventoryItem("", "gear", 2f, "")), 10f);
        assertEquals(1, inventory.items().size(), "item sem nome nao entra na lista");

        SheetData sheet = sheetWith(inventory);
        assertEquals("10.00", sheet.getText("maxWeight"));
        // Texto incompleto ("12.") mantem o valor anterior em vez de zerar.
        assertEquals(10f, sheet.withField("maxWeight", "12.").inventory().maxWeight(), 0.0001f);
        assertEquals(12.5f, sheet.withField("maxWeight", "12.5").inventory().maxWeight(), 0.0001f);
        // Valor que nao muda devolve a propria ficha: o receptor sai sem transmitir.
        assertSame(sheet, sheet.withField("maxWeight", "10"));
    }

    @Test
    @DisplayName("O item e o inventario sobrevivem a um round-trip pelo CODEC de NBT")
    void nbtRoundTrip() {
        SheetData original = sheetWith(new SheetData.Inventory(
                List.of(new SheetData.InventoryItem("Espada", "arma", 12.34f, "uma espada"),
                        new SheetData.InventoryItem("Corda", "ferramenta", 0.5f, "")),
                30f));

        CompoundTag tag = (CompoundTag) SheetData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        SheetData decoded = SheetData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();

        assertEquals(original.inventory(), decoded.inventory());
        assertEquals("Espada", decoded.inventory().items().get(0).name());
        assertEquals("arma", decoded.inventory().items().get(0).type());
        assertEquals(12.34f, decoded.inventory().items().get(0).weight(), 0.0001f);
        assertEquals("uma espada", decoded.inventory().items().get(0).description());
        assertEquals(30f, decoded.inventory().maxWeight(), 0.0001f);
    }

    @Test
    @DisplayName("Um save anterior a Fase 2B abre com o inventario vazio")
    void oldSaveWithoutInventory() {
        CompoundTag tag = (CompoundTag) SheetData.CODEC.encodeStart(NbtOps.INSTANCE,
                sheetWith(SheetData.Inventory.EMPTY)).getOrThrow();
        tag.remove("inventory");

        SheetData decoded = SheetData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();

        assertEquals(SheetData.Inventory.EMPTY, decoded.inventory());
    }

    @Test
    @DisplayName("O inventario sobrevive ao round-trip pelo STREAM_CODEC da ficha")
    void roundTrip() {
        SheetData original = sheetWith(new SheetData.Inventory(
                List.of(new SheetData.InventoryItem("Espada", "arma", 12.34f, "uma espada"),
                        new SheetData.InventoryItem("Corda", "ferramenta", 0.5f, "")),
                30f));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SheetData.STREAM_CODEC.encode(buf, original);
        SheetData decoded = SheetData.STREAM_CODEC.decode(buf);

        assertEquals(original.inventory(), decoded.inventory());
        assertEquals(2, decoded.inventory().items().size());
        assertEquals(12.34f, decoded.inventory().items().get(0).weight(), 0.0001f);
        assertEquals("uma espada", decoded.inventory().items().get(0).description());
        assertEquals(30f, decoded.inventory().maxWeight(), 0.0001f);
    }

    @Test
    @DisplayName("O align do modelo copia o inventario em vez de apagalo")
    void alignKeepsInventory() {
        SheetData original = sheetWith(new SheetData.Inventory(List.of(item("Mapa", 1f)), 5f));
        SheetData aligned = SheetModelHolder.current().align(original);
        assertEquals(1, aligned.inventory().items().size());
        assertEquals("Mapa", aligned.inventory().items().get(0).name());
        assertEquals(5f, aligned.inventory().maxWeight(), 0.0001f);
    }
}