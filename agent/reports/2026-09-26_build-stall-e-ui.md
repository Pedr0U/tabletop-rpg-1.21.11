# Implementation Report

## Status

PARTIAL. Build verde e artefato regenerado. Validacao visual e de runtime do
usuario ainda pendente.

## Objective

Atender as tres diretivas do usuario nesta rodada e aplicar duas decisoes de
layout tomadas por ele:

1. Ter no agente global as regras de explicar pedido de permissao e de
   perguntar em caso de duvida.
2. Descobrir e registrar por que a build travava.
3. Afastar o botao "x" da barra de rolagem da Skills.
4. Alargar os campos de texto e as barras de HP/Mana na Status, e dizer onde
   fica o botao para o usuario ajustar sozinho.

## Scope / Subtasks

- S1: verificar se as regras de permissao e de duvida existiam no agente global.
- S2:(isso e um S1b) experimentar a causa da travamento da build.
- S3: gravar a causa em memoria duravel.
- S4: mover o botao "x" para fora da faixa de clique da barra.
- S5: aumentar FIELD_W_MAX e documentar o botao.
- S6: build + varredura de encoding + conferir o artefato.
- S7: relatorio.

## What Changed

- `AGENTS.md` global ganhou a secao `## Ask before inventing, and explain every
  permission request`, com quatro regras: perguntar quando houver ambiguidade
  (sobretudo visual), nunca resolver ambiguidade em silencio, dizer de forma
  direta quando algo foi assumido, e explicar em uma frase qualquer comando
  fora do workspace.
- `SkillsScreen`: o recuo do botao "x" passou a ser `BAR_W + 2 * BAR_PAD`
  (12px), aplicado apenas quando a lista transborda. `listaTransborda` foi
  reintroduzida localmente; a barra continua na borda extrema.
- `StatusScreen`: `FIELD_W_MAX` subiu de 190 para 240, com documentacao do teto
  real e do unico lugar a editar.
- `ai-operational-discipline.md`: nova secao com a causa da travamento da build
  e o resumo das quatro causas conhecidas de travar neste projeto.

## Files Changed

- `C:\Users\Pedro\.config\opencode\AGENTS.md` (fora do repo, global)
- `agent/memory/ai-operational-discipline.md`
- `src/client/java/com/pedro/tabletoprpg/client/SkillsScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/StatusScreen.java`
- `agent/reports/2026-09-26_build-stall-e-ui.md` (este)

## Decisions

- DECISAO: manter a barra na borda extrema, como o usuario pediu, e afastar o
  "x". O nome da skill perde 12px, e so quando a lista transborda.
- DECISAO: o recuo e condicional a `listaTransborda` em vez de fixo, para o
  "x" e o nome nao ficarem com folga inutil quando nao ha barra na tela.
- DECISAO: `--no-daemon` passa a ser obrigatorio quando a saida do `gradlew` e
  filtrada por pipe.
- DECISAO: 240 em FIELD_W_MAX, e nao o maximo, porque o usuario pediu "um
  pouco" e o valor maximo (~306) e exatamente o layout encostado na borda que
  ele ja recusou antes.

## Validation

- `.\gradlew.bat build --no-daemon --console=plain`: `BUILD SUCCESSFUL in 10s`,
  comando retornou em 10s. `scanEncoding`: 58 arquivos, 0 mojibake, 0 ideograma,
  0 U+FFFD.
- Aritmetica do layout conferida a mao a partir do codigo: zona de clique da
  barra e `[x0+panelW-12, x0+panelW]`; retangulo do "x" e
  `[x0+panelW-28, x0+panelW-12]`. Sem intersecao, com 12px de recuo. Com 9px
  havia intersecao de 3px.
- Artefato: `build/libs/tabletop-rpg-1.0.0.jar`, 440671 bytes, 103 entradas.
  `StatusScreen.class` e `SkillsScreen.class` conferidos no caminho correto.
  Nenhuma fonte modificada e mais nova que o jar.
- NAO validado: o layout novo em jogo, o clique do "x" com lista transbordando,
  e o efeito visual do aumento de 240. Exige o usuario rodando o cliente.
- `runClient` nao foi repetido: as mudancas sao duas constantes de geometria,
  sem alteracao de estatico de mixin, e a injecao de runtime ja estava
  validada na rodada anterior.

