# TableTop RPG Mod - Minecraft 1.21.11

## Visão Geral
Mod de tabletop RPG para Minecraft Fabric 1.21.11 que adiciona um menu principal interativo com sistema de turns, modos de jogo e navegação entre telas.

## Requisitos

- **Minecraft Launcher**: Versão 1.21.11 com Fabric
- **Fabric Loader**: >= 0.19.5
- **Java**: Versão 21 ou superior
- **Fabric API**: Necessária (gerenciada automaticamente pelo gradle)

## Instalação

### Método 1: Via Gradle (Desenvolvimento)

1. Clone ou extraia o projeto
2. Execute o gradle para gerar o arquivo `.jar`:
   ```bash
   ./gradlew build
   ```
   ou no Windows:
   ```bash
   .\gradlew build
   ```

3. O arquivo `.jar` será gerado em `build/libs/tabletop-rpg-*.jar`

4. Coloque o `.jar` na pasta `mods` do seu perfil Fabric no launcher

### Método 2: Via CurseForge / Modrinth

- Baixe a versão mais recente do mod
- Coloque na pasta `mods` do seu perfil Fabric

### Configuração do Minecraft

1. Abra o launcher do Minecraft
2. Crie um perfil Fabric para a versão **1.21.11**
3. Certifique-se de que o **Fabric Loader** esteja instalado
4. Vá em "Mods" e certifique-se de que o TableTop RPG esteja ativo
5. Jogar!

## Como Testar

### Testando o Menu Principal

1. Inicie o Minecraft com o mod carregado
2. Pressione a tecla **`R`** (padrão) para abrir o menu do TableTop RPG
3. O menu deve aparecer centralizado na tela com a proporção correta

### Verificando o Comportamento em Diferentes Resoluções

- **Telas grandes** (1920x1080, 2560x1440): O menu deve escalar para cima, ocupando mais da tela mantendo a proporção 408x612
- **Telas médias** (1600x900, 1366x768): O menu deve caber completamente com margens iguais
- **Telas pequenas** (1280x720, notebooks): O menu escala para baixo, mas os botões nunca ficam menores que 100px de largura

### Testando a Tela de Jogadores

1. No menu principal, clique no botão **"Players"**
2. A tela de lista de jogadores deve abrir
3. Os botões dos jogadores devem estar centralizados e com largura consistente (entre 100-200px)
4. Clique em "Back" para retornar ao menu principal

### Testando o Modo Mestre vs Jogador

- **Como Mestre**: Ao abrir o menu, você verá "Role: MASTER" e pode acessar todas as funcionalidades
- **Como Jogador**: Ao abrir o menu, você verá "Role: PLAYER" e o botão "End Turn" aparecerá se for o seu turno

## Controles

| Tecla | Função |
|-------|--------|
| `R` | Abrir/Fechar menu principal TableTop RPG |

## Estrutura do Projeto

```
src/
├── main/
│   ├── java/com/pedro/tabletoprpg/
│   │   ├── TabletopRpg.java       # ModInitializer principal
│   │   └── RpgNetworking.java     # Payloads e handlers de rede
│   └── resources/
│       ├── fabric.mod.json        # Configuração do mod
│       └── assets/tabletop-rpg/
│           └── textures/gui/rpg_menu.png  # Textura do menu (408x612)
└── client/
    ├── java/com/pedro/tabletoprpg/client/
    │   ├── TabletopRpgClient.java # ClientModInitializer
    │   ├── RpgMenuScreen.java     # Menu principal (TEXTO CORRIGIDO)
    │   └── PlayerListScreen.java  # Tela de lista de jogadores (TEXTO CORRIGIDO)
    └── resources/
        └── tabletop-rpg.client.mixins.json
```

## Problemas Conhecidos e Correções

### Problemas Resolvidos nesta versão:

1. **Escalonamento do Menu**: Removido o teto `Math.min(1f, ...)` que impedia o menu de escalar em telas maiores. Agora o menu proporcionalmente se ajusta a qualquer resolução.

2. **Largura Mínima dos Botões**: Adicionada proteção `MIN_BUTTON_WIDTH = 100` para evitar que botões fiquem muito estreitos em telas muito pequenas.

3. **PlayerListScreen**: 
   - Largura dos botões agora escala entre 100-200px em vez de fixa em 200px
   - Espaçamento vertical ajustado para melhor visualização
   - Posicionamento do botão "Back" mais consistente

4. **Razão de Aspecto**: A textura do menu (408x612, proporção 1:1.5) é mantida em todas as resoluções sem distorção ou corte.

## Desenvolvimento

### Build Manual

```bash
# Compilar apenas o cliente (necessário para testes)
./gradlew client:jar

# Ou compilar tudo
./gradlew build
```

### Adicionando Novas Funcionalidades

1. Edite `RpgMenuScreen.java` para alterar o layout do menu
2. Edite `PlayerListScreen.java` para alterar a tela de jogadores
3. Adicione novos payloads em `RpgNetworking.java` para novas funcionalidades
4. Registre novos receptores em `TabletopRpgClient.java`

## Licença

Este projeto está licenciado sob a licença CC0-1.0 - veja o arquivo LICENSE para mais detalhes.