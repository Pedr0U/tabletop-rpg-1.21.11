# Relatorio 28/09/2026 - Cinco mudancas na ficha, no menu de skills e no Sheet Editor

**Estado: 5 de 5 itens implementados e TESTADOS EM JOGO pelo usuario em 29/09/2026, com uma
correcao pedida e ja aplicada (titulo da secao da ficha). Build verde, catalogo atualizado,
nada commitado.** O ultimo checkpoint continua sendo o C05 (`d29d5ee`), porque o usuario
pediu que o commit so venha quando ele pedir. Working tree sujo: 9 arquivos de codigo,
`FUNCIONALIDADES-E-COMANDOS.md` e a memoria do projeto, mais este relatorio (novo).

## Objetivo

Cinco mudancas que o usuario chamou de pequenas, avisando que as proximas serao maiores e
ficarao separadas:

1. Campo "Player" na frente do bloco Identity da ficha, com caixinha para o nome do
   jogador dono da ficha, editavel.
2. Quadrado um pouco mais escuro na parte de baixo do menu de skills, separando a lista de
   skills criadas da area de descricao.
3. Botao para editar a skill selecionada no menu de skills.
4. Campo de valor maximo dos atributos e pericias no Sheet Editor (padrao: atributo de -30
   a 30, pericia 30).
5. Em vez de nao deixar colocar nomes de pericias iguais, deixar colocar, porem nao deixar
   salvar e avisar "nome de pericias iguais".

## Divergencias entre o que o usuario descreveu e o que o codigo faz

- A "ficha" e a `StatusScreen`. O primeiro campo e o nome do personagem com rotulo
  configuravel (padrao "Name"); o bloco se chama `Identity` no `SheetData`. **Nao existe
  `EditBox` de nome de pericia na ficha**: o nome vem do modelo.
- A recusa de nome repetido acontece no **Sheet Editor** (`SheetModel.withPericiaText`),
  e o botao que trava e o **Save**. O item 5 foi confirmado com o usuario como sendo do
  Sheet Editor, e nao da ficha.
- O item 4 nao era pequeno: os tetos eram constantes fixas em `SheetData`, lidas por cerca
  de 12 pontos da ficha, entao os limites precisaram virar dado do modelo.

## Decisoes do usuario, tomadas antes de codar

- Item 5: **no Sheet Editor**, que e onde a caixa hoje volta sozinha e existe o Save.
- Item 3: **reaproveitar o rodape**. O botao Edit carrega nome e descricao nas caixas que ja
  existem e o Add vira Save, substituindo a skill no mesmo lugar da lista.
- Item 4: **3 campos**: minimo do atributo (-30), maximo do atributo (30) e maximo da
  pericia (30). O minimo da pericia continua fixo em 0.
- Escopo: **os 5 itens**, com o commit so quando o usuario pedir.

## Implementado

### Item 4 - limites de valor configuraveis (era o item grande)

- `SheetModel` ganhou 3 `int` no fim do record (`attributeValueMin`, `attributeValueMax`,
  `periciaValueMax`), 3 defaults (-30, 30, 30), `withValueLimits`, 3 chaves NBT com
  `optionalFieldOf` e 3 `VAR_INT` no encoder **e** no decoder.
- `SheetData` ganhou os mesmos 3 `int`, e o **clamp autoritativo foi para o construtor
  compacto do record externo**: ele percorre atributos e pericias e limita cada valor.
  Assim `withField`, `withPericiaValue`, `mutatePericia`, `align` e a leitura do NBT ficam
  limitados de uma vez so, sem mexer em cada call site.
- Os records internos perderam os limites de regra e ganharam teto absoluto largo
  (atributo -999..999, pericia 0..999), porque o limite de regra passou a vir do modelo.
- `SheetModel.align` reescreve os 3 limites na ficha que reconstroi, entao **baixar o teto
  tambem corta valores ja salvos**.
- Cliente: cerca de 12 pontos que liam as constantes passaram a ler os limites da ficha,
  com fallback ao default quando a ficha ainda nao chegou.
- `SheetEditorScreen` ganhou a secao "Value limits", entre a secao de XP e a de atributos,
  com 3 `EditBox` numericos, filtro de digitos, guarda de numero completo e 3 `Slot`.
