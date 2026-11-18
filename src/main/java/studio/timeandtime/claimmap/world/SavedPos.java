package studio.timeandtime.claimmap.world;

/**
 * Saved coordinates and rotation for a player in a specific world.
 */
public record SavedPos(double x, double y, double z, float yaw, float pitch) {}
