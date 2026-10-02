# Push com merge do trabalho do Pedro, e um preset que nao estava arrumado

## Objetivo

Fechar o push dos 14 commits locais de Presets, combinando com o commit novo do outro
desenvolvedor sem perder trabalho dos dois lados, e conferir se o preset aceitava
rolagem personalizada com repeticao.

## Estado no inicio

- Branch `main`, HEAD `4dd3335`, arvore limpa.
- `origin/main` estava atrasado na referencia local. **Foi preciso `git fetch`**: sem
  isso, `origin/main..HEAD` listava 14 commits como "nao pushados" e nao revelava
  nada do lado do outro. Referencia velha e uma leitura de "meu" estado, nao do
  repositorio.
- Checkpoint criado antes de qualquer escrita:
  `git tag -f checkpoint/antes-push-2026-10-02 HEAD`.

## Merge

Um commit novo do outro desenvolvedor: `2619049` "Pagina 3 (Skills/Magias): barra de
scroll corrigida, cards padronizados e reordenacao de skills".

Arquivos em comum com o meu trabalho: **so um**,
`agent/memory/project-memory.md`. Ele mexeu em `StatusScreen.java` (762 linhas) e num
relatorio -- nenhum dos dois foi tocado por mim.

O conflito foi de append: os dois lados anexaram no fim do arquivo. Resolvido
preservando as duas caudas (5685 linhas minhas + 96 dele), com guarda que valida os
tres marcadores antes de gravar e confirma que nenhum sobrou depois.

Merge commit `54fed70`, depois separado da correcao seguinte.

## O preset com `6#2d6dl1` NAO estava arrumado

A jogadora avisou que o commit do outro desenvolvedor resolvia as rolagens
personalizadas tipo `6#2d6dl1` no preset, e que ja estava resolvido.

**FATO:** `git show --stat 2619049` mostra tres arquivos, e nenhum deles e o caminho do
preset. O commit nao toca `FormulaResolver`, `RollPreset` nem `RpgNetworking`.

**FATO:** o preset recusava a formula. Teste de regressao novo, antes de qualquer
correcao:

```
com.pedro.tabletoprpg.RollPreset$PresetException: unknown attribute 'dl' in formula..
  Valid names: Strength, Dexterity, Constitution, ...
```

**Causa raiz.** `FormulaResolver.scanWords` andava com `isDiceAt` ate o fim do `d6` e
caia no `dl` do `dl1`. Nao existe atributo chamado `dl`, entao ele devolvia a palavra
inteira e o preset era recusado. `isDiceAt` so examina o que vem **depois** do `d` -- e
depois do dado vem o operador. `DiceFormula` ja conhecia `dl`/`kh` como operadores desde
a gramatica deles; quem nao conhecia era o scanner de nomes, que eu escrevi.

**Correcao.** `isKeepDropAt`: `kh`/`dl` seguidos de numero sao operador de dado, nao
nome, pelo mesmo motivo do `d6`. O numero e obrigatorio, porque `dl1` sem dado antes
continua invalido -- so passa a ser recusado pelo parser, e nao como "atributo
desconhecido".

Teste `createAcceptsRepeatAndKeepDrop` cobre `6#2d6dl1`, `4d6kh3`, `4d6dl1kh3`,
`3d6++2dl1` e o `dl1` solto (que tem de ser recusado, senao a guarda seria frouxa).

Commit `c963581`.

## Problema em aberto: a memoria esta triplicada

**FATO:** `agent/memory/project-memory.md` tem 8295 linhas, e o conteudo esta em parte
repetido. Titulos aparecem 3x (`2026-09-29 - rodada fechada em jogo e checkpoint`,
`FATO verificado - largura de texto em pixel`, entre outros) e um aparece 6x
(`HIPOTESE - 28/09/2026, a confirmar em jogo`).

**FATO:** os commits `26bdfdf` (+2659/-2611) e `1bb88ad` (+8087/-2586) reescreveram o
arquivo inteiro. Nao e problema de separador de linha: base, HEAD e origin/main estao
todos em CRLF puro.

**Consequencia:** grep em qualquer licao antiga devolve 3 resultados, e o primeiro nao e
necessariamente o mais recente. Isso e exatamente o que torna um arquivo de memoria
inutil: a leitura da licao pode ser a versao velha.

**Nao corrigido nesta fase.** Deduplicar 8295 linhas automaticamente e arriscado sem
saber qual das tres copias tem a edicao mais recente de cada licao.

**RESOLVIDO depois, a pedido da jogadora.** Ver `2026-10-02_memoria-deduplicada.md`.
8408 -> 2948 linhas, 17 caracteres de controle reparados, e **nenhuma** licao perdida --
tres delas estavam prestes a ser.

## Validacao

| O que | Resultado |
| --- | --- |
| Merge | `54fed70`, 1 arquivo conflitado resolvido preservando os dois lados |
| Build | `BUILD SUCCESSFUL`, `scanEncoding` OK (148 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD) |
| Testes | **166 testes, 0 falhas** (165 + 1 novo de regressao) |
| Layout das 9 telas | OK, so 240x180 reprova (preexistente) |
| Jar | `build/libs/tabletop-rpg-1.0.0.jar` 660KB, 02:33:05 |
| Jar contem as correcoes | `javap`: `FormulaResolver.isKeepDropAt`, `PresetsScreen.arrowsLeft`/`formulaMax` |
| Bug do `dl` | falhou antes da correcao, passa depois |

O build foi rodado **depois** do merge, nao antes: o outro desenvolvedor mexeu 762
linhas em `StatusScreen`, e um build verde antes do merge nao diria nada sobre o
resultado combinado.

## Problemas

- **`6#2d6dl1` estava quebrado e nao por quem a jogadora pensava.** Se eu tivesse
  confiado na frase e mergeado sem conferir, o preset continuaria recusando a formula no
  preset que ela ia testar.
- **Conflito de memoria sem conflito de codigo.** O unico arquivo em comum era
  knowledge log. O risco real de conflito de codigo era zero; o trabalho de reconciliacao
  foi de texto, e ainda assim consumiu a maior parte do cuidado.

## Proximo passo para a jogadora

Testar `build/libs/tabletop-rpg-1.0.0.jar` (Fabric API 0.141.6+1.21.11, Java 21+,
Loader 0.19.5+). Os dois pontos desta fase:

1. as setas coladas no `Del` e o nome do preset de volta ao tamanho;
2. criar um preset com `6#2d6dl1` e rolar -- deve ser aceito.
