# 2026-09-26 — Divisão da memória em duas casas

## Objetivo
O usuário perguntou se eu poderia gravar minha memória na pasta `agent/` **dentro do
projeto**, e se dá para fazer isso todo final de sessão, ou se existe um jeito mais fácil.

## Diagnóstico: o problema real não era onde escrever
Investiguei as duas pastas e achei uma **cabeça dividida**, não só uma permissão faltando:

| | `tabletop-rpg-1.21.11/agent/memory/` | `GitHub/agent/memory/` |
|---|---|---|
| `project-memory.md` | **35,5 KB** | 20,9 KB |
| No git | **Sim** (22 arquivos versionados) | Não |
| Dono | outro agente | eu |
| Fatos de engenharia do código |até 25/09 | **os de 26/09 (eu)** |

As 7 primeiras seções são **idênticas** nas duas: a externa foi semeada como cópia da do
projeto. Resultado: a memória de engenharia do projeto — a maior e a versionada — era a
única em que eu não podia escrever, e eu vinha gravando interno de Java no lugar errado.
Isso é exatamente o que faz a próxima sessão ler contextocontraditório.

## Decisões do usuário (26/09/2026)
1. Autorizado gravar em `tabletop-rpg-1.21.11/agent/memory/`, **e** adicionar a permissão de
   `edit` nesse caminho no `opencode.jsonc`.
2. **Migrar** os fatos de engenharia já gravados na memória externa para a memória do projeto.
3. Recusado: adicionar descrição às 20 Skill Checks fixas (mudaria protocolo e persistência).
4. Recusado: transformar a aura em esfera com Y (é regra de jogo).

## O que mudou

### Regra nova: uma casa por tipo de fato
- **Engenharia do código** → `tabletop-rpg-1.21.11/agent/memory/project-memory.md`,
  versionada no git junto com o código.
- **TCC** → `GitHub/agent/memory/project-memory.md`: decisão e requisito do usuário,
  disciplina, índice de relatórios, prompt e configuração.
- Sem duplicar o mesmo aprendizado nos dois.

### Achado importante: permissão por agente SOBRESCREVE a global
A primeira tentativa foi mexer só em `~/.config/opencode/opencode.jsonc`. Ao ler o prompt,
vi que o `agente-tcc` tem o **próprio** bloco `permission` no frontmatter, com a lista
completa de `edit`. Pela skill `customize-opencode`: *"Per-agent `permission:` overrides
top-level `permission:`"*. Pior: dentro de um objeto de regras vale a **última regra que
casa**, e o frontmatter do agente tem `"*": allow` como primeira entrada. Se o merge
colocasse as regras do agente **depois** das minhas, o `"*": allow` venceria e a minha
permissão específica nunca entraria em vigor — silenciosamente.
**Correção:** a entrada foi posta nos **dois** lugares, e o do frontmatter do agente é o
que realmente garante o efeito. `opencode.jsonc:8-12` e `agente-tcc.md:28`.

### `agente-tcc.md` — 5 trechos corrigidos
- **Frontmatter (28):** novo `allow` para `~/Documents/GitHub/tabletop-rpg-1.21.11/agent/memory/**`.
- **Contexto persistente:** as duas memórias agora são nomeadas sem ambiguidade, com aviso
  explícito de que **existem dois arquivos chamados `project-memory.md`** e que se deve
  citar o caminho completo ao escrever.
- **Precedência:** a frase "não apague nem edite a pasta `agent/` do projeto" foi
  substituída pelo **escopo de escrita**: a memória do projeto pode ser escrita em
  append-only; `HANDOFF.md`, os checklists e `agent/reports/` do outro agente **continuam
  intocáveis**.
- **Memória e relatórios:** nova seção de roteamento (qual arquivo recebe qual fato),
  com marcação obrigatória `FATO verificado` / `HIPOTESE` e a regra de acrescentar
  correção em vez de reescrever histórico.
- **Orçamento de passos:** "grave em `project-memory.md`" passou a dizer "no arquivo
  correspondente ao tipo de fato", porque o nome sozinho já é ambíguo.

### Migração
Duas seções movidas da memória externa para a do projeto, com o cabeçalho
`## DIVISAO DA MEMORIA (26/09/2026)` e a marcação de onde veio. Reescritas **sem acentos**,
para seguir o estilo do arquivo de destino (o outro agente escreve assim). Na memória
externa ficou uma seção `## FATO DE ENGENHARIA MUDOU DE CASA (26/09/2026)` com o ponteiro.
Nada foi sobrescrito ou apagado: os dois arquivos só ganharam conteúdo, e a duplicata foi
trocada por referência explícita.

## Arquivos alterados
- `C:\Users\Danylo Henrique\.config\opencode\opencode.jsonc` (+5) — `permission.edit`
- `C:\Users\Danylo Henrique\.config\opencode\agents\agente-tcc.md` (5 trechos) — a regra
- `tabletop-rpg-1.21.11\agent\memory\project-memory.md` (+36) — 2 seções migradas + cabeçalho
- `GitHub\agent\memory\project-memory.md` — ponteiro no lugar das 2 seções

## Validações executadas
| O quê | Resultado |
|-------|-----------|
| `opencode.jsonc` parseado com `ConvertFrom-Json` | **JSON válido**, `permission.edit` com a entrada nova |
| `agente-tcc.md` — entrada do `allow` | presente na **linha 28** |
| `git status` do projeto | `agent/memory/project-memory.md` aparece como modificado, **+36 linhas**, como previsto |
| Migration | conferida por leitura: as 2 seções existem no destino, e a externa tem só o ponteiro |
| Build do mod | **não aplicável** — nenhuma linha de código do mod mudou neste turno |

## Limitações e pendências
- **É preciso reiniciar o opencode.** Configuração e prompt são carregados uma vez no
  start; esta sessão segue com a configuração antiga. A permissão só vale a partir da próxima.
- **A permissão não foi testada em sessão nova.** Nesta sessão a escrita funcionou, mas
  não prova a regra de configuração. Conferir no próximo turno: se ele pedir permissão
  para `agent/memory/`, o `jsonc` está incompleto.
- A memória do projeto agora **aparece no `git status`** e vai entrar no commit do
  usuário. É o objetivo (memória versionada com o código), mas é uma mudança visível.
- Nada disso foi validado em jogo — não há mudança de runtime.
- Os dois arquivos `project-memory.md` vão continuar coexistindo. O risco de confusão
  diminuiu (há ponteiro nos dois lados e o prompt avisa), mas não zera: o prompt continua
  citando `project-memory.md` em outros lugares.

## Aprendizados
- **Permissão por agente sobrepõe a global em `opencode.json`.** Alterar só o `jsonc`
  pode não ter efeito nenhum. Ler o frontmatter do agente antes de mexer em permissão.
- **Dentro de `permission.<tool>`, vale a última regra que casa** e a ordem é a de
  inserção: regras amplas primeiro, estreitas depois. Um `"*": allow` no frontmatter do
  agente vem antes das regras específicas, o que é a ordem correta.
- **Diretório de referência passa pela fronteira `external_directory` sozinho**; o que
  barra escrita lá é a permissão de `edit`, não a de diretório externo.
- **Nome de arquivo ambíguo é fonte de erro de roteamento:** dois `project-memory.md` em
  pastas diferentes obrigam a citar o caminho completo sempre.
- **Migrar sem duplicar é melhor do que copiar:** trocar o conteúdo pela referência explica
  a mudança para quem ler no futuro e evita as duas cópias divergirem.
