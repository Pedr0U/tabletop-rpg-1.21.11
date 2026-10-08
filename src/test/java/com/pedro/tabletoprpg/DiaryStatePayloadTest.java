package com.pedro.tabletoprpg;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do {@link RpgNetworking.DiaryStatePayload} e do seu {@code STREAM_CODEC}.
 *
 * <p><b>Por que estes testes existem:</b> o codec do estado do diario e escrito a
 * mao, e nada amarra encoder e decoder em tempo de compilacao. O problema 3 (07/10/2026)
 * colocou um numero de versao como primeiro campo: o round-trip abaixo prova que os dois
 * lados continuam casando, e o teste de layout desconhecido prova que um pacote de outra
 * versao do mod nao estoura a leitura -- vira um payload vazio marcado com a versao que
 * chegou, e o receptor mostra o aviso em vez de corromper o diario.
 */
class DiaryStatePayloadTest {

    private static RpgNetworking.DiaryStatePayload estado(
            List<DiaryEntry> entradas, boolean podeReverter, int foco, List<Integer> pendentes) {
        return new RpgNetworking.DiaryStatePayload(
                RpgNetworking.DiaryStatePayload.LAYOUT_VERSION,
                entradas, podeReverter, foco, pendentes);
    }

    @Test
    @DisplayName("estado do diario sobrevive ao round-trip pelo STREAM_CODEC")
    void roundTrip() {
        RpgNetworking.DiaryStatePayload original = estado(
                List.of(
                        new DiaryEntry(1, DiaryEntry.ROOT, "Nos", "texto multiline\ncom acento: cao\ncafe melao", 7L, 0L),
                        new DiaryEntry(2, 1, "Filho", "descricao", 9L, 3L)),
                true,
                2,
                List.of(2, 4));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RpgNetworking.DiaryStatePayload.STREAM_CODEC.encode(buf, original);

        RpgNetworking.DiaryStatePayload deVolta =
                RpgNetworking.DiaryStatePayload.STREAM_CODEC.decode(buf);

        // E o pacote inteiro foi consumido: nada ficou por ler no buffer.
        assertEquals(0, buf.readableBytes(), "encode tem de escrever exatamente o que o decode le");
        assertEquals(original, deVolta);
        assertEquals(RpgNetworking.DiaryStatePayload.LAYOUT_VERSION, deVolta.version());
        assertEquals(2, deVolta.entries().size());
        assertEquals("Nos", deVolta.entries().get(0).title());
        assertEquals(3L, deVolta.entries().get(1).pinSeq());
        assertTrue(deVolta.canUndo());
        assertEquals(2, deVolta.focusId());
        assertEquals(List.of(2, 4), deVolta.pendingIds());
    }

    @Test
    @DisplayName("layout desconhecido nao estoura: vira payload vazio com a versao recebida")
    void layoutDesconhecidoNaoEstoura() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        // Um pacote de uma versao futura (ou antiga): versao 99 e qualquer lixo atras.
        buf.writeVarInt(99);
        buf.writeBytes(new byte[]{(byte) 0xDE, (byte) 0xAD, 0x00, 0x01, 0x02, 0x03});

        RpgNetworking.DiaryStatePayload deVolta =
                RpgNetworking.DiaryStatePayload.STREAM_CODEC.decode(buf);

        assertEquals(99, deVolta.version());
        assertEquals(List.of(), deVolta.entries());
        assertEquals(false, deVolta.canUndo());
        assertEquals(0, deVolta.focusId());
        assertEquals(List.of(), deVolta.pendingIds());
        assertEquals(0, buf.readableBytes(), "o resto do buffer desconhecido tem de ser varrido");
    }

    @Test
    @DisplayName("um payload legitimo nao pode ser confundido com layout errado")
    void versaoCertaNaoFalsa() {
        RpgNetworking.DiaryStatePayload original = estado(List.of(), false, 0, List.of());

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RpgNetworking.DiaryStatePayload.STREAM_CODEC.encode(buf, original);
        RpgNetworking.DiaryStatePayload deVolta =
                RpgNetworking.DiaryStatePayload.STREAM_CODEC.decode(buf);

        // Um diario vazio legitimo tem a versao CERTA, e nao dispara o caminho de erro.
        assertEquals(RpgNetworking.DiaryStatePayload.LAYOUT_VERSION, deVolta.version());
        assertEquals(original, deVolta);
        assertNotEquals(99, deVolta.version());
    }
}