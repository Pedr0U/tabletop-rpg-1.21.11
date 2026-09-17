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

### Configuração do Minecraft

1. Abra o launcher do Minecraft
2. Crie um perfil Fabric para a versão **1.21.11**
3. Certifique-se de que o **Fabric Loader** esteja instalado
4. Vá em "Mods" e certifique-se de que o TableTop RPG esteja ativo
5. Jogar!

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

## Desenvolvimento

### Build Manual

```bash
# Compilar apenas o cliente (necessário para testes)
./gradlew client:jar

# Ou compilar tudo
./gradlew build
```
4. Registre novos receptores em `TabletopRpgClient.java`

## Licença

Este projeto está licenciado sob a licença CC0-1.0 - veja o arquivo LICENSE para mais detalhes.
