# Merge do codigo do amigo com o Diario (03/10/2026)

## Objetivo

A jogadora pediu para trazer (`pull`) o trabalho do amigo — **fichas de ameaca do Mestre**, 2
commits ja pushados — sem perder o Diario, para testar as duas coisas juntas e so entao fazer
commit e push.

Estado de partida: `main` em `8d1e975` (= `origin/main`), **0 commits a frente**, com o Diario
inteiro **nao commitado** (6 arquivos rastreados modificados + 13 nao rastreados).

## Por que isso era arriscado de verdade

O codigo do amigo e o nosso tocavam **os mesmos 6 arquivos**. Em 5 deles, as duas alteracoes
caiam na mesma regiao do arquivo (o fim do `RpgNetworking`, o fim do `TabletopRpgClient`, o
array do `mixins.json`, e o fim do arquivo de memoria). Um `git pull` com arvore suja e so
mudancas nao commitadas tenta recarregar por cima: os dois autores estavam **escrevendo no
mesmo lugar ao mesmo tempo**.

## Sequencia, e por que nesta ordem

1. **`git fetch`** — nao toca a arvore de trabalho. Confirmou `8d1e975..f246796`, 2 commits a
   frente, 0 atras.
2. **Backup fora do repositorio**, em `%TEMP%\opencode\backup-diario\`: um patch das 6
   modificacoes e a copia dos 13 arquivos nao rastreados (14 arquivos, 301 KB). Motivo: o
   `stash` seria a **unica** copia do trabalho, e uma resolucao de conflito errada seria
   irreversivel. Um backup commitado seria justamente o commit que ela pediu para nao fazer.
3. **`git stash push -u`** — 6 modificados + 13 nao rastreados guardados.
4. **`git merge --ff-only origin/main`** — fast-forward puro (0 commits a frente nao ha o que
   reconciliar, e `--ff-only` recusa em vez de criar um commit de merge silencioso).
5. **`git stash pop`** — 4 conflitos, 2 auto-mesclados.
6. Resolucao manual dos 4, build, testes, conferido tudo.

**O stash continua existindo** (`stash@{0}`): `git stash pop` **nao descarta** o stash quando ha
conflito. Ele ficou como a unica copia git do estado pre-merge. Manter e a decisao; descartar
so depois que ela commitar.

## Os 4 conflitos, e como cada um foi resolvido

### 1. `tabletop-rpg.mixins.json` — o mais simples

Os dois acrescentaram um item no fim do array `mixins`. Nao havia escolha: os dois entram.

    "PlayerRollPresetPersistenceMixin",
    "PlayerThreatSheetPersistenceMixin",
    "PlayerDiaryPersistenceMixin"

### 2. `TabletopRpgClient.java` — dois blocos de receptores

Os dois acrescentaram `registerGlobalReceiver` no mesmo ponto. Sao registros **independentes**,
nao ha exclusao: as duas feature coexistem. Os dois blocos foram mantidos, o do amigo primeiro.

### 3. `RpgNetworking.java` — tres conflitos num arquivo so

- **Registro de tipos**: 6 do amigo, 9 nossos. Ambos mantidos.
- **Chamada dos receptores**: `registerThreatSheetReceiver(); registerDiaryReceiver();`.
- **O grande (546 linhas dos dois lados):** aqui o conflito tinha uma **armadilha**. Os dois
  lados terminavam o bloco com um metodo **aberto**, e o git juntou um unico `}` final para os
  dois. Resolver "deixando como veio" teria compilado? Nao — `threatSheetRefuse` e
  `registerDiaryReceiver` ficariam sem a chave de fechamento. A solucao foi dar **uma chave a
  cada metodo**.

### 4. `agent/memory/project-memory.md` — 251 linhas do amigo + 536 nossas

Os dois anexaram licoes no fim do arquivo. Conferido antes de concatenar: **nenhuma
colisao de titulo** (as do amigo sao `###` sobre fichas, as nossas sao `## Licao A` a `## Licao
G` sobre o diario). Concatenao: lado do amigo, depois o nosso, um separador.

## Defeitos que eu introduzi no merge, e como foram pegos

**Cinco linhas do amigo perderam a indentacao** e foram para a coluna 0. Todas as cinco eram
comentarios ou uma entrada de JSON: **o build passou**, porque indentacao de comentario e de
JSON nao e erro de sintaxe. Quem pegou foram duas verificacoes que eu rodei porque build verde
nao prova nada:

- `git diff --cached -U0 | Select-String '^-[^-]'` — mostrou 4 remocoes que nao deveriam existir;
- varredura de linhas em coluna 0 nos dois arquivos.

As cinco foram corrigidas. A **sexta** linha em coluna 0 (`// O store e quem manda no id:`, hoje
linha 2207 do `RpgNetworking`) **nao e minha**: ela ja estava assim no commit dele (HEAD linha
2195, identica). Nao mexi — mexer no codigo dele por cosmetica durante um merge cria um diff que
ninguem pediu.

