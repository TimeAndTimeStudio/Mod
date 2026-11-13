package studio.timeandtime.claimmap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claim Map: server-only Fabric mod that divides the world into 500x500 cells
 * which players can claim from a web page, renders an up-to-date map of only
 * the explored chunks and protects claimed cells (see plan.md).
 */
public class ClaimMap implements ModInitializer {
	public static final String MOD_ID = "claimmap";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static ClaimMap INSTANCE;

	public static ClaimMap instance() {
		return INSTANCE;
	}

	public static MinecraftServer getServer() {
		return INSTANCE == null ? null : INSTANCE.server;
	}

	private volatile MinecraftServer server;
	private ClaimMapConfig config;
	private RegionManager regions;
	private MapRenderer maps;
	private Protection protection;
	private LinkManager links;
	private WebServer web;

	@Override
	public void onInitialize() {
		INSTANCE = this;

		config = new ClaimMapConfig();
		config.load();
		regions = new RegionManager(config);
		regions.load();
		maps = new MapRenderer(config);
		links = new LinkManager(config);
		protection = new Protection(regions);
		web = new WebServer(config, regions, maps, links);

		new ClaimCommands(regions, links, config).register();
		protection.register();

		// Draw a map of exactly the chunks that are actually loaded / explored.
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> maps.onChunkLoaded(level, chunk));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			this.server = server;
			maps.start();
			web.start();
			LOGGER.info("[claimmap] Started. Web map/API on {}:{} (see config/claimmap/claimmap.properties)",
					config.bindAddress, config.port);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			web.stop();
			maps.flush();
			maps.stop();
			regions.save();
			this.server = null;
		});
	}
}
