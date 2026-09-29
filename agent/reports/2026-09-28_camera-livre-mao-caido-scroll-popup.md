# 2026-09-28 - Camera livre, mao do caido e scroll do popup de skills

Rodada de correcao de 3 bugs reportados pelo usuario em jogo. **Sem commit** (decisao do
usuario: commit so quando ele pedir). Nada foi enviado para remoto.

## Objetivo

1. Camera livre: personagem congelado ao entrar andando, congelado no ar se tivesse pulado,
   e pernas em pose de corrida parada.
2. Com 0 HP, na 1a pessoa do vanilla, a mao do personagem sai para fora da tela ao virar
   com o mouse.
3. Menu de skills: com descricao muito longa aparecem duas barras de scroll; ao descer, a
   de cima desce junto e a de baixo nao sobe mais, "mesmo clicando e arrastando".

## Escopo e diagnostico previo

Tresareas deInvestigacao separadas, por `tcc-pesquisa` (so leitura) e leitura direta do
codigo, com `javap` nos jars remapeados de 1.21.11 do cache do Loom
(`minecraft-clientonly` e `minecraft-common`). Tres perguntas ao usuario antes de editar,
porque duas das tres causas dependiam disso:

- barra de baixo = barra do popup de descricao (e nao do campo de texto do rodape);
- camera livre: corpo parado **mas assentando no chao**;
- mao do caido: personagem **local**, camera do **vanilla em 1a pessoa**.

A mudanca toca mixin, entao foi submetida ao `tcc-change-gate` e aprovada pelo usuario
antes de qualquer edicao, junto com a remocao da instrumentacao temporaria.

## Causa raiz de cada sintoma

1. **Camera livre.** `LocalPlayerMixin` cancelava `aiStep()` quando a camera livre estava
   ligada. Em 1.21.11 e o `LivingEntity.aiStep()` que chama `travel()` (movimento,
   gravidade, colisao) **e** `calculateEntityAnimation()` (animacao das pernas). O
   cancelamento tirava a gravidade - dai o personagem preso no ar - e deixava o
   `walkAnimation` congelado no ultimo valor, com `speed` 1.0, produzindo a pose de corrida
   parada. **Correcao aplicada:** a camera livre nao cancela mais nada; ela zera apenas o
   movimento no `ClientInput`, e a fisica e a animacao voltam a ser vanilla.
2. **Mao do caido.** Nesse cenario `SpectatorCameraController.isActive()` e **falso** (a
   camera de espectador esta desligada), entao nem o `DownedBodyAlignMixin` nem o
   `EntityTurnMixin` nem o `GameRendererMixin` agem. O corpo continua girando normal com o
   mouse - nao ha desalinhamento -, mas a mao em **primeira pessoa** do vanilla e desenhada
   num jogador deitado na pose `SWIMMING` forcada por `ClientPlayerPoseMixin`. A mao e o
   unico desenho do proprio corpo que existe na 1a pessoa.
3. **Scroll.** `mouseScrolled` rolava o popup e, quando o popup ja tinha chegado ao fim, caia
   no bloco da lista e rolava a lista com a mesma roda: e a "barra de cima descendo junto".
   E, quando o popup transborda, `onScrollbar` so aceitava o trilho de 6 px (+2 px de cada
   lado), entao um clique 2 px fora da barra nao pegava nada - sem nenhuma resposta visual,
   que e lido como "arrastar nao sobe".

## Arquivos alterados (8 no total: 1 novo, 7 modificados)

