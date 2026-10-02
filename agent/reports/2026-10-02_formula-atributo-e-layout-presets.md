# 02/10/2026 -- Formula com atributo no preset e layout da tela de Presets

## Objetivo

Fechar a validacao que faltava na fase anterior e corrigir o que o teste em jogo
revelou. Duas pendencias separadas, uma por bug:

1. `1d6+Strength` nao era aceito no preset, apesar de o `FormulaResolver` existir e
   estar testado.
2. A tela de Presets nao cabia na tela: campos e amostras de cor saiam do quadro, e
   Save, Use, status e voltar estavam abaixo da dobra.

No meio da execucao veio um pedido de escopo: **tirar a pericia da formula**, deixar
so o atributo.

## O que mudou

### 1. Formula com nome de atributo (BUG, causa confirmada)

**Causa raiz.** Em `RollPreset.create`, `DiceFormula.parse(formula)` rodava sobre a
formula **crua** e **antes** de qualquer checagem de nome. O `DiceFormula` so
entende numeros e dados, entao `1d6+Strength` era recusado como sintaxe invalida e o
`FormulaResolver` **nunca era chamado**. O recurso era inalcancavel pela tela e pelo
comando ao mesmo tempo, e o build continuava verde.

**Por que passou.** `RollPresetTest` exercitava `create` com `1d20+5` e `banana` --
nunca com um nome de atributo. O `FormulaResolverTest` cobria o resolver bem, mas nao
a **integracao** dele com o `create`. A lacuna nao era do `FormulaResolver`: era da
ordem das validacoes dentro do `create`, que so o teste de integracao pega.

**Correcao.**

- Os nomes passam a ser conferidos primeiro.
- O parser recebe `FormulaResolver.placeholderFormula(formula, model)`, que troca cada
  nome por `0`. O que sobra para o parser e a estrutura de dados e sinais (`1d6+0`),
  que e a parte que ele sabe julgar.
- O preset guarda a formula **com** o nome. O placeholder existe so para o parser.
- A mensagem do parser volta com `replace(forParsing, formula)`: sem isso a jogadora
  leria "unexpected '+' ... in '1d6+0+'" e procuraria um zero que nunca escreveu.

**Testes novos** (`RollPresetTest`, 7 testes): `1d6+Strength` aceito e guardado com o
nome; nome nunca vira valor nem placeholder; id, label e sem acento; nome desconhecido
recusado com a lista de validos; pericia recusada; erro de estrutura citando a formula
digitada (`1d6+Strength+`, nao `1d6+0+`); teto de nomes respeitado.

### 2. Pericia fora da formula (DECISAO do usuario)

`FormulaResolver` acceptava nome de pericia como token independente e
`+Athletics+Strength` somava as duas coisas. A pericia saiu. Consequencias:

- `Kind`, `Token.kind` e `periciaValue` sairam; `Token` agora e `(id, display)`.
- `table()` so popula atributos.
- Mensagens: "unknown attribute or skill" -> "unknown attribute"; "attribute or skill
  names" -> "attribute names".
- **`tokens()` foi removido.** Nao era usado em producao -- so em teste. Ele existia
  para uma lista de tokens clicavel na GUI que nunca foi construida. Com a pericia fora,
  sobrou um metodo publico sem caller e tres testes que so testavam ele.
- Teste novo prova a recusa: `1d6+Athletics` no `create` tem de falhar **no save**,
  e nao passar e so falhar na rolagem depois que a jogadora achou o preset valido.

### 3. Layout da tela (BUG, medido)

O painel media **326px num painel de 240px**. Tudo abaixo das amostras de cor ficava
fora da tela, **sem erro nem aviso** -- o Minecraft simplesmente nao desenhou. Duas
causas somadas:

1. altura fixa para a lista (`VISIBLE_ROWS = 6` x 22px), sendo a lista a unica parte
   que deveria encolher;
2. o orcamento descontava `2 * PAD` quando `panelY` nunca e menor que `PAD` -- faltava
   um `PAD`, e o `panelY` minimo empurrava o fundo para fora mesmo com a conta certaina.

**Correcao.** `chrome` (tudo menos a lista) tem altura fixa; a lista recebe o que
sobrar da janela, entre 1 e `MAX_ROWS` linhas; o painel e centralizado nos dois eixos.
Mais:

- O bloco `rotulo + campo` e centralizado como uma unidade. Centralizar so o campo
  punha o rotulo "Formula" para fora do painel, porque o rotulo e mais largo que o
  espaco sobrando de um lado.
