# 2026-09-26 — Relatórios do TCC copiados para a pasta do projeto

## Objetivo
O usuário pediu o mesmo tratamento que a memória: **copiar os relatórios** da pasta de
relatórios do TCC (`GitHub/agent/reports/`) para dentro do projeto
(`tabletop-rpg-1.21.11/agent/reports/`), e depois fixar a regra para as sessões futuras.

## Decisões do usuário (26/09/2026)
1. Copiar os relatórios acumulados para a pasta do projeto.
2. **"Projeto vira o arquivo único"**: relatório de sessão de engenharia vai **só** para
   `tabletop-rpg-1.21.11/agent/reports/`, versionado com o código. O TCC recebe apenas
   memória de processo, sem cópia do relatório.

## O que foi feito

### Cópia: 12 de 14 arquivos, com duas colisões tratadas
- **12 copiados**, todos conferidos por **SHA-256 byte a byte** após a cópia.
- A pasta do projeto foi de **18 para 30** relatórios, 312,7 KB no total.
- Os 12 novos aparecem como `??` no `git status`, prontos para o commit do usuário.

**As 2 colisões de nome foram puladas de propósito:**
`2026-09-23_analise-estado.md` e `2026-09-24_phase-4-aura-fix.md` existem nas duas pastas.
Hashes diferentes, e a versão do projeto é **maior** (4029 vs 3978 bytes; 9338 vs 9255).
O `Compare-Object` de conteúdo **não achou nenhuma linha diferente**: a diferença é só fim
de linha (CRLF no projeto, LF no externo), o que fecha com os 51 e 83 bytes de diferença.
Ou seja, mesmo conteúdo dos dois lados. **Não sobrescrever** um arquivo do outro agente por
zero ganho seria puro risco.
### `agente-tcc.md` — 3 trechos corrigidos
- **Frontmatter (29):** novo `allow` para `~/Documents/GitHub/tabletop-rpg-1.21.11/agent/reports/**`,
  senão criar relatório novo ali pediria permissão toda sessão.
- **Escopo de escrita na pasta `agent/`:** antes dizia que `agent/reports/` era intocável.
  Agora a regra é **criar e acrescentar, nunca sobrescrever**: memória em append-only e
  relatórios datados novos são permitidos; `HANDOFF.md`, os checklists e os relatórios
  **já existentes** do outro agente continuam intocáveis, com instrução explícita de
  **pular o arquivo e avisar** em caso de colisão de nome.
- **Memória e relatórios:** a seção de roteamento passou de "duas memórias" para
  **"dois lugares de contexto, um lugar por tipo de artefato"**, com tabela de 4 linhas
  cobrindo memória e relatório × engenharia e TCC, mais exemplos do que entra em cada.
  O passo 1 passou a dizer "sem copiar para a outra pasta".

## Arquivos alterados
- `tabletop-rpg-1.21.11\agent\reports\` — **+12 arquivos novos** (cópia, sem edição)
- `C:\Users\Danylo Henrique\.config\opencode\agents\agente-tcc.md` — 3 trechos

## Validações executadas
| O quê | Resultado |
|-------|-----------|
| Colisão de nomes, SHA-256 dos 4 arquivos | detectado antes de copiar |
| `Compare-Object` de conteúdo nas 2 colisões | **0 linhas diferentes** (só CRLF × LF) |
| Conferência pós-cópia, SHA-256 origem × destino | **12/12 byte a byte** |
| `git status` do projeto | 12 `??` em `agent/reports/`, mais 3 modificados |
| Build do mod | **não aplicável** — nenhuma linha de código mudou |

## Limitações e pendências
- **Reinicie o opencode.** Prompt e configuração só valem no start seguinte.
- **A permissão de `agent/reports/**` não foi testada em sessão nova.** Nesta sessão a
  escrita em `agent/memory/` funcionou; a de `reports/` só será exercida no próximo
  relatório de sessão, quando este texto já estiver no arquivo.
- **Nada foi apagado.** As cópias duplicadas de 23 e 24/09 continuam nas duas pastas por
  decisão (histórico). Se quiser limpar, é pedido explícito.
- Este relatório é o **primeiro gravado pela regra nova** e, por isso mesmo, não é a prova
  de que a regra funciona: ele foi escrito antes de a permissão estar ativa na sessão.

## Aprendizados
- **Conferir colisão de nome antes de copiar em pasta versionada.** Dois relatórios
  homônimos com CRLF × LF vão parecer "conteúdo diferente" pelo hash e pela contagem de
  bytes; `Compare-Object` nas linhas separa o falso positivo do conflito real.
- **`write`/`edit` só alcança arquivo que já existe.** Para criar relatório novo na pasta
  do projeto é preciso `write`, e a permissão precisa cobrir `agent/reports/**` — não só
  `agent/memory/**`. A entrada antiga não bastava.
- **Regra de escrita é diferente de permissão.** Permitir o caminho faz a ferramenta não
  perguntar; o que define o que pode ser feito é a instrução. Aqui foram as duas coisas:
  permissão nova no frontmatter e "criar e acrescentar, nunca sobrescrever" no texto.
