# Ficha de ameaça ligada ao item e ao monstro (02/10/2026)

## Objetivo
O item da ficha deixa de ser só apresentação e passa a ser o controle: abre a ficha
própria para editar, amarra a ficha num monstro e dá ao Mestre o jeito de apontar esse
monstro em comando.

## Decisões do Mestre (02/10/2026, antes de implementar)
1. Botão direito **no ar, sem bloco na visão**, abre a ficha — e já vem editável.
2. Nesse caminho o botão é **"Atualizar"**: salva sem entregar outro item.
3. Amarrar **substitui** o vínculo anterior.
4. A ficha passa a exigir **nome de exibição**, e o campo **não** é mais trancado pela
   caixa "Exibir nome em cima da ameaça?": a caixa só decide se o nome aparece flutuando.
5. A identidade da ficha é um **id imutável**, não o nome.

## Arquivos
Novos: `src/main/java/com/pedro/tabletoprpg/ThreatSheetBinding.java`

Alterados: `ThreatSheet.java`, `ThreatSheetStore.java`, `RpgNetworking.java`,
`ModItems.java`, `CombatController.java`, `MasterCommands.java`,
`TabletopRpgClient.java`, `ThreatSheetScreen.java`, `lang/en_us.json`,
`agent/memory/project-memory.md`

## 1. Id imutável

`ThreatSheet.key()` é `normalizeKey(nome)`. Renomear "Goblin" para "Goblin Chefe" mudava
a chave, e o item da mochila e a tag do mob — que apontavam pela chave — ficavam órfãos.
Como o Mestre pediu vínculo, isso precisava ser resolvido antes, não depois.

`ThreatSheet` ganhou `String id` como primeiro componente. Vazio significa "ainda não
salvou"; `ThreatSheetStore` sorteia o UUID no primeiro save e **reaproveita o id
guardado** em toda edição, mesmo que o cliente tenha mandado outro. O cliente nunca
sorteia — `withId()` existe para o servidor, não para a tela.

Sem migração: a feature ainda não tinha sido validada em jogo, então não havia ficha
gravada em NBT de jogador para converter. `replaceAll` sorteia id do que chegar sem id,
para o caso de NBT escrito à mão.

O item passou a gravar `sheet.id()` no lugar de `sheet.key()`.

## 2. Abrir no ar e "Atualizar"

`ModItems.onUseItem` ganhou o ramo da ficha. O clique no ar **não tem cliente para abrir
tela** — o item carrega só o id — então o servidor resolve a ficha e manda o payload
`ThreatSheetOpenPayload` (S2C) com a ficha pronta. Isso evita dois desvios: o cliente não
tem `ThreatSheetStore`, e o item pode estar com id velho.

O `ThreatSheetSavePayload` ganhou `deliverItem`. "Salvar" (menu) continua entregando item;
"Atualizar" (aberto pelo item) grava por cima e não entrega nada, senão cada edição
multiplicaria o stack na mochila.

O `originalName` enviado pelo item é o **nome atual** da ficha, que é o que o
`ThreatSheetStore.save` usa para localizar a linha a trocar. Errar isso faria cada
"Atualizar" nascer uma ficha nova.

## 3. Vínculo com o monstro

Classe nova `ThreatSheetBinding`. O vínculo fica na **tag do mob**
(`tabletoprpg_sheet_<id>`), não no NBT do jogador: o vínculo pertence ao monstro e tem de
valer com o Mestre desconectado e depois do restart. É o mesmo caminho que o
`insertEnemy` já usa com `tabletoprpg_inserted`.

Dois mapas em memória (`mob -> id` e `id -> mob`) são **cache**, nunca fonte da verdade:
`selfHeal()` refaz varrendo as entidades carregadas, espelhando o `selfHealCameraMobs` que
o projeto já tem.

`bind()` substitui: tira a tag do vínculo anterior daquele mob e tira o id dos outros mobs
que o declaravam, para dois nunca responderem pelo mesmo nome em comando.

**Precedência:** o ramo entra no `UseEntityCallback` do `CombatController` **antes** do
`toggleSelection`, porque esse callback consome o clique e devolve `FAIL` — quem chega
antes ganha. Mesmo raciocínio do Camera Tool. Efeito colateral aceito: com a ficha na mão,
o Mestre não seleciona monstro por clique; ele usa `/rpg mob <nome>`.

O nome customizado é gravado **sempre**, e a visibilidade segue a caixa. É o que faz
`@e[name="..."]` achar o monstro mesmo com o nome escondido. O nome do mob é atualizado
nos dois caminhos de save (menu e item), senão renomear pelo menu deixaria o `@e` velho.

## 4. Nome obrigatório

Cliente avisa antes de montar a ficha (status vermelho, sem perder a edição); servidor
recusa com `EMPTY_DISPLAY_NAME`. A caixa de marcar só mexe em `showDisplayName`.

## 5. `/rpg mob <nome>`

Argumento `greedyString`, porque nome de exibição tem espaço. Casa sem acento e sem
espaço, só entre monstros **amarrados** a uma ficha (sem ficha, o nome é o do vanilla e
não distingue duas criaturas iguais). Mais de um acerto é recusado com aviso, em vez de
escolher um e o Mestre achar que escolheu.

Refatorei `toggleSelection` para extrair `CombatController.selectMob(...)`, usado pelos
dois caminhos — assim o clique e o comando produzem exatamente o mesmo estado.

