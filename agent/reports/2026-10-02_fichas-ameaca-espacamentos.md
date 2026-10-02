# Fichas de Ameaça — espacamentos e cabecalhos de coluna (02/10/2026)

## Objetivo
Sete pontos de "colado" relatados pelo Mestre depois do teste da rodada 3, mais o
cabecalho `Perícia` / `Bônus` na lista de perícias.

## Escopo
So `src/client/java/com/pedro/tabletoprpg/client/ThreatSheetScreen.java`. Nenhum arquivo
de `src/main`, nenhuma classe nova, nenhuma mudanca de modelo, codec, payload ou rede.

## Causa raiz comum
Quase todos os pontos eram **espelhamento de posicao**, nao falta de espaco. O
`SECTION_H = 10` era a altura da faixa do titulo, mas o botao `+ Caracteristica` /
`+ Habilidade` / `Editar` tem `SMALL_BTN_H = 16` e e desenhado em `y - 3`. O botao
terminava em `y + 13` e a lista comecava em `y + 10`: o botao **invadia 3 px da caixa**
das listas. Isso e "colado com a caixa" e "sem separacao".

O resto e o mesmo problema ja descrito na rodada 3, agora com a consequencia pratica:
o fundo das listas e desenhado em `region.x - 2 .. region.x + region.w - BAR_W - 2`, e
os widgets usavam `region.x + 4` ou `region.x + 2`. O `Del` terminava a 2 px da borda.

## Alteracao por item

1. **Nome de Exibicao x checkbox** — o checkbox nascia em `y + LABEL_H + FIELD_H`,
   exatamente na borda de baixo do campo. Agora `+ 6`, e o bloco reserva os mesmos 6 px
   no teste de `inColumn` e no avanco de `y`.
2. **Caixa de valor do atributo x scrollbar** — a caixa era criada em
   `region.x + region.w - boxW`, ou seja encostando (e invadiendo) a barra de rolagem da
   coluna. Agora `region.x + region.w - boxW - 8`.
3. **Rotulo do atributo x caixa de valor** — o "STR Strength" era truncado com
   `room = attrRegion.w - boxW - GAP - 4` e desenhado em `attrRegion.x + 2`, entao os dois
   pareciam um texto unico. Agora `room` com 16 px de folga e o rotulo em
   `attrRegion.x + 6`.
4. **Nome e bonus da pericia x borda** — o nome foi para `x + 6`, o bonus para 12 px
   antes da barra (antes eram 8, e ficavam a 2 px da borda), e o texto da linha desceu
   1 px (`rowY + 2`), porque a linha tem 11 px e o texto nascia colado no topo.
5. **Botoes das caracteristicas x borda** — `editW` passou de `- 8` para `- 16` de folga
   nas tres listas. O `Del` e posicionado por `edit.getRight() + GAP`, entao acompanha o
   `editW` sozinho.
6. **`+ Caracteristica` x caixa** — ver causa raiz: faixa do cabecalho passou a
   `SECTION_H + SMALL_BTN_H + 4`.
7. **`+ Habilidade` x caixa** — idem, nas colunas de passivas e de ativas.

**Cabecalhos novos:** a lista de perícias ganhou uma faixa de 10 px acima com `Perícia`
a esquerda e `Bônus` alinhado com a coluna do valor. A faixa foi descontada da altura da
lista para o total continuar cabendo: `periciaH` ganhou `periciaHeadH` e a altura da
regiao perdeu `periciaHeadH`, entao a lista tem exatamente a mesma altura de antes, so
que 30 px mais abaixo (20 da faixa do cabecalho + 10 dos cabecalhos).

## Validacao
- `.\gradlew.bat compileClientJava --no-daemon --console=plain` → **BUILD SUCCESSFUL em 9s**.
- `compileJava` nao foi reexecutado: `src/main` nao foi tocado.
- Cliente relancado pelo Mestre para teste.
- **Nada validado em jogo.** Todos os sete itens sao afirmacao de posicao no codigo, e o
  unico jeito de confirmar e o olho do Mestre.

## Nota de metodo
Pela terceira vez o mesmo sintoma ("colado") teve causa em `render` ou em aritmetica de
faixa, nunca em falta de espaco real. O padrao que funciona: medir a distancia entre a
borda da caixa e o primeiro pixel do elemento, no codigo, e nao escolher um numero.

## Pendencias
- Confirmar os sete pontos em tela.
- Item 3 da rodada 3 (descricao vs atributos) segue sem confirmacao do Mestre.
- `FUNCIONALIDADES-E-COMANDOS.md` desatualizado e `HANDOFF.md` intocado, por decisao
  do Mestre.