- As 17 amostras cabem numa linha (236px num painel de 300), em vez de duas.
- Voltar, Save e Use dividem a mesma faixa. Voltar continua no canto inferior esquerdo.
- Nome e formula da linha da lista tem larguras por fracao (`rowTexts`), calculadas
  uma vez e usadas pelas duas metades -- antes nome e formula se atropelavam porque
  cada uma se cortava pelo espaco restante da outra.
- "Color: <nome>" vira um par centralizado. O nome da cor sozinho, encostado no campo,
  era mais largo que a coluna do rotulo e saia pela esquerda do painel.

**Como foi verificado.** Nao da para testar `Screen` em JUnit (precisa de `Minecraft`).
Escrevi um script que espelha as formulas do `layout()` e confere se algo sai da tela
em cada dimensao. Ele **achou um estouro residual** de 10px em 427x240 depois da
primeira correcao (a causa 2 acima), que a revisao nao tinha pego.

```
OK     427x240  painel=300x230 y=6    lista=32-112 (4 linhas)  campo=200px@x137
OK     480x270  painel=300x250 y=10   lista=36-136 (5 linhas)  campo=200px@x163
OK     640x360  painel=300x270 y=45   lista=71-191 (6 linhas)  campo=200px@x243
OK     960x540  painel=300x270 y=135  lista=161-281 (6 linhas) campo=200px@x403
OK     854x480  painel=300x270 y=105  lista=131-251 (6 linhas) campo=200px@x350
OK     320x200  painel=296x190 y=6    lista=32-72 (2 linhas)   campo=200px@x83
FALHA  240x180  painel=216x184 y=6                              -> estoura 10px embaixo
```

427x240 e a dimensao em que a jogadora estava testando (854x480 com GUI scale 2).
Nela a lista fica com 4 linhas e rola. Ela perguntou se valia trocar os campos para
lado a lado e ganhou ~1 linha; **decidiu manter empilhado**.

### 4. Log de diagnostico (pedido da jogadora)

O `runClient.log` da sessao anterior mostrava a sessao inteira **sem nenhuma prova de
que a tela chegou a ser aberta**. Isso torna um layout quebrado indistinguivel de uma
sessao em que ninguem clicou no botao. Adicionado:

- `DiceRollScreen.openPresets`: uma linha na abertura, com `guiScaledWidth/Height`.
- `PresetsScreen.layout`: o layout inteiro em uma linha (painel, lista, campo, amostras,
  rodape, status).
- `TabletopRpgClient`: `PresetListPayload` (quantos presets, tela aberta?) e
  `PresetResultPayload` (`ok`, mensagem, tela aberta?).

## Problemas encontrados e como foram tratados

| # | Problema | Como apareceu | Tratamento |
|---|----------|---------------|------------|
| 1 | `1d6+Strength` recusado no save | Relato da jogadora | Causa confirmada em `RollPreset.create`; ordem das validacoes invertida |
| 2 | Painel 326px em tela de 240px | Relato da jogadora | Layout medido a partir da janela |
| 3 | Estouro residual de 10px em 427x240 | **Script de layout**, nao a revisao | `avail` descontaba `2 * PAD` em vez de `4 * PAD` |
| 4 | Nenhum teste cobria `create` com nome de atributo | Revisao da causa | 7 testes novos |
| 5 | `MIN_ROWS = 2` forcado punha o rodape fora da tela em janela baixa | Script de layout | Piso virou 1 linha; so 240x180 ainda estoura |
| 6 | Dois ideogramas chineses num relatorio da fase anterior | **`scanEncoding` derrubou o build** | Detector estava certo; corrigido no `.md` |
| 7 | Byte NUL no `project-memory.md` desde outra sessao | Ferramenta recusou ler o arquivo como binario | Repara byte a byte (`0xFF5555`) |
| 8 | `tokens()` sem caller em producao | Revisao da mudanca de escopo | Removido, com os 3 testes |
| 9 | `openPresets` calculava `formula` e nunca usava | Revisao | Morto removido |
| 10 | Ideograma e aspas chinesas nas minhas proprias notas | `scanEncoding` / conferencia | Descritos por codepoint |

Os numeros 6, 7 e 10 sao o mesmo defeito aparecendo tres vezes: **texto em outra
escrita entra em arquivo do projeto**. O detector do build pegou o 6; o 7 eu achei
so porque a ferramenta se recusou a ler o arquivo; o 10 eu criei ao documentar o
defeito, citando os caracteres proibidos.

## Testes

162 testes, todos passando.

| Suite | Antes | Agora |
|-------|-------|-------|
| FormulaResolverTest | 17 | 19 |
| RollPresetTest | 20 | 27 |
| DiceFormulaTest | 82 | 82 |
| SheetModelCodecTest | 22 | 22 |
| SheetDataInventoryTest | 10 | 10 |
| LangKeyArgsTest | 2 | 2 |

`FormulaResolverTest` ficou com 19 e nao com mais porque tres testes de `tokens()`
sairam junto com o metodo. As duas falhas que apareceram no caminho foram **erro meu
de teste**, nao do produto: a soma esperada de `(Strength+Animal Handling)` estava
invertida, e o helper "ficha sem o atributo" montava o atributo com valor 0 em vez de
omite-lo -- que e justamente a distincao que o teste queria exercitar.

## Arquivos alterados

- `src/main/java/com/pedro/tabletoprpg/FormulaResolver.java` -- so atributo, `placeholderFormula`, `tokens`/`Kind`/`periciaValue` removidos.
- `src/main/java/com/pedro/tabletoprpg/RollPreset.java` -- nomes conferidos antes do parser; mensagem com a formula original.
- `src/client/java/com/pedro/tabletoprpg/client/PresetsScreen.java` -- layout medido; painel centralizado; colunas por fracao.
- `src/client/java/com/pedro/tabletoprpg/client/DiceRollScreen.java` -- log na abertura; variavel morta removida.
- `src/client/java/com/pedro/tabletoprpg/client/TabletopRpgClient.java` -- log dos dois pacotes de preset.
- `src/test/java/com/pedro/tabletoprpg/FormulaResolverTest.java` -- reescrito para atributo so; 19 testes.
- `src/test/java/com/pedro/tabletoprpg/RollPresetTest.java` -- 7 testes de formula com atributo.
- `agent/memory/project-memory.md` -- 4 licoes; reparo do byte NUL.
- `agent/reports/2026-10-02_presets-com-atributo-e-tela.md` -- dois ideogramas corrigidos.

## Validacao

**Feita em teste de codigo:**

- `gradlew build` verde, incluindo `scanEncoding`.
- 165 testes, 0 falhas (82 `DiceFormulaTest`, 30 `RollPresetTest`, 19
  `FormulaResolverTest`, 22 `SheetModelCodecTest`, 10 `SheetDataInventoryTest`,
  2 `LangKeyArgsTest`).
- Layout conferido por script nas dimensoes reais. 427x240, que era o caso
  reportado, fecha com 4px de folga embaixo.
- `RollPreset.commandName()` conferido ponta a ponta contra o armazenamento:
  `create("Dano Espada")` -> salvo -> `find(uuid, commandName())` encontra, e a
  chave da forma underscored e igual a chave do nome com espaco.

**Feita em jogo (`runClient` da sessao anterior a esta, log de 02:25-02:26):**

- A `PresetsScreen` abriu 4 vezes. Zero excecao do mod.
- Layout real registrado: `tela=960x505 painel=300x270 em (330,117)
  lista=143..263 (6 linha(s) de 20) campo=200x18 campoX=403
  amostras=1 linha(s) de 16 px` -- bate com o previsto pelo script.
- `PresetListPayload recebido: 0 -> 1 preset(s)`.
- `PresetResultPayload: ok=true msg="Preset 'Dano Espada' created
  (2d6+Strength, Yellow)"`.
- No chat: `rolled a: 2d6 [6,4] + 2 = 12`. `DiceFormula.parse("2d6+Strength")`
  lanca excecao, entao uma rolagem bem-sucedida do preset prova que o nome foi
  resolvido no caminho real.

**Nao feita -- depende de olhar humano:**

- A correcao de ordem de desenho desta fase nao tem prova automatica possivel: o
  sintoma era visual ("o botao nao aparece mas da para clicar") e nenhum log
  distingue "botao desenhado" de "botao tapado". A correcao esta no codigo e o
  build esta verde; **quem confirma e a jogadora no proprio Minecraft**.
- O mesmo vale para o risco por baixo da linha em edicao e para o botao `Use`
  mandando o nome underscored.

## Limite conhecido

Em 240x180 de GUI o painel estoura 10px embaixo. Essa dimensao exige GUI scale 5 ou
mais, abaixo do que as proprias telas do Minecraft suportam. Nao contorci o layout
por isso. Se aparecer, o caminho e reduzir `MAX_ROWS`.

## Estado final

Branch `main`, arvore limpa, `c01f33b`. Nada pushado para `origin/main`.
Artefato a testar: `build/libs/tabletop-rpg-1.0.0.jar` (658 KB, 02/10 00:45).