## O que o vanilla já resolvia
`/tp @e[name="Goblin"] x y z` funciona sem mod nenhum, desde que o monstro tenha nome
customizado. Foi isso que motivou o nome de exibição ser obrigatório.

## Problema encontrado e corrigido no caminho
`scanEncoding` reprovou o `build` inteiro com 4 ideogramas: duas frases da memória de
ontem entraram com palavras em ideograma no meio do português (duas em "Como... rapido",
uma em "cada pixel... tela"). Erro meu, de digitação. O ideograma é hard fail e a tarefa
está ligada ao `check`, então quebrava build, não só a tarefa. Corrigido e registrado na
memória. O exemplo acima reproduzia os caracteres, e **reproduzir o defeito reprova o
build de novo** — descrever sem colar.

Também: um resíduo de edição deixou uma chave `}` sobrando em
`saveThreatSheetFromScreen` e o compilador acusou `illegal start of type` — foi o
`compileJava` pegando, não o `build`.

## Validação
- `compileJava` + `compileClientJava`: **BUILD SUCCESSFUL em 10s**.
- `build` completo: **BUILD SUCCESSFUL em 12s**, com `scanEncoding` OK (166 arquivos, 0
  mojibake, 0 ideograma, 0 U+FFFD).
- **Não validado em jogo.** Nada disto foi aberto no cliente ainda.
- Verificado no código, não em jogo: ordem dos `UseEntityCallback`
  (`PlayerControlHandler` só recusa quem não pode interagir, e o Mestre sempre pode).

## Pendente de teste do Mestre
1. Botão direito no ar com a ficha → abre editável, botão "Atualizar".
2. "Atualizar" → salva e **não** cria item novo.
3. Botão direito num monstro com a ficha na mão → mensagem de vínculo, nome em cima se a
   caixa estiver marcada.
4. Rebind em outro mob → o primeiro solta.
5. Renomear a ficha → item e vínculo continuam válidos.
6. `/rpg mob <nome>` → seleciona.
7. `/tp @e[name="<nome de exibição>"] <x> <y> <z>` → acha o monstro.
8. Reiniciar o servidor → o vínculo continua (tag) e `/rpg mob` ainda acha.
9. Salvar com nome de exibição vazio → recusa nos dois lados.

## Próximos passos
- Comando de iniciativa (não existe ainda; o Mestre pediu para lembrar).
- Textura provisória do item ainda é um retângulo branco.
- `FUNCIONALIDADES-E-COMANDOS.md` desatualizado e `HANDOFF.md` intocado, por decisão do
  Mestre.

## Rodada 2 -- teste em jogo, dois defeitos reais (02/10/2026)

O Mestre testou em jogo e REPORTOU QUE A CORREÇÃO ANTERIOR ESTAVA ERRADA. Registro aqui porque
o raciocínio que escrevi antes era o oposto do que acontece.

### Defeito 1: o item abria a ficha ao mesmo tempo que amarrava
- **Causa raiz:** eu havia argued que o `UseEntityCallback` devolvendo `FAIL` impediria o
  `UseItemCallback`. Não impede. Os dois chegam em pacotes separados e a ordem não é garantida.
- **Correção:** os dois caminhos passam a consultar a mesma decisão de mira. Se há criatura sob a
  mira, amarra; se não há, abre. A ordem dos eventos deixou de importar.
- **Ray trace:** reescrito com `ProjectileUtil.getEntityHitResult(...)`, o mesmo padrão que o
  highlight do cliente já usava. A versão com maths própria não compilava
  (`AABB.expandTowards` e `Vec3.clamp` não existem em 1.21.11).
- **Log:** os dois caminhos registram em `[TabletopRPG]`, para o próximo teste dizer qual
  disparou em vez de eu inferir.

### Defeito 2: "Atualizar" não substituía a ficha vinculada no mob
- **Causa raiz:** `RpgNetworking` usava a ficha enviada pelo cliente depois de gravar. O store é
  quem manda no id (reaproveita o guardado ou sorteia um novo quando a ficha é nova), então o id
  do cliente pode estar vazio ou desatualizado. O `mobOf` procurava uma tag inexistente, o mob
  não era renomeado, e o item entregue nascia com id morto.
- **Correção:** reler a ficha do store pelo `key()` após o save e usar essa em todos os caminhos.
- **Causa raiz provável do nome no mob virar UUID:** não confirmada. O `displayName` da ficha
  passou a conter o id. Pode ser o Mestre ter digitado o UUID no campo para tentar fazer o
  seletor funcionar. **Sem dado do usuário, não dá para afirmar.**

### Identificação: o que foi decidido
- `@e[name="..."]` é identificador **humano**: quebra ao renomear a ficha e colide com homônimos.
- A **scoreboard tag** `tabletoprpg_sheet_<id>` já é gravada na amarra e **não muda** quando a
  ficha é editada. É a chave confiável, e funciona hoje sem código novo.
- O Mestre conheceu a opção de encurtar a tag e **optou por manter como está**.
- Falha silenciosa virou recusa explícita: amarrar ficha sem nome de exibição agora diz para
  preencher o campo e clicar em Atualizar, em vez de deixar `@e[name=""]` falhar sem explicação.

### Validação
- `build` verde, `scanEncoding` OK (167 arquivos, 0 mojibake/ideograma/U+FFFD).
- Cliente relançado, mod inicializado sem crash nem erro de mixin.
- **NÃO validado em jogo:** Convergence dos dois caminhos, substituição após "Atualizar",
  recusa de nome vazio e comportamento da tag. Todos esses pontos são exatamente os que o
  Mestre precisa testar agora.