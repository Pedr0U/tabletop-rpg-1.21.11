package com.pedro.tabletoprpg;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

import java.util.Random;

/**
 * Rolagem de iniciativa.
 *
 * <p>A caixa "usar iniciativa padrao" do <b>modelo</b> (Sheet Editor do Mestre)
 * decide qual regra vale para a mesa inteira:
 * <ul>
 *   <li>Marcada: cada jogador rola a <b>propria</b> formula de iniciativa, que ele
 *       digita na ficha (campo "Initiative"). Vazia rola {@code 1d20} seco.</li>
 *   <li>Desmarcada: a pericia marcada como iniciativa, com a mesma conta do
 *       {@code /rpg roll <pericia>}; sem pericia marcada, {@code 1d20}.</li>
 * </ul>
 * Mob nao tem formula propria: com a caixa marcada rola {@code 1d20}; desmarcada,
 * usa a pericia marcada na propria ficha.
 */
public final class InitiativeRoller {

    private static final Random RANDOM = new Random();

    /** Resultado de uma rolagem de iniciativa: total e mensagem ja formatada. */
    public record Roll(long total, Component message) {
    }

    private InitiativeRoller() {
    }

    /** Rolagem de um jogador. {@code extra} e a formula com sinal somada ao fim ("+1d20"). */
    static Roll playerRoll(ServerPlayer player, String extra) throws DiceFormula.SyntaxException {
        SheetData sheet = SessionManager.getSheet(player.getUUID());
        SheetModel model = SheetModelHolder.current();
        String name = player.getName().getString();
        if (model.initiativeUseDefault()) {
            String own = sheet == null ? "" : sheet.progress().initiativeFormula();
            return formulaRoll(name, resolve(compact(formulaOrDefault(own) + extra), sheet, model));
        }
        SheetData.Pericia pericia = sheet == null ? null : sheet.periciaById(model.initiativePericiaId());
        if (pericia != null) {
            MasterCommands.PericiaResult result = MasterCommands.periciaResult(player, pericia, extra);
            return new Roll(result.total(), result.message());
        }
        return formulaRoll(name, "1d20" + extra);
    }

    /**
     * Rolagem de um mob: com a caixa marcada, a formula da propria ficha do mob
     * (atributos da ficha dele); desmarcada, a pericia marcada na ficha dele.
     */
    static Roll mobRoll(Mob mob) throws DiceFormula.SyntaxException {
        SheetModel model = SheetModelHolder.current();
        String name = mob.getName().getString();
        ThreatSheet ficha = ThreatSheetStore.findAnyById(ThreatSheetBinding.sheetOf(mob));
        if (model.initiativeUseDefault()) {
            String own = ficha == null ? "" : ficha.initiativeFormula();
            return formulaRoll(name, resolveMob(compact(formulaOrDefault(own)), ficha, model));
        }
        String periciaId = model.initiativePericiaId();
        if (periciaId.isEmpty() || ficha == null) {
            return formulaRoll(name, "1d20");
        }
        for (ThreatSheet.PericiaValue pericia : ficha.pericias()) {
            if (pericia.id().equals(periciaId)) {
                String sign = pericia.value() < 0 ? "" : "+";
                return formulaRoll(name, "1d20" + sign + pericia.value());
            }
        }
        return formulaRoll(name, "1d20");
    }

    /**
     * Confere se uma formula de iniciativa e reconhecida: nomes de atributo e
     * pericia precisam existir no modelo, e a estrutura precisa passar no parser de
     * dados. Vazia e valida (rola 1d20). Espacos sao ignorados, como no /rpg roll.
     */
    public static boolean isValidFormula(String raw, SheetModel model) {
        String formula = compact(raw);
        if (formula.isEmpty()) {
            return true;
        }
        try {
            DiceFormula.parse(FormulaResolver.placeholderFormula(formula, model));
            return true;
        } catch (FormulaResolver.ResolveException | DiceFormula.SyntaxException e) {
            return false;
        }
    }

    /** Formula como o jogador digitou; vazia vira {@code 1d20}. */
    static String formulaOrDefault(String raw) {
        String formula = raw == null ? "" : raw.trim();
        return formula.isEmpty() ? "1d20" : formula;
    }

    /** Tira espacos, igual ao {@code /rpg roll}, para o parser ver so dados e sinais. */
    public static String compact(String raw) {
        return raw == null ? "" : raw.replace(" ", "");
    }

    private static Roll formulaRoll(String name, String formula) throws DiceFormula.SyntaxException {
        DiceFormula.Outcome outcome = DiceFormula.parse(compact(formula))
                .evaluate(sides -> RANDOM.nextInt(sides) + 1);
        return new Roll(outcome.total(), MasterCommands.formulaMessage(name, outcome));
    }

    /** Troca os nomes de atributo pelos valores da ficha do mob. */
    private static String resolveMob(String formula, ThreatSheet ficha, SheetModel model)
            throws DiceFormula.SyntaxException {
        try {
            return FormulaResolver.resolve(formula, id -> mobValue(ficha, id), model);
        } catch (FormulaResolver.ResolveException e) {
            throw new DiceFormula.SyntaxException(e.getMessage(), e.getKey(), e.getArgs());
        }
    }

    private static Integer mobValue(ThreatSheet ficha, String attributeId) {
        if (ficha == null) {
            return null;
        }
        for (ThreatSheet.AttributeValue attribute : ficha.attributes()) {
            if (attribute.id().equals(attributeId)) {
                return attribute.value();
            }
        }
        return null;
    }

    private static String resolve(String formula, SheetData sheet, SheetModel model)
            throws DiceFormula.SyntaxException {
        if (sheet == null) {
            return formula;
        }
        try {
            return FormulaResolver.resolve(formula, sheet, model);
        } catch (FormulaResolver.ResolveException e) {
            throw new DiceFormula.SyntaxException(e.getMessage(), e.getKey(), e.getArgs());
        }
    }
}
