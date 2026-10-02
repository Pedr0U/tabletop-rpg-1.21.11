# Estado - Presets com atributo e tela de Presets (02/10/2026)

## Baseline
- branch `main`, arvore limpa no inicio da fase.
- HEAD na entrada: `d031a923` (item sem prefixo, cor `default` removida, GUI compilando).
- HEAD na saida: `9ba4b2c`.
- `FormulaResolver.java` **nao existia** em nenhum lugar do disco nem no Git. A sessao
  anterior da ferramenta foi interrompida antes de gravar byte. **Nao havia corrupcao a
  recuperar** — o commit anterior estava in tegro e o build verde.

## Objetivo
1. `1d6+Strength` na formula do preset soma o valor **atual** do atributo de quem rola.
2. Tela de **Presets** no lugar do formulario solto: lista, delete com confirmacao em
   dois cliques, setas de ordem, formula no hover, campos de nome/formula/cor, Save e
   Use, e voltar para a aba Rolls.

## Decisoes do usuario (02/10/2026)
1. `+Strength` soma o **valor cru** do atributo (14 soma 14), nao o modificador D&D.
2. A ordem das setas e **persistida**: o store virou lista ordenada.
3. Pericia e **token independente**: `+Athletics` soma o valor dela, e
   `+Athletics+Strength` soma os dois. A jogadora escolhe na formula.
4. Ao renomear um preset, o servidor **reentrega o item** e **avisa que o antigo ficou**
   (item duplicado na mochila, com o nome velho).
5. A lista e desenhada com **fundo escuro, sem textura nova**; altura fixa.
6. Botao de criar: preencher os campos + Save cria; clicar num preset da lista preenche
   os campos para editar, e o mesmo Save grava a alteracao.

## Decisoes tecnicas (tomadas sem perguntar, registradas aqui)
- Nomes aceitos: `id`, `label` e `name` do `SheetModel`, casados sem acento, sem
  diferenciar maiuscula e sem espaco. `+STR` == `+strength` == `+Strength`.
- Colisao entre atributo e pericia com o mesmo nome: **o atributo vence**. Sem regra
  fixa, o mesmo preset resolveria valores diferentes conforme a ordem da tabela.
- O scanner casa o **maior trecho primeiro**, para `+Animal Handling` funcionar.
- A resolucao roda em `MasterCommands.rollMessageFor`, o unico caminho por onde passam
  comando, item e tela.
- A ficha usada e a de **quem rola**, nao a do preset.

## Subtarefas feitas
1. `FormulaResolver` + validacao no `RollPreset.create` + testes. → `e304b9c`
2. `RollPresetStore` de mapa para lista, com migracao do NBT antigo. → `a0a1096`
3. `PresetsScreen` substituindo `PresetCreateScreen`, payloads novos, botao `Presets`
   na tela de rolagem. → `e92f813`
4. Correcao do acúmulo de widgets de lista e da mensagem duplicada. → `9ba4b2c`

## Validacao
- `.\gradlew.bat build` → `BUILD SUCCESSFUL`, `scanEncoding OK: 140 arquivo(s)`.
- 153 testes, 0 falhas: `DiceFormulaTest` 82, `FormulaResolverTest` 17,
  `LangKeyArgsTest` 2, `RollPresetTest` 20, `SheetDataInventoryTest` 10,
  `SheetModelCodecTest` 22.

## Relatorio
`agent/reports/2026-10-02_presets-com-atributo-e-tela.md`

## Pendente
- **A tela nunca foi aberta em jogo.** O `runClient` valida que o mod carrega, mas a
  `PresetsScreen` so e instanciada no clique em `Presets`. Layout, sobreposicao de
  campos e a rolagem da lista continuam **nao validados**.
- `preset_create_failed` diz "Could not create the preset" mesmo em falha de edicao.
- O item continua guardando o nome em tag propria; renomear entrega item novo e deixa o
  antigo na mochila (decisao do usuario).
