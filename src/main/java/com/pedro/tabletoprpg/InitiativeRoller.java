package com.pedro.tabletoprpg;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

import java.util.Random;

/**
 * Rolagem de iniciativa (Fase 2).
 *
 * <p>A caixa "usar iniciativa padrao" da mesa decide qual formula vale:
 * <ul>
 *   <li>Marcada: a formula que o Mestre digitou no Sheet Editor e a rolagem
 *       inteira (vazia vira {@code 1d20}).</li>
 *   <li>Desmarcada: a pericia marcada como iniciativa, com a mesma conta do
 *       {@code /rpg roll <pericia>}; sem pericia marcada, {@code 1d20}.</li>
 * </ul>
 * Mob segue a mesma regra, mas le a pericia da propria ficha.
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
        if (model.initiativeUseDefault()) {
            return formulaRoll(player.getName().getString(), resolve(tableFormula(model) + extra, sheet, model));
        }
        SheetData.Pericia pericia = sheet == null ? null : sheet.periciaById(model.initiativePericiaId());
        if (pericia != null) {
            MasterCommands.PericiaResult result = MasterCommands.periciaResult(player, pericia, extra);
            return new Roll(result.total(), result.message());
        }
        return formulaRoll(player.getName().getString(), "1d20" + extra);
    }

    /** Rolagem de um mob, sem ficha de jogador: a formula da mesa ou a pericia da ficha dele. */
    static Roll mobRoll(Mob mob) throws DiceFormula.SyntaxException {
        SheetModel model = SheetModelHolder.current();
        String name = mob.getName().getString();
        if (model.initiativeUseDefault()) {
            return formulaRoll(name, tableFormula(model));
        }
        String periciaId = model.initiativePericiaId();
        ThreatSheet ficha = ThreatSheetStore.findAnyById(ThreatSheetBinding.sheetOf(mob));
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

    /** Formula da mesa como foi digitada; vazia vira {@code 1d20}. */
    static String tableFormula(SheetModel model) {
        return formulaOrDefault(model.initiativeFormula());
    }

    static String formulaOrDefault(String raw) {
        String formula = raw == null ? "" : raw.trim();
        return formula.isEmpty() ? "1d20" : formula;
    }

    /**
     * Confere se a formula da mesa e reconhecida: nomes de atributo ou pericia
     * precisam existir no modelo, e a estrutura precisa passar no parser de dados.
     * Vazia e valida (vira 1d20).
     */
    public static void validateTableFormula(String raw, SheetModel model) throws DiceFormula.SyntaxException {
        String formula = raw == null ? "" : raw.trim();
        if (formula.isEmpty()) {
            return;
        }
        try {
            DiceFormula.parse(FormulaResolver.placeholderFormula(formula, model));
        } catch (FormulaResolver.ResolveException e) {
            throw new DiceFormula.SyntaxException(e.getMessage(), e.getKey(), e.getArgs());
        }
    }

    public static boolean isValidTableFormula(String raw, SheetModel model) {
        try {
            validateTableFormula(raw, model);
            return true;
        } catch (DiceFormula.SyntaxException e) {
            return false;
        }
    }

    private static Roll formulaRoll(String name, String formula) throws DiceFormula.SyntaxException {
        DiceFormula.Outcome outcome = DiceFormula.parse(formula).evaluate(sides -> RANDOM.nextInt(sides) + 1);
        return new Roll(outcome.total(), MasterCommands.formulaMessage(name, outcome));
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