**Reparo de texto com a ferramenta errada.** Um bloco de texto em PowerShell com **crases**
(`drawListOverlay`) foi escrito com aspas duplas, e no PowerShell a crase e o **caractere de
escape**: as crases foram silenciosamente engolidas e um `alguem` virou `algueme`. Reparado
palavra por palavra, com guard de "tem de bater exatamente 1 vez".

## Validacao

| Checagem | Resultado |
|---|---|
| `gradlew build` | **BUILD SUCCESSFUL** |
| Testes | **211, 0 falhas** (9 suites; as 45 do diario intactas) |
| `scanEncoding` | **OK** — 180 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD |
| Marcadores de conflito no repo | **zero** |
| `diff --name-only --diff-filter=U` | vazio |
| Jar | 846 KB, **273 entradas**: 28 do diario + 45 das fichas, assets do item incluso |
| **Linhas do nosso lado que sumiram** | **0** (comparacao por conteudo, ignorando indentacao, contra `stash@{0}`) |
| Chaves de NBT | 3 distintas: `tabletoprpg_diary`, `tabletop_rpg_threat_sheets`, `tabletoprpg_roll_presets` |
| Botoes no menu | `"Fichas de Ameaca"` (dentro do `if isMaster`), `"Diario"` (no trecho comum) |

### A verificacao que valeu mais

**Comparar o conteudo de cada arquivo contra a copia pre-merge, ignorando indentacao.** Build
verde diz que compila; nao diz que nenhum `registerGlobalReceiver` sumiu silenciosamente. O
resultado — **0 linhas do nosso lado ausentes** — e a prova de que o merge nao perdeu nada do
Diario. Vale mais que qualquer `BUILD SUCCESSFUL`.

**NAO validado em runtime.** Continua valendo o que valia antes: nenhuma tela de Diario rodou
desde a rodada 1, e o round-trip do NBT continua sendo o maior risco em aberto. **Agora ha um
risco novo e maior:** o round-trip do NBT nunca foi testado **com os dois mixins juntos**. As
chaves sao distintas (confere acima), mas duas injeções no mesmo ponto do mesmo método
(`addAdditionalSaveData`/`readAdditionalSaveData` com `TAIL`) é exatamente o tipo de coisa que
falha em runtime e nao no build. **E o primeiro lugar para olhar se algo parecer errado.**

## Estado para a jogadora

- Arvore limpa de conflitos, **build verde**, **nada commitado** (como ela pediu).
- O diff staged tem 6 arquivos rastreados; os 13 do diario seguem nao rastreados.
- `stash@{0}` preservado como rede de seguranca — pode ser descartado depois do commit.
- Ela pode testar as duas features juntas e entao commitar.

---

# Pos-jogo: commit `fa7523d`, push feito, e a validacao em jogo

## FACT: as duas features funcionam juntas

A jogadora testou em jogo e respondeu *"perfeito! ambos estao funcionando"*. Commit
`fa7523d`, push para `origin/main` (`f246796..fa7523d`), arvore limpa, local e remoto
identicos.

**Este e o primeiro teste em runtime do Diario.** As rodadas 1 a 7 (e o merge) tinham sido
validados por build + 211 testes unitarios + inspecao do jar — nunca pelo jogo. Todos os
defeitos que ela reportou (breadcrumb ausente, rascunho que nao gravava, `[Reverter]` morto)
eram de **runtime**: compilavam, os testes passavam, e a tela nao fez a coisa pedida.

## O que o teste em jogo cobre, e o que nao cobre

**Cobre:** as telas abrem e navegam; o texto novo aparece com o ponto amarelo no cartao e no
endereco; `[Salvar]`, `Esc`, `[X]` e o `[Voltar]` da Tela 1 gravam; o `[Reverter]` desfaz na
ordem; as fichas de ameaca do amigo funcionam junto.

**NAO cobre, e continua aberto:** o **round-trip do NBT entre sessoes** — isto e, se o diario
criado hoje volta depois de reconectar no servidor. "Funciona" enquanto a tela esta aberta nao
fala da persistencia. Se ela nao reconectou desde que criou as anotacoes, esse item segue sem
prova. E o **primeiro** lugar para olhar se algo aparecer vazio depois de um restart.

## Estado final do repositorio

    fa7523d Diario: arvore de anotacoes por jogador, com rascunho, [Salvar] e Reverter em ordem
    f246796 Item de ficha: amarra pela mira e Atualizar repara o vinculo do mob
    cc8936f Fichas de ameaca: item, editor, persistencia NBT e layout da ficha

- 20 arquivos, **6449 insercoes, 1 delecao** — a delecao e a virgula que a entrada nova no
  array do `mixins.json` exigiu. Nenhuma linha de codigo existente foi removida.
- `stash@{0}` **foi preservado** propositalmente (rede de seguranca do pre-merge). Depois deste
  commit ele pode ser descartado com `git stash drop`, quando ela quiser.
- Memoria do projeto: 3860 linhas, com as licoes A-H.