- `SheetData.STREAM_CODEC` virou encoder/decoder manual: o record passou de 6 para 9
  componentes e `StreamCodec.composite` nao passa de 6. Mesmo caminho que o `SheetModel` ja
  usava.

### Item 5 - nome de pericia repetido no Sheet Editor

- `SheetModel.withPericiaText` nao recusa mais nome repetido; continua recusando vazio.
- `SheetEditorScreen.periciaRow` nao reverte mais a caixa; `hasDuplicateName()` desabilita o
  `Save`; `canDiscard()` passou a considerar o caso, senao o Mestre ficaria preso; o aviso
  novo entrou na cadeia entre "pericia without a name" e "unsaved changes".
- Chave nova `screen.tabletoprpg.sheet_editor.pericia_duplicate_name` = "duplicate pericia
  name".

### Item 2 - quadrado mais escuro na descricao

- `SkillsScreen`: cor nova `COL_DESC_BG = 0xF2090A0E` e `renderDescBackdrop`, desenhada
  **antes** do early return de "sem selecao", para o separador existir sempre. Nenhum
  calculo de layout, clique ou arrasto foi tocado. `COL_TOOLTIP_BG` ficou intacta de
  proposito, porque e compartilhada com o tooltip de hover.

### Item 1 - campo Player na ficha (feito depois do corte)

- `SheetData.Identity` ganhou `playerName` como **quinto** componente, no fim, com
  `clean(..., MAX_NAME, "")` no construtor compacto; vazio continua sendo valor legitimo.
- Persistencia: chave `playerName` com `optionalFieldOf(..., "")`, o que mantem a leitura
  das fichas salvas antes desta mudanca, e o quinto par do `STREAM_CODEC` do record.
- `SheetData` ganhou `TEXT_FIELDS` e `LABELLED_FIELDS` (listas de campos known e com
  rotulo), `getText`, e o caso `playerName` em `withField`, no mesmo estilo dos casos
  existentes.
- `SheetModel.labelOf` passou a devolver o literal **"Player"** para `playername`. Vale
  registrar o porque de nao existir chave de traducao: a ficha **nao usa** chave nenhuma
  (todo texto e literal em ingles no Java), e `SheetData.labelOf` pergunta ao modelo
  primeiro, com o `default` do modelo devolvendo a propria chave. Um caso novo ali
  seria codigo morto.
- `StatusScreen.buildPanel` ganhou uma linha nova **antes** do campo de nome do
  personagem, por `addField("playerName", ...)`, com caixa estreita
  (`playerBoxW = max(60, boxW / 2)`) e o `neededRows` somado em 1. O campo sai pelo mesmo
  `SheetFieldPayload` dos outros, entao herda `canEditSheet`: **dono ou Mestre**.
- Construtores e `defaultSheet` foram atualizados em bloco, para o novo componente nao
  depender de overload.

### Item 3 - botao Edit para a skill selecionada (feito depois do corte)

- `SheetData` ganhou `SkillOp.UPDATE` e `withSkill(int, String, String)`, que substitui a
  skill **no indice dado**, sem perder a ordem da lista. Renomear para um nome que outra
  skill ja usa e recusado, porque `sanitizeSkills` mantem a primeira e o resultado viraria
  dependente da ordem.
- `RpgNetworking`: `SheetSkillPayload` ganhou o campo de indice, a factory `update(...)` e
  o receptor `updateSkill(...)`. O servidor **recusa em silencio** se o indice estiver fora
  da faixa ou se **o nome em diante nao bater** com a skill naquele indice, que e a defesa
  contra quem reordena a lista entre o clique e o Save. O `VAR_INT` do indice foi para o
  codec como quinto par do `composite`, e o opcoal do somatorio de tamanho tambem.
- `SkillsScreen`: o rodape passou a ter `Edit` e `Add` lado a lado (`editW = max(24,
  footerW / 4)`, o resto para o `Add`); o `Edit` carrega nome e descricao da skill
  selecionada nas duas caixas que ja existiam, o `Add` vira `Save`, e um segundo clique no
  `Edit` e o `Discard`. O `Edit` fica cinza sem selecao ou sem permissao. O modo de edicao
  **nao sobrevive a um `init()`**, entao resize volta ao modo `Add` — um Save sobre texto
  que o jogador nao digitou seria pior do que voltar ao começo. A selecao e guardada por
  **nome** e o indice e **reancorado pelo nome** a cada ficha que chega
  (`onSheetReceived`), porque o indice e o que impede o `Edit` de carregar e o `Save` de
  gravar na skill que deslidou de posicao depois de um add, remove ou move. O `Save` em
  modo edicao nao limpa as caixas: a saida vem do estado autoritativo do servidor.

