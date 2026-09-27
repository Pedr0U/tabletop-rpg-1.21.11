# 2026-09-26 — Destino do mob pela superfície real, escada pendurada e X com confirmação

## Objetivo
Corrigir o destino do mob quando o mestre clica num bloco, depois de o teste em jogo
revelar que blocos atravessáveis, meios blocos e placas de pressão ficavam errados, e
adicionar uma interação com blocos de escada. Fechar com versionamento no git.

## Escopo
`src/main/java/com/pedro/tabletoprpg/CombatController.java` (principal) e
`src/client/java/com/pedro/tabletoprpg/client/SkillsScreen.java` (pendente do turno
anterior, incluído no mesmo checkpoint por decisão do usuário).

---

## O que mudou

### 1. O Y do destino passou a ser o topo da superfície real

**Causa raiz.** O código usava `dest.getY() + 1.0`, que assume que o topo da forma de
colisão do bloco é o topo do bloco. Isso é falso para qualquer bloco de menos de 1 bloco
de altura. E o teste que o mod usava para decidir era `BlockState.blocksMotion()`, que
em 1.21.11 é:

```java
return block != COBWEB && block != BAMBOO_SAPLING && isSolid();
// isSolid(): b.getSize() >= 0.7291666666666666 || b.getYsize() >= 1.0
```

`getSize()` é o maior lado horizontal. Meio bloco tem lado 1.0 e altura 0.5, placa de
pressão tem 0.875 de lado e 0.125 de altura, moldura de portal do fim tem 1.0 de lado e
0.0625 de altura. **Todos os três passam no teste** e todos os três recebiam `+1`, então
o mob boiava acima da superfície real.

**Correção.** O topo vem da forma de colisão, não de um `+1` fixo:

```java
BlockState clicked = level.getBlockState(dest);
VoxelShape clickedShape = clicked.getCollisionShape(level, dest, CollisionContext.empty());
if (clicked.is(Blocks.LADDER)) {
    groundY = dest.getY();
} else if (!clickedShape.isEmpty() && clickedShape.bounds().maxY > 0.0) {
    groundY = dest.getY() + clickedShape.bounds().maxY;
} else {
    groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, dest.getX(), dest.getZ());
}
```

Usa a forma de **colisão**, não a de seleção, porque folha, cerca e placa se comportam de
formas diferentes nas duas.

| Clique | Antes | Agora |
|--------|-------|-------|
| bloco cheio | topo | topo (igual) |
| meio bloco | +1, boiando | metade do bloco |
| placa de pressão | +1, boiando | 2/16 |
| portal do fim | +1, boiando | 1/16 |
| cerca | topo | topo (igual) |
| escada | topo do bloco | pendurado na escada |
| grama, teia, redstone, videira | superfície da coluna | superfície da coluna (igual) |

### 2. Escada: mob pendurado, no ar

Pedido do usuário: clicar numa escada, mesmo sem bloco sólido embaixo, deixa o mob no ar
como se estivesse segurando a escada.

`groundY = dest.getY()` para `Blocks.LADDER`. Isso funciona porque o vanilla não prende a
entidade: `Entity.collide` devolve o vetor intacto quando o comprimento é zero, e com
`setNoAi(true)` nem `LivingEntity.travel` roda, logo não há gravidade nem código de
escada. O mob fica exatamente onde foi posto. Não é preciso `setNoGravity` nem
`noPhysics`.

`LadderBlock` não sobrescreve `getCollisionShape` e não tem `.noCollision()`, então a
forma de colisão é a mesma do `getShape`: uma fatia de 3 px colada na parede, com Y de 0
a 16. A caixa do mob, centrada no bloco, vai de 0.2 a 0.8 no eixo horizontal, enquanto a
fatia da escada ocupa de 0.8125 a 1.0: **não se sobrepõem**, então o check de AABB aceita
a posição. Isso vale para mobs de largura normal; um ravager (1.95) ou um ghast (4.0)
alcança os blocos vizinhos e é recusado, o que é correto, porque ele não caberia ali.

### 3. Andaime: nenhuma mudança necessária

O usuário pediu que o mob ficasse em cima do andaime e não o atravessasse. **O código já
fazia as duas coisas.** `ScaffoldingBlock.getCollisionShape` não devolve forma vazia: com
`CollisionContext.empty()`, `isAbove()` retorna sempre `true`, `isDescending()` é falso e
`isPlacement()` é falso, então o método retorna `SHAPE_STABLE`, cujo topo é Y=16/16. O
ramo da forma de colisão pega o andaime e põe o mob em cima, e a forma não vazia impede
atravessar. Não existe `withCollidingWithScaffolding` em 1.21.11; o campo sumiu.

