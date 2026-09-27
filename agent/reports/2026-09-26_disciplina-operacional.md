# Relatório — Adoção da disciplina operacional do contexto do projeto

## Status

CONCLUÍDO — as lições aplicáveis de `ai-operational-discipline.md` foram extraídas e divididas entre o agente principal e a skill de RPG. Nenhum arquivo do repositório do mod foi alterado; não houve build nem teste em jogo. A validação automatizada do OpenCode segue impossível porque o executável `opencode` não está no PATH.

## Objetivo

Aproveitar o aprendizado operacional do agente anterior sem duplicar contexto: o que é disciplina de fluxo vai para o agente, o que é fato técnico de Minecraft e encoding vai para a skill.

## Método

Leitura integral de `tabletop-rpg-1.21.11/agent/memory/ai-operational-discipline.md` (365 linhas) e classificação de cada seção em três destinos: agente principal, skill `tcc-rpg-knowledge`, ou não adotar.

## Adotado no agente principal (`agente-tcc.md`)

Nova seção "Disciplina operacional", com cinco subseções:

- **Contexto é recurso escasso**: `todowrite` desde o primeiro passo em tarefas com mais de duas etapas; descoberta delegada ao subagente em vez de ler arquivo grande; `read` só do intervalo; filtrar saída de terminal; "BUILD SUCCESSFUL" é resultado terminal.
- **Orçamento de passos e comandos longos**: alvo de 6 a 8 chamadas de ferramenta por turno; comando acima de 60s anunciado antes; gravação da lição em memória imediatamente após o diagnóstico, não no fim; item caro em turno próprio; responder assim que a ferramenta retorna.
- **Três causas de "travar"**: pipe segurado pelo Gradle daemon (fix `--no-daemon`), orçamento de passos, contexto saturado. Mais o quarto sintoma já visto: one-liner de PowerShell com API inexistente em laço.
- **Honestidade de validação**: build não valida injeção de mixin; nunca dizer "pronto para testar" com risco aberto; conferir caminho de classe antes de afirmar que algo está no jar; hipótese continua `HIPOTESE:` até bytecode ou teste confirmar.
- **Pavio curto com o usuário**: comentar resultado assim que chega, anunciar etapa longa antes, dizer o que não foi validado.
- **Permissão de pasta**: explicar em uma frase qualquer acesso fora do workspace, inclusive pasta pré-aprovada, e pedir confirmação para apagar temporário.

## Adotado na skill `tcc-rpg-knowledge`

Nova seção "Armadilhas de build, mixin e persistência (1.21.11)" e nova seção "Encoding e texto em português", com os fatos verificados pelo agente anterior: `--no-daemon` com pipe, `runClient` validado pelo log e não pelo tempo de espera, seção `server` do mixin não cobre singleplayer, `private` obrigatório em campo e método estático de mixin, descriptor concreto em método genérico, persistência por `ValueInput`/`ValueOutput` com `Codec` do DataFixer, `Entity.getPersistentData()` inexistente, `MouseButtonEvent` nas assinaturas de mouse, `AvatarRenderer` sem `setupRotations`, e as quatro regras de encoding.

## Adotado como diretiva de política

A diretiva do usuário "explicar toda permissão de pasta" estava no contexto do projeto como lição; foi incorporada como regra permanente do agente, porque é preferência de trabalho e não knowledge técnico.

## Não adotado, e por quê

- **One-liner de PowerShell em arquivo `.ps1` com `-ErrorAction Stop`**: a lição original é parcialmentesuperada. O ambiente executa PowerShell 5.1 sob demanda, sem estado entre chamadas, então um script em arquivo custa uma ida e volta a mais. Mantive a regra que importa: nunca varredura em laço no prompt, e sempre `grep`/`read` ou a task `scanEncoding`.
- **Detalhes de mojibake já reparados** (`U+00D0` em `CombatController.java`, fragmento `(+1)`): são dois casos específicos, o arquivo está reparado e versionado. A regra genérica de encoding ficou; o exemplo literal não.
- **Referência a `HANDOFF.md` do projeto como destino de gravação**: mantida apenas a menção de gravar estado em disco, sem ordenar escrita na pasta do outro agente.

## Verificação

- Varredura de caracteres CJK, fullwidth e `U+FFFD` nos dois arquivos alterados: 0 ocorrências. Isso foi checado porque a lição do próprio contexto do projeto é que CJK entra por descuido e só aparece na varredura.
- `agente-tcc.md` e `SKILL.md` relidos após a edição.
- `git status` do repositório do mod: vazio, nenhuma alteração.

## Limitações

- Estas regras são texto; elas não impedem que o contexto sature. O efeito real só aparece na próxima sessão, depois de reiniciar o OpenCode.
- `runClient` continua sem validação automatizada: depende do log e do usuário.

## Próximos passos sugeridos

1. Reiniciar o OpenCode para carregar o agente e a skill atualizados.
2. Na próxima sessão, testar uma tarefa de RPG curta e conferir se a disciplina aparece na prática.

## Aprovação posterior do usuário: `--no-daemon` sempre (26/09/2026)

O usuário aprovou adotar `--no-daemon` em toda chamada do Gradle, sem a condição de "só quando a saída é filtrada por pipe". Alterações:

1. `agente-tcc.md`: a causa número 1 passou a ser regra sem exceção, com o custo (~5s) e o motivo (10 minutos perdidos por build, duas vezes) registrados; a seção "Regras técnicas" ganhou a regra como primeiro item.
2. `tcc-rpg-knowledge`: o item 7 do checklist de teste e a seção de armadilhas de build passaram a prescrever `--no-daemon` em `build`, `test`, `check` e `runClient`.
3. `tcc-implementador.md` e `tcc-validador.md`: uma linha cada lembrando da flag, dentro do estilo sem acentos que esses arquivos já usavam.
4. `external_directory` dos quatro agentes: liberado `~/Documents/GitHub/tabletop-rpg-1.21.11/agent/**` para leitura, evitando pedido de permissão a cada sessão por causa da referência `tcc-project`. A permissão de `edit` dessa pasta segue não liberada, por decisão: ela pertence ao outro agente.

As permissões de `bash` não foram alteradas: os padrões existentes `*gradlew* build*`, `*gradlew* test*` e `*gradlew* check*` já casam com a flag adicional.

Verificação: varredura de CJK, fullwidth, cirílico e grego nos quatro agentes deu 0; frontmatter dos quatro arquivos continua com `mode` e `permission`; menções de `--no-daemon` presentes em `agente-tcc` (3), `tcc-implementador` (2) e `tcc-validador` (2), como esperado.
