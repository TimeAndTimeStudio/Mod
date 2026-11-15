package studio.timeandtime.claimmap.world;

/** Lifecycle of a world discovered under {@code worlds/}. */
public enum WorldState {
	DISCOVERED,
	LOADING,
	LOADED,
	UNLOADING,
	UNLOADED,
	ERROR
}
