# Implementation Report

## Status

PARCIAL. Build verde e scan limpo. Validacao visual NAO foi feita: este agente
nao consegue ler imagens, entao o print do usuario nao pode ser inspecionado.
Os dois feedbacks foram tratados a partir do codigo, e um deles foi confirmado
por aritmetica, o outro nao.

## Objective

Retomada pedida pelo usuario em 26/09/2026, depois que ele testou com
`runClient`:

1. "A parte da descricao com quebra de linhas ficou perfeita" (confirmado por
   ele, nao ha o que fazer).
2. Tirar a barra de rolagem de cima do texto do popup.
3. A barra de rolagem das skills continua "por cima" do botao de excluir; o
   botao do nome da skill deve encolher para o X ir mais para a esquerda.
4. O usuario tinha alterado o codigo do popup e o agente deveria analizar as
   alteracoes antes de mexer.

## Scope / Subtasks

- S1: identificar o que o usuario mudou.
- S2: popup, barra sobre o texto.
- S3: respiro entre a barra da lista e o botao de excluir.
- S4: build e scan.

## What Changed

- `popupLines()` passou a quebrar o texto em `popupW - 17`, e nao mais
  `popupW - 10`. Nenhum outro numero do layout foi tocado.
- `BAR_SLOT` foi de 20 para 32.
- Comentarios e docstrings adjustedos para registrar as formulas.

O usuario mudou, e foi PRESERVADO:

- `POPUP_H` de 68 para **120** (so 3x a altura do nome, como ele pediu).
- `popupY` de `y + skillRows * rowH + 4` para `+ 16`.

## Files Changed

- `src/client/java/com/pedro/tabletoprpg/client/SkillsScreen.java`
- `agent/reports/2026-09-26_skills-popup-e-respiro.md` (este)

## Decisions

- DECISAO: reservar a coluna da barra na largura da quebra SEMPRE, e nao so
  quando o texto transborda. Se a largura dependesse da barra, o texto
  reflutuaria no instante em que ela aparecesse, e o popup daria um salto
  visivel. O custo e quebrar 7px antes, o que e invisivel.
- DECISAO: `BAR_SLOT = 32`, e nao um numero grande. A formula esta no proprio
  codigo: respiro = `BAR_SLOT - (BAR_W + 2 * BAR_PAD)` = `BAR_SLOT - 12`.
  Com 32 sao 20px, inequivoco a olho; 40 dariam 28px se ainda faltar.
- DECISAO: nao corrigi o problema do popup invadindo o campo de nome (abaixo).
  E consequencia de um ajuste visual que o usuario acabou de fazer e validar
  visualmente; mexer nisso as cegas seria repetir o erro da rodada anterior.

## Validation

- `.\gradlew.bat build --no-daemon --console=plain`: `BUILD SUCCESSFUL in 10s`.
  `scanEncoding`: 60 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD.
- Feedback do popup, CONFIRMADO por aritmetica no codigo:
  - antes, texto de `popupX + 5` ate `popupX + popupW - 5`;
  - barra de `popupX + popupW - 9` ate `popupX + popupW - 3`;
  - ou seja, 4px de sobreposição real, exatamente o sintoma relatado;
  - depois, o texto para em `popupX + popupW - 12`, tres px antes da barra.
- Feedback da lista, NAO reproduzido pelo codigo: com `BAR_SLOT = 20` a
  aritmetica dava 8px de respiro, e nao sobreposicao. Ver Remaining Issues.
- Geometria depois da mudanca: botao X em `[x0+panelW-52, x0+panelW-32]`, zona
  de clique da barra em `[x0+panelW-12, x0+panelW]`, respiro de 20px.
- NAO validado: nada foi visto em jogo nem no print.

## Problems Encountered

1. **Este modelo nao aceita imagem.** `run/screenshots/2026-09-26_09.33.04.png`
   existe (480071 bytes) e nao pode ser aberto aqui. O print do usuario ficou
   sem uso, e a unica via foi o codigo.
2. O segundo feedback nao bate com o codigo: com 20px de reserva ja havia 8px
   de folga, e nao sobreposicao. Ou o print e de um build anterior a mudanca,
   ou o que se ve e aperto visual e nao sobreposicao de pixel. Nao ha como
   decidir isso sem ver a imagem.

## Root Causes

1. FAT (popup): `popupLines()` quebrava em `popupW - 10` sem descontar a coluna
   da barra, e `renderPopup` desenhava o trilho em `popupX + popupW - BAR_W -
   BAR_PAD`. Com o texto comecando 5px a esquerda da moldura, os ultimos 4px de
   uma linha cheia ficavam sob o trilho. A barra era desenhada ANTES do texto
   no mesmo metodo, entao o trilho opaco escondia o texto.
2. NAO CONFIRMADO (lista): ver Remaining Issues. Nao foi achado um caminho no
   codigo que produza a sobreposicao relatada.

## Fixes

- `popupLines()` agora reserva 17px (5 de padding + 12 da barra), e o texto
  termina 3px antes do trilho.
- `BAR_SLOT` em 32, com a formula do respiro documentada no proprio codigo,
  para o usuario ajustar sem caçar numero em lugar nenhum.

## Remaining Issues

- **O print nao pode ser lido por este agente.** Se o layout ainda estiver
  errado, a unica via e o usuario descrever onde exatamente, em pixels
  aproximados, ou entao este agente precisa de um caminho que leia imagem.
- **Sobreposicao do popup com o campo de nome, achada por aritmetica e NAO
  corrigida.** Com `POPUP_H = 120` e `popupY = y + skillRows * rowH + 16`:
  a faixa reservada vai de `listBottom` ate `nameY - 6`, ou seja 120px; o popup
  comeca em `listBottom + 16` e pode ter ate 120px de altura, terminando em
  `listBottom + 136`, que e 16px DEPOIS de `nameY - 6` (`listBottom + 120`).
  So acontece quando a lista nao esta saturada em 8 linhas E a descricao tem
  11 linhas ou mais. E consequencia direta do ajuste do usuario; corrigir as
  cegas seria repetir o erro de layout da rodada anterior.
- `AGENTS.md` global em 194 linhas, contra o maximo de 151 pedido. Pendente de
  rodada anterior.
- Diagnostico do braco do caido continua esperando `[DownAlign]` do log.
- Nada commitado.

## Lessons / Memory

- Este agente **nao consegue ler imagem**. Nao voltar a prometer analise de
  print; pedir descricao em texto, ou dizer que a imagem nao pode ser aberta.
  A leu o print tres vezes nesta sessao antes de descobrir que o modelo nao
  suporta entrada de imagem.
- Nao repetir a mudanca do usuario como se fosse erro. `POPUP_H = 120` e
  `+ 16` sao dele e foram preservados; so o que estava errado foi corrigido.
- Respeito de folga se mede por formula, e a formula se escreve no codigo.
  "8px de respiro" e verificavel; "parece grudado" nao e.

## Next Steps

1. Usuario roda `.\gradlew.bat runClient` e confirma os dois pontos.
2. Se a barra da lista ainda parecer em cima do X, o unico numero e
   `BAR_SLOT`; 40 da 28px de respiro, e o nome da skill encolhe mais.
3. Se o popup invadir o campo de nome com descricoes longas, o ajuste e em
   `popupY` e `POPUP_H`, e a escolha visual e do usuario.
4. Condensar o `AGENTS.md` global para no maximo 151 linhas.