### 4. Cerca e blocos com algo acima: recusar, como antes

O usuário optou por manter a recusa. **Nenhuma mudança feita.** O mecanismo, para registro:
o check de AABB testa a caixa do mob no destino, e com a cerca em Y=64 os pés vão para 65
e o corpo ocupa Y=65 e Y=66. **O bloco da cerca, em Y=64, nunca entra no teste.** Como o
mob tem 1.8 a 2.0 de altura, ele precisa de 2 blocos de ar acima da superfície. A recusa
acontece quando se clica a base de uma cerca de 2 blocos de altura, quando há telhado ou
placa acima, ou quando o mob é largo e invade o bloco vizinho. A recisão é um negativo
verdadeiro: o mob realmente não cabe.

### 5. Mensagem de recusa

`No room above that block to place the monster.` passou a dizer `entity`, por pedido do
usuário. Só essa mensagem mudou; `Monster selected` e `Monster moving to` ficaram como
estão.

---

## Hipóteses refutadas durante o diagnóstico

Duas teorias minhas que a verificação em bytecode derrubou. Vale registrar porque a
primeira parece convincente:

1. **"O mob afunda porque a gravidade o puxa meio bloco."** Falso. Com `setNoAi(true)`,
   `LivingEntity.travel` só é chamado quando `isEffectiveAi()` é verdadeiro, e
   `Mob.isEffectiveAi()` retorna falso com NoAI. Não há gravidade nem `move()` fora do
   `travel`.
2. **"O check de AABB barra a grama."** Falso. `getCollisionShape` de grama, fio de
   redstone e teia de aranha é **caixa vazia**, porque esses blocos têm `.noCollision()`.
   O check não tinha como acusar nada.
3. **"`getHeight` devolve o primeiro Y livre."** Falso. Em 1.21.11 os `+1` de
   `primeHeightmaps` e o `-1` de `ChunkAccess.getHeight` se cancelam com o `+1` de
   `Level.getHeight`, e o valor devolvido para terra em Y=63 é 64, que é o Y certo dos
   pés. O heightmap não era a causa do afundamento.

---

## Arquivos alterados

| Arquivo | Mudança |
|---------|---------|
| `src/main/java/com/pedro/tabletoprpg/CombatController.java` | destino pela superfície real, caso da escada, mensagem `entity`, imports de `Blocks`, `BlockState`, `Heightmap`, `CollisionContext`, `VoxelShape` |
| `src/client/java/com/pedro/tabletoprpg/client/SkillsScreen.java` | confirmação em dois cliques no X (pendente do turno anterior) |
| `agent/memory/project-memory.md` | +36 linhas, memória de engenharia |
| `agent/reports/` | +13 relatórios |

---

## Validações executadas

| O quê | Resultado |
|-------|-----------|
| `.\gradlew.bat build --no-daemon` | **BUILD SUCCESSFUL in 16s** |
| `scanEncoding` | OK, 74 arquivos em `src/` e `agent/`, 0 mojibake, 0 ideograma, 0 U+FFFD |
| Build anterior (a versão `blocksMotion()`) | BUILD SUCCESSFUL in 14s |
| Verificação em bytecode do vanilla 1.21.11 | 6 itens sobre escada e andaime, todos FATO |
| `git status` | árvore limpa depois do commit |
| Teste em jogo | **NÃO FEITO** |

Duas falhas de compilação ocorreram e foram corrigidas na hora: `getCollisionShape` não
existe com um único argumento neste mappings, e `Blocks` fica em
`net.minecraft.world.level.block`, não `net.minecraft.world.level`.

---

## Versionamento

- Commit: `c2871bb` — "Destino do mob pela superficie real, escada pendurada e X com confirmacao"
- Tag anotada: `checkpoint-20260926-2247-destino-mob-superficie-escada`
- Branch: `main`, 16 arquivos, 1470 inserções, 8 remoções
- 26/09/2026 22:47:29
- Sem push.
- Histórico de checkpoints registrado em
  `C:\Users\Danylo Henrique\Documents\GitHub\agent\VERSIONAMENTOS.md`

---

## Limitações e pendências

- **Nada foi validado em jogo.** O build está verde e o comportamento no vanilla foi
  verificado em bytecode, mas o mestre ainda precisa testar com `runClient`: meio bloco,
  placa de pressão, portal do fim, escada e andaime.
