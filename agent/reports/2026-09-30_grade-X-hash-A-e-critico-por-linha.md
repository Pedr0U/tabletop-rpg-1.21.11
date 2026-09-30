# Relatório 2026-09-30 — `X#A` (repetição da fórmula inteira) e crítico por linha

## Objetivo

Duas coisas pedidas pelo usuário em 30/09/2026:

1. **Bug:** a rolagem em grade (`2#d20`) somava o restante da fórmula só na última
   rolagem. O pedido era `X#A` onde **A é a fórmula inteira**; o exemplo do usuário foi
   `4#(2d6+1d8+5)`, que tem que rodar `2d6+1d8+5` quatro vezes.
2. **Mudança:** na rolagem em grade, mostrar **quais linhas** foram críticas e deixar
   **amarelo** o dado que foi crítico.

## Escopo e decisões (tomadas com o usuário antes de editar)

A mudança era de gramática do parser e de regra de sistema, então as três decisões que
restavam foram perguntadas e respondidas antes de qualquer edição:

| Pergunta | Resposta do usuário |
|---|---|
| `2#d20+5` deve somar o `+5` nas 2 voltas? | Sim: `#` passa a valer para a fórmula inteira |
| O que conta como crítico? | Sem `cN`, o **valor máximo do dado**; com `cN`, o `cN` sobrepõe |
| Como marcar a linha? | `CRIT` na linha **e** dado crítico em amarelo |

## Causa raiz do bug

Não era acumulador mal resetado. eram **três fatos somados**:

1. **Aritmética:** `+5` é um `Term` próprio (`Group` com `dice == null`), avaliado
   **fora** do laço de repetição e somado ao `total` uma única vez
   (`DiceFormula.java`, `rollTerm`). Logo `2#d20+5` era `(d20+d20)+5`, nunca
   `2 × (d20+5)`.
2. **Gramática:** `parseGroup` lia `N#` e **exigia um `NdM`** logo depois. Não existia
   parêntesis em lugar nenhum da gramática, então `4#(2d6+1d8+5)` era **recusado** com
   `SyntaxException`, e a constante ficava por fora do grupo por construção.
3. **Exibição:** `MasterCommands.repeatBody` escrevia as linhas do `#` primeiro e a
   constante entrava depois no mesmo `StringBuilder`, o que colocava o `+ 5` **na mesma
   linha visual da última volta** (`d20 [3] = 3 + 5 = 17`). Era essa linha que produzia
   a impressão de "somou só na última".

## Solução implementada

### `DiceFormula.java` (motor, Java puro, sem `§`)

- **Parênteses** como agrupamento, com aninhamento: `parseParenthesized` + `parseTerms`,
  e `parse` passa a recusar sobra de texto e `)` avulso.
- **`N#` agora consome um grupo**, não um dado:
  - com parênteses → repete a subfórmula (`4#(2d6+1d8+5)`);
  - sem parênteses → repete **todo o resto da fórmula no nível corrente**, parando
    **antes do próximo `N#`** (é isso que faz `2#d20+3#d4` valer `2 × d20 + 3 × d4`);
  - o **primeiro termo** do grupo nunca é o `#` que fecha o grupo, e é por isso que
    `2#(1#d6+2)` repete um `#` dentro de outro;
  - com `repeat == 1` os parênteses são transparentes: `(2d6+3)` mostra os termos soltos.
- **`rollFormulaGroup`**: cada volta rola a fórmula inteira com dados novos; o
  `Round.subtotal` é o total de A naquela volta (com as constantes) e o `Round.label` é
  o **trecho digitado** (`formulaLabel`), sem remontar a fórmula.
- **Crítico:** `die.critical` passou a ser
  `critAt() >= 0 ? value >= critAt() : value >= sides()` (`rollOneDie`). Sem `cN` vale o
  máximo do dado; com `cN` vale o `cN`. Descartado nunca é crítico.
- **`Round.critical`**: algum dado **mantido** da volta foi crítico. Calculado por
  `anyKeptCritical(List<Face>)` sobre as faces da própria volta, **não** sobre
  `Budget.groupCritical`, que é acumulado da rolagem inteira e não serve para dizer qual
  linha foi a crítica.
- **`Face.critical`**: novo booleano, populado por `facesOf`. Transporta dado, **não cor**:
  o `§` continua só em `MasterCommands`.
- **Orçamento:** `checkStaticBudget` passou a contar a fórmula inteira multiplicada por
  `repeat`, **incluindo dentro de parênteses**, com saturação em `MAX_TOTAL_ROLLS + 1`
  para o `long` não estourar com aninhamento profundo.

