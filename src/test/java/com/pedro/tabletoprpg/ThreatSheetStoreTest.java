package com.pedro.tabletoprpg;

import com.mojang.serialization.DataResult;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes da copia de fichas (Bug C, especificacao de 07/10/2026).
 *
 * <p><b>Por que estes testes existem:</b> a copia e o campo {@code copyOf} que amarra
 * a ficha nova a mae. As regras sao da jogadora: copia nasce sem vinculo de mob,
 * "mae (n)" com o MENOR n livre, apagar a mae apaga mae e copias juntas, e renomear a
 * mae nao renomeia copias existentes (so copias novas seguem o nome novo). O codec tem
 * de sobreviver ao NBT antigo (ficha gravada antes do campo existir vira original).
 */
class ThreatSheetStoreTest {

    private static final UUID U = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static ThreatSheet ficha(String nome) {
        return new ThreatSheet("",
                new ThreatSheet.Identity(nome, 5, nome, false, "", "", ""),
                new ThreatSheet.Vitals(10, 20, 12),
                "descricao",
                List.of(new ThreatSheet.AttributeValue("forca", 5)),
                List.of(new ThreatSheet.PericiaValue("percepcao", 3)),
                List.of("Caracteristica"),
                List.of(new ThreatSheet.Ability("Passiva", "efeito")),
                List.of(new ThreatSheet.Action("Acao", "+5", "1d8", "golpe")),
                "");
    }

    /** Salva e devolve a ficha com o id que o servidor atribuiu. */
    private static ThreatSheet salva(String nome) {
        ThreatSheetStore.forget(U);
        ThreatSheet saved = ficha(nome);
        ThreatSheetStore.SaveResult resultado = ThreatSheetStore.save(U, saved, "");
        assertTrue(resultado.ok(), "save: " + resultado);
        return ThreatSheetStore.find(U, nome);
    }

    @Test
    @DisplayName("copia herda o conteudo e nasce com o menor numero livre")
    void copiaNasceComMenorNumeroLivre() {
        ThreatSheet mae = salva("Creeper");

        ThreatSheetStore.SaveResult primeira = ThreatSheetStore.createCopy(U, mae.id());
        assertEquals(ThreatSheetStore.SaveResult.CREATED, primeira);
        ThreatSheet copia = ThreatSheetStore.find(U, "Creeper (1)");
        assertNotNull(copia);
        assertTrue(copia.isCopy());
        assertEquals(mae.id(), copia.copyOf());
        assertNotEquals(mae.id(), copia.id());
        // Conteudo herdado: atributo, pericia, caracteristica, passiva e ativa.
        assertEquals(mae.attributes(), copia.attributes());
        assertEquals(mae.pericias(), copia.pericias());
        assertEquals(mae.features(), copia.features());
        assertEquals(mae.passives(), copia.passives());
        assertEquals(mae.actions(), copia.actions());
        assertEquals(mae.vitals(), copia.vitals());

        assertEquals(ThreatSheetStore.SaveResult.CREATED, ThreatSheetStore.createCopy(U, mae.id()));
        assertNotNull(ThreatSheetStore.find(U, "Creeper (2)"));

        // Menor numero livre: apagar a (1) e copiar de novo faz nascer (1).
        assertNotNull(ThreatSheetStore.remove(U, "Creeper (1)"));
        assertEquals(ThreatSheetStore.SaveResult.CREATED, ThreatSheetStore.createCopy(U, mae.id()));
        assertNotNull(ThreatSheetStore.find(U, "Creeper (1)"));
        assertNull(ThreatSheetStore.find(U, "Creeper (3)"));
    }

    @Test
    @DisplayName("renomear a mae nao renomeia copias existentes, so as novas")
    void renomearMaeNaoRenomeiaCopiaExistente() {
        ThreatSheet mae = salva("Goblin");
        assertEquals(ThreatSheetStore.SaveResult.CREATED, ThreatSheetStore.createCopy(U, mae.id()));
        ThreatSheet copia = ThreatSheetStore.find(U, "Goblin (1)");

        ThreatSheet maeRenomeada = mae.withName("Orc");
        assertEquals(ThreatSheetStore.SaveResult.RENAMED,
                ThreatSheetStore.save(U, maeRenomeada, "Goblin"));

        // A copia existente mantem o nome antigo E o vinculo com a mae.
        ThreatSheet copiaDepois = ThreatSheetStore.findById(U, copia.id());
        assertEquals("Goblin (1)", copiaDepois.identity().name());
        assertEquals(mae.id(), copiaDepois.copyOf());

        // Copia nova segue o nome novo da mae.
        assertEquals(ThreatSheetStore.SaveResult.CREATED, ThreatSheetStore.createCopy(U, mae.id()));
        assertNotNull(ThreatSheetStore.find(U, "Orc (1)"));
    }

    @Test
    @DisplayName("apagar a mae apaga mae e copias juntas")
    void apagarMaeApagaCopias() {
        ThreatSheet mae = salva("Chefe");
        assertEquals(ThreatSheetStore.SaveResult.CREATED, ThreatSheetStore.createCopy(U, mae.id()));
        assertEquals(ThreatSheetStore.SaveResult.CREATED, ThreatSheetStore.createCopy(U, mae.id()));

        ThreatSheet removida = ThreatSheetStore.remove(U, "Chefe");
        assertNotNull(removida);
        assertEquals(mae.id(), removida.id());
        assertTrue(ThreatSheetStore.snapshot(U).isEmpty());
        assertNull(ThreatSheetStore.findById(U, mae.id()));
    }

