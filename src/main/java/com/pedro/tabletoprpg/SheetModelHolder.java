package com.pedro.tabletoprpg;

/**
 * O modelo da ficha <b>em uso agora</b>, nos dois lados.
 *
 * <p>Existe para que {@link SheetData} possa alinhar a ficha com o modelo sem
 * precisar conhecer o mundo: o construtor de {@code SheetData} roda em todo
 * lugar (NBT, payload, ficha nova) e o unico ponto onde o modelo esta disponivel
 * e um campo estatico. O servidor o publica em
 * {@link SheetModelStore#get(net.minecraft.server.MinecraftServer)} e o cliente
 * o publica ao receber o payload de modelo.
 *
 * <p><b>Por que um estatico e nao passar o modelo por parametro:</b> a regra de
 * alinhamento precisa valer em <i>todo</i> lugar onde uma ficha e construida,
 * incluindo dentro de codecs e de construtores de record. Passar o modelo
 * adiante mudaria a assinatura de quase tudo e ainda deixaria buracos. O preco
 * e um global mutavel — justificavel porque so o servidor escreve, e so uma
 * vez por edicao, propagando para todos os clientes na mesma hora.
 *
 * <p><b>Antes do primeiro sync, o valor e {@link SheetModel#defaults()}:</b> e o
 * que o jogo mostrava antes do Sheet Editor existir, entao um cliente que abre
 * a ficha cedo demais ve a ficha de sempre, nunca uma tela quebrada.
 */
public final class SheetModelHolder {

    private static volatile SheetModel current = SheetModel.defaults();

    private SheetModelHolder() {
    }

    /** O modelo em uso. Nunca {@code null}. */
    public static SheetModel current() {
        return current;
    }

    /** Publica um novo modelo. {@code null} volta para o padrao. */
    public static void set(SheetModel model) {
        current = model == null ? SheetModel.defaults() : model;
    }
}
