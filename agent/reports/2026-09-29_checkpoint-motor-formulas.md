# Checkpoint 2026-09-29 - Motor de formulas (Subtask 1)

## Onde o projeto esta
Working tree: `C:\Users\Pedro\Downloads\ModTableTop\tabletop-rpg-template-1.21.11-main`
HEAD/branch: `0f2a3ae`, arvore limpa no inicio desta rodada (nada pre-existente para preservar).

## Objetivo da rodada
Ampliar `/rpg roll` com formulas avancadas e implementar iniciativa configuravel.
Escopo combinado com o usuario em 4 subtasks atomicas.

## Estado por subtask
| Subtask | Estado | Validacao |
|---|---|---|
| 1a `DiceFormula.java` | PRONTA | `compileJava` exit=0 |
| 1b testes de `DiceFormula` | PRONTA | 47 testes, 45->47 verdes |
| 1c integrar em `MasterCommands` | NAO INICIADA | - |
| 1d build + testes + diff + checkpoint | build verde, falta revisao de diff | `gradlew build` exit=0, 67 testes |
| 2 Sheet Editor: pericia de iniciativa | NAO INICIADA | - |
| 3 lista de iniciativa (servidor/comandos) | NAO INICIADA | - |
| 4 HUD + HUD Editor | NAO INICIADA | - |

## Display do descarte e do `#` (29/09/2026, pedido do usuario)

O motor passou a expor ESTRUTURA em vez de texto pronto, porque com uma String unica o
chat nao sabia onde cada face estava para riscar so as descartadas:
`Part{sign, label, faces, rounds, flat, repeat, text}`, `Face{text, discarded}`,
`Round{label, faces, subtotal}`. A COR continua toda em `MasterCommands`
(`appendTermBody`, `repeatBody`, `facesBody`); `DiceFormula` segue sem `§` e sem Minecraft.

**Descarte no lugar, nao fora da lista.** Um campo `discarded` por face; cada sufixo
ordena SO os sobreviventes e os devolve para os slots que ja ocupavam. Isso e o que faz
`4d20kh2` mostrar `[18,17,14,10]` e `4d6dl1` mostrar `[1,2,3,4]`, exatamente como o
usuario escreveu. Se os sobreviventes fossem para o comeco da lista, `dl1` viraria
`[2,3,4,1]`. Os dois casos tem teste proprio porque sao a regra inteira.

**`§c§m` para o descartado e `§r§b` para restaurar.** So trocar a cor nao desligaria o
riscado: em Minecraft o riscado e ESTILO, independente da cor. O `§r` zera o estilo.

`#` virou cabecalho + uma linha por volta, com `\n` e o subtotal da volta. Limite de 20
voltas exibidas, com `...+N more`; o total da formula continua no fim da mensagem.

**Comportamento que mudou e o usuario deve saber:** `dropped X` sumiu, e `[none]` tambem
(sumiram porque todos os descartados agora aparecem marcados, com total 0).

**ERRO MEU, nao do subagente:** eu escrevi no briefing "total 34" para `4d20kh2`.
18+17 = 35. O subagente recusou mascarar e usou 35. O exemplo do usuario estava com a
soma errada; o codigo esta certo.

**Nao validado:** a COR nao foi vista em jogo. So por leitura do codigo e build. O que
falhar visualmente deve ser o `§r§b` ou o `\n`, nao a aritmetica (que tem 71 testes).

## Estado final desta rodada
`.\gradlew.bat build` = `BUILD SUCCESSFUL`, `exit=0`, **91 testes** (71 + 20), 0 falhas.
`DiceFormula.java` e ASCII puro (verificado com `rg -c "[^\x00-\x7F]"` = vazio).
Nada commitado. HEAD continua `0f2a3ae`.

1. **FATAL, corrigido:** `DiceFormula.parse` consumia o sinal separador de termo no
   fim do laco e o descartava. Resultado: TODO termo depois de um dado virava
   positivo. `d8-1` dava 6 (5+1) em vez de 4; `d20-5` dava 25.achado pelos testes
   `minusFlatTerm` / `termo negativo no meio guarda o sinal na peca`.
2. **Cosmético, corrigido:** o chat perdeu o rótulo do dado. Sem o `dice.label()`,
   `d20+10` aparecia como `"[7] + 10"` em vez de `"d20 [7] + 10"`. O formato
   antigo do `rollDice` mostrava o dado, entao isso era regressao de display.
   Primeiro consertei sem o espaco (`d20[7]`), os testes pegaram, e corrigi.


## O que JA esta pronto e NAO deve ser refeito
Arquivo novo `src/main/java/com/pedro/tabletoprpg/DiceFormula.java`, Java puro, sem
import de Minecraft, para poder ser testado em JUnit com rolagem deterministica.

- `DiceFormula.parse(String)` -> `Formula`; lanca `DiceFormula.SyntaxException`.
- `Formula.evaluate(IntUnaryOperator)` -> `Outcome{total, parts, critical}`.
- `Outcome.parts()` devolve `Part{sign, text}` SEM cor, para o `MasterCommands`
  reaproveitar o `appendRollTerm` que ja existe. Nao ha codigo de cor nesta classe.
- `Outcome.plainText()` monta "4d6kh3 [6,5,4] + 2" sem cor, para teste e log.
- `Outcome.critical()` marca se algum dado bateu o limiar de `cN`.
- Tetos: `MAX_DICE=100`, `MAX_SIDES=1000`, `MAX_REPEAT=100`, `MAX_PER_DIE=999`,
  `MAX_THRESHOLD=1000`, `MAX_TOTAL_ROLLS=100000`, `DISPLAY_LIMIT=20`.
- Helper de teste: `rollGroupQuiet` (sem texto, usado nas voltas de `N#`) e
  `rollGroupWithDetail` (com texto, usado na volta unica). `rollPool` rola os dados,
  `reduce` vira a lista em numero (soma ou contagem de `>>`/`<<`).

## Ordem de resolucao dentro de um dado (DECISAO, documentada no javadoc da classe)
1. rola `count` dados de `sides` lados
2. explosao: a cadeia inteira conta como UM dado (soma das faces)
3. `++N`/`--N` soma UMA vez por dado inicial, depois da cadeia
4. `kh`/`kl`/`dh`/`dl` sobre os valores ja modificados
5. `>>N`/`<<N` trocam a soma pela CONTAGEM

Consequencia desejada: `4d6++2dl1` = "some 8 e jogue fora o pior".

## Decisoes do usuario (29/09/2026) que valem para o resto da fase
1. A lista de iniciativa MANDA na ordem dos turnos; `/rpg turn give` continua
   existindo como override do Mestre.
2. Desempate: maior **Destreza**; se ainda empatar, avisar para re-rolar.
   Destreza e o atributo de id `"dexterity"` no `SheetModel.defaults()` (FATO
   verificado: os ids de atributo sao `"strength"`, `"dexterity"`, ... e NAO `attr_N`;
   `attr_N` so e gerado por `addAttribute`). Entao o desempate e estavel mesmo se
   o Mestre renomear o rotulo.
3. O valor entra na lista so quando a pericia rolada e a DESIGNADA no Sheet Editor
   e a iniciativa esta ativa. Sem pericia designada: avisar e nao capturar nada.
4. HUD Editor arrasta SO a lista de iniciativa; posicao e mostrar/ocultar ficam no
   servidor, por jogador.
5. O valor capturado e o TOTAL da rolagem (d20 + valor da pericia + atributo).

## Premissas assumidas (devem ser confirmadas pelo usuario, NAOFacts)
- `c18` marca o critico no texto e NAO altera o numero. O usuario disse apenas
  "define 18, 19 e 20 como criticos", sem dizer o que o critico faz. Se ele quiser
  dobrar, e uma linha em `rollOneDie`/saida.
- `>>N` conta sobre os dados que sobraram depois de `kh/kl/dh/dl`.
- `--` passou a significar "por dado". Antes `d8--1` era recusado; agora e valido e
  significa `d8` com `-1` em cada face. Mudanca de comportamento inherente ao
  pedido, e esta documentada.

## Regra de encoding desta rodada
Arquivo escrito em ASCII, Portugues sem acento, sem qualquer caractere de outro
sistema de escrita.

## Proximo passo exato
Escrever `src/test/java/com/pedro/tabletoprpg/DiceFormulaTest.java` (JUnit 5,
`org.junit.jupiter`, como `SheetModelCodecTest`) e rodar
`.\gradlew.bat test --tests "*DiceFormulaTest*" --console=plain`.
