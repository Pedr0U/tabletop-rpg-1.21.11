# Fichas de Ameaça — rodada 3 de layout (02/10/2026)

## Objetivo
Corrigir os itens que o Mestre voltou a reprovar depois do teste em jogo da rodada 2.

## Escopo desta rodada
Itens 3, 2 (parte nova), 5, 6, 7, 9 e o pedido final sobre o botão `+ Habilidade` e a
largura desigual das colunas. Item 8 continua em aberto: ver "Pendencias".

## Arquivos alterados
- `src/client/java/com/pedro/tabletoprpg/client/ThreatSheetScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/ThreatPericiasScreen.java`

Nenhum arquivo do servidor foi tocado: modelo, codecs, store, payloads e mixin seguem
como na rodada anterior. Nenhum arquivo novo.

## Alteracao por item

### 3 — descricao grande demais, atributos fora de vista (AINDA SEM CONFIRMACAO)
Causa raiz encontrada no codigo, nao no olho: `descH` era
`max(DESC_MIN_H, colViewH() - COL_FIXED_H - DESC_HEAD_H - ATTR_HEAD_H - ROW_H)`, isto e,
a descricao ficava com **todo** o espaco sobrante e a lista de atributos recebia uma
unica linha (`ROW_H`). Com uma linha de atributo visivel e seis a doze atributos no
modelo, a rolagem da coluna era obrigatoria.

Agora o espaco sobrante e dividido: a descricao fica com no maximo metade
(`descH = max(DESC_MIN_H, min(leftover - ROW_H - GAP, leftover / 2))`) e o resto vai
para os atributos. A rolagem da coluna continua existindo como recurso.

### 2 (parte nova) — sobrevida
`CharacterSheetScreen.COL_HP_OVER = 0xFFFF8A8A` ja era a cor do excedente do jogador.
O `drawHpBar` da ameaca usava `max(hp, max)` como denominador, entao a barra ja
crescia, mas a barra inteira era pintada de `COL_HP` e o texto ficava branco.

Agora: a barra vai de `COL_HP` ate o teto, o que passa dele usa `COL_HP_OVER`, e o
texto vira `12 / 10 (+2)` na mesma cor quando ha excedente. O modelo ja permitia
(`hp = clamp(hp, MIN_RESOURCE, MAX_RESOURCE)`).

### 5 — pericias coladas na caixa
O fundo de toda lista e desenhado em `fill(region.x - 2, region.y, region.x +
region.w - BAR_W - 2, ...)` e o texto em `region.x + 2`: a distancia real da borda do
fundo ate o texto e de 4 px. O inset feito na rodada 2 mexeu em `region.x`, que leva
fundo e texto juntos, por isso nao mudou nada visivelmente.

Agora o texto vai em `periciaRegion.x + 6`, o valor termina 8 px antes da barra, e o
valor negativo sai na cor de excedente.

### 6 — negativos e "+" na exibicao
`VALUE_MIN` ja era `-999`, ou seja o modelo nunca recusou negativo. O defeito era de
digitacao: o filtro era `-?\\d{0,4}`, e colar o "-" depois dos digitos ("5-") era
rejeitado, entao so dava para digitar negativo com o sinal primeiro.

O filtro passou a aceitar o sinal nas duas pontas, e o `save` normaliza antes de
converter: o "-" vale em qualquer ponta, o "+" e ignorado, e o que sobra sao digitos.
A exibicao usa `formatValue`: `+12` para positivo, `-12` para negativo.

### 7 e 9 — caracteristicas, passivas e ativas coladas e estourando
Mesma causa do item 5. O botao de edicao das tres listas estava em `.bounds(region.x, y,
editW, FIELD_H)`, ou seja encostado na borda do fundo. Agora e `.bounds(region.x + 4, y,
editW, FIELD_H)` com `editW = region.w - delW - GAP - 8`. As descricoes das passivas e
das ativas, e a linha `Bonus: ... Dano: ...`, tambem entraram 4 px para dentro e
perderam 8 px de largura, para nao passarem por cima da barra do container.

### Pedido final — botao e largura das colunas
O `+ Habilidade` das ativas estava em linha propria (havia sido separado na rodada 2
porque o painel era estreito). Com o painel de 600 px ele voltou para a mesma linha do
titulo, igual ao das passivas.

A caixa da esquerda parecer maior vinha de `colBarX() = leftX - BAR_W`: a barra da
coluna esquerda ficava dentro da margem do painel, e nao dentro da coluna. Agora `colW`
reserva `BAR_W + 2` e a barra ocupa essa fatia entre as colunas, com as duas caixas
medindo exatamente `colW`.

## Validacao
- `.\gradlew.bat compileClientJava --no-daemon --console=plain` → **BUILD SUCCESSFUL em 9s**.
- `compileJava` nao foi reexecutado: nenhum arquivo de `src/main` foi tocado nesta rodada.
- Cliente relancado com `Win32_Process.Create` para o Mestre testar.
- **NADA foi validado em jogo ainda.** Todo o item 3, 5, 7 e 9 continua sendo leitura de
  codigo ate o olho do Mestre confirmar.

## Limite conhecido desta rodada
O pedido e sempre da forma "esta colado", "esta estourando", "esta maior". Isso se mede
pela distancia entre a borda do fundo e o conteudo, no codigo de `render`, e nao pela
leitura do layout. Duas das tres correcoes anteriores de padding mudaram `region.x`, que
nao muda nada visivel. Se algo voltar a reclamar, a proxima tentativa tem de ser medir a
distancia no `render`, nao realocar o widget.

## Pendencias
- **Item 8 segue em aberto.** O pedido foi "os caracteres atuais/maximos estao atras do
  botao e o nome Caracteristica esta do lado do quadro, e pra ser em cima". A rodada 2
  pôs o rotulo acima da caixa e o contador em `usado/maximo`. O Mestre continuou
  marcando como pendente sem dizer o que ainda esta errado, entao nao houve chute: as
  outras correcoes desta rodada foram adivinhacao suficiente.
- Confirmar item 3 em tela, agora com a descricao limitada a metade do espaco.
- Uso do item, spawn, nametag, comando e gamerule continuam fora de escopo.
- `FUNCIONALIDADES-E-COMANDOS.md` nao foi atualizado; `HANDOFF.md` nao foi tocado.