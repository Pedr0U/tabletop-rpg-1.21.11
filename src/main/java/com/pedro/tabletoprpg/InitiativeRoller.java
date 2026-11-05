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
 *   <li>Marcada: rola a <b>formula padrao da mesa</b> ({@link SheetModel#initiativeFormula()},
 *       configurada na tela "Configurar iniciativa", ex.: {@code 1d20}) mais a
 *       <b>formula de iniciativa da ficha</b> de quem rola (04/11/2026), ex.: {@code 2d6+1}.
 *       Vazia na ficha = so a formula da mesa.</li>
 *   <li>Desmarcada: a pericia marcada como iniciativa, com a mesma conta do
 *       {@code /rpg roll <pericia>}; sem pericia marcada, {@code 1d20}.</li>
 * </ul>
 * Mob usa a mesma regra: formula padrao + bonus da ficha de ameaca, ou a pericia
 * marcada na propria ficha.
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
            String own = sheet == null ? "" : sheetTerm(sheet.progress().initiativeFormula());
            return formulaRoll(name, resolve(compact(model.initiativeFormula() + own + extra), sheet, model));
        }
        SheetData.Pericia pericia = sheet == null ? null : sheet.periciaById(model.initiativePericiaId());
        if (pericia != null) {
            MasterCommands.PericiaResult result = MasterCommands.periciaResult(player, pericia, extra);
            return new Roll(result.total(), result.message());
        }
        return formulaRoll(name, "1d20" + extra);
    }

    /**
     * Rolagem de um jogador com uma formula completa no lugar da formula da mesa
     * (ex.: {@code /rpg initiative roll 2d6+3}). A formula digitada substitui a
     * padrao <b>so naquela rolagem</b>, e o bonus da ficha nao entra nela.
     */
    static Roll playerFormulaRoll(ServerPlayer player, String formula) throws DiceFormula.SyntaxException {
        SheetData sheet = SessionManager.getSheet(player.getUUID());
        SheetModel model = SheetModelHolder.current();
        String name = player.getName().getString();
        return formulaRoll(name, resolve(compact(formula), sheet, model));
    }

    /**
     * Rolagem de um mob: com a caixa marcada, a formula padrao da mesa mais a
     * formula de iniciativa da ficha de ameaca; desmarcada, a pericia marcada na
     * ficha dele.
     */
    static Roll mobRoll(Mob mob) throws DiceFormula.SyntaxException {
        SheetModel model = SheetModelHolder.current();
        String name = mob.getName().getString();
        ThreatSheet ficha = ThreatSheetStore.findAnyById(ThreatSheetBinding.sheetOf(mob));
        if (model.initiativeUseDefault()) {
            String own = ficha == null ? "" : sheetTerm(ficha.initiativeFormula());
            return formulaRoll(name, resolveMob(compact(model.initiativeFormula() + own), ficha, model));
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

    /**
     * Formula padrao da mesa (04/11/2026): so dados e numeros, sem nome de
     * atributo ou pericia. Vazia e valida (o modelo a troca por 1d20).
     */
    public static boolean isValidPlainFormula(String raw) {
        String formula = compact(raw);
        if (formula.isEmpty()) {
            return true;
        }
        try {
            DiceFormula.parse(formula);
            return true;
        } catch (DiceFormula.SyntaxException e) {
            return false;
        }
    }

    /**
     * Formula da ficha como termo da soma da mesa (04/11/2026): "2d6" vira
     * "+2d6", "-2" fica "-2", "+3" fica "+3" (a dica do campo sugere o sinal, e
     * "++3" nao passaria no parser) e vazio nao acrescenta nada. Assim a formula
     * da mesa continua valendo e a da ficha entra como parcela, em vez de
     * substituir.
     */
    private static String sheetTerm(String formula) {
        String clean = compact(formula);
        if (clean.isEmpty()) {
            return "";
        }
        char first = clean.charAt(0);
        return first == '-' || first == '+' ? clean : "+" + clean;
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
