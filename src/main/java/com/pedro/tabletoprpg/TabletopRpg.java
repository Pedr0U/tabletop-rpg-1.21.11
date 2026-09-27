package com.pedro.tabletoprpg;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TabletopRpg implements ModInitializer {
	public static final String MOD_ID = "tabletop-rpg";

	// This logger is used to write text to the console and the log file.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("[TabletopRPG] Inicializando o mod...");

		// Registra os handlers de interação (ataque, uso de bloco/item/entidade)
		PlayerControlHandler.register();

		// Registra as regras de dano da sessão (jogadores e mobs imunes a dano físico)
		DamageControlHandler.register();

		// Registra o controle de combate (seleção/movimento de monstros, auras, highlight)
		CombatController.register();

		// Registra os comandos do Mestre (/rpg ...)
		MasterCommands.register();

		// Registra o Sheet Editor e a aba criativa do mod. Antes dos payloads:
		// o item usa o payload `open_sheet_editor` quando clicado.
		com.pedro.tabletoprpg.item.ModItems.register();

		// Registra os payloads de rede (comum) e os receptores do lado servidor
		RpgNetworking.registerPayloads();
		RpgNetworking.registerServerReceivers();

		// Liga o modelo global da ficha ao ciclo de vida do servidor: carrega do
		// SavedData do overworld no boot e manda o modelo atual a cada jogador
		// que entra. Precisa vir DEPOIS de registerServerReceivers, porque o
		// JOIN usa o payload que ali foi registrado.
		SheetModelStore.register();

		// Grava a ficha de cada jogador no encerramento do servidor (Ctrl+C).
		// Sem isso a ficha vive so no mapa estatico e se perde ao fechar.
		SheetPersistenceEvents.register();

		LOGGER.info("[TabletopRPG] Mod inicializado com sucesso.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