## Arquivos alterados (9 de codigo, nenhum novo)

| Arquivo | Itens |
|---|---|
| `src/main/java/com/pedro/tabletoprpg/SheetModel.java` | 1, 4, 5 |
| `src/main/java/com/pedro/tabletoprpg/SheetData.java` | 1, 3, 4 |
| `src/main/java/com/pedro/tabletoprpg/RpgNetworking.java` | 3, 4 (o log do save ganhou os limites) |
| `src/client/java/com/pedro/tabletoprpg/client/StatusScreen.java` | 1, 4 |
| `src/client/java/com/pedro/tabletoprpg/client/CharacterSheetScreen.java` | 4 |
| `src/client/java/com/pedro/tabletoprpg/client/SheetEditorScreen.java` | 4, 5 |
| `src/client/java/com/pedro/tabletoprpg/client/SkillsScreen.java` | 2, 3 |
| `src/main/resources/assets/tabletop-rpg/lang/en_us.json` | 4 chaves + 1 |
| `src/test/java/com/pedro/tabletoprpg/SheetModelCodecTest.java` | 4, 5 |

Fora do `src/`: `FUNCIONALIDADES-E-COMANDOS.md` (versionado) e
`agent/memory/project-memory.md`.

## Validacoes executadas

- `.\gradlew.bat build --no-daemon --console=plain` apos os itens 4 e 2: **BUILD SUCCESSFUL
  em 12s**; `scanEncoding` OK, 95 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD.
- `.\gradlew.bat test --tests "*SheetModelCodecTest*"`: 20 testes, 1 falhou (causa abaixo).
- `.\gradlew.bat build --no-daemon` com o item 5: **BUILD SUCCESSFUL em 19s**, `scanEncoding`
  OK, nenhum teste falhando.
- `.\gradlew.bat build --no-daemon --console=plain` **final, com os 5 itens**:
  **BUILD SUCCESSFUL em 19s**; `scanEncoding` OK, **96 arquivos**, 0 mojibake, 0 ideograma,
  0 U+FFFD; nenhum teste falhando. O detector de encoding roda dentro do build, entao o
  relatorio e a memoria deste arquivo entram na conferencia.
- `check-catalogo.ps1`: **OK, nenhuma divergencia mecanica** (17 executaveis, 19 citados,
  2 teclas, 9 telas), antes e depois da edicao do catalogo.
- `git diff --check`: limpo, sem whitespace quebrado. Os avisos de LF/CRLF do git sao
  esperadissimos no Windows e nao sao erro.
- **Nada validado em jogo.** Nenhuma `runClient` nesta rodada.

## Problemas encontrados, causa e solucao

1. `SheetModelCodecTest.java:101` nao compilava: `encodeStart` devolve `Tag`, e o teste
   declarava `CompoundTag`. **Causa raiz:** `Codec.encodeStart` devolve `DataResult<Tag>`, e
   so o `parse` aceita `Tag`; `CompoundTag` e apenas o tipo concreto do NBT gravado.
   Correcao: variavel do tipo `Tag` mais o import de `net.minecraft.nbt.Tag`.
2. O teste "intervalo invertido" falhou com `expected: <7> but was: <10>`. **O teste estava
   errado, nao o codigo:** com min 10 e max 5 o intervalo vira `[10,10]`, entao o 7 esta
   **fora** dele e 10 e o unico lugar legitimo para parar. A expectativa foi trocada pelo
   invariante que importa: o intervalo invertido existe e corta para o piso, em vez de virar
   intervalo vazio (o sintoma seria o atributo do Mestre pular de 10 para outro numero).

## Limitacoes e riscos conhecidos

