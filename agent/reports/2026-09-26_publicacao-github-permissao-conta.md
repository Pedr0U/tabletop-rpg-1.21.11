# Publicação no GitHub: permissão de conta e envio das tags

**Data:** 26/09/2026, entre 22:50 e 23:15
**Escopo:** enviar ao GitHub o commit C03 e os três checkpoints; descubrir e resolver o
bloqueio de permissão da conta.

## Objetivo

O usuário queria o versionamento no GitHub, não só local. Isso exigia resolver a
permissão da conta e decidir o que publicar, porque o repositório é público.

## Decisões do usuário

- Publicar **tudo**, incluindo o conteúdo de `agent/` (memória de engenharia e
  relatórios), mesmo com o repositório público. Autorização explícita.
- Autorizou **commit + push** da correção da placa de pressão.
- Confirmou que `Danylohcs` é a conta dele e que o repositório foi criado pela conta
  `Pedr0U`, e que ele não pode alterar as configurações dessa outra conta.

## Estado do repositório

- Remoto: `https://github.com/Pedr0U/tabletop-rpg-1.21.11.git`
- Visibilidade: **pública** (verificado pela API anônima do GitHub).
- `main` local estava 2 commits na frente de `origin/main`, que estava em `a53f26f`.
- A credencial guardada no Windows é da conta `Danylohcs`
  (`cmdkey /list` → `git:https://github.com`, usuário `Danylohcs`).

## C03: commit da placa de pressão

- Commit `47e7577`, tag anotada `checkpoint-20260926-2303-placa-de-pressao`.
- Arquivos: `CombatController.java` (+15/-1), `project-memory.md` (+10) e o relatório
  `2026-09-26_destino-mob-superficie-escada.md` (+239).
- Antes de commitar, o relatório foi corrigido: ele dizia "sem commit" e passou a citar o
  checkpoint C03. Sem isso o histórico registraria um estado falso.
- Build anterior já verde: `.\gradlew.bat build --no-daemon` → `BUILD SUCCESSFUL`.

## Problema 1: push recusado com 403

Primeira tentativa falhou:

```
remote: Permission to Pedr0U/tabletop-rpg-1.21.11.git denied to Danylohcs.
fatal: ... error: 403
```

**Diagnóstico.** A mensagem do GitHub **nomeia a conta** que autenticou, ou seja, a
autenticação funcionou. Se o token estivesse inválido ou expirado, a mensagem seria
diferente ("could not read Username" ou "Invalid username or password"). A recusa foi por
**permissão**, não por credencial. Causa provável: a conta estava como colaborador com
permissão **Read**, que o GitHub também rotula como "colaborador", mas que não permite
enviar commits.

**Como confirmar o próprio nível de acesso:** abrir o repositório logado como `Danylohcs` e
olhar a aba **People**. Se aparecer "Read", é falta de escrita.

**Resolução.** O usuário concedeu a permissão e repetiu o push:

```
a53f26f..47e7577  main -> main
```

Funcionou **com a credencial antiga**, sem reautenticar: a checagem de permissão acontece a
cada requisição, então não é preciso gerar token novo quando o acesso muda.

## Problema 2: as tags não subiram

`git push origin main` envia **apenas a branch**. As três tags continuaram locais e o
usuário teria perdido o versionamento no GitHub sem perceber, porque o push reporta
sucesso. O envio correto foi:

```powershell
git push origin --tags
```

Confirmado no remoto: as três tags anotadas existem em `origin`.

## Validações executadas

| O que | Como | Resultado |
|-------|------|-----------|
| Push do `main` | `git push origin main` | `a53f26f..47e7577` |
| Sincronia local/remota | `git status -sb`, `git rev-list --count origin/main..HEAD` | sem divergência, `AHEAD: 0` |
| `main` no remoto | `git ls-remote origin refs/heads/main` | `47e7577` |
| Tags no remoto | `git ls-remote --tags origin` | as 3 tags `checkpoint-*` |
| Build | `.\gradlew.bat build --no-daemon` | `BUILD SUCCESSFUL` |

## Não validado

- **Comportamento em jogo não foi validado.** C02 e C03 estão no GitHub, mas o teste com
  `runClient` ainda não aconteceu. Placa de pressão, escada, andaime e msg de recusa
  continuam como "código pronto para testar".
- A correção de videira e a camada de neve não foram implementadas.
- O repositório foi confirmado como **público** pela API; não houve verificação de se o
  conteúdo de `agent/` já estava parcialmente público em `a53f26f`.

## Riscos e observações

- Publicar `agent/` em repositório público expõe o nome do usuário, o caminho local
  `C:\Users\Danylo Henrique\Documents\GitHub\agent` e o processo do TCC. O usuário
  autorizou isso, mas é uma decisão que não deve ser repetida por omissão em outro
  projeto.
- O e-mail `danylohcs@gmail.com` já consta como autor dos commits enviados, por isso a
  identidade Git é pública no histórico. Isso foi anterior a esta sessão.
- Nenhum `push --force` foi executado.

## Aprendizados

1. **`git push origin main` não envia tags.** Sempre accompany de `git push origin
   --tags` ao versionar, e verifique com `git ls-remote --tags origin`.
2. **403 que nomeia a conta = problema de permissão, não de credencial.** Essa distinção
   economiza tempo: não adianta regenerar token, é preciso acesso de escrita.
3. **Colaborador Read não envia commit.** O GitHub chama ambos de "colaborador".
4. **Mudança de permissão não exige novo token**, porque a autorização é checada por
   requisição.

## Próximos passos

- Testar placa de pressão, escada, andaime e recusa em jogo.
- Se aprovado, C04 com a validação registrada.
- Ajustar `AuraRenderer.java:101` ao novo Y do mob.
