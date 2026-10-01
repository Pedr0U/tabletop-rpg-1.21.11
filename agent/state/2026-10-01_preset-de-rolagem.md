# Estado - Item de Preset de Rolagem (01/10/2026)

## Baseline
- branch `main`, HEAD `8637473`, arvore limpa no inicio da fase.
- Tag de rollback: `checkpoint-20261001-preset-rolagem` == `8637473`.
- `origin/main` == `8637473` no inicio da fase.

## Objetivo
Item "Preset de Rolagem" (sprite provisorio do Bundle do vanilla, copiado para dentro
do mod como asset editavel):

- `/rpg preset create <nome> <formula> <cor>` cria o preset e ENTREGA o item.
- `/rpg preset use <nome>` rola.
- `/rpg preset delete <nome>` remove o preset; **o item NAO e retirado do inventario**.
- `/rpg preset list` lista os presets da jogadora.
- Clicar com o botao direito no item rola automaticamente no chat.
- Se o preset foi deletado, usar o item mostra "preset nao existe".
- Tela de Rollings: botao "Create Preset" embaixo de Clear/Back, centralizado, que so
  aparece quando ha valor na rolagem. Abre um formulario com Nome, Formula (preenchida
  com a rolagem atual) e as 17 cores.

## Decisoes do usuario (01/10/2026)
1. `/rpg preset *` vale para **qualquer jogador** (mesma politica do `/rpg roll`).
2. Criar pelo menu Rolls **tambem entrega o item**, igual ao comando.
3. O item **mostra o nome do preset** no inventario.

## Paleta (17 opcoes, ids do vanilla)
`default`, `white`, `light_gray`, `gray`, `black`, `brown`, `red`, `orange`, `yellow`,
`lime`, `green`, `cyan`, `light_blue`, `blue`, `purple`, `magenta`, `pink`.

## Fatos apurados na exploracao (FACT)
- `DiceRollScreen` (`src/client/java/.../DiceRollScreen.java`) e um `Screen` de layout
  MANUAL. `bottomY = panelY + 370*scale` e a linha Clear/Back; a textura do painel tem
  612 px de altura, entao **ha pouco espaco abaixo** -> o botao novo precisa caber em
  ~370..395*scale sem sair do painel.
- O criterio existente de "ha valor na rolagem" e `selectedDice.isEmpty() && modifier == 0`
  (linha 120, dentro de `getRollExpression`).
- O cliente rola por comando de chat: `player.connection.sendCommand("rpg roll " + expr)`.
  Nao existe payload de rede para rolagem.
- `MasterCommands.rollDice(ctx, rawFormula)` (linha 910) ja devolve a mensagem pronta ou
  `null`. E o ponto de reuso para preset; `rollFormula` (829) decide privado/publico.
- A raiz `/rpg` **nao tem** `.requires(...)`; o papel e conferido em cada handler via
  `verifyMasterPermission(ctx)`. Como qualquer jogador pode usar preset, `preset` NAO
  chama `verifyMasterPermission` (igual a `/rpg roll`).
- Persistencia por jogador ja existe: mixin `PlayerSheetPersistenceMixin`
  (`addAdditionalSaveData`/`readAdditionalSaveData` com `ValueOutput.store(String, Codec, T)`
  e `ValueInput.read(String, Codec<T>)`), cache estatico em `SessionManager`, e gravacao
  forcada no logout por `SheetPersistenceEvents`. Precedente direto para os presets.
- `DiceFormula.parse(String)` lanca `SyntaxException` (checked) com mensagem pronta para
  o chat. Aceita `d`/`D`, `NdM`, numeros, `N#`, parenteses, sufixos. Espacos sao ignorados.
  Limites: 100 dados, 1000 faces, 100.000 rolagens.
- Registro de item: `ResourceKey` + `new Item(new Item.Properties().setId(KEY)...)`
  + `Registry.register`. O `setId` e obrigatorio.
- `UseItemCallback` e o gancho de uso (nao `Item#use`), registrado em `ModItems.register()`.

## Pendente de decisao tecnica
Como a cor chega no item. Em 1.21.11 **nenhum** `models/item/*.json` do jogo usa `"tints"`
(verificado no jar do cliente), e o `models/item/bundle.json` do vanilla e um
`item/generated` puro sem tint. A pesquisa sobre `DyedColor`/DataComponent estava em
andamento quando este arquivo foi escrito. Enquanto isso, a cor fica num campo do preset
independente de como o item e renderizado.

## Subtarefas (ordem)
1. `RollPreset` (name, formula, colorId) + armazenamento por jogador (mixin NBT + cache).
   Validacao: `gradlew build` + teste unitario de codec round-trip.
2. Comandos `/rpg preset create|use|list|delete` reutilizando `MasterCommands.rollDice`.
   Validacao: `gradlew build`.
3. Item `roll_preset` + assets + `UseItemCallback` -> rola / avisa preset inexistente.
   Validacao: `gradlew build` + conferir a classe no jar.
4. Cor no item (depende da pesquisa acima).
5. GUI: botao "Create Preset" + tela de formulario + payload C2S com validacao de
   formula no servidor e retorno de erro.
   Validacao: `gradlew build` + boot do cliente.
6. `lang/en_us.json`, `FUNCIONALIDADES-E-COMANDOS.md`, memoria e relatorio.

## Notas de ambiente
- `javap`: `C:\Program Files\Java\jdk-25\bin\javap.exe`.
- Build: `.\gradlew.bat build --no-daemon --console=plain`; conferir a linha `BUILD`, nao
  o `$LASTEXITCODE`.
- `write`/`edit` as vezes ecoam o texto corrompido na conversa: o arquivo esta certo,
  confiar no build/`scanEncoding`.