### `MasterCommands.java` (cor)

- `facesBody`: face mantida e crítica → `§e` + texto + `§r§b` (o `§r` devolve o aqua do
  contexto para o próximo dado da lista não sair amarelo). Descartado continua
  `§c§m` + `§r§b`, e o `discarded` é testado **antes** do `critical`.
- `repeatBody`: `§c§l CRIT` no fim da linha quando `round.critical()`. O `CRIT` global
  do fim da mensagem continua valendo ao lado.
- O caminho de perícia (`rollPericia` → `tailText`) já desce por
  `appendTermBody`/`facesBody`/`repeatBody`, então as duas cores e o marcador de linha
  valem **automaticamente** nos dois caminhos. Não houve mudança nesse arquivo além das
  duas funções acima.

### `DiceFormulaTest.java`

- `repeatKeepsTermSign` **ajustado** (`2#d6-3` agora é `2 × (d6-3)`), por decisão do usuário.
- `noCriticalWithoutSuffix` **substituído** por `criticalWithoutSuffixUsesMaxFaces`
  (a semântica oposta é a nova regra).
- 9 testes novos: `repeatRepeatsWholeFormula`, `repeatGroupWithParentheses`,
  `nestedParentheses`, `twoRepeatGroupsInOneFormula`, `unbalancedParenthesesRejected`,
  `repeatGroupOverBudgetRejected`, `faceCriticalOnMaxFaces`, `roundCriticalFlag`,
  `discardedDieIsNeverCritical`.
- Nenhum teste foi removido. Total: 80 (71 + 9).

### `FUNCIONALIDADES-E-COMANDOS.md` (catálogo, **untracked**, sem rede de segurança do git)

Cinco trechos corrigidos: a linha do comando na tabela da seção 3, a lista de sufixos da
seção de dados, a descrição do bloco `#`, uma nova entrada para a **regra de crítico** (o
`cN` não estava documentado antes) e o parágrafo de limites (orçamento com parênteses).
Todas as citações por **símbolo**, nunca por linha.

## Arquivos alterados

| Arquivo | Mudança |
|---|---|
| `src/main/java/com/pedro/tabletoprpg/DiceFormula.java` | parênteses, `#` sobre fórmula inteira, crítico padrão, `Round.critical`, `Face.critical`, orçamento |
| `src/main/java/com/pedro/tabletoprpg/MasterCommands.java` | `§e` no dado crítico, `§c§l CRIT` na linha |
| `src/test/java/com/pedro/tabletoprpg/DiceFormulaTest.java` | 2 testes ajustados, 9 novos (80 no total) |
| `FUNCIONALIDADES-E-COMANDOS.md` | 5 trechos |

## Validações executadas

| Gate | Resultado |
|---|---|
| `.\gradlew.bat test --tests "*DiceFormulaTest*" --no-daemon --console=plain` | `BUILD SUCCESSFUL`, 80 testes, 0 falhas |
| `.\gradlew.bat build --no-daemon --console=plain` | `BUILD SUCCESSFUL in 15s` |
| `scanEncoding` (dentro do build) | OK: 100 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD |
| `DiceFormula.java` sem `§` e sem não-ASCII | conferido (0 caracteres) |
| `check-catalogo.ps1` | 1 divergência, **pré-existente e classificada como falso positivo** (ver abaixo) |
| `runClient` | subiu, **o usuário rolou em jogo** e conferiu a conta |

### Validação em jogo (evidência no log, não suposição)

O usuário testou e fechou o cliente às 13:00:01. As fórmulas e a aritmética no
`run/logs/latest.log`:

| Digitado | Saída | Confere |
|---|---|---|
| `2#20+5` | `20+5 [] = 25` nas duas linhas, total `50` | 2 × 25 = 50 |
| `2#d20+5` | `[6] = 11`, `[19] = 24`, total `35` | (6+5)+(19+5) = 35; **antes seria 30** |
| `2#d20c5+5` | `[1] = 6`, `[10*] = 15 CRIT`, total `21` | 6+15; só o 10 (≥5) crítico |
| `10#d20c5` | 10 linhas, `CRIT` só nas que rolaram ≥ 5 | crítico por linha + global |

O bug do `+5` só na última volta está **corrigido e comprovado em jogo**, não só no teste.

## Problemas encontrados

