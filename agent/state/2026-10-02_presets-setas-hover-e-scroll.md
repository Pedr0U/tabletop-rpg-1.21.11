# Estado -- 02/10/2026 -- Presets: setas no hover, travadas, scroll e aviso

## Onde paramos

Cinco feedbacks visuais da jogadora corrigidos em `PresetsScreen`. Build verde, 165
testes passando, jar de 659KB gerado. **Nada foi validado em jogo** -- sao pixel e
gesto de mouse.

## Commit desta rodada

O commit que contem este arquivo e a mudanca de `PresetsScreen`. Ver `git log -1`.

## O que mudou

Arquivo unico: `src/client/java/com/pedro/tabletoprpg/client/PresetsScreen.java`.

| # | Pedido | Como ficou | Evidencia |
| --- | --- | --- | --- |
| 1 | setas mais proximas do `Del`, mesma ordem | `arrowsRight()` com `ROW_BTN_GAP` em vez de `GAP` | script de layout: formula ganha 4px |
| 2 | setas so no hover | `showArrowsOnHoveredRow()`, antes de `super.render` | `visible` **e** `active` juntos |
| 3 | seta travada mais escura | `addArrow()` cria as duas sempre; `DARK_GRAY` + `active = false` | tooltip `Already first/last` |
| 4 | formula invadindo o nome | `rowTexts` usa a borda real do botao, nao fracao | formula 88px -> 79px, nome 119px |
| 5 | scroll invertido | `+ (int) -Math.signum(scrollY)` | bytecode + 3 telas irmas ja certas |
| 6 | aviso branco, 2 linhas, dentro do quadro | `layoutStatus()` + `STATUS_LINES = 2`, `0xFFFFFFFF` | com tela aberta o aviso nao vai ao chat |

## Armadilhas ja vencidas (nao repetir)

- **Script de layout envelhece junto.** Mudou o `chrome`, o script continuou imprimindo
  os numeros antigos com "OK". Falso negativo silencioso. Atualizado.
- **`plainSubstrByWidth` devolve String, nao indice.** A assinatura de indice tem 3
  argumentos com `boolean`. Tratar como `int` nao compila.
- **Widget invisivel ainda recebe clique.** `AbstractWidget.mouseClicked` testa
  `isActive()` e `isMouseOver()`, nunca `isVisible()`.
- **Editor de texto converte escape Unicode.** Nao passa `\u2191` pelo `edit`; as
  setas foram trocadas por faixa de linhas num script.

## Custo aceito

Segunda linha de aviso tira 10px da lista: **427x240 cai de 4 para 3 linhas**
(resolucao do teste da jogadora), 320x200 cai de 2 para 1. Telas normais (>=480x270)
continuam com 6.

## Proximo passo

Esperar o teste da jogadora no Minecraft dela com
`build/libs/tabletop-rpg-1.0.0.jar` (Fabric API 0.141.6+1.21.11, Java 21+, Loader
0.19.5+).

Candidatos se ela relatar de novo, com o log novo para diagnosticar:

1. as setas nao aparecem com o mouse em cima da linha (faixa de deteccao apertada);
2. a seta travada continua clara demais;
3. a formula ainda toca o botao do nome com nome longo;
4. o aviso ainda estoura com mensagem muito longa.

O caminho de rolagem do aviso e sempre o mesmo: `TabletopRpgClient` escolhe tela OU
chat, nunca os dois.
