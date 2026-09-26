package com.pedro.tabletoprpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Garante que a ficha de cada jogador seja gravada no encerramento do servidor.
 *
 * <p><b>FACT (relato do usuario em 25/09/2026):</b> fechar o servidor com
 * Ctrl+C e abrir de novo apagava a ficha, enquanto sair e entrar do servidor sem
 * fechar mantinha. Esse contraste e a prova do que acontecia:
 * <ul>
 *   <li>sair/entrar mantem porque o {@link SessionManager} e um mapa ESTATICO
 *       e o processo do servidor continua vivo — nada foi lido do disco;</li>
 *   <li>Ctrl+C mata o processo, o mapa vai embora e so resta o que estiver no
 *       NBT em disco.</li>
 * </ul>
 *
 * <p><b>FACT (log do servidor):</b> o NBT em si funciona — ha a linha
 * {@code Ficha carregada do NBT de <jogador> (1 skills, 20 pericias)}, ou seja,
 * a ficha foi gravada num logout anterior e relida. O que faltava era gravar no
 * <b>desligamento</b>: o vanilla so salva os jogadores no logout e no autosave
 * periodico, e o `CharStream` de um Ctrl+C pode matar o processo antes disso.
 *
 * <p><b>Por que {@code SERVER_STOPPING}:</b> e antes do mundo ser descarregado,
 * os jogadores ainda existem e ainda podem ser gravados. O Fabric dispara esse
 * evento dentro da sequencia de parada do {@code MinecraftServer} (o proprio
 * Fabric injeta {@code beforeShutdownServer}), entao ele roda tambem no
 * desligamento por Ctrl+C, desde que o shutdown hook da JVM chegue a rodar.
 */
public final class SheetPersistenceEvents {

    private SheetPersistenceEvents() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPING.register(SheetPersistenceEvents::saveAllSheets);
    }

    /**
     * Grava o NBT de todos os jogadores conectados.
     *
     * <p>{@code saveAll()} chama {@code addAdditionalSaveData} em cada
     * {@code ServerPlayer}, que e exatamente o hook onde o
     * {@code PlayerSheetPersistenceMixin} injeta a ficha. Nao ha caminho
     * alternativo: e o mesmo codigo que ja funciona no logout.
     *
     * <p><b>FACT (javap 1.21.11):</b> o metodo publico e {@code saveAll()};
     * {@code savePlayers()} e {@code save(ServerPlayer)} (protected) nao existem
     * nesta versao.
     *
     * <p>Envolve em try/catch porque um erro aqui acontece durante o
     * desligamento derrubaria o proprio servidor e perderia TODOS os jogadores,
     * o que e pior do que perder uma ficha.
     */
    private static void saveAllSheets(MinecraftServer server) {
        try {
            int antes = SessionManager.sheetCount();
            server.getPlayerList().saveAll();
            TabletopRpg.LOGGER.info(
                    "[TabletopRPG] Desligando: NBT de {} jogador(es) gravado(s), {} ficha(s) em memoria.",
                    server.getPlayerList().getPlayerCount(), antes);
        } catch (Throwable t) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Falha ao gravar as fichas no desligamento.", t);
        }
    }
}