- **A escada não tem animação de escalada.** `onClimbable()` é só uma consulta de bloco
  em `LivingEntity` e o mob com NoAI parado satisfaz a condição, mas `Pose` não tem
  `CLIMBING` e `HumanoidModel.setupAnim` não menciona escalada. O mob fica parado no ar
  segurando a escada, mas **não levanta os braços**. Não há como isso ser feito sem mixin
  no renderizador, o que está fora do escopo.
- **Mobs largos não cabem em escada.** Ravager e ghast são recusados pelo check de AABB.
- **A aura não acompanha o novo Y.** `AuraRenderer.java:101` ainda usa
  `getHeight(MOTION_BLOCKING, x, z) + 1.05`. Com o mob no chão real de uma coluna com
  grama, o anel fica cerca de 1 bloco acima dos pés do mob. Problema pré-existente com a
  copa de árvore, aggravated por esta mudança. **Pendente de decisão do usuário.**
- **Videira não foi tratada.** Ela está em `BlockTags.CLIMBABLE` e tem colisão vazia, então
  cai no ramo da superfície da coluna e o mob vai para o chão debaixo. O usuário pediu
  apenas escada; tratar a videira como a escada é uma linha a mais, se ele quiser.

## Próximos passos

1. Teste em jogo dos cinco casos da tabela.
2. Decidir sobre o `AuraRenderer` alinhar com o Y novo.
3. Decidir se a videira deve pendurar como a escada.

---

## Complemento: placa de pressão (26/09/2026, depois do checkpoint C02)

O usuário testou e a **placa de pressão continuou boiando**. Causa raiz, verificada em
bytecode: em `Blocks.java` a placa é declarada com **`.noCollision()` e
`.forceSolidOn()` ao mesmo tempo**.

Isso produz uma combinação que nenhum dos dois ramos anteriores pegava:

- `hasCollision == false`, então `getCollisionShape(...)` devolve **`Shapes.empty()`** e o
  ramo "forma de colisão não vazia" é pulado;
- `forceSolidOn` faz `calculateSolid()` responder `true`, então `blocksMotion()` é
  `true` e o heightmap `MOTION_BLOCKING_NO_LEAVES` **conta a placa**, devolvendo Y+1.

Ou seja: a placa é ao mesmo tempo "sem colisão" e "chão", e o código a tratava como
atravessável, caindo no heightmap que aincludes.

**Correção:** um ramo intermediário que usa a **forma de seleção**, que existe e tem o
topo certo — `Block.column(14, 0, 1.0)` solta e `Block.column(14, 0, 0.5)` pressionada,
isto é, topo em 1/16 e 8/16. O teste `blocksMotion()` é o que separa a placa de um
decorativo como a grama, que também tem forma de seleção mas não é chão.

```java
} else {
    VoxelShape selectionShape = clicked.getShape(level, dest);
    groundY = clicked.blocksMotion() && !selectionShape.isEmpty() && selectionShape.bounds().maxY > 0.0
            ? dest.getY() + selectionShape.bounds().maxY
            : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, dest.getX(), dest.getZ());
}
```

O guarda `!isEmpty()` antes de `bounds()` não é defensivo: `VoxelShape.bounds()` **lança**
`UnsupportedOperationException("No bounds for empty shape.")` em forma vazia, e a placa é
justamente o caso que dispara isso.

**Validação:** `.\gradlew.bat build --no-daemon` → BUILD SUCCESSFUL in 15s, `scanEncoding`
OK em 75 arquivos, 0 mojibake, 0 ideograma. **Não validado em jogo.**

**Estado no git:** o commit `c2871bb` (C02) contém a versão **anterior**, sem esta
correção. A correção entrou no checkpoint **C03**, tag
`checkpoint-20260926-2303-placa-de-pressao`, por decisão do usuário de commitar e enviar
ao GitHub. O repositório `github.com/Pedr0U/tabletop-rpg-1.21.11` é **público**, e o
usuário autorizou explicitamente publicar o conteúdo de `agent/` junto com o código.

**Camada de neve continua noHeightmap.** `Blocks.SNOW` tem `.forceSolidOff()`, então
`blocksMotion()` é `false` e ela não entra no ramo novo: o mob vai para a superfície da
coluna e fica com os pés dentro da camada de neve, 1/16 a 8/16 de diferença visual. O
usuário não reclamou disso. Tratar a neve como a placa é a mesma linha de código, se ele
quiser.
