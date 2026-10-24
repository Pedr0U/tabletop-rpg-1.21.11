# 2026-10-23 - Traducao de textos em ingles para pt_br (lotes validados por build)

## Objetivo
Traduzir para portugues os textos em ingles visiveis ao jogador, mantendo o ingles como idioma base
(`en_us.json`). Nomes de pericias, atributos e campos preenchiveis ficam de fora, como pedido.
O usuario ja havia testado as mudancas anteriores ("esta tudo certo") e aprovou tudo nesta etapa.

## Feito (cada lote com build EXIT:0 e 240 testes sem falha)
- Dados de rolagem (`DiceRollScreen`): placeholder "Select dice..." traduzido; comparacao por
  texto trocada por checagem de estado (sem dados e sem modificador).
- Titulo "Character Status" (`StatusScreen`, ponto de chamada).
- Erros de formula (`DiceFormula`, `FormulaResolver`, `RollPreset`): viraram chaves
  `message.tabletoprpg.formula.error.*`, `formula.resolve.*` e `preset.error.*`. Chat usa
  `toComponent()` nos pontos do `MasterCommands` e do `RpgNetworking`, entao sai no idioma de quem
  recebe. `getMessage()` continua em ingles.
- Ponto duplo em "formula.. Valid names" corrigido (aprovado pelo usuario).
- Rotulos da ficha: "Player" -> "Jogador" (so rotulo; o dado `playername` nao mudou), "Initiative",
  "Character Appearance", "Character Backstory".
- Telas: `RpgMenuScreen`, `RpgSettingsScreen`, `PresetsScreen`, `PlayerListScreen`,
  `InventoryItemScreen`, `SkillFormScreen`, `SpellFormScreen`, `CharacterSheetScreen`
  ("Name required", "Editable", "Loading...", titulo "Ficha: %s").
- `MasterCommands`: 10 avisos de chat (modo, turno, sessao, inimigo, hora do mundo, hover) e os
  botoes do menu ASCII (`createBtn`/`createSuggestBtn` agora recebem `Component`).
- `DamageControlHandler`: mensagem de mob destruido ja estava traduzida.
- `en_us`: quatro chaves `threat_sheet_item_*` estavam com valor em portugues; agora em ingles.
  Decisao do implementador sem confirmacao: "ND" virou "CR" no nivel da ficha. REVISAR.

## Pendente (proxima etapa)
1. Rolagens do `MasterCommands` (linhas ~1021 e ~1740: "rolled", "TOTAL", "CRIT"): exige trocar o
   retorno de `rollDice`/`rollMessageFor` de String para Component e ajustar tres chamadores
   (`rollFormula`, `openRoll`, `rollAndPublish`). Refatoracao, nao so texto.
2. Hovers dos botoes do menu (`Component.literal(hover)` em `createBtn`): ainda em ingles.
3. `DiaryStore` (5 mensagens em ingles) e `DiaryStore`-relacionadas.
4. `SheetData.Spell.circleLabel()` (~1563-1572): sufixo "Circulo" fixo em portugues e as magias
   aparecem "1o Circulo" tambem em ingles. Exige ajuste em `SheetData` (fora da autorizacao anterior).
5. Rotulos de secao da ficha ("Identity", "Progress", "Attributes", "Skill Checks", "Bonus") em
   `CharacterSheetScreen` subclasses e `StatusScreen`.
6. Nomes de modo de camera (`SpectatorCameraController` `Mode.getDisplayName()`: "3rd Person",
   "1st Person", "Top-Down", "Free") e nomes de cor de predefinicao (`RollPresetColor.displayName`).
7. Conteudo padrao das fichas: o usuario vai descrever depois.

## Validacao
- Build EXIT:0 em cada lote; 240 testes, 0 falhas em `build/test-results/test/*.xml`.
- Nenhum teste automatizado cobre o chat nem as telas. Validacao visual e in-game pendente do usuario.

## Para testar em jogo (usuario)
- Cliente em ingles e em portugues: menus, configuracoes, predefinicoes, lista de jogadores,
  inventario, perícia, magia, "Name required", dica dos itens de ficha de ameaca.
- Formula invalida: `/rpg roll 1d6+Xyz`, `/rpg roll 2d`, predefinicao invalida.
- Botoes do menu ASCII e avisos de chat (modo, turno, inimigo).
- Largura: "Descricao", "Editar Item", "Ficha do Jogador", "Encerrar Turno", "Sim" e "Velocidade de
  transicao" em pt_br podem transbordar.

## Limitacao operacional (aprendizado)
Subagentes recusam mais de 3 arquivos por tarefa. Rodar dois builds Gradle ao mesmo tempo no mesmo
projeto causou falhas intermitentes (EOF, arquivos de resultado ausentes): fazer um build por vez.