- **Isto foi visto em jogo em 29/09/2026.** O usuario testou os 5 itens e disse que "o
  restante ta funcionando normal"; a unica correcao pedida foi o titulo da secao, feita no
  mesmo dia e registrada na atualizacao do fim deste relatorio. As duas hipoteses visuais
  (a cor `0xF2090A0E` e a meia largura do campo Player) nao foram reclamadas, entao o
  usuario nao reclamou delas, o que nao e o mesmo que uma medicao.
- **Revisao de codigo dos itens 1 e 3 feita pelo subagente, nao linha a linha pelo agente
  principal.** O que sustenta os dois itens hoje e o build verde mais os testes de codec e
  o `git diff --check`. Antes do commit vale uma passada de `tcc-validador` no diff.
- **Item 3 depende de reordenacao durante o Save:** o servidor protege pelo nome, e o
  cliente reancora o indice pelo nome, mas o caminho feliz nunca foi exercitado em jogo.
- **Item 1 aumenta a altura da ficha em uma linha.** `StatusScreen.buildPanel` soma 1 em
  `neededRows`; em resolucao muito baixa a coluna ja rolava, entao o risco e a rolagem
  ficar maior, e nao quebrao de layout.
- **Assimetria de UX no item 4:** a caixa do maximo do atributo nao aceita `-`, entao um
  intervalo todo negativo (piso -5, teto 0) nao pode ser digitado no campo do teto. O clamp
  do modelo produz o mesmo intervalo efetivo, entao funciona, mas e assimetrico.
- **Os limites passam a ser gravados tambem no NBT de cada jogador** (3 chaves
  `optionalFieldOf`), porque `SheetData` e quem carrega os limites. `align` os reescreve a
  partir do modelo, entao o modelo continua sendo a fonte e o NBT da ficha e uma copia.
- **`SheetData.defaultSheet` le o modelo por `SheetModelHolder`, que e estatico e nao e
  resetado entre testes.** Os testes passam nesta ordem, mas e risco latente.
- **Com dois nomes de pericia iguais, `/rpg roll <pericia>` rola a primeira** das duas:
  `MasterCommands.findPericia` devolve a primeira com o nome normalizado. Registrado, nao
  corrigido, e agora mais facil de chegar nisso, porque o repetido passa a ser aceito.
- **`suppressNotify` virou codigo morto** no `SheetEditorScreen` depois do item 5: sobraram
  a declaracao e 5 guards que nunca sao verdadeiros, e o Javadoc do campo ainda descreve o
  laco de eco que nao existe mais. O Javadoc do `periciaRow` tambem ainda cita o ramo de
  reversao removido. Os dois sao limpeza de comentario.
- Nenhuma regra de permissao nova: o campo Player do item 1 herda `canEditSheet` (dono ou
  Mestre) pelo `SheetFieldPayload`; o `Edit` do item 3 reaproveita a checagem que o `Add` ja
  fazia.

## Aprendizados

- **FATO:** `StreamCodec.composite` tem teto de 6 campos. Passar disso exige encoder e
  decoder manuais, como o `SheetModel` ja fazia. O Javadoc do `SheetModel` avisa que encoder
  e decoder sao lambdas independentes: acrescentar em um lado so compila e quebra em
  runtime. Aqui os dois lados foram conferidos, 3 `VAR_INT` na mesma ordem nos dois.
- **FATO:** acrescentar um campo **no fim** de um `StreamCodec.composite` e simetrico por
  construcao, porque `composite` pareia encoder e decoder posicao a posicao. O risco de
  dessimetria dos `VAR_INT` manuais e especifico daquele arquivo e nao se aplica quando o
  codec e um `composite`. Ainda assim, campo no fim e a unica ordem que sobrevive a uma
  base ja em uso.
- **FATO:** o clamp mais barato de colocar num record do Minecraft e no construtor compacto
  do record **externo**, e nao no de cada record interno. Com os limites como campos de fora,
  todo caminho que reconstroi a ficha fica limitado de uma vez, inclusive a leitura de NBT
  de um mundo velho. Nao preciso tocar em cada `withX`.
- **FATO:** `sanitizePericias` deduplica por **id**, nao por nome. Por isso dois nomes
  iguais com ids diferentes ja coexistiam no modelo, e o unico obstaculo era a recusa no
  `withPericiaText` mais a reversao da caixa na tela. A recusa de renomear para um nome
  ocupado tambem segue dessa regra: o que sobrevive e a primeira.
- **FATO:** as telas da ficha nao usam chave de traducao: todo texto e literal em ingles no
  Java. So o Sheet Editor tem chaves `screen.tabletoprpg.sheet_editor.*`.
- **FATO:** `FUNCIONALIDADES-E-COMANDOS.md` **esta versionado** neste repositorio
  (`git status` mostra ` M`). A skill `catalogo-sync` afirma que o arquivo nao esta no git
  e que nao ha como recuperar a versao anterior; isso **nao e verdade** aqui, e muda o
  cuidado: uma sobrescrita errada do catalogo se recupera pelo git.
- **HIPOTESE (nao verificada):** `0xF2090A0E` contra o painel `0xF216161C` deve ser
  perceptivel, por ser mais escuro no canal azul. So o jogo confirma.
- **HIPOTESE (nao verificada):** o campo `playerName` na frente do nome do personagem fica
  legivel com meia largura e piso de 60px. So o jogo confirma, e so em resolucao baixa o
  risco aparece.

## Atualizacao 29/09/2026: teste em jogo e correcao do titulo da secao

O usuario testou os 5 itens em jogo e pediu **uma** correcao: o titulo da secao da ficha,
que aparecia como `Name`, deveria voltar a `Identity`.

**A premissa do relato nao bate com o codigo, e a causa nao foi o campo Player.** Nao existe
literal `"Identity"` em nenhum ponto do codigo, nem no `HEAD` (commit `d29d5ee`): o titulo
era `y = addSection(model.nameLabel(), x0, y, leftW)`, e o default de `nameLabel` e
`"Name"` (`SheetModel.java: SheetModel`, no construtor compacto e no
`optionalFieldOf("nameLabel", "Name")` do codec). A linha **nao aparece no diff** desta
rodada. Os outros tres titulos — `Vitals`, `Progress`, `Attributes` — ja eram literais, entao
aquele era o unico que mostrava o rotulo do **campo** no lugar do nome da **secao**; com o
modelo padrao, a ficha mostrava `Name` como titulo e `Name:` como primeiro campo.

**Correcao:** o titulo virou o literal `"Identity"`, igual aos outros tres. Uma linha em
`StatusScreen.java: buildPanel`, com o comentario explicando a troca. O que continua
vindo do modelo e o rotulo do **campo** (`SheetData.labelOf`), que e o que o Mestre
renomeia no Sheet Editor: titulo de secao e rotulo de campo sao coisas diferentes. Efeito
colateral aceito: com um `nameLabel` customizado, o titulo da secao passa a dizer
`Identity` enquanto o campo diz o nome customizado.

**Nao houve mudanca no catalogo** por causa disso: `FUNCIONALIDADES-E-COMANDOS.md` nao
documentava a origem do titulo da secao, entao segue correto como esta.

- `.\gradlew.bat build --no-daemon --console=plain` depois da correcao: **BUILD SUCCESSFUL
  em 22s**, `scanEncoding` OK em 96 arquivos, nenhum teste falhando.
- **Esta correcao ainda nao foi vista em jogo.** E uma mudanca de texto literal, o risco e
  baixo, mas o portao continua sendo o usuario abrir a ficha e ver `Identity` no titulo.

## Proximos passos

1. **Teste em jogo dos 5 itens**, que e o portao que falta:
   - ficha: o campo `Player` aparece antes do nome, com a caixa estreita; dono e Mestre
     editam; o valor sobrevive a reconectar; ficha antiga continua abrindo com o campo
     vazio;
   - item 4: mexer nos 3 limites, ver valor salvo sendo cortado ao baixar o teto, e
     reconectar para ver persistir;
   - item 5: digitar pericia repetida, ver o aviso, ver o `Save` travado e `Discard`
     destravando a tela;
   - item 2: o quadrado escuro aparece **com e sem** skill selecionada, e a cor fica
     perceptivel;
   - item 3: `Edit` carrega nome e descricao, `Save` atualiza **no mesmo lugar** da lista,
     `Add` volta ao normal, e o botao fica cinza sem selecao.
2. Passada de `tcc-validador` no diff antes do commit, principalmente nos itens 1 e 3.
3. Commit e tag so quando o usuario pedir, e ai registrar o C06 em
   `GitHub/agent/VERSIONAMENTOS.md`.
