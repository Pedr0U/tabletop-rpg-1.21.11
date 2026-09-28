# Corrige o item sheet_editor e adiciona aceleracao por segurada

**Data:** 28/09/2026
**Escopo:** dois bugs de item, uma feature nova, um catalogo. Nenhuma mudanca de
protocolo, persistencia, mixin ou schema.

## Objetivo

Duas entradas do usuario, na mesma sessao:

1. **Item `sheet_editor`:** o sprite e o nome nao tinham sido corrigidos, apesar de
   eu ter marcado isso como resolvido no commit `988335c`. O usuario testou e
   confirmou que **nao mudou**.
2. **Feature:** aceleracao por segurada nos botoes `+`/`-` de **atributos** e
   **pericias** da ficha, com progressao, para nao exigir um clique por
   incremento. **HP/Mana explicitamente fora do escopo**, porque o modelo de vida
   e mana vai mudar em breve.

## Correcao do item: dois bugs com a mesma raiz

### Sprite: o modelo nunca foi lido

O Minecraft 1.21.11 carrega o modelo de item **somente** de
`assets/<namespace>/items/<id>.json`. Confirmado no jar nomeado:
`ClientItemInfoLoader` usa `FileToIdConverter.json("items")` e **nao ha fallback
legado** em runtime. `bakedItemStackModels` so e populado dali, e a busca e
`getOrDefault(id, missingModels.item())`.

O arquivo `assets/tabletop-rpg/items/sheet_editor.json` **nunca existiu** em
nenhum commit. O `models/item/sheet_editor.json` que eu alterei no commit
`988335c` estava correto e **nunca foi lido pelo jogo**. Por isso o sprite nao
mudou e nao mudou agora tambem.

**Erro meu:** eu alterei o arquivo errado, nao verifiquei qual arquivo o jogo
realmente lê, e declarei corrigido. A verificacao exigia olhar o jar.

**Detalhe que economiza tempo futuro:** a ausencia e **silenciosa**. O loader so
loga warning quando um arquivo **existe** e falha ao parsear; arquivo faltante
nunca chega ao parser. Nao ha mensagem no log que aponte para isso.

**Correcao:** criado `src/main/resources/assets/tabletop-rpg/items/sheet_editor.json`
apontando para `tabletop-rpg:item/sheet_editor`, espelhando o vanilla
`assets/minecraft/items/writable_book.json`.

### Nome: hifen no namespace

`Item$Properties.method_63688` chama `Util.makeDescriptionId("item", key.identifier())`,
que concatena `namespace + "." + path` **preservando o hifen**. A chave real e
`item.tabletop-rpg.sheet_editor`. O `en_us.json` tinha
`item.tabletoprpg.sheet_editor`, sem hifen, e o jogo mostrava a chave crua.

**Correcao:** chave do item passou a ter o hifen.

**Auditoria das outras 35 chaves** (feita por subagente, parser de JSON, nao a
olho): nenhuma outra quebrada. O arquivo tem dois padroes misturados e **ambos
estao corretos**: chaves derivadas automaticamente do id (`item.tabletop-rpg.*`,
`key.category.tabletop-rpg.rpg`) e chaves passadas como literal no codigo
(`screen.tabletoprpg.*`, `key.tabletoprpg.*`, `itemGroup.tabletoprpg.*`).
Em particular, `item.tabletoprpg.sheet_editor.denied` esta correta **e deve
permanecer sem hifen**, porque `ModItems.java:119` passa a string como literal
para `Component.translatable`. Esse detalhe e facil de "arrumar errado" depois.

## Feature: aceleracao por segurada

### Desenho

Novo `src/client/java/com/pedro/tabletoprpg/client/HoldStepButton.java`
(`extends Button`), usado so em `StatusScreen.addAttributeRow` e
`StatusScreen.addPericiaRow`. `addResourceRow` (HP/Mana) fica com `Button`
comum.

Perfil: passo imediato, **400 ms** de silencio, **10 passos/s**, rampa linear ate
**25 passos/s** em **2 s** (2,4 s ate o teto). 20 Hz.

### Fatos de API que viabilizaram ou quase impediram

- `Button` e **`abstract`**, nao `final`: subclassing sem mixin.
- `AbstractButton.renderWidget` e **`protected final`**: so da para implementar
  `renderContents`, cujo corpo do vanilla foi copiado literalmente.
- `AbstractWidget` **nao tem** `tick()`: criei `tick()` em `CharacterSheetScreen`
  que percorre `children()`. Ele **nao existia antes** (verificado), entao nao
  duplicou.
- O release **nao testa `isMouseOver`** em 1.21.11: e roteado pelo **foco**.
  "Continua valendo com o cursor fora" vem de graca.
- `MouseHandler.isLeftPressed()` **parece** a API certa e **nao serve**: o
  bytecode mostra que `onButton` so escreve o campo quando `screen == null &&
  getOverlay() == null`, ou seja, morreria no primeiro tick com a ficha aberta.
  A solucao foi `GLFW.glfwGetMouseButton(Window.handle(), 0)` direto.

### Defeito que a revisao achou e que teria matado a feature

