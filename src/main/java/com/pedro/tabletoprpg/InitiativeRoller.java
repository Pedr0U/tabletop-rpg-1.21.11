package com.pedro.tabletoprpg;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

import java.util.Random;

/**
 * Total da rolagem de iniciativa (Fase 2).
 *
 * <p>Caixa "usar iniciativa padrao" marcada: {@code 1d20 + bonus da mesa}, onde o
 * bonus e a formula digitada pelo Mestre. Desmarcada: a pericia marcada como
 * iniciativa, com a mesma conta do {@code /rpg roll <pericia>} (d20 + pericia +
 * atributo). Sem pericia marcada, {@code 1d20} puro. Mob segue a mesma regra, mas
 * le a pericia da ficha dele.
 */
final class InitiativeRoller {

    private static final Random RANDOM = new Random();

    private InitiativeRoller() {
    }

    static long playerTotal(ServerPlayer player) throws DiceFormula.SyntaxException {
        SheetData sheet = SessionManager.getSheet(player.getUUID());
        SheetModel model = SheetModelHolder.current();
        if (model.initiativeUseDefault()) {
            return evaluate(resolve(bonusFormula(model.initiativeFormula()), sheet, model));
        }
        SheetData.Pericia pericia = sheet == null ? null : sheet.periciaById(model.initiativePericiaId());
        if (pericia == null) {
            return evaluate("1d20");
        }
        return MasterCommands.periciaRollTotal(player, pericia);
    }

    static long mobTotal(Mob mob) throws DiceFormula.SyntaxException {
        SheetModel model = SheetModelHolder.current();
        if (model.initiativeUseDefault()) {
            return evaluate(bonusFormula(model.initiativeFormula()));
        }
        String periciaId = model.initiativePericiaId();
        ThreatSheet ficha = ThreatSheetStore.findAnyById(ThreatSheetBinding.sheetOf(mob));
        if (periciaId.isEmpty() || ficha == null) {
            return evaluate("1d20");
        }
        for (ThreatSheet.PericiaValue pericia : ficha.pericias()) {
            if (pericia.id().equals(periciaId)) {
                return evaluate("1d20") + pericia.value();
            }
        }
        return evaluate("1d20");
    }

    /**
     * Monta a formula da caixa de bonus: vazio vira {@code 1d20}; "3" vira
     * {@code 1d20+3}; "-2" vira {@code 1d20-2}; "1d20+3" vira {@code 1d20+1d20+3}.
     */
    static String bonusFormula(String bonus) {
        String raw = bonus == null ? "" : bonus.trim();
        if (raw.isEmpty()) {
            return "1d20";
        }
        char first = raw.charAt(0);
        if (first == '+' || first == '-') {
            return "1d20" + raw;
        }
        return "1d20+" + raw;
    }

    static long evaluate(String formula) throws DiceFormula.SyntaxException {
        return DiceFormula.parse(formula).evaluate(sides -> RANDOM.nextInt(sides) + 1).total();
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
