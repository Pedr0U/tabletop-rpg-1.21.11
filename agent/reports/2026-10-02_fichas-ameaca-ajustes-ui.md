# Fichas de ameaça — ajustes de UI depois do teste em jogo (02/10/2026)

## Objetivo

13 apontamentos do Mestre sobre a ficha de ameaça,antho depois de testar em jogo. Metade
são bugs de dado, metade é formatação.

## Escopo entregue

### Bugs de dado (a alteraçao do texto não aparecia, ou não era gravada)

| # | Sintoma | Causa raiz |
|---|---|---|
| 2 | HP máximo não mudava ao digitar | O desenho lê o mapa `values`, e ele só era sincronizado em `save()`, nos formulários e ao reconstruir a coluna. Barras e rótulos mostravam valor velho até troca de aba. `syncFromWidgets()` no topo do `render`. |
| 6 | Valor da perícia não era salvo | `save()` lia `row.value` (modelo) sem passar pelas caixas; `syncRows()` só rodava em `rebuildList()`. `syncRows()` antes de ler as linhas. |
| 10 e 13 | `net.minecraft.util.FormattedCharSequence$$...` no lugar da descrição das passivas e ativas | `drawWrapped` fazia `lines.get(i).toString()`, e o `toString()` dessa sequência devolve o **nome da classe**. Helper `plainText()` que percorre os code points com `accept()`. |
| 3 | Atributos fora de alcance com a rolagem | `mouseScrolled` consultava a lista **antes** da coluna: com o mouse sobre os atributos, a roda movia a janela interna de 18 px e a coluna não andava. Coluna passou a ter prioridade dentro dela. |

### Formatação

| # | Sintoma | Mudança |
|---|---|---|
| 1 | Ficha pequena | `MAX_PANEL_W` 430 → **600** (mesma régua de `StatusScreen.java:104`) e **altura sem teto**, como a ficha do jogador, que deriva de `topY..bottomY`. |
| 4 | Rótulos das abas | `"Ficha"` → **"Principal - PG 1"**, `"Habilidades"` → **"Habilidades - PG 2"**. |
| 5 | Perícias coladas no teto | Região da lista recuada `PAD` nos quatro lados; o fundo recua junto, então o espaço fica visível. |
| 8 | Rótulo do campo ao lado da caixa | Rótulo **em cima** da caixa, alinhado com a borda esquerda. A caixa ganhou a largura toda do painel: antes a largura do maior rótulo era descontada dela. |
| 8 | "Caracteres atuais/máximos atrás do botão" | **Não existia contador nenhum** na tela: só `setMaxLength`, que corta em silêncio. Criado um contador `usado/máximo` na linha do rótulo, encostado na borda direita do campo, longe do rodapé. |
| 12 | "Ataques e Habilidades Ativas" entrava no botão | O `+ Habilidade` desceu para a linha de baixo. Na linha de baixo não há como sobrepor, em qualquer largura de painel. |
| 11 | Sem confirmação de exclusão | Del em **dois cliques** ("Del?") em características, passivas e ativas, por índice absoluto da lista, no mesmo padrão de `ThreatSheetsScreen`. |
| 7 e 9 | Formatação das caixas na lista | A caixa da linha de característica era `FEATURE_ROW_H - 2` (16 px) e a das habilidades era `FIELD_H` (18 px). As três passaram a usar `FIELD_H`, e `FEATURE_ROW_H` virou `FIELD_H + 4` para a folga entre linhas. |

## Validação

- `.\gradlew.bat compileClientJava --no-daemon --console=plain` → `BUILD SUCCESSFUL` (3 vezes: fixes de dado, formatação, confirmação de exclusão).
- Build completo não foi rerodado nesta rodada.

## Problema de processo

O `tcc-pesquisa` devolveu **números de linha inventados** num arquivo de 1434 linhas e
não localizeu as ocorrências que prometeu achar. Toda a linha citada neste relatório foi
conferida por leitura direta antes de virar edição. Registrado na memória do TCC.

## Pendente de teste em jogo

1. **Item 3 (rolagem)**: o Mestre respondeu "ainda não testei de novo" — o cliente estava
   aberto com o código anterior às correções. Precisa relançar para confirmar.
2. **Itens 7 e 9**: a normalização das caixas das linhas foi feita a partir da leitura do
   código; o defeito era visual e o usuário apontou "na lista dentro da ficha". Falta o
   olhar dele para confirmar que era a altura da caixa.
3. Contador de caracteres novo, abas renomeadas e painel maior: precisam de conferência
   visual.

## Limitações

- A mudança de altura sem teto aumenta a área ocupada; em telas lógicas muito baixas
  (< 230 px) o painel pode passar da tela. A fórmula de `layout()` é a antiga.
- Nada foi testado em jogo nesta rodada.