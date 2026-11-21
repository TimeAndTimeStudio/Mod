package studio.timeandtime.claimmap.lod;

import net.minecraft.resources.Identifier;

/**
 * Protocol constants and packet identifiers for tml:lod.
 */
public final class LodProtocol {
	public static final String NAMESPACE = "tml";
	public static final String PATH = "lod";
	public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath(NAMESPACE, PATH);

	public static final int VERSION = 1;
	public static final int MAX_DISTANCE = 1024;
	public static final int IN_FLIGHT = 8;
	public static final int PER_SECOND = 64;
	public static final int SIDE = 16;

	private LodProtocol() {}
}
