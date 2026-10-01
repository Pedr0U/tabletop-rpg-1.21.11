package com.pedro.tabletoprpg;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;

/**
 * Descobre qual entidade o Mestre realmente quer alcancar quando clica com o
 * botao direito.
 *
 * <p><b>Por que este arquivo existe (bug de 01/10/2026):</b> o clique no jogo nao
 * acerta a entidade, acerta a <b>parte</b> dela. O Ender Dragon e montado de partes
 * separadas -- corpo, pescoco, cabeca, asas, cauda -- e cada uma delas e uma entidade
 * a parte no mundo. Confirmado com {@code javap} no {@code minecraft-common} 1.21.11:
 *
 * <pre>
 *   EnderDragon     extends Mob          // instanceof Mob = true
 *   EnderDragonPart extends Entity       // instanceof Mob = FALSE
 * </pre>
 *
 * <p>O Mestre clicava no corpo do dragao, o cliente acertava um {@code EnderDragonPart},
 * e o teste {@code entity instanceof Mob} reprovava -- entao nada acontecia, sem erro
 * e sem mensagem. Nao era hitbox dificil de acertar: era o filtro.
 *
 * <p><b>Por que so o dragao:</b> a reescrita de entidades do 1.21.9 removeu
 * {@code EntityPart}, e {@code Entity} nao tem {@code getParts()}. Nao existe API
 * generica para achar o pai de uma parte. {@code EnderDragonPart.parentMob} e publico e
 * final, entao e o unico acesso direto. Criaturas de mod feitas de partes continuam sem
 * solucao automatica -- por isso {@link #describe} existe: quando um clique nao serve,
 * o log diz <b>qual</b> entidade foi realmente atingida, que e a pista para o
 * diagnostico.
 */
public final class EntityTargets {

    private EntityTargets() {
    }

    /**
     * Devolve a entidade que o clique pretendia alcancar.
     *
     * <p>Se o clique acertou uma parte, devolve o pai; senao devolve a propria
     * entidade. O chamador continua testando {@code instanceof Mob} -- esta classe so
     * desembrulha, nao decide o que e valido.
     *
     * @param clicked a entidade que o jogo reportou no clique; pode ser nula
     * @return o pai, se era uma parte; a propria entidade, caso contrario
     */
    public static Entity resolve(Entity clicked) {
        if (clicked instanceof EnderDragonPart part) {
            return part.parentMob;
        }
        return clicked;
    }

    /**
     * Nome legivel da entidade que o clique acertou, para o log de diagnostico.
     *
     * <p>Mostra a parte <b>e</b> o pai, porque e essa diferenca que explica o
     * defeito: o jogador ve o dragao, o log diz que veio um {@code EnderDragonPart}.
     *
     * @param clicked a entidade crua do clique; pode ser nula
     * @param resolved o resultado de {@link #resolve}, para comparar
     */
    public static String describe(Entity clicked, Entity resolved) {
        StringBuilder sb = new StringBuilder();
        sb.append("clicada=").append(typeOf(clicked));
        if (clicked != resolved) {
            sb.append(" -> pai=").append(typeOf(resolved));
        }
        return sb.toString();
    }

    private static String typeOf(Entity entity) {
        if (entity == null) {
            return "nenhuma";
        }
        return entity.getType().toString();
    }
}