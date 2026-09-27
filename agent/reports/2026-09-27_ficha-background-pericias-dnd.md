# 2026-09-27 — Ficha: `Background`, perícias de D&D 5e e botões `-`/`+` (partes 1 e 2)

**Status:** código e catálogo concluídos; **build verde**; **validação em jogo PENDENTE do usuário**.

## Objetivo

Entregar as duas primeiras partes do pedido de evolução da ficha, e deixar a terceira
(item de edição do modelo global) separada para depois.

## Escopo entregue

**Parte 1 — UX dos valores**
- Troca dos glifos `>` e `<` por botões **`-`** e **`+`** em atributos, perícias, HP e Mana.
  (HP e Mana não estavam no pedido; entraram por consistência visual — o mesmo par de
  botões na mesma coluna. **Mudança além do escopo pedido, entregue de propósito e
  destacado aqui para o usuário poder rejeitar.**)
- Teto de **30** nos atributos e nas perícias, com o `+` **cinza/desligado** ao chegar nele
  e o `-` desligado no piso 0 das perícias. O servidor é quem garante o limite
  (`SheetData.Attributes`, `SheetData.Pericia`); o cinza é só o feedback.
- A largura da caixa de número passou a ser calculada a partir do teto
  (`StatusScreen.valueBoxWidth`), então **dois dígitos aparecem**. Antes o texto era cortado
  por `font.plainSubstrByWidth` com largura fixa, e o `10` era exibido como `1` — bug real,
  corrigido.
- Cabeçalho **`Bonus`** acima da coluna de valores das perícias: o número ao lado do nome é
  o bônus investido, não o resultado da rolagem (que ainda soma o atributo).

**Parte 2 — `Background` e perícias**
- `Background` como campo de texto livre da identidade, gravado junto com os outros campos.
  Não tem efeito mecânico: é um rótulo que o Mestre consulta.
- As 16 linhas de espaço reservado (`skill 0` a `skill 15`) foram substituídas pelas
  **18 perícias básicas de D&D 5e em inglês**, cada uma com o atributo correspondente.
  Entregue primeiro com 20 linhas — as 18 de D&D mais `Initiative` e `Melee` — e depois
  corrigido para **só as 18**, por decisão do usuário. Motivo da correção: conferi no código
  que a perícia `Initiative` **não é lida em lugar nenhum de `src/`** (o modo combate não
  consulta a ficha para ordenar turnos) e que `Melee` só duplicava `Athletics` (FOR) com um
  nome que o D&D não usa para teste de habilidade. Meu argumento inicial para mantê-las não se
  sustentava.

## Layout em janela baixa — achado da revisão e decisão do usuário

A revisão independente achou que a coluna esquerda **não cabe** quando a altura de linha atinge
o piso de `MIN_ROW_H` (12px). O espaço útil é `altura da tela - 86px`, e o total de linhas é
`neededRows * rowH`, com `neededRows` = 12 + linhas de atributo. Com o piso ativo:

- **480x270** (escala GUI automática padrão em monitor 1080p): 18 linhas x 12 = 216px para 184px
  disponíveis. Estouro de 32px, e as **duas últimas linhas caíam em cima do botão `Back`**, que é
  desenhado depois e por cima. **Corrigido:** a "folga de 4" do orçamento era falsa — ela não é
  desenhada, e como o espaço é dividido pelo total de linhas ela só aumentava o total, que é
  justamente o que estoura. Removida: sobram 16 linhas, o estouro cai para 8px e fica dentro do
  painel. Em janela normal `rowH` bate em `MAX_ROW_H` nos dois casos, então nada muda visualmente.
- **426x240** (escala GUI automática padrão em monitor 720p): 16 x 12 = 192px para 154px
  disponíveis. A última linha vai para fora da tela e colide com o `Back`. **Não corrigido, por
  decisão do usuário em 27/09/2026: "deixar como está".** Já estava quebrado antes desta mudança
  (era 4px de sobreposição). Quem estiver nesse caso aumenta a escala GUI nas opções de vídeo.
  As alternativas eram baixar o piso da altura de linha (linhas de ~9px, botões pequenos de
  clicar) ou rolar a coluna esquerda (mudança grande, e o usuário já pediu rolagem só para as
  perícias na fase do editor).

## Arquivos alterados

- `src/main/java/com/pedro/tabletoprpg/SheetData.java` — tetos, `Background` em `Identity`
  (codec de rede, NBT, `withField`, `labelOf`, `TEXT_FIELDS`), lista de perícias,
  `LEGACY_PERICIA_NAMES` e `matchesPericiaName`.
- `src/client/java/com/pedro/tabletoprpg/client/StatusScreen.java` — botões `-`/`+`,
  `valueBoxWidth`, cabeçalho `Bonus`, lógica de `active` em `renderContent`, campo
  `Background`, `fieldLabelWidth`.
- `FUNCIONALIDADES-E-COMANDOS.md` — lista de perícias, tetos, `Background`, cabeçalho
  `Bonus`, botões desligados, regra do apelido.

## Migração de fichas antigas — o ponto de risco real

`sanitizePericias` casa o NBT salvo com a lista atual **por nome** e, sem casamento, substitui
pelo padrão **sem erro e sem log**. Renomear uma perícia sem prover apelido zera a
perícia em silêncio — armadilha já registrada na memória do projeto.

Apelidos registrados: `Acrobacia`→`Acrobatics`, `Diplomacy`/`Diplomacia`→`Persuasion`.

**Perdas conhecidas e inevitáveis (27/09/2026):**
1. Os antigos `perícia N` e `skill N` são descartados. A tela antiga **mostrava as 20 linhas com
   `-`/`+` editáveis**, então quem ajustou `skill 3` para 5 **perde os 5**, sem erro e sem log. Não
   há linha nova que receba esse número.
2. `Melee`/`Luta` e `Initiative`/`Iniciativa` saíram do padrão, então o valor que tinham é
   descartado do mesmo jeito (2 por padrão). Nenhum apelido resolve: resolveria inventando uma
   19ª e 20ª linha que o usuário não pediu.

Minha primeira versão desta seção afirmava que a migração era "sem perda". Era falso e foi
corrigido, tanto no Javadoc quanto no catálogo.

**Colisão `Diplomacy`/`Diplomacia` → `Persuasion`:** na prática é **teórica**, porque a lista
salva é sempre sanitizada para os nomes do padrão, então os dois nomes nunca coexistem numa
ficha real. A regra de desempate está escrita no método, mas não é exercitada.

## Validações executadas

| O que | Resultado |
|---|---|
| `.\gradlew.bat build --no-daemon --console=plain` | `BUILD SUCCESSFUL` (compila servidor **e** cliente) |
| `scanEncoding` | OK, 77 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD |
| `check-catalogo.ps1` | `EXITCODE=0`, 23 literais / 17 executáveis / 19 citados, nenhuma divergência mecânica |

## O que NÃO foi validado

- **Nada foi testado em jogo.** A parte visível desta mudança (botões, cabeçalho `Bonus`,
  `Background`, a coluna com 18 perícias) só existe quando o Minecraft renderiza. Build
  verde não prova layout, e não prova que o clique no `+` desativado está barrado de
  verdade.
- **A migração não foi exercitada fora do jogo.** Tentei rodar as classes reais numa JVM
  isolada (`SheetData.defaultSheet`, `sanitizePericias`, os clamps) e **não foi possível**:
  o `SheetData` tem inicializador estático que monta o `StreamCodec`, que precisa de
  `net.minecraft.network.codec.ByteBufCodecs`, e o jar mapeado do Minecraft não existe como
  arquivo no cache do Gradle (só `minecraft-common.jar` / `minecraft-server.jar` / 
  `minecraft-client.jar` crus, com nomes ofuscados). A busca por `ByteBufCodecs.class` em
  todos os jars de `.gradle` e de `build/` não achou nada. O teste ficou escrito em
  `%LOCALAPPDATA%\Temp\opencode\TestMigracao.java` e **não roda** sem ajuste de classpath.
  A lógica foi conferida **por leitura** e por revisão independente, não por execução.
- **A aritmética de layout** foi conferida por cálculo e conferida com a conta da revisão
  independente, mas não por render.

## Revisão independente

Foi pedida revisão ao subagente `tcc-validador`, somente leitura. Ela **não achou bloqueador**
e confirmou que não há widget sobreposto, nome de perícia invadido, colisão do cabeçalho
`Bonus`, nem `active` sempre verdadeiro dentro do painel. Achou o problema de layout em janela
baixa, a documentação falsa que eu tinha escrito, e confirmou que a colisão
`Diplomacy`/`Diplomacia` é teórica.

Correções que vieram dela e foram aplicadas: layout em 480x270, cabeçalho `Bonus` de `-8` para
`-9` (folga de 1px com fonte de resource pack), guarda de truncamento no número do atributo
(valor forjado muito negativo invadiria o rótulo), parâmetros mortos de `addBonusHeader`, e
o bloco de documentação falsa em `SheetData`, `MasterCommands`, `CharacterSheetScreen`,
`StatusScreen` e no catálogo.

Um ponto do validador **não procede:** ele supôs que um clique em `+` com atributo 30 mostraria
31 por um frame. Não mostra — com o valor em 30, `plusButton().active` já é `false`, então não
há clique possível. O mesmo vale para as perícias.

## Riscos conhecidos

1. **Perda de valor na migração** (descrita acima): `skill N` com valor ajustado, e o valor de
   `Melee`/`Initiative`. Avisado, documentado e não corrigível sem inventar linhas.
2. **426x240 continua estourando** na coluna esquerda, por decisão do usuário.
3. **`-` em atributo negativo:** o atributo não tem piso, e o `-` **nunca** desliga, para
   permitir voltar de um valor muito negativo. Coerente com a regra "atributo não tem piso", mas
   o par de botões não é simétrico ali.
4. Mudança em HP/Mana além do escopo pedido (ver escopo).

## Próximos passos

1. **Usuário testa em jogo:** abrir a `Status`, conferir o cabeçalho `Bonus`, o `Background`
   gravando, o `+` desligando em 30, e o `-` desligando em 0 nas perícias; conferir numa
   ficha **antiga** se `Acrobacia` e `Diplomacy` migraram com valor preservado, e conferir
   que a lista mostra 18 linhas.
2. Se aprovado, planejar a **parte 3**: item exclusive do Mestre, salvo no mundo, com IDs
   estáveis para os rótulos, 1–10 atributos, até 30 perícias e scroll isolado na coluna
   direita. O codec de topo de `SheetData` tem limite de seis grupos, então número variável
   de atributos exige codec próprio. **Decisão pendente nessa fase:** o limite de 30 nos
   atributos é de agora; o modelo editável pode mexer nele, e aí o teto deixa de ser
   constante do código.
3. Commit e tag deste estado, **quando o usuário pedir**.