| Arquivo | Mudanca |
|---|---|
| `src/client/java/.../mixin/ClientInputMixin.java` | **NOVO.** `@Mixin(KeyboardInput.class) extends ClientInput`, `@Inject("tick", TAIL)`. Com a camera livre ativa: `moveVector = Vec2.ZERO` e reconstroi o record `Input` sem pulo nem agachar, preservando frente/tras/esquerda/direita/corrida. |
| `src/client/java/.../mixin/LocalPlayerMixin.java` | Remove a condicao `\|\| isFreeCameraActive()` do cancelamento (fica so `locked` e `downed`) e reescreve o Javadoc da classe. |
| `src/client/resources/tabletop-rpg.client.mixins.json` | Registra `ClientInputMixin`. |
| `src/client/java/.../mixin/GameRendererMixin.java` | `renderItemInHand` passa a cancelar tambem por `TabletopRpgClient.downed` (1 linha) + Javadoc. |
| `src/client/java/.../SkillsScreen.java` | Roda restrita a regiao sob o cursor, sem fall-through; `onScrollbar` aceita a margem de 12 px do popup; popup testado antes da lista no `mouseClicked`; Javadocs corrigidos. |
| `src/client/java/.../mixin/DownedBodyAlignMixin.java` | Remocao da instrumentacao `DIAG_TEMP` (4 campos, metodo `tabletopRpg$diag`, 2 chamadas, import `Locale`). Condicao `!spectator` **mantida**. |
| `src/client/java/.../CinematicCameraRig.java` | Remocao do gravador de diagnostico (`lastAppliedYaw`, `lastAppliedPartialTick`, `tabletopRpg$recordApplied`). `getInterpolatedYaw/Position/Pitch`, `isActive`, `activate`, `update`, `deactivate` preservados. |
| `src/client/java/.../mixin/CameraMixin.java` | Remocao da chamada ao gravador; a aplicacao da rotacao da camera permanece. |

`git diff --stat`: 7 arquivos, +115 / -157. `grep` final em `src/`: zero referencias a
`DIAG_`, `tabletopRpg$diag`, `lastAppliedYaw`, `lastAppliedPartialTick`, `DownAlign`.

## Validacoes executadas

| Comando | Resultado |
|---|---|
| `.\gradlew.bat build --no-daemon --console=plain` | **BUILD SUCCESSFUL em 22s.** `scanEncoding`: 94 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD. |
| `.\gradlew.bat runClient --no-daemon` (≈100s, com saida capturada em `%LOCALAPPDATA%\Temp\opencode\rc.out` porque `run/` e do proprio Minecraft) | `run/logs/latest.log` com **"Sound engine started"**; `run/crash-reports` = **0**; nenhum `InvalidMixin`/`InvalidInjection`/`MixinApplyError`. O unico `Caused by` e `MinecraftClientHttpException: 401` do authlib, normal em ambiente de dev. |
| `javap` + `jar tf` sobre `build/libs/tabletop-rpg-1.0.0.jar` | Caminho de classe conferido antes do marcador: `com/pedro/tabletoprpg/client/mixin/ClientInputMixin.class` existe no jar, e a classe compilada estende `net.minecraft.class_744` (`ClientInput` no nome intermediario do jar remapeado). `ClientInputMixin` esta na lista do `tabletop-rpg.client.mixins.json` dentro do jar. |
| `git status --short` | Somente os 7 modificados + 1 novo esperados. |

## Limitacoes - o que NAO foi validado

- **Nenhum teste em jogo.** As tres correcoes estao em build verde e o cliente abre sem
  crash, mas o comportamento nao foi visto pelo usuario.
- **A aplicacao do mixin novo nao esta provada.** `KeyboardInput` so e carregada ao entrar
  num mundo; um `runClient` que so chega ao menu nao exercita esse mixin. A prova vem de
  entrar num mundo - o cliente que subiu nesta rodada esta aberto e serve para isso.
- **Efeito colateral assumido e nao testado:** com a camera livre ligada, pular e agachar
  deixam de responder (o corpo fica realmente parado). Ataque, uso de item e corrida
  continuam, por construcao do record preservado. O usuario pediu "corpo parado", mas
  precisa confirmar que nao sent falta do pulo.
- **O sintoma "a barra de baixo nao sobe ao arrastar" nao foi reproduzido em codigo.** A
  aritmetica de `setPopupScrollFromMouse` esta correta, e o comentario de 26/09 registra que
  o usuario **ja** tinha validado esse arrasto com sucesso. A correcao de folga (margem de
  12 px) e defensiva; se o sintoma repetir, o proximo passo e instrumentar
  `popupScroll`/`draggingScrollbar`/`barTrackH` por frame, e nao chutar de novo.
- A instrumentacao `[DownAlign]` foi removida antes de o log ser lido. As hipoteses (a),
  (b) e (c) que ela media continuam **nao verificadas** - porem as duas causas raiz desta
  rodada foram encontradas por leitura de codigo, sem depender delas.
- Sem multiplayer, sem servidor dedicado, sem validacao de performance.

