package studio.timeandtime.claimmap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Closes ended auctions once per second on the server thread (PHASES.md 6.4).
 * Settling is idempotent, so a missed tick or a restart is harmless.
 */
public final class AuctionTicker {
	private final AuctionManager auctions;
	private int ticks;

	public AuctionTicker(AuctionManager auctions) {
		this.auctions = auctions;
	}

	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 20 != 0) return;
			try {
				auctions.closeExpired(System.currentTimeMillis());
			} catch (RuntimeException e) {
				ClaimMap.LOGGER.error("[claimmap] Auction settle tick failed", e);
			}
		});
	}
}
