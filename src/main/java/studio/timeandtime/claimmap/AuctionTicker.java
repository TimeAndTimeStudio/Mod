package studio.timeandtime.claimmap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Closes ended auctions and retries pending player-market deliveries once per
 * second on the server thread (PHASES.md 6.4). Everything is idempotent, so a
 * missed tick or a restart is harmless.
 */
public final class AuctionTicker {
	private final AuctionManager auctions;
	private final PlayerMarket market;
	private int ticks;

	public AuctionTicker(AuctionManager auctions, PlayerMarket market) {
		this.auctions = auctions;
		this.market = market;
	}

	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 20 != 0) return;
			try {
				auctions.closeExpired(System.currentTimeMillis());
				market.retryPending();
			} catch (RuntimeException e) {
				ClaimMap.LOGGER.error("[timesmp] Auction settle tick failed", e);
			}
		});
	}
}