## Aprendizados duraveis (resumo; completo em `agent/memory/project-memory.md`)

- Em 1.21.11, `LivingEntity.aiStep()` e o unico caminho de `travel()` e de
  `calculateEntityAnimation()` para o jogador local: cancelar `aiStep()` tira gravidade e
  animacao ao mesmo tempo. Congelar movimento **pelo input** e o caminho certo.
- `ClientInput.tick()` e vazio; quem sobrescreve e `KeyboardInput`, e `LocalPlayer.input` e
  sempre um `KeyboardInput`. `Input` e um record imutavel de 7 booleanos: nao ha setter, tem
  que reconstruir o record.
- `runClient` que so chega ao menu **nao valida** mixin de classe carregada sob demanda.
- `BAR_SLOT` em `SkillsScreen` vale 32, e nao 12: a faixa util do respiro da barra e
  `BAR_W + 2*BAR_PAD`.
- Quando um bug so aparece num caminho em que um mecanismo esta desligado, o
  `DownedBodyAlignMixin` nao era o suspeito: o unico desenho do proprio corpo na 1a pessoa e
  a mao, e ela era escondida so na camera de espectador.

## Atualizacao 28/09/2026, depois de o usuario jogar: a aplicacao do mixin JA saiu

O primeiro corte deste relatorio dizia que a aplicacao do `ClientInputMixin` nao estava
provada, porque `KeyboardInput` so e carregada ao entrar num mundo. **Isso estava errado
por omissao:** o usuario ja tinha jogado com este build antes de eu escrever aquilo.

Evidencia lida em `run/logs/latest.log` (log de 21:14; build novo as 20:21, cliente as
20:22):

- o mundo `New World` foi carregado e jogado por ~50 min;
- **0 crash-reports**, nenhum `InvalidMixin` / `InvalidInjection` / `MixinApplyError` /
  `NoSuchMethodError` / `IllegalAccessError` (o unico `Caused by` e o 401 do authlib);
- 20 linhas do mod, incluindo 3 `MenuRequestPayload` do `Player444` (20:56-20:57), que
  exercitam o caminho de dados do mod;
- **0 linhas `[DownAlign]`** - o marcador existia so no build antigo, entao o cliente
  obrigatoriamente rodou **este** build, com as tres correcoes e a instrumentacao removida.

Conclusao: o padrao "mixin estende a classe-pai direta do alvo" funciona em runtime, e o
risco tecnico da rodada esta eliminado. **O que continua sem confirmacao e o
COMPORTAMENTO das tres correcoes** - nao ha no log como provar se a camera livre assentou
no chao, se a mao sumiu ou se as barras rolam separadas. Isso depende do relato do usuario.

## Validacao em jogo: APROVADA pelo usuario (28/09/2026)

Depois da atualizacao acima, o usuario respondeu **"eu testei e esta tudo certo"**. Isso
promove as tres correcoes de "build verde" para **VALIDADO EM JOGO**, e o efeito colateral
do pulo/agachar na camera livre foi aceito como esta.

Ressalva de honestidade: o usuario deu uma confirmacao geral, sem detalhar qual dos quatro
itens da lista de teste ele percorreu (camera livre andando, camera livre com pulo/agachar,
mao do caido na 1a pessoa, as duas barras do menu de skills). O registro e "confirmacao
geral do usuario", nao "cada item conferido um a um".

Observacao sem relacao aparente com o mod: um unico `Can't keep up! ... Running 320865ms
or 6417 ticks behind` as 21:14, compativel com o jogo ficar parado/ sem foco por varios
minutos. Nao medido, nao atribuido.

## Proximos passos
1. O usuario entra num mundo no cliente que esta aberto: isso valida a aplicacao do
   `ClientInputMixin` (se o mixin nao aplicar, o jogo cai na entrada do mundo).
2. Teste em jogo dos tres bugs: camera livre andando / pulando; camera livre com pulo e
   agachar (confirmar que nao incomodam); 0 HP na 1a pessoa virando o mouse; descricao longa
   com as duas barras, rolando e arrastando as duas.
3. Se o arrasto da barra de baixo continuar sem resposta, instrumentar antes de mudar.
4. Commit so quando o usuario pedir (codigo + memoria do projeto + relatorio).