    @Test
    @DisplayName("apagar uma copia nao toca na mae nem nas irmas")
    void apagarCopiaNaoApagaMaeNemIrma() {
        ThreatSheet mae = salva("Dragao");
        assertEquals(ThreatSheetStore.SaveResult.CREATED, ThreatSheetStore.createCopy(U, mae.id()));
        assertEquals(ThreatSheetStore.SaveResult.CREATED, ThreatSheetStore.createCopy(U, mae.id()));

        assertNotNull(ThreatSheetStore.remove(U, "Dragao (1)"));

        assertNotNull(ThreatSheetStore.find(U, "Dragao"));
        assertNotNull(ThreatSheetStore.find(U, "Dragao (2)"));
        assertNull(ThreatSheetStore.find(U, "Dragao (1)"));
        // Nao sobrou orfao: o remanescente continua apontando para a mae.
        ThreatSheet irma = ThreatSheetStore.find(U, "Dragao (2)");
        assertEquals(mae.id(), irma.copyOf());
    }

    @Test
    @DisplayName("copia de copia e recusada no servidor")
    void copiaDeCopiaRecusada() {
        ThreatSheet mae = salva("Troll");
        assertEquals(ThreatSheetStore.SaveResult.CREATED, ThreatSheetStore.createCopy(U, mae.id()));
        ThreatSheet copia = ThreatSheetStore.find(U, "Troll (1)");

        assertEquals(ThreatSheetStore.SaveResult.COPY_OF_COPY,
                ThreatSheetStore.createCopy(U, copia.id()));
    }

    @Test
    @DisplayName("nome ocupado empurra a copia para o proximo numero livre")
    void nomeDuplicadoEmpurraNumero() {
        ThreatSheet mae = salva("Liche");
        // Uma ficha comum chamada "Liche (1)" ja existe: nao e copia, mas ocupa o nome.
        ThreatSheet ocupante = ficha("Liche (1)");
        assertEquals(ThreatSheetStore.SaveResult.CREATED, ThreatSheetStore.save(U, ocupante, ""));

        assertEquals(ThreatSheetStore.SaveResult.CREATED, ThreatSheetStore.createCopy(U, mae.id()));
        // O nome (1) continua com a ficha comum (que nao e copia), e a copia foi para (2).
        ThreatSheet ocupanteDepois = ThreatSheetStore.find(U, "Liche (1)");
        assertNotNull(ocupanteDepois);
        assertFalse(ocupanteDepois.isCopy());
        assertNotNull(ThreatSheetStore.find(U, "Liche (2)"));
    }

    @Test
    @DisplayName("nome da mae no tamanho maximo ainda cabe com o sufixo")
    void nomeMaximoCabeComSufixo() {
        ThreatSheet mae = salva("a".repeat(ThreatSheet.MAX_NAME));
        assertEquals(ThreatSheetStore.SaveResult.CREATED, ThreatSheetStore.createCopy(U, mae.id()));
        String copiaNome = mae.identity().name().substring(0, ThreatSheet.MAX_NAME - " (200)".length())
                + " (1)";
        ThreatSheet copia = ThreatSheetStore.find(U, copiaNome);
        assertNotNull(copia);
        assertTrue(copia.identity().name().length() <= ThreatSheet.MAX_NAME);
        assertEquals(mae.id(), copia.copyOf());
    }

    @Test
    @DisplayName("NBT antigo sem o campo copy_of carrega como ficha original")
    void nbtAntigoViraOriginal() {
        ThreatSheet mae = salva("Necromante");
        CompoundTag nbt = gravaNbt(mae);
        // Remove o campo como se o NBT tivesse sido gravado antes do campo existir.
        nbt.remove("copy_of");

        ThreatSheet carregado = ThreatSheet.CODEC.parse(NbtOps.INSTANCE, nbt).getOrThrow();
        assertEquals("", carregado.copyOf());
        assertFalse(carregado.isCopy());
        assertEquals("Necromante", carregado.identity().name());
    }

    @Test
    @DisplayName("NBT round-trip preserva o copy_of da copia")
    void nbtRoundTripPreservaCopia() {
        ThreatSheet mae = salva("Elemental");
        assertEquals(ThreatSheetStore.SaveResult.CREATED, ThreatSheetStore.createCopy(U, mae.id()));
        ThreatSheet copia = ThreatSheetStore.find(U, "Elemental (1)");

        ThreatSheet carregado = ThreatSheet.CODEC.parse(NbtOps.INSTANCE, gravaNbt(copia)).getOrThrow();
        assertTrue(carregado.isCopy());
        assertEquals(mae.id(), carregado.copyOf());
    }

    @Test
    @DisplayName("STREAM_CODEC round-trip preserva o copy_of")
    void streamCodecRoundTripPreservaCopia() {
        ThreatSheet mae = salva("Golem");
        assertEquals(ThreatSheetStore.SaveResult.CREATED, ThreatSheetStore.createCopy(U, mae.id()));
        ThreatSheet copia = ThreatSheetStore.find(U, "Golem (1)");

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ThreatSheet.STREAM_CODEC.encode(buf, copia);
        ThreatSheet deVolta = ThreatSheet.STREAM_CODEC.decode(buf);
        assertEquals(0, buf.readableBytes());

        assertEquals(copia, deVolta);
        assertEquals(mae.id(), deVolta.copyOf());
    }

    private static CompoundTag gravaNbt(ThreatSheet ficha) {
        DataResult<Tag> resultado = ThreatSheet.CODEC.encodeStart(NbtOps.INSTANCE, ficha);
        Tag tag = resultado.getOrThrow();
        assertTrue(tag instanceof CompoundTag);
        return (CompoundTag) tag;
    }
}