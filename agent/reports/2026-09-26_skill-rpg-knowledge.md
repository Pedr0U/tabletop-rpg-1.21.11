# Relatório — Skill de conhecimento de RPG (`tcc-rpg-knowledge`) e caminhos do projeto

## Status

CONCLUÍDO — a skill foi criada, integrada ao agente principal e registrada na memória. Não houve alteração no código do mod, portanto não houve build nem teste em jogo. A validação automatizada do OpenCode continua impossível nesta sessão porque o executável `opencode` não está no PATH.

## Objetivo

Criar uma skill que dê ao agente conhecimento de RPG (rolagem de dados, testes, turnos, iniciativa, ficha, condições, monstros e hordas) sem inventar regras, e registrar onde está de fato o código do projeto.

## Alterações

1. Criada `C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\tcc-rpg-knowledge\SKILL.md` (172 linhas).
2. `agente-tcc.md`: novo passo 3 no fluxo obrigatório carregar `tcc-rpg-knowledge` em tarefas de mecânica de RPG; nova seção de contexto com o caminho do código-fonte e com a memória técnica do próprio projeto.
3. `memory/project-memory.md`: nova seção append-only "Skill de conhecimento de RPG (26/09/2026)".

## Conteúdo da skill

- **Regra 0**: o projeto não é D&D 5e nem outro sistema publicado; antes de propor número ou fórmula, verificar memória/código e perguntar o que não foi decidido.
- **Vocabulário** de atributo, perícia, skill, DC, vantagem, ataque, CA, turno, iniciativa, horda, downed e mana, com a coluna "cuidado no mod" apontando a diferença entre o termo genérico e a regra real aqui.
- **Matemática de dados**: notação `NdS+M` suportada, ausência de modificador negativo, ausência de vantagem/crítico/dado explosivo, aritmética em `long`, RNG do servidor.
- **Iniciativa**: marcada como planejada e não implementada, com o que o usuário já decidiu (fórmula, desempate por Destreza e depois ordem de chegada, remanejamento por `/rpg initiative set`, HUD à esquerda).
- **Ficha**: limites que não são bug (HP negativo e acima do máximo, atributo sem clamp, Mana `0..MAX_RESOURCE`, lista de perícias sanitizada e persistida) e o custo de adicionar um campo novo.
- **Combate/horda/mestre**: inserção e controle de mobs, imunidade a dano externo, remoção da fome, e a regra de autoridade do servidor.
- **Mapa de arquivos** do projeto e o alerta de que existem dois diretórios `agent/`.
- **Checklist** de 8 pontos antes de propor ou implementar uma mecânica.
- **Armadilhas** já cometidas ou quase cometidas: fórmula de perícia não é `d20 + atributo`, lista de perícias é fixa, HP <= 0 é deitado e não morto, fome e regeneração não existem, parser só soma, layouts de tela usam offsets fixos, aura define o movimento, turno de monstro ainda não existe, `/rpg roll` sem argumento lista perícias.

## Fatos verificados no código (26/09/2026)

- `MasterCommands.java`: `DICE_TERM = ^(\d*)[dD](\d+)$` e `MODIFIER_TERM = ^\d+$`; `rollSkill` calcula `1d20 + perícia + atributo` em `long`; `/rpg roll` sem argumento chama `listSkills`; `openRoll` é exclusivo do mestre; o RNG da perícia é `player.getRandom()`.
- `SheetData.java`: `Attributes` com seis atributos e sem clamp, defaults zerados, `Pericia` com valor e atributo, `PERICIAS_PADRAO` fixo, `MAX_HP_FLOOR = -999`, HP acima do máximo permitido, `hp <= 0` = deitado.
- `SessionManager.java`: modos `FREE`, `INVESTIGATION`, `COMBAT`.
- O projeto contém a memória técnica mais detalhada do TCC, com 292 linhas e quirks de render, mixin e teste, além do `HANDOFF.md` do estado da última rodada.

## Divergência encontrada (não alterada)

A memória do projeto registra "atributos 2 cada" como valor inicial, e o comentário do código afirma que o padrão pedido é `0`. Não corrigi nada: é uma decisão do usuário e precisa ser confirmada antes de virar alteração.

## Validação

- Leitura de `SKILL.md` após a escrita: 172 linhas, 0 caracteres CJK ou `U+FFFD` (o projeto tem `scanEncoding` como gate de build e reprova CJK).
- Frontmatter conferido: `name` e `description` presentes, no formato das demais skills.
- `agente-tcc.md` relido: numeração do fluxo 1 a 8 consistente e sem duplicata.
- Build Gradle e `runClient` não executados: não houve mudança de código do mod.

## Limitações

- A skill descreve regras; ela não valida equilíbrio e não substitui a decisão do usuário sobre sistema de regras.
- O working tree da sessão (`C:\Users\Danylo Henrique\Documents\Default Project`) não é o projeto do mod.

## Próximos passos sugeridos

1. Confirmar com o usuário se os valores iniciais dos atributos são `0` (código) ou `2` (memória).
2. Confirmar qual pasta de memória é a canônica para as próximas sessões, já que existem duas.
3. Se quiser, adicionar uma referência `tcc-project` na configuração global apontando para `Documents/GitHub/tabletop-rpg-1.21.11`.
4. Decidir as regras ainda abertas antes de implementar: DC, vantagem, crítico, CA, progressão de nível e turno de monstro/horda.
