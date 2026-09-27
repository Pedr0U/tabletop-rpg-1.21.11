package com.pedro.tabletoprpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Onde o modelo da ficha <b>sobrevive ao servidor fechar</b>.
 *
 * <p><b>Por que {@code SavedData} e nao o NBT de um jogador:</b> o modelo e do
 * mundo, nao de ninguem. Se ficasse no NBT de um jogador, ele se perderia quando
 * o Mestre saisse, e pior: cada cliente leria um modelo diferente.
 *
 * <p><b>Fica no overworld.</b> {@code server.overworld().getDataStorage()} e o
 * mesmo lugar que o vanilla usa para dados do mundo; qualquer dimensao serve,
 * porque o modelo descreve a ficha e nao o terreno. Ler de outra dimensao daria
 * um segundo arquivo com o mesmo conteudo e as duas copias divergiriam.
 *
 * <p><b>API verificada com javap no jar do Loom (1.21.11):</b> {@code SavedData}
 * nao tem mais {@code save}/{@code read} nem {@code factory}; o codec entra pelo
 * {@link SavedDataType}, e {@code get} recebe o tipo, nao uma String.
 * {@code ServerLevel.getDataStorage()} e {@code MinecraftServer.overworld()}
 * existem. {@code DataFixTypes.LEVEL} e o que o vanilla usa para dados salvos.
 *
 * <p><b>Atencao: {@code get} e {@code computeIfAbsent} nao sao equivalentes.</b>
 * {@code DimensionDataStorage} tem as duas sobrecargas e elas diferem em
 * comportamento, nao so em assinatura:
 *
 * <ul>
 *   <li>{@code get(type)} e <b>somente busca</b>: devolve {@code null} se o
 *       dado nunca foi gravado.</li>
 *   <li>{@code computeIfAbsent(type)} <b>cria</b> o dado, usando a factory que
 *       o proprio {@link SavedDataType} carrega.</li>
 * </ul>
 *
 * <p>Como {@code null} e um retorno legal para {@code get()}, o erro nao aparece
 * em tempo de compilacao. Usar {@code get()} derrubou o servidor no primeiro
 * boot de um mundo novo: o store vinha {@code null} e {@link #get} desreferenciava
 * na linha seguinte. {@link #get} usa {@code computeIfAbsent} e por isso nunca
 * devolve {@code null}. Verificar que a assinatura compila nao diz nada sobre a
 * semantica que a implementacao escolhe.
 *
 * <p><b>Salvar:</b> {@link #update} chama {@code setDirty()}, que e o que faz o
 * vanilla gravar no proximo autosave. Nao ha gravacao manual porque, diferentemente
 * da ficha do jogador (que vive num mapa estatico e precisa de
 * {@code SheetPersistenceEvents}), este objeto ja e o dado do vanilla.
 */
public class SheetModelStore extends SavedData {

    public static final Codec<SheetModelStore> CODEC = RecordCodecBuilder.create(i -> i.group(
            SheetModel.CODEC.optionalFieldOf("model", SheetModel.defaults()).forGetter(SheetModelStore::model)
    ).apply(i, SheetModelStore::new));

    /**
     * Tipo do dado salvo.
     *
     * <p>O id vira o nome do arquivo em {@code data/}, entao so pode ter letras,
     * digitos, {@code _} e {@code -}. O prefixo do mod evita colisao com o
     * vanilla e com outros mods.
     */
    private static final SavedDataType<SheetModelStore> TYPE = new SavedDataType<>(
            "tabletop_rpg_sheet_model", SheetModelStore::new, CODEC, DataFixTypes.LEVEL);

    private SheetModel model;

    /**
     * Construtor sem argumento, exigido pelo {@link SavedDataType}.
     *
     * <p><b>Por que existe:</b> o vanilla nao sabe decodificar este dado; ele
     * apenas pede "me da um {@code SheetModelStore} vazio" e preenche com o
     * que leu do arquivo. Sem este construtor, {@code SheetModelStore::new}
     * nao compila.
     */
    public SheetModelStore() {
        this(SheetModel.defaults());
    }

    public SheetModelStore(SheetModel model) {
        this.model = model == null ? SheetModel.defaults() : model;
    }

    public SheetModel model() {
        return model;
    }

    /**
     * Troca o modelo e marca o dado como sujo para o vanilla gravar.
     *
     * <p>Publica tambem no {@link SheetModelHolder}, porque e o que as fichas ja
     * carregadas vao ler no proximo alinhamento.
     */
    public void update(SheetModel model) {
        this.model = model == null ? SheetModel.defaults() : model;
        setDirty();
        SheetModelHolder.set(this.model);
    }

    /**
     * Carrega (ou cria) o modelo do mundo e o publica.
     *
     * <p><b>Chamar sempre antes de usar o modelo no servidor.</b> O
     * {@link SheetModelHolder} comeca no padrao, e sem esta chamada uma ficha
     * criada logo apos o login do jogador seria alinhada contra o padrao em vez
     * do modelo salvo.
     */
    public static SheetModelStore get(MinecraftServer server) {
        // computeIfAbsent, e NAO get. Sao duas sobrecargas parecidas do
        // DimensionDataStorage: get() e so uma busca e devolve null quando o
        // dado nunca foi gravado; computeIfAbsent() cria a partir da factory que
        // o proprio SavedDataType carrega (SheetModelStore::new, ver TYPE).
        //
        // Usar get() derrubava o servidor no primeiro boot de um mundo novo: o
        // store vinha null e a linha seguinte desreferenciava. Compilava
        // tranquilo, porque null e um retorno legal para get().
        SheetModelStore store = server.overworld().getDataStorage().computeIfAbsent(TYPE);
        SheetModelHolder.set(store.model());
        return store;
    }

    /**
     * Liga o modelo ao ciclo de vida do servidor.
     *
     * <p><b>Por que um evento e nao o {@code onInitialize}:</b> o modelo vive
     * num {@code SavedData} do overworld, e o overworld so existe depois que o
     * servidor subiu. Ler no {@code onInitialize} daria null. O
     * {@code SERVER_STARTED} e o primeiro ponto em que o mundo esta pronto.
     *
     * <p><b>Por que no JOIN e nao so no start:</b> o modelo pode ter sido
     * editado enquanto ninguem estava conectado (ou o Mestre pode editar durante
     * a sessao). Quem entra recebe o estado atual, e nao o do boot.
     */
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SheetModelStore store = get(server);
            TabletopRpg.LOGGER.info("[TabletopRPG] Modelo da ficha carregado: {} atributo(s), {} pericia(s).",
                    store.model().attributeCount(), store.model().periciaCount());
            // Realinha logo aqui, e nao so quando alguem editar o modelo.
            // A ficha do host de um singleplayer e lida do NBT durante a carga do
            // mundo, e essa carga pode rodar ANTES de SERVER_STARTED -- nesse
            // intervalo o Holder ainda vale o modelo PADRAO, e a ficha ficaria
            // com a lista errada ate a proxima edicao do Mestre ou ate reconectar.
            // Como o align e idempotente, rodar sobre fichas ja alinhadas aqui
            // nao tem efeito colateral.
            int realinhadas = SessionManager.realignAllSheets();
            if (realinhadas > 0) {
                TabletopRpg.LOGGER.info("[TabletopRPG] {} ficha(s) alinhada(s) com o modelo carregado.", realinhadas);
            }
        });

        // O segundo parametro do JOIN e PacketSender, nao ServerPlayer: quem
        // acabou de entrar se pega pelo handler, que ja traz o ServerPlayer.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                RpgNetworking.sendSheetModel(handler.player, SheetModelHolder.current()));
    }
}
