# Relatório — Referência `tcc-project` e contexto do agente anterior

## Status

CONCLUÍDO — a referência foi criada na configuração global e a precedência entre os dois contextos foi definida no agente principal. Nenhum arquivo do repositório do mod foi alterado, portanto não houve build nem teste em jogo. A validação automatizada do OpenCode segue impossível nesta sessão porque o executável `opencode` não está no PATH.

## Objetivo

Decidir se a pasta `agent/` dentro do projeto — deixada pelo outro agente do time — deveria entrar no contexto, e evitar que ela competes ou sobrescreva o contexto do TCC.

## Investigação

Comandos executados: `glob` por `SKILL.md`, `AGENTS.md`, `opencode.json` e `opencode.jsonc` no projeto (nenhum resultado), listagem recursiva da pasta `agent/`, `git log -- agent`, `git log -3`, `git branch -a` e `git status`.

Constatações:

- A pasta contém 21 arquivos: `HANDOFF.md`, três memórias (`project-memory.md` com 36 KB, `ai-operational-discipline.md` com 19,5 KB, `fase3-ficha-checklist.md` com 13,8 KB) e 17 relatórios, sendo dois deles `.docx`.
- **Não existe nenhuma skill, `AGENTS.md` ou configuração de agente** nessa pasta. Não hánothing a importar em termos de skills.
- Tudo está commitado por `Olivas` no repositório do mod; working tree limpa, 20 commits, último em 26/09/2026 10:02, branches `main` e `pasta-Net` com `origin` configurado.
- O conteúdo é mais recente e mais técnico que o contexto do TCC em engenharia: armadilhas de build, mixins, persistência, render, armadilhas de `StreamCodec` na 1.21.11 e a diretiva "perguntar, nunca inventar".
- Parte está desatualizada: `fase3-ficha-checklist.md` afirma que a ficha não é persistida, o que a FASE 3E corrigiu depois.

## Decisão

Adicionar **apenas a referência**, sem copiar arquivos, e definir precedência explícita. Motivo: duplicar os arquivos criaria duas fontes que divergem com o tempo, e a pasta do projeto está versionada no repositório — o outro agente continuaiscountro por ela.

## Alterações

1. `C:\Users\Danylo Henrique\.config\opencode\opencode.jsonc`: nova referência `tcc-project` apontando para `C:/Users/Danylo Henrique/Documents/GitHub/tabletop-rpg-1.21.11/agent`.
2. `C:\Users\Danylo Henrique\.config\opencode\agents\agente-tcc.md`: nova seção "Precedência de contexto" e proibição de editar a pasta `agent/` do projeto.
3. `memory/project-memory.md`: nova seção append-only "Referência do contexto do projeto (26/09/2026)".

## Precedência definida

1. Código e runtime do projeto.
2. Contexto do projeto (`tcc-project`): `project-memory.md`, `HANDOFF.md`, `reports`.
3. Contexto do TCC (`tcc-context`): `memory`, `reports`.
4. DOCX, scripts de ideias técnicas e relatórios antigos: propostas e histórico.

## Escopo da referência

A referência cobre **apenas a pasta `agent/`**, não o projeto inteiro, para não despejar o código-fonte no contexto a cada sessão. O código continua sendo acessado normalmente quando a sessão roda dentro do projeto.

## Validação

- `opencode.jsonc` relido: duas referências, JSON válido, vírgulas corretas.
- `agente-tcc.md` relido: seção nova com quatro níveis de precedência e a proibição de editar o contexto do outro agente.
- Varredura de caracteres CJK e `U+FFFD` nos arquivos alterados: 0 ocorrências.

## Limitações

- A referência só é carregada após reiniciar o OpenCode.
- Contexto do projeto não validado linha a linha; é grande e parcialmente desatualizado. Use-o como pista, confirme no código.
- Nenhuma mudança no mod; build e `runClient` não executados.

## Próximos passos sugeridos

1. Reiniciar o OpenCode e confirmar que `tcc-project` aparece entre as referências.
2. Quando for implementada a próxima mecânica, decidir se as lições de `ai-operational-discipline.md` valem para o nosso fluxo (build travando, orçamento de passos, varredura de encoding).
3. Resolver a divergência dos valores iniciais dos atributos: `0` no código, `2` na memória do projeto.
