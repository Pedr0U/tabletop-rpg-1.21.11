# Layout, validacao e rascunho do editor (28/09/2026)

## Objetivo

Rodada de correcao dos 6 defeitos reportados pelo usuario em teste no jogo, sobre o estado
depois da rodada anterior (`2026-09-27_correcoes-7-bugs-ficha.md`). O usuario **confirmou** que
os 7 bugs da rodada anterior estao corrigidos e funcionando.

## Escopo

6 defeitos reportados + 1 bloqueador e 3 importantes achados pela revisao independente.
Nenhuma feature nova. Nenhuma mudanca de schema, codec, rede ou persistencia.

## Alteracoes, por defeito do usuario

### 1. Textura do item

O item estava sem textura. Trocado de `minecraft:item/book` para
`minecraft:item/writable_book` (livro com pena), a pedido do usuario como placeholder.
Verificado por revisao que o `ModItems` registra um `new Item` puro e so existe um model de
item no mod, entao o model e resolvido pelo nome do registro.

### 2. Caixas de texto fora do menu e atras do titulo

**Causa raiz (duas, medidas):**
- `applyScroll` no editor usava `ROW_H` (20) no guard de visibilidade em vez da altura real do
  widget (`ROW_H - 4` = 16). Com scroll, a 1a linha ia para `y = 12..28`, invadindo a faixa
  do titulo (`TITLE_Y = 10`) e 14px **acima** do painel (que comeca em `CONTENT_TOP - 2 = 26`).
- O titulo era desenhado **depois** de `super.render()`, entao cobria a caixa.

**Correcao:** criterio unico `fitsInPanel(y, h, top, bottom)`, usado tanto pelos widgets (com
`slot.widget().getHeight()`, a altura real) quanto pelos `Caption` (com `font.lineHeight`),
eliminando a assimetria anterior. E o titulo foi movido para **antes** de `super.render()` nas
duas telas, para o texto ficar na frente do widget.

### 3. Nome longo atravessa os botoes/caixa de valor

Exemplo do usuario: "Pontos de Determinacao (PD)" no rotulo de Mana extrapolava sobre o `-`, a
barra e a caixa.

**Causa raiz:** a largura do rotulo era medida pelo rotulo real, mas **limitada a `leftW / 3`**
em `fieldLabelWidth`, e o texto era desenhado sem nenhum corte. Rotulo de ~140px contra 124px
reservados: invadia.

**Correcao, conforme pedido explicito do usuario** ("text-wrap pra ir pra linha debaixo e
centralize conforme o posicionamento da caixa de texto a frente dele"):
- `addWrappedLabel` quebra com `font.split` em ate 2 linhas e **centraliza** cada linha dentro
  da largura reservada (`x0 + (maxW - font.width(part)) / 2`).
- `maxW` termina antes da caixa a frente, entao a 2a linha nunca a toca.
- Se ainda nao couber em 2 linhas, corta com **reticencias**, para o usuario **ver** que foi
  truncado, em vez de um corte silencioso que parece um nome completo.
- Aplicado em campo, secao, atributo e HP/Mana. `rowH` e `fitRowHeight` **nao** foram mexidos,
  e a caixa nao foi movida: o bloco de 2 linhas e centralizado na linha inteira, preservando o
  alinhamento das 4 caixas de identidade que o usuario ja havia aprovado.

**Limitacao declarada:** o bloco e adaptativo (`labelBlockAdvance()`), mas duas linhas de tinta
de 8px **nao cabem fisicamente** num `rowH` de 12-13px, que e o caso em GUI 480x270 (escala 4).
Nesse regime o wrap e fisicamente impossivel e o rotulo sai truncado com reticencias. A invasao
esta resolvida em todos os casos; o wrap so acontece onde ha altura.

### 4. Teto de HP/Mana em 9999

O filtro de `createFieldBox` era generico e identico para todos os campos numericos
(`-?\d{0,9}`, ate 9 digitos), sem olhar o teto de cada campo. O servidor ja tinha
`MAX_RESOURCE = 9999`, entao o usuario digitava e via o valor recortado em silencio.

**Correcao:** teto **por campo**, com `NO_CEILING` como sentinela de "sem teto".
HP e Mana = `SheetData.MAX_RESOURCE` (9999); `level` e `xp` seguem sem teto no cliente, como
o usuario pediu (nao mexer no que ja funciona). `withinCeiling` decide por numero, nao por
contagem de caracteres: `"9999"` passa, `"99991"` e recusado, `"-999"` (4 chars com sinal)
passa. O servidor continua a autoridade.

**Efeito colateral encontrado na revisao:** a guarda de truncamento de `drawValue`, sozinha,
passou a **mentir**. Com rotulo longo de Mana, `labelW` cresce -> `boxW` encolhe -> a barra
encolhe para ~39px, e `"9999 / 9999"` (~61px) saia como `"1234 /"`: leitura plausivel e
errada de um recurso, justamente nos valores de 4 digitos que a rodada anterior legalizou.
Corrigido degradando o **formato** em degraus: `"hp / hpMax"` -> so o valor atual -> corte
(pso valor forjado fora da faixa legal). Nunca metade de um formato.

### 5. Nome de pericia vazio

**Causa raiz:** `SheetModel.withPericiaText` devolve **a mesma instancia** quando o nome alvo
fica vazio, e `periciaRow` tratava identidade (`next == staged`) como "rejeitado", reescrevendo
a caixa com `setValue(live.name())`. Como `setValue` chama `moveCursorToEnd`, o cursor saltava
para o fim, e o usuario via a letra voltar - "nao deixa apagar a primeira letra".

**Correcao, conforme a decisao condicional do proprio usuario** ("se for requisito ter o nome
da pericia, desative o botao de salvar e mostre um aviso"):
- A caixa **aceita** ficar vazia e nao reverte mais.
- O nome invalido **nao entra no `SheetModel`**: `pendingNames` (posicao -> texto) rastreia na
  tela. Escolha deliberada: deixar vazio entrar no modelocionaria `SheetModel.sanitizePericias`,
  que **descarta** pericia sem nome e, se a lista toda esvaziar, **restaura as 18 padrao** — e
  como a identidade de linha aqui e a **posicao** (`periciaAt(pos)`), todas as linhas seguintes
  se deslocariam e o botao de atributo e o `X` passariam a operar na pericia errada.
- `saveButton.active = isDirty() && !hasPendingName()`, e o aviso
  `screen.tabletoprpg.sheet_editor.pericia_no_name` ocupa o mesmo slot do aviso "unsaved", com
  prioridade.
- `X` intacto e funcional: e o unico caminho de saida de um nome invalido.
- `discardButton.active` passou a `canDiscard() = isDirty() || hasPendingName()`. Sem isso,
  apagar o nome sem mexer em mais nada deixava Salvar, Descartar e Reset **todos** desligados:
  beco sem saida. A revisao confirmou que nao resta nenhum caminho sem saida.
- Nome **duplicado** continua revertendo: duas pendentes iguais nao tem ordem de resolucao.

### 6. ESC sem salvar perdia tudo

**Requisito do usuario:** fechar sem salvar e reabrir o item mostra os dados, mas **sem entrar
em vigor**; so o Descartar volta ao normal, e so o Salvar aplica.

**Correcao:** `draft` guarda o `staged` na saida; `takeDraft()` reidrata ao abrir sem enviar
nada ao servidor (o `baseline` continua sendo o do servidor, entao nada entra em vigor);
`clearDraft()` e chamado por `save()`, `discard()` e `reset()`.

`draft` e `pendingNames` precisam ser **`static`**: `TabletopRpgClient.java` faz
`new SheetEditorScreen()` a cada abertura, entao campo de instancia nao resolveria. E ai veio o
bloqueador.

## Defeitos encontrados pela revisao independente

### BLOQUEADOR - rascunho vazava entre mundos

`draft` e `pendingNames` sao estaticos, e o cleanup de `ClientPlayConnectionEvents.DISCONNECT`
em `TabletopRpgClient` zera `SheetModelHolder` mas **nao** os dois. Cenario confirmado:

1. Mestre no Mundo A abre o editor, edita, aperta ESC -> `draft` sujo.
2. Desconecta. O cleanup zera `SheetModelHolder`, nao o `draft`.
3. Entra no Mundo B; o JOIN publica o modelo de B.
4. Abre o item -> `takeDraft()` devolve o modelo do **Mundo A**, `baseline` = modelo do **Mundo B**.
5. Aperta Salvar -> o **`SheetModelStore` do Mundo B passa a ser o modelo do Mundo A, persistido.**

Ou seja, sobrescrita persistida de dados entre mundos e servidores. Agravante: `pendingNames` e
chaveado por posicao, entao uma marca do Mundo A pode cair numa linha inexistente no Mundo B,
deixando o Salvar desligado com um aviso que nomeia uma linha que nao existe.

**Correcao:** `SheetEditorScreen.discardTransientState()` (publico, delega para `clearDraft()`,
entao os dois nao podem divergir) chamado no mesmo `DISCONNECT`, imediatamente depois de zerar
o `SheetModelHolder`.

Sobre varios jogadores: **refutado** pelo codigo. `build.gradle` usa
`splitEnvironmentSourceSets()`, entao `src/client/java` nem entra no classpath do servidor
dedicado; e a unica referencia a tela e em `TabletopRpgClient`. O `static` e por JVM de cliente,
ou seja, um jogador local.

### IMPORTANTE - o rascunho so sobrevivia ao ESC e ao botao Close

`onClose()` nao e o caminho de **toda** saida de tela. Abrir o inventario (tecla E) ou qualquer
outra tela com o editor aberto e sujo jogava a edicao fora sem registrar o rascunho. O hook
correto e `Screen.removed()`, que `Minecraft.setScreen` chama em qualquer troca (existe em
1.21.11, nao e final, nao estava sobrescrito).

**Correcao:** `removed()` novo, com `onClose()` e `removed()` chamando um `captureDraft()`
privado e idempotente.

**Divergencia encontrada pelo implementador, contra a premissa do briefing:** `reset()`
(Restaurar) poe `SheetModel.defaults()` no `staged` mas **nao** mexe no `baseline`, entao
`isDirty()` fica verdadeiro depois do Restaurar e `removed()` gravaria rascunho com o padrao.
Verificado que **e** o comportamento correto: o padrao nao esta gravado no servidor e e o que o
Mestre esta vendo, entao fechar depois do Restaurar tem de preservar.

**Ordem entre `removed()` e o evento `DISCONNECT` nao e garantida pela API.** Se `removed()`
rodasse depois do handler, recriaria o rascunho que o cleanup acabou de zerar, desfezendo o
bloqueador. `captureDraft()` so registra com `this.minecraft.getConnection() != null`. Nao ha
perda: ESC, Close, inventario e qualquer outra troca acontecem com conexao viva.

### IMPORTANTE - o wrap so funcionava com janela alta

Ver acima: o wrap exigia `rowH >= 18`, o que com 18 linhas exige altura de GUI ~410+, ou seja
escala 1-2. Em escala 3-4 o rotulo era **cortado sem reticencias**, sem o usuario ver. O bloco
foi tornado adaptativo e o corte ganhou reticencias.

### NIT relevantes

- `onClose` nao limpa `pendingNames` porque ele **precisa** sobreviver com o rascunho (a caixa
  remontada se repopula dele). O comentario antigo dizia o contrario; foi corrigido.
- `EditBox.insertText` aplica o filtro e engole a tecla **sem feedback** quando o teto e
  atingido; `deleteCharsToVal` nao aplica filtro, entao apagar funciona. E armadilha de UX, nao
  travamento: trocar "9999" por outro valor grande continua possivel.
- `shiftPendingAbove` foi confirmado correto: `sanitizePericias` deduplica por
  `name().toLowerCase()`, entao `removePericia` remove exatamente 1 elemento e o shift de 1 e
  exato.

## Arquivos

- `src/client/java/com/pedro/tabletoprpg/client/StatusScreen.java`: `drawValue` com degradacao
  de formato, teto por campo, `addWrappedLabel` nos rotulos, reticencias no nome da pericia.
- `src/client/java/com/pedro/tabletoprpg/client/CharacterSheetScreen.java`: `addWrappedLabel`,
  `truncateWithEllipsis`, `labelBlockAdvance`, `createFieldBox(..., ceiling)`,
  `withinCeiling`, `TextLine` com `FormattedCharSequence`, titulo antes de `super.render()`.
- `src/client/java/com/pedro/tabletoprpg/client/SheetEditorScreen.java`: `fitsInPanel`,
  `pendingNames`, `captureDraft`/`removed`/`onClose`, `discardTransientState`, Save/Discard/Reset.
- `src/client/java/com/pedro/tabletoprpg/client/TabletopRpgClient.java`: reset do estado
  transiente no `DISCONNECT`.
- `src/main/resources/assets/tabletop-rpg/models/item/sheet_editor.json`: `writable_book`.
- `src/main/resources/assets/tabletop-rpg/lang/en_us.json`: chave `pericia_no_name`.
- `FUNCIONALIDADES-E-COMANDOS.md`: see pendencia abaixo.

## Validacoes executadas

- `.\gradlew.bat build --no-daemon --console=plain` -> **BUILD SUCCESSFUL** (rodado 2x: um
  falhou com 1 erro de compilacao, corrigido; o segundo passou).
- `[scanEncoding] OK: 87 arquivo(s), 0 mojibake, 0 ideograma, 0 U+FFFD.`
- `check-catalogo.ps1` -> `OK: nenhuma divergencia mecanica entre codigo e catalogo`, exit 0.
- Revisao independente: **1 BLOQUEADOR** e **3 IMPORTANTES** encontrados, todos corrigidos.
- As 5 assinaturas de API introduzidas foram confirmadas por `javap` no jar do cliente 1.21.11.

**O que NAO foi validado:** nenhum dos 6 defeitos, nem os 4 da revisao, foi revalidado em jogo.
Sao todos layout, medida de pixel em runtime e estado de tela, exatamente o que build e teste
unitario nao pegam. `src/test` so tem `SheetModelCodecTest`, que nao toca nenhuma tela.

## Limitacoes e pendencias

1. **O wrap de 2 linhas nao e possivel em GUI 480x270.** `rowH` cai para 12-13px e duas linhas
   de tinta de 8px se sobrepoem. La o rotulo sai truncado com reticencias. Se o usuario quiser o
   wrap naquela resolucao, o caminho e reduzir o numero de linhas visiveis (scroll na coluna) ou
   subir o piso de `rowH` as custas de conteudo visivel. **Decisao do usuario, nao aplicada.**
2. **Catalogo nao atualizado com as regras desta rodada.** A tarefa foi cancelada pelo usuario
   antes de comecar. `FUNCIONALIDADES-E-COMANDOS.md` reflete a rodada anterior, mas nao: teto de
   9999 no cliente, degradacao de formato da barra, wrap/reticencias, nome de pericia vazio
   (Save desativado + aviso) e o rascunho do ESC. **Pendente.**
3. O plano de **id estavel em `PericiaDef`** segue aprovado e nao executado, por decisao do
   usuario: depois do teste em jogo. Ver a secao de plano na memoria.
4. Sem commit automatico: o usuario pediu commit e tag ao fim desta rodada (ver secao seguinte).

## Aprendizados duraveis

- `Screen.removed()` e o hook de **toda** saida de tela, nao so `onClose()`. `onClose()` cobre
  ESC e o botao de fechar; abrir outra tela (inventario, outra GUI) passa por `removed()`.
- Estado `static` de tela precisa entrar no **mesmo** cleanup de desconexao que o estado
  equivalente. `SheetModelHolder` ja era resetado no `DISCONNECT`; o rascunho e as pendencias
  do editor foram adicionados depois e **nao** entraram no cleanup, criando vazamento entre
  mundos. Sempre que criar `static` de tela, procurar o cleanup e se cadastrar nele.
- A ordem entre `removed()` e o evento de desconexao **nao e garantida**. Blindar com checagem de
  `getConnection() != null`.
- **Nao degradar Formatado cortando caractere.** `"9999 / 9999"` cortado vira `"1234 /"`, que e
  uma leitura plausivel e errada de um recurso. Degradar o formato: inteiro -> so o valor -> corte.
- Corte silencioso e pior que corte com reticencias: sem a marca, o usuario le um truncado como
  se fosse o nome completo. Usar 3 pontos, que e o glifo mais barato da fonte (6px).
- `splitEnvironmentSourceSets()` no `build.gradle` tira `src/client/java` do classpath do
  servidor dedicado: `static` de tela e por JVM de cliente, um jogador local. Nao ha
  compartilhamento entre jogadores.
- API 1.21.11, corrigidas por `javap` no jar: `FormattedCharSequence` esta em
  `net.minecraft.util` (nao em `network.chat`), **nao** tem `length()`, **nao** estende
  `FormattedText`, e so se percorre com `accept(FormattedCharSink)`. `FormattedText` tem
  `getString()`. `Font.split(FormattedText,int)` chama
  `getSplitter().splitLines(text, maxWidth, Style.EMPTY)`, entao `getString()` da 1a linha do
  `splitLines` e um prefixo real do texto - e o jeito correto de achar onde a 1a linha termina.
  `Font.getSplitter()` e publico.
- `javap` nao esta no PATH nesta maquina; esta em
  `C:\Program Files\Java\jdk-21.0.12\bin\javap.exe`. O jar mapped com as classes esta em
  `~/.gradle/caches/fabric-loom/minecraftMaven/.../minecraft-common-1.21.11-loom.mappings.*.jar`;
  `minecraft-client-only.jar` **nao** contem as classes de `network.chat`/`util`.