**O eco do servidor apagava o valor otimista a cada passo.** Cada passo da rajada
envia um payload; cada payload gera um `broadcastSheet`; cada eco voltava e
limpava o valor otimista. Com ~50 ms de ida e volta e 25 passos/s, o eco do
passo N chegava quando o cliente ja estava no passo N+3. O proximo passo
recalculava de um valor velho e **reenviava um valor menor** que o ja gravado: o
numero subia e voltava na tela, e a aceleracao **nao se materializava**.

**Correcao:** o eco so limpa o pendente quando o autoritativo **iguala** o
pendente. Enquanto a rajada esta a frente, o pendente e mantido. Como o servidor
aplica em ordem, o ultimo eco iguala o ultimo enviado e a reconciliacao se
resolve sozinha, sem timeout. Pende fora da faixa legal e descartado, para o
cliente nunca mostrar um numero invalido preso.

A limpeza de **desconexao** e de **troca de ficha alvo** continua total, por
`onModelChanged` e por tela nova. Desconexao nao tem `clear()` explicito porque
`targetName` e `final` e a tela e destruida; adicionar seria codigo morto.

### Defeito de segurada orfa

Se o release nunca chega (soltar fora da janela, `alt+tab` no meio), a rajada
seguia sozinha **ate o teto, gravando no servidor**. Corrigido com watchdog no
`tick()`: se a rajada esta ativa e o GLFW nao reporta o botao esquerdo
pressionado, cancela.

### Detalhe de som

`playDownSound` e chamado por `AbstractWidget.mouseClicked`, e as repeticoes
passam por `tick()` -> `onPress`, que **nao** passa por `mouseClicked`. Entao o
som do clique inicial intacto e as repeticoes sao silenciosas, de graca.

### Detalhe de teclado

`AbstractButton.keyPressed` tambem chama `onPress` (Enter/Espaco no botao
focado) e em 1.21.11 **nao ha rota de release por teclado** que chegue ao botao.
Sem guarda, um Enter segurado rampava ate o teto. `onPress` so abre rajada com
`input instanceof MouseButtonEvent`; o passo por teclado continua normal.

## Decisoes do usuario (perguntadas, nao assumidas)

| Pergunta | Resposta |
|---|---|
| Agrupar os passos em payload de lote? | **Nao**, aceitar o trafego. Cada passo = 1 broadcast da ficha inteira. |
| Vale para o Mestre editando outro jogador? | **Sim**, e a mesma tela. |
| Shift como passo grande? | **Nao**, por enquanto. |

O custo de rede foi registrado como risco e o usuario aceitou. Nao ha limite de
taxa no receptor: da esquerda ao teto sao ~60 envios, com a ficha completa
serializada para o dono e para cada Mestre conectado.

## Validacoes

| Verificacao | Resultado |
|---|---|
| `gradlew build --no-daemon` | `BUILD SUCCESSFUL` |
| `scanEncoding` | `OK: 91 arquivo(s)`, 0 mojibake, 0 ideograma, 0 U+FFFD |
| `check-catalogo.ps1` | `OK: nenhuma divergencia mecanica` |
| Revisao independente | 0 bloqueadores; 2 importantes e 1 nit, os 3 corrigidos |

**Erro meu no caminho:** escrevi caracteres chineses em `project-memory.md` e o
`scanEncoding` derrubou o build. Corrigido. O gate funcionou como deveria.

**NAO validado em jogo:** nada. Nem o sprite, nem o nome, nem um unico segundo
de segurada. Build e revisao nao pegam pixel nem ritmo de rajada.

## Pendencias

- **Teste em jogo do item:** confirmar o icone de livro com pena e o nome
  "Sheet Editor" no inventario e no criativo.
- **Teste em jogo da rajada:** sentir o atraso de 400 ms e a rampa; segurar ate
  o teto e ver que **para** e que o numero **nao volta**; soltar com `alt+tab` no
  meio; confirmar que HP/Mana continuam 1 clique por passo.
- **Teste do vazamento entre mundos:** nada mudou em disconnect nesta sessao,
  mas o mapa otimista passou a sobreviver mais tempo por eco. Vale reconferir.
- **id estavel em `PericiaDef`:** confirmado pelo usuario em jogo (renomear zera
  valor e perde atributo). Continua aprovado e **nao executado**. E o proximo
  trabalho grande.
- **Data de referencia do catalogo** continua apontando para a FASE 3 de
  27/09/2026, Discussao para depois.
- **Reconciliacao aplica a faixa de atributos a todo `pendingNumeric`**, HP/Mana
  incluidos. Como HP/Mana nao tem rajada, nao ha pendente aFrente para sustentar,
  e a igualdade normal resolve. Documentado no Javadoc, mas e um detalhe que
  merece um olhar se o modelo de vida/mana mudar.

## Aprendizado

Eu declarei o sprite corrigido sem verificar qual arquivo o jogo lê. O custo foi
uma rodada de teste do usuario jogada fora. **Em mod, "alterei o arquivo certo"
e "o jogo lê esse arquivo" sao afirmacoes diferentes**, e a segunda precisa de
evidencia do jar. Vale procurar `javap` no cache do Loom como padrao antes de
afirmar que um recurso de asset esta aplicado.