## Problems Encountered

1. A build travava duas vezes, 10 minutos cada, mesmo com `BUILD SUCCESSFUL`
   impresso. Nao era lentidao nem contexto.
2. Erro proprio de 3px: implementei o recuo do "x" como 9px, ignorando que a
   zona de clique da barra cresce `BAR_PAD` dos dois lados. So percebi ao
   conferir a aritmética contra o codigo, antes de fechar.
3. `AGENTS.md` global continua acima do limite de linhas pedido.

## Root Causes

1. FACT (experimento direto): o processo do Gradle daemon herda o handle de
   stdout do pipeline do PowerShell e o mantem aberto. O pipeline nunca chega a
   EOF, entao o shell espera muito depois do build ter terminado. Sem
   `--no-daemon` a chamada ficou 600s pendurada; com `--no-daemon` a mesma
   chamada retorna em 7s. Nao e build lento, nao e o Gradle travado, nao e o
   modelo pensando.
2. FACT: as regras de permissao e de duvida nao estavam no `AGENTS.md` global.
   Eu li as 279 linhas antes de sobrescrever e elas nao estavam la; estavam em
   `agent/memory/ai-operational-discipline.md`, ou seja, valiam so para este
   repo. Configuracao incompleta, nao esquecimento do agente.
3. FACT: `BAR_PAD` entra dos dois lados da barra em `onListScrollbar`
   (linha 322), logo o recuo necessario e `BAR_W + 2 * BAR_PAD`, nao
   `BAR_W + BAR_PAD`.

## Fixes

- `--no-daemon` registrado como regra para gradlew com saida filtrada.
- Secao nova no agente global, trazendo as duas regras para o contexto de todo
  projeto, e nao so deste.
- Recuo do "x" corrigido para 12px antes de qualquer entrega.
- `FIELD_W_MAX` em 240, com o teto real e o unico numero a editar documentados
  no proprio codigo.

## Remaining Issues

- Layout da Skills e da Status ainda nao vistos em jogo pelo usuario.
- `C:\Users\Pedro\.config\opencode\AGENTS.md` esta em 194 linhas; o alvo era no
  maximo 151. Nao alcancado nesta rodada.
- O usuario TESTA com `.\gradlew.bat runClient` a partir das fontes, nao
  instalando jar. `build/libs/tabletop-rpg_TESTE_26-09-2026_0443.jar` esta
  defasado e e irrelevante para o teste; nao apaguei nenhum arquivo.
- `runClient` nao retorna porque o jogo fica de pe: confirmar por
  `Sound engine started` em `run/logs/latest.log` e `run/crash-reports/`
  vazio, nunca pelo tempo de espera.
- Nada commitado. Arvore suja: 6 modificados, 1 nao rastreado preexistente
  (`agent/NEXT-SESSION-PROMPT.md`) e 2 relatorios.

## Lessons / Memory

- Confirmado e gravado: sempre `--no-daemon` quando a saida do gradlew passa
  por pipe. Ja custou 20 minutos nesta rodada.
- Se `BUILD SUCCESSFUL` ja apareceu, o resultado e terminal e o timeout que vier
  depois e lixo do pipe. Nao reexecutar.
- Um numero que se le como "largura da barra" e a soma de tres termos, um deles
  aplicado dos dois lados. Conferir a formula inteira, nao o nome da constante.
- Nao descrever um valor pelo nome informal dele. O comentario dizia 9px porque
  eu tinha calculado `BAR_W + BAR_PAD`; a zona de clique real e 12. Comentario
  com aritmetica errada e pior que comentario nenhum.

## Next Steps

1. Usuario roda `.\gradlew.bat runClient` e responde: o "x" aparece e remove a
   skill com a lista transbordando; os campos e as barras de HP/Mana ficaram
   largos o suficiente.
2. Se ainda apertado, o usuario edita so `FIELD_W_MAX` em `StatusScreen`
   (linha do campo), sabendo que acima de ~306 o numero perde efeito.
3. Condensar o `AGENTS.md` global para no maximo 151 linhas, preservando a
   secao nova e a regra anti-ideograma, e revalidar ASCII + build.
4. Decidir o destino do jar TESTE defasado, que e irrelevante para o teste.
5. NUNCA mais pedir para o usuario instalar jar. O alvo do teste e
   `runClient` sobre as fontes.
