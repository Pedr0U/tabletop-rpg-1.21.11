package com.pedro.tabletoprpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Blocos trancados pelo mestre, gravados no <b>overworld</b> para sobreviverem
 * ao reinicio do mundo (decisao do usuario em 30/09/2026).
 *
 * <p><b>Por que {@code SavedData} e nao o NBT do bloco:</b> mudar o
 * {@code BlockState} exigiria uma propriedade {@code locked} que <b>nao
 * existe</b> em 1.21.11 para os blocos que interessam (o vanilla so tem
 * {@code LOCKED} em end portal frame e em {@code locked_trapdoor}), e um bloco
 * de mod que nao declara essa propriedade lancaria ao receber
 * {@code setValue}. Guardar a posicao fora do bloco tambem tem uma vantagem
 * visivel: destravar o bloco devolve ele ao estado original, sem resquicio.
 *
 * <p><b>Por que {@code computeIfAbsent} e nao {@code get}:</b>
 * {@code DimensionDataStorage} tem as duas sobrecargas e elas diferem em
 * <b>comportamento</b>: {@code get(type)} e somente busca e devolve
 * {@code null} quando o dado nunca foi gravado, enquanto
 * {@code computeIfAbsent(type)} cria o dado a partir da factory que o proprio
 * {@link SavedDataType} carrega. Como {@code null} e retorno legal de
 * {@code get()}, o erro nao aparece em tempo de compilacao -- e foi exatamente
 * assim que {@code SheetModelStore} derrubou o servidor no primeiro boot de um
 * mundo novo. Ver {@code SheetModelStore} para o registro completo.
 *
 * <p><b>API verificada com javap no jar nomeado do Loom (1.21.11):</b>
 * {@code SavedData} nao tem mais {@code save}/{@code read}; o codec entra pelo
 * {@link SavedDataType}. {@code Level.RESOURCE_KEY_CODEC} e
 * {@code BlockPos.CODEC} existem, entao a chave (dimensao + posicao) e
 * serializavel sem codec manual.
 *
 * <p><b>Arquivo gerado:</b> {@code data/tabletop_rpg_block_locks.dat} dentro
 * do overworld.
 */
public class BlockLockStore extends SavedData {

    /**
     * Uma posicao trancada: dimensao + {@link BlockPos}.
     *
     * <p><b>Por que a dimensao faz parte da chave:</b> {@code BlockPos} sozinho
     * e ambiguo entre o Overworld, o Nether e o End, que compartilham o mesmo
     * arquivo de dados. Sem a dimensao, trancar um bau no Overworld trancaria
     * o bau de mesma posicao no Nether.
     *
     * <p>Record porque {@code equals}/{@code hashCode} por valor sao
     * exatamente o que o {@link Set} precisa para deduplicar.
     */
    public record Locked(ResourceKey<Level> dimension, BlockPos pos) {
    }

    public static final Codec<Locked> LOCKED_CODEC = RecordCodecBuilder.create(i -> i.group(
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Locked::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(Locked::pos)
    ).apply(i, Locked::new));

    public static final Codec<BlockLockStore> CODEC = RecordCodecBuilder.create(i -> i.group(
            LOCKED_CODEC.listOf().optionalFieldOf("locks", List.of()).forGetter(BlockLockStore::lockList)
    ).apply(i, BlockLockStore::new));

    /**
     * Tipo do dado salvo. O id vira o nome do arquivo em {@code data/}, entao so
     * pode ter letras, digitos, {@code _} e {@code -}.
     */
    private static final SavedDataType<BlockLockStore> TYPE = new SavedDataType<>(
            "tabletop_rpg_block_locks", BlockLockStore::new, CODEC, DataFixTypes.LEVEL);

    /**
     * Conjunto ordenado: a ordem de insercao mantem o arquivo legivel e
     * deterministico, e o {@code Set} evita trancas duplicadas (o mesmo bau
     * clicado duas vezes com o comando armado nao vira duas entradas).
     */
    private final Set<Locked> locks = new LinkedHashSet<>();

    /** Construtor sem argumento, exigido pelo {@link SavedDataType}. */
    public BlockLockStore() {
    }

    public BlockLockStore(List<Locked> locks) {
        if (locks != null) {
            this.locks.addAll(locks);
        }
    }

    /** Copia defensiva: o codec grava a partir disto, sem poder mutar o store. */
    private List<Locked> lockList() {
        return List.copyOf(locks);
    }

    public boolean isLocked(ResourceKey<Level> dimension, BlockPos pos) {
        return locks.contains(new Locked(dimension, pos.immutable()));
    }

    /**
     * Tranca a posicao. Devolve {@code false} (e nao grava) se ja estava
     * trancada, para o chamador conseguir responder "ja estava trancada".
     */
    public boolean lock(ResourceKey<Level> dimension, BlockPos pos) {
        if (isLocked(dimension, pos)) {
            return false;
        }
        locks.add(new Locked(dimension, pos.immutable()));
        setDirty();
        return true;
    }

    /** Destrava. Devolve {@code false} se nao estava trancada. */
    public boolean unlock(ResourceKey<Level> dimension, BlockPos pos) {
        if (!locks.remove(new Locked(dimension, pos.immutable()))) {
            return false;
        }
        setDirty();
        return true;
    }

    public int count() {
        return locks.size();
    }

    /**
     * Copia imutavel de tudo que esta trancado, em todas as dimensoes.
     *
     * <p>Usada para mandar o estado ao cliente (a aura do Mestre). E uma copia
     * de proposito: o cliente recebe um retrato, nunca a referencia viva, e uma
     * alteracao no store depois de montado o retrato nao muda o que esta indo
     * pela rede.
     */
    public List<Locked> snapshot() {
        return List.copyOf(locks);
    }

    /**
     * Carrega (ou cria) o dado do overworld.
     *
     * <p>Chamar sempre antes de usar. Sem isso, o primeiro clique de trancar num
     * mundo novo receberia {@code null} do {@code computeIfAbsent} -- o que nao
     * acontece, e e exatamente o que o {@code computeIfAbsent} garante.
     */
    public static BlockLockStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }
}