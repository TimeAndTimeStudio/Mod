package studio.timeandtime.claimmap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Sends short transient messages to the player's action bar (the overlay
 * text above the hotbar) instead of chat, so they don't clutter the chat log.
 */
public final class ActionBar {
	private ActionBar() {
	}

	public static void send(ServerPlayer player, String text) {
		if (player == null || !player.isAlive()) return;
		player.sendOverlayMessage(Component.literal(text));
	}
}
