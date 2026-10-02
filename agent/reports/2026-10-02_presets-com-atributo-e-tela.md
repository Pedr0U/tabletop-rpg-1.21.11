# Fase: Presets de rolagem com atributo/pericia e tela de Presets

**Data:** 02/10/2026
**Branch:** `main`
**HEAD:** `9ba4b2c`
**Estado:** 3 commits nesta fase, todos com build verde.

---

## Objetivo

Retomar o mod (`tabletop-rpg-1.21.11`) depois da sessao anterior ter travado ao criar
`FormulaResolver.java`, e implementar os feedbacks do usuario sobre Presets de Rolagem:

1. `1d6+Strength` na formula do preset soma o valor **atual** do atributo de quem rola.
2. Tela de **Presets** no lugar do formulario solto: lista, delete com confirmacao, setas
   de ordem, formula no hover, campos de nome/formula/cor, Save e Use, e voltar para a
   aba Rolls.

## Verificacao do estado (antes de escrever qualquer coisa)

- `git status --short` limpo, `HEAD = d031a923` na entrada.
- `FormulaResolver.java` **nao existia** em nenhum lugar do disco nem no Git. A geracao
  anterior da ferramenta foi interrompida antes de gravar byte. **Nao havia nada
  corrompido para recuperar.**
- Build baseline verde: `.\gradlew.bat build` → `BUILD SUCCESSFUL`, `scanEncoding OK:
  138 arquivo(s)`.

## Decisoes do usuario (perguntadas antes de implementar)

| Pergunta | Resposta |
|---|---|
| `+Strength` soma valor cru ou modificador D&D? | **Valor cru** (14 soma 14) |
| Onde gravar a ordem das setas? | **Persistir como lista ordenada** |
| Pericia: valor, ou valor + atributo ligado? | **O jogador escolhe na formula** (`+Athletics+Strength`) |
| Item ao renomear preset? | **Reentregar o item, avisando do duplicado** |
| Quantos presets cabem na lista? | **Fundo escuro desenhado, sem textura** (lista de altura fixa) |
| Botao de criar? | Preenche os campos + Save cria; clicar num preset preenche os campos para editar |

## Decisoes tecnicas tomadas sem perguntar (registradas)

- **Nomes aceitos:** `id`, `label` e `name` do `SheetModel`, casados sem acento, sem
  diferenciar maiuscula e sem espaco. `+STR`, `+strength` e `+Strength` caem no mesmo
  atributo.
- **Colisao de nome:** atributo vence pericia. Sem regra fixa, o mesmo preset resolveria
  valores diferentes conforme a ordem da tabela.
- **Multi-palavra:** o scanner casa o maior trecho primeiro, para `+Animal Handling`
  funcionar. Precisa porque nomes de pericia tem espaco.
- **Onde a resolucao roda:** em `MasterCommands.rollMessageFor`, o unico caminho por onde
  passam comando, item e tela. Se ficasse no `presetUse`, `/rpg roll 1d6+Strength` e o
  clique no item dariam numeros diferentes.
- **A ficha e a de quem rola**, nao a do preset: dois jogadores usando o mesmo preset
  tem resultados diferentes, que e o ponto.

## Subtarefas

### 1. `FormulaResolver` (commit `e304b9c`)

Novo arquivo em `src/main/java/com/pedro/tabletoprpg/FormulaResolver.java`.

- `resolve(formula, sheet, model)`: troca nomes por numeros e devolve a formula resolvida.
- `tokens(formula, model)`: devolve os nomes usados, para a tela e para a validacao.
- `ResolveException` com frase pronta para o chat.

**Bug encontrado pelos testes:** `resolve` nao checava `MAX_TOKENS`; so `tokens` checava.
Uma formula com 1000 nomes passava sem limite. Corrigido.

**Bug encontrado pelos testes:** o scanner parava no espaco, entao `+Animal Handling` era
recusado. Corrigido com casamento do maior trecho.

Aplicado em:
- `MasterCommands.rollMessageFor` — resolucao antes do `DiceFormula.parse`.
- `RollPreset.create` — validacao dos nomes no Save, para o erro nao aparecer so ao rolar.

17 testes novos em `FormulaResolverTest`.

### 2. `RollPresetStore` de mapa para lista (commit `a0a1096`)

O store guardava `Map<String, RollPreset>` e reordenava por nome a cada leitura. Duas
coisas quebravam: as setas nao tinham onde gravar, e um preset editado mudava de lugar
por causa do alfabeto.

- `list` devolve na ordem gravada; `put` mantem a posicao ao editar.
- `move(from, to)`, `indexOf(name)`, `list` como copia defensiva.
- `CODEC` le lista e, com `withAlternative`, o mapa antigo. A migracao ordena por nome,
  que e a ordem que a versao anterior exibia.

**Detalhe do codec:** a alternativa e a LISTA e nao o mapa. O `withAlternative` testa a
alternativa so quando o principal falha, e lista (`ListTag`) e mapa (`CompoundTag`) sao
distinguiveis pelo tipo de tag. Se o principal fosse o mapa, um preset novo gravado como
lista nunca voltaria.

20 testes no `RollPresetTest` (eram 15), incluindo round-trip do NBT novo e leitura do
formato antigo.

### 3. Tela de Presets (commits `e92f813`, `9ba4b2c`)

`PresetsScreen.java` substitui `PresetCreateScreen.java` (removido).

- Lista com nome, cor e formula a vista; a formula tambem no tooltip.
- `Del` em dois cliques, como nas skills e magias. O pending e **indice**, nao nome: o
  nome pode mudar entre os dois cliques e apagar o preset errado e pior do que nao apagar.
- Setas ^ e v reordenam.
- Clicar num preset preenche os tres campos; o mesmo Save cria ou edita.
- `Save` e `Use` embaixo, e `< Back to Rolls` no canto inferior esquerdo.
- Fundo desenhado com `fill`, sem textura nova.
- Botao `Create Preset` da tela de rolagem virou `Presets`, **sempre visivel** (a tela
  nova tambem cria do zero, entao esconder sem valor de rolagem tiraria a unica forma de
  abrir a lista so para reordenar ou apagar).

**Bug encontrado revisando o codigo, antes de rodar:** `rebuildListOnly()` e chamado a
cada seta e a cada clique do Del, e ele **somava** widgets novos sem remover os antigos.
O `Screen` nao tem como remover um widget especifico, entao a lista duplicava: 6 linhas
viravam 12 botoes no mesmo lugar, com indices velhos presos nos widgets antigos, e a
seta passaria a mover o preset errado. Corrigido com `listWidgets` + `addListWidget` +
`removeWidget`.

**Duplicacao de aviso:** a resposta do servidor aparecia na linha de status E no chat.
Agora so na tela quando ela esta aberta; so no chat quando a jogadora fechou a tela no
meio do caminho.

### Rede (`RpgNetworking.java`)

| Payload | Direcao | Funcao |
|---|---|---|
| `PresetListRequestPayload` | C2S | "me manda a lista" (abre a tela) |
| `PresetListPayload` | S2C | lista completa, na ordem da jogadora |
| `PresetSavePayload` | C2S | salva, criando ou editando (`originalName` decide) |
| `PresetDeletePayload` | C2S | apaga (item fica na mochila) |
| `PresetMovePayload` | C2S | uma seta: `name` + `up` |
| `PresetResultPayload` | S2C | `ok` + mensagem + lista nova |

Regras no servidor (todas em `savePresetFromScreen`):
- nome vazio → recusa
- `RollPreset.create` recusa formula e cor invalidas
- preset editado que sumiu do servidor → recusa (nao cria um novo sem a jogadora pedir)
- colisao de nome → recusa, **exceto** quando o dono da chave e o proprio preset editado
- teto de `MAX_PRESETS` → so conta ao criar
- renomear → remove o viejo e poe o novo **no lugar dele** (nao no fim da lista)
- sem espaco na mochila → o preset foi **salvo mesmo assim**, e a resposta e recusa com o
  texto do inventario cheio mais a lista atualizada

`RollPreset.STREAM_CODEC` novo: o `CODEC` e do NBT e NBT nao viaja em pacote.

## Arquivos alterados

