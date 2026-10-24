# 2026-10-23 - Traducao: continuacao (rolagens, dicas, secoes, cores, en_us) - ESTADO FECHADO

## Objetivo
Continuar a traducao de textos em ingles para pt_br, do ponto em que o relatorio
`2026-10-23_traducao-textos-ingles.md` parou. Usuario aprovou o escopo e decidiu encerrar
este bloco "por enquanto", com as pendencias abaixo.

## Feito (cada lote com build EXIT:0 e 240 testes sem falha)
- Rolagens do chat (`MasterCommands`): `rollMessageFor`, `rollDice`, `rollFormula`, `openRoll`,
  `rollAndPublish`, `rollPericia` viraram Component com chaves `message.tabletoprpg.roll.*`.
- Dicas dos botoes do menu ASCII (`menu.hover.*`); `createBtn`/`createSuggestBtn` recebem Component.
- Circulo da magia: `SheetData.Spell.circleLabel()` -> `screen.tabletoprpg.spell.circle`.
- Titulos de secao da ficha (`StatusScreen`): `status.section.*`.
- Nomes de modo de camera (`SpectatorCameraController`): `screen.tabletoprpg.camera_mode.*`.
- Cores de predefinicao (`RollPresetColor`): `screen.tabletoprpg.roll_color.*` (via `getString()`).
- `en_us.json`: 45 valores que estavam em portugues passaram para ingles (idioma base).
- Botoes "Fichas de Ameaca" e "Diario" do menu (`rpg_menu.threat_sheets`, `rpg_menu.diary`).

## Decisoes
- Mensagens do diario (`DiaryStore`) ficam em ingles: nao vao ao chat, so ao log do servidor.
- Botoes "Del"/"Del?" de predefinicao ficam em ingles: botao de 20 px.
- Mensagens de log (`LOGGER`) ficam em ingles.

## Pendencias (usuario decidiu manter assim por enquanto)
1. (ALTO impacto, rede) `RpgNetworking.translatableText` e `RollPresetColor.displayName()` usam
   `getString()` no servidor: mensagens de predefinicao saem no idioma do servidor, nao do jogador.
   Correcao = payloads passarem chave + argumentos (mudanca de STREAM_CODEC). Aguarda aprovacao.
2. `MasterCommands` ~701: `@e[name="..."]` compara com `mob.getName().getString()`; mob sem nome
   customizado pode nao casar se o nome vier traduzido no servidor. Corrigir junto com o item 1.
3. `DiaryScreenBase` ~153 `SCREEN_TITLE` "Diario" fixo (arquivo fora da autorizacao).
4. `ThreatSheetScreen` ~1356: botao "Atualizar"/"Salvar" em Java, fora do lang.
5. `DiceRollScreen` ~202 "§lDice Roller" fixo em ingles.
6. `ThreatPericiasScreen` ~227 "*Escolha a Perícia*" fixo em portugues.

## Validacao
- Build EXIT:0 no ultimo lote; 240 testes, 0 falhas.
- Validacao independente do lote anterior: APROVADO COM RESSALVAS (paridade 406 chaves, sem U+FFFD).
- NAO testado em jogo. Pendente do usuario: menus, configuracoes, predefinicoes, inventario, pericia,
  magia, dicas, rolagem no chat (cores), e predefinicao com cliente e servidor em idiomas diferentes.

## Registros
- Memoria do projeto: bloco datado "PROGRESSO (continuacao 3)" em `agent/memory/project-memory.md`.
- Nada commitado.
