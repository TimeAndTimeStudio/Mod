package studio.timeandtime.claimmap;

/**
 * Single lock for every money / mailbox / auction / shop mutation (R4).
 * Never hold this while waiting on the Minecraft server thread.
 */
public final class Market {
	public static final Object LOCK = new Object();

	private Market() {
	}
}