- `src/main/java/com/pedro/tabletoprpg/FormulaResolver.java` (novo)
- `src/main/java/com/pedro/tabletoprpg/RollPreset.java`
- `src/main/java/com/pedro/tabletoprpg/RollPresetStore.java`
- `src/main/java/com/pedro/tabletoprpg/MasterCommands.java`
- `src/main/java/com/pedro/tabletoprpg/RpgNetworking.java`
- `src/main/java/com/pedro/tabletoprpg/mixin/PlayerRollPresetPersistenceMixin.java`
- `src/main/resources/assets/tabletop-rpg/lang/en_us.json` (2 chaves)
- `src/client/java/com/pedro/tabletoprpg/client/PresetsScreen.java` (novo)
- `src/client/java/com/pedro/tabletoprpg/client/PresetCreateScreen.java` (**removido**)
- `src/client/java/com/pedro/tabletoprpg/client/DiceRollScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/TabletopRpgClient.java`
- `src/test/java/com/pedro/tabletoprpg/FormulaResolverTest.java` (novo)
- `src/test/java/com/pedro/tabletoprpg/RollPresetTest.java`

## Testes e resultados

- `FormulaResolverTest`: 17 testes, 0 falhas.
- `RollPresetTest`: 20 testes, 0 falhas.
- Suite completa: 153 testes, 0 falhas.
  (`DiceFormulaTest` 82, `FormulaResolverTest` 17, `LangKeyArgsTest` 2, `RollPresetTest`
  20, `SheetDataInventoryTest` 10, `SheetModelCodecTest` 22)
- `.\gradlew.bat build` → `BUILD SUCCESSFUL`, `scanEncoding OK: 140 arquivo(s), 0
  mojibake, 0 ideograma, 0 U+FFFD`.

## Problemas e causas

1. **`FormulaResolver` nao existia.** Nao era corrupcao: a sessao anterior morreu antes de
   gravar. Nada a recuperar.
2. **`MAX_TOKENS` so no `tokens`.** Achei pelos testes; `resolve` rodava sem teto.
3. **Nomes com espaco recusados.** Achei pelos testes; o scanner parava no espaco.
4. **Widgets de lista acumulando.** Achei revisando, antes de rodar o cliente.
5. **`withAlternative` com dois argumentos.** A assinatura e de um argumento nesta
   versao do DFU; a segunda string nao existe.
6. **Mojibake e ideograma em `PresetsScreen`.** O `scanEncoding` pegou dois ideogramas
   chineses que entraram num comentario, no lugar de "rolar nele". O build **falhou** — e
   o detector estava certo: nao ha scroll vertical em portugues.
7. **Seis expectativas minhas erradas nos testes** (`2d6+20+2` em vez de `2d6+d20+2`,
   `resolve` devolvendo soma em vez de formula, helper que duplicava pericia em vez de
   mexer na existente). Corrigidas no teste, nunca no codigo de producao.

## Nao resolvido / pendente

- **A tela nunca foi aberta em jogo.** O `runClient` desta sessao valida que o mod
  carrega (mixin, registro de payload, lang), mas a `PresetsScreen` so e instanciada no
  clique em `Presets`. Layout, sobreposicao dos campos e a rolagem da lista continuam
  **nao validados** — sao o tipo de coisa que um build verde nunca prova.
- O item continua carregando o nome numa tag propria. Renomear entrega item novo e
  **deixa o antigo na mochila** (decisao do usuario); o aviso esta em
  `preset_renamed`.
- `preset_create_failed` diz "Could not create the preset" mesmo quando o erro e de
  edicao. A frase funciona, mas nao e precisa.
- O uso do item continua sendo `/rpg preset use <nome>` via comando de chat (o caminho
  antigo). Funciona, mas nao ha feedback de erro na tela se o preset tiver sumido.

## Notas para o proximo desenvolvedor

- **Build verde nao prova GUI.** A `PresetsScreen` compila e so quebra em runtime, no
  clique. Se for mexer nela, suba o jogo.
- `Screen` nao tem remocao de widget individual: `clearWidgets` leva tudo junto (e o
  texto digitado nos `EditBox` com ele). Para recriar parte da tela, guarde os widgets
  daquela parte e use `removeWidget`.
- `EditBox` **exige** `addRenderableWidget` para entrar no ciclo de desenho/clique.
- Em 1.21.11 o `mouseClicked` recebe `MouseButtonEvent`, nao `(double, double, int)`.
- `withAlternative` so testa a alternativa quando o codec **principal** falha. Para
  migracao de formato, o principal tem de ser o formato novo, e a distincao tem de ser
  do tipo de tag.
- `rollMessageFor` e o funil de **toda** rolagem. Qualquer coisa que mude o resultado de
  uma rolagem (inclusive nomes de atributo) entra la, e nao em `presetUse`.