1. **`check-catalogo.ps1` acusou `/rpg/roll/Pericia/0-2` como comando que não existe mais.**
   Divergência **pré-existente**, não introduzida aqui (o trecho não foi tocado). O script
   compara **literais de comando registrados** no código; esse texto é exemplo de
   **entrada do jogador**, não um literal registrado. E o caminho de cauda existe
   (`MasterCommands.java: splitPericiaPrefix`, que devolve um `MixedRoll`, usado em
   `rollOrSkill`). Classificado como falso positivo da heurística de literal; o catálogo
   **não** foi mexido por causa disso. Detector rodado com `-ProjectRoot` explícito: sem
   ele ele não acha a raiz a partir do diretório de trabalho da sessão.
2. **`Unknown: ChildProcess.kill` no `Start-Process` do `runClient`.** Sintoma já
   conhecido: é o cleanup do harness, não falha do Gradle. O jogo subiu normalmente,
   como o log e o `latest.log` provam. Confirmar sempre pelo log e por
   `Get-Process java`, nunca pelo retorno da chamada.
3. **`2#(1#d6+2)` era ambíguo na especificação.** O implementador resolveu com a regra
   "o primeiro termo do grupo não é o `#` que fecha o grupo". Efeito colateral aceito:
   `(3#d6)` e `2#3#d6` passam a ser fórmulas válidas (o segundo vira `2 × (3 × d6)`), e
   `2#4` (número puro depois do `#`) também.

## Limitações conhecidas

- **`Face.critical` carrega o `DieResult.critical` cru**, ou seja, pode ser `true` num
  dado descartado. O chat testa `discarded` primeiro, então na tela nunca aparece amarelo
  num dado descartado, e `Outcome`/`Round` filtram descartado. O teste garante as regras
  observáveis, não o campo cru.
- **Faces de um `#` aninhado não aparecem na linha do grupo de fora.** `facesOf(parts)`
  só junta `Part.faces()`, e `Part.repeated` não tem faces por nivel. Em `2#(1#d6+2)` o
  subtotal da linha está certo, mas o dado interno não aparece entre colchetes.
- **Erro de orçamento com parênteses muito aninhados mostra `100001`** em vez do número
  exato, por causa da saturação em `MAX_TOTAL_ROLLS + 1`.
- **Constante pura como `X#` mostra `[]`**: `2#20+5` lista `20+5 []` porque 20 não é um
  dado e não tem face. Cosmetico, não foi pedido.
- **`gradlew build` não valida carga de classe nem cor no chat.** O amarelo e o `CRIT`
  por linha só existem de fato no log de jogo acima, não no build.

## Aprendizados duráveis

1. **`X#` é repetição de fórmula inteira desde 30/09/2026.** A regra da gramática é: com
   parênteses repete a subfórmula; sem parênteses repete o resto do nível corrente até o
   próximo `N#`; `repeat=1` deixa o parêntesis transparente. O `N#` **não** fecha o grupo
   no primeiro termo, e é por isso que dá para aninhar.
2. **Crítico tem padrão: sem `cN`, é o valor máximo do dado.** `cN` sobrepõe o máximo.
   Dado descartado nunca é crítico. Antes disso o crítico só existia com `cN`.
3. **Corrupção visual no chat quase sempre formatação vazando, não erro de conta.** A
   linha `d20 [3] = 3 + 5 = 17` era concatenação (`repeatBody` + `appendRollTerm`),
   enquanto a conta era `(7+3)+5`. O sintoma aparente era de aritmética.
4. **Para dizer qual linha foi crítica, olhe a face da volta, não o `Budget`.**
   `Budget.groupCritical` é acumulado da rolagem inteira e serve para o `CRIT` global.
5. **Cor não entra no motor.** `Face` e `Round` carregam **booleanos**; `§` só em
   `MasterCommands`. O `scanEncoding` do build reprovaria `§` em `DiceFormula.java`, e a
   separação é o que mantém o motor testável com JUnit sem Minecraft.
6. **`check-catalogo.ps1` precisa de `-ProjectRoot` quando o diretório de trabalho não é
   a raiz do mod**, e ele acusa exemplo de entrada do jogador como literal ausente.

## Próximos passos

- Nenhum código pendente. Aguardando o veredito do usuário sobre a **aparência** do
  amarelo e do `CRIT` por linha (a aritmética já está comprovada pelo log).
- Commit/tag **não feitos**: só mediante pedido explícito do usuário. Árvore suja com os
  3 arquivos de código/teste + catálogo untracked.
- Se o usuário quiser, dá para fechar o caso das faces do `#` aninhado (limitação 2).