# Relatório de Implementação — Arquitetura do agente TCC e skills

## Status

CONCLUÍDO — o agente principal, os subagentes, a configuração global, as skills e o contexto documental foram estruturados. A validação automatizada do OpenCode não pôde ser executada porque o executável `opencode` não está disponível no PATH desta sessão.

## Objetivo

Organizar o agente do TCC para coordenar tarefas, perguntar em caso de dúvida, bloquear decisões de alto impacto sem aprovação, validar alterações, gerar relatórios e manter memória durável.

## Decisão de arquitetura

Escolhi um agente principal com três subagentes restritos:

- `agente-tcc`: agente principal, responsável por contexto, coordenação, perguntas, validação final, memória e relatório.
- `tcc-pesquisa`: somente leitura, para investigação de código, APIs e arquitetura.
- `tcc-implementador`: alterações pequenas, delimitadas e reversíveis, sem escrever memória ou relatórios.
- `tcc-validador`: revisão e execução de verificações, sem editar código.

A separação foi escolhida porque o agente principal precisa conversar com o usuário e tomar decisões, enquanto as tarefas de pesquisa, implementação e validação têm escopos e riscos diferentes.

## Arquivos alterados

- `C:\Users\Danylo Henrique\.config\opencode\opencode.jsonc`
- `C:\Users\Danylo Henrique\.config\opencode\agents\agente-tcc.md`
- `C:\Users\Danylo Henrique\.config\opencode\agents\tcc-pesquisa.md`
- `C:\Users\Danylo Henrique\.config\opencode\agents\tcc-implementador.md`
- `C:\Users\Danylo Henrique\.config\opencode\agents\tcc-validador.md`
- `C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\project-memory\SKILL.md`
- `C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\code-review-tcc\SKILL.md`
- `C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\minecraft-modding\SKILL.md`
- `C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\networking-sync\SKILL.md`
- `C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\vtt-mechanics\SKILL.md`
- `C:\Users\Danylo Henrique\Documents\GitHub\agent\opencode.json`
- `C:\Users\Danylo Henrique\Documents\GitHub\agent\memory\project-memory.md`
- `C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\tcc-change-gate\SKILL.md`
- `C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\tcc-validation\SKILL.md`
- `C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\tcc-requirements\SKILL.md`
- `C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\tcc-document-reader\SKILL.md`
- `C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\minecraft-fabric-1-21-11\SKILL.md`
- `C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\git-workflow\SKILL.md`

## Skills adicionadas

- `tcc-change-gate`
- `tcc-validation`
- `tcc-requirements`
- `tcc-document-reader`
- `minecraft-fabric-1-21-11`
- `git-workflow`

## Skills revisadas

- `project-memory`: leitura obrigatória, memória append-only, relatório por tarefa, distinção entre evidência e inferência.
- `code-review-tcc`: revisão baseada no diff, riscos e evidências; não força MVC ou comentários desnecessários.
- `minecraft-modding`: detecta loader, versão, mappings e ciclo de vida antes de recomendar uma API.
- `networking-sync`: servidor autoritativo, validação, limites de pacotes, reconexão e descarte de estado.
- `vtt-mechanics`: não impõe grid, iniciativa, névoa de guerra ou outras regras sem aprovação do usuário.

## Skill de versionamento Git

- `git-workflow` trata pedidos de versionamento, checkpoint, snapshot, commit, tag, branch e restauração.
- O padrão é criar um commit do estado intencional e uma tag anotada local com nome como `checkpoint-AAAAMMDD-HHMM-descricao-curta`.
- A skill exige inspeção prévia, seleção explícita de arquivos, validação pós-commit e informações de acesso por `git show`, `git diff` e `git switch -c`.
- Push, reset destrutivo, amend, remoção de refs e operações irreversíveis continuam bloqueados sem pedido explícito.

## Economia de contexto

- A configuração global agora usa `compaction.auto: true` e `compaction.prune: true`.
- O OpenCode poderá compactar sessões longas e remover saídas antigas de ferramentas para reduzir o contexto; o impacto real depende do modelo e da tarefa.

## Documentos lidos

- `analise-mecanica-slide-settings.md`: referência conceitual do Create; não é dependência do mod.
- `Mecânicas Opcionais Ideias Técnicas.md`: requisitos e propostas; propostas não foram tratadas como implementadas.
- `memory/project-memory (1).md`: snapshot histórico adicional; a memória canônica é `memory/project-memory.md`.
- `Relatório de Desenvolvimento.docx`: extraído localmente com segurança; resume arquitetura, telas, networking, câmera, rolagens, correções de UI e sugestões de otimização, mas não substitui código, build ou teste em jogo.

## Problemas encontrados e soluções

1. O agente era um subagente simples e não tinha subagentes nem protocolo de risco. A solução foi criar um agente principal com delegação restrita.
2. O agente não exigia relatório e memória append-only ao final. A solução foi tornar esse fluxo obrigatório no prompt e na skill `project-memory`.
3. O `opencode.json` legado usava `permissions`, campo inválido. A solução foi migrar para `permission` e manter o agente legado como subagente de compatibilidade.
4. Havia regras rígidas conflitantes ou dependentes de contexto nas skills. A solução foi remover MVC obrigatório, grid obrigatório, batching absoluto e suposições de API.
5. O documento DOCX é binário e não pôde ser aberto pelo leitor normal. A solução foi extrair o texto diretamente do pacote OOXML com uma ferramenta local segura, preservando o original, e registrar as limitações e contradições.

## Validação

- O schema global foi mantido com `$schema`, `default_agent`, `subagent_depth`, `compaction` e `references` em campos aceitos pela configuração do OpenCode.
- A busca confirmou que não há mais o campo inválido `permissions` nos JSONs do agente.
- As pastas e os onze arquivos `SKILL.md` foram relidos após a criação.
- O texto do `Relatório de Desenvolvimento.docx` foi extraído com sucesso do pacote OOXML; o arquivo original não foi alterado.
- `opencode --version` e `opencode agent list` não puderam ser executados: o comando não foi encontrado no PATH.
- Nenhum build do mod foi executado, pois esta alteração é de configuração do OpenCode e o código do mod não está no diretório de trabalho atual.

## Próximos passos

1. Reiniciar o OpenCode para carregar a configuração, os agentes e as skills.
2. Confirmar que `agente-tcc` aparece como agente principal padrão.
3. Testar uma tarefa real e verificar a delegação para pesquisa, implementação e validação.
4. Usar o texto extraído do DOCX como contexto histórico e confirmar qualquer afirmação no código-fonte antes de alterá-lo.
5. Executar o build do mod no diretório real do projeto antes de considerar uma alteração de gameplay validada.
