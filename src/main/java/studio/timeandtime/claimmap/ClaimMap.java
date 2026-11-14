package studio.timeandtime.claimmap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Claim Map: server-only Fabric mod that divides the world into 500x500 cells
 * which players can claim from a web page, renders an up-to-date map of only
 * the explored chunks and protects claimed cells (see plan.md).
 */
public class ClaimMap implements ModInitializer {
	public static final String MOD_ID = "claimmap";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	/** Data backup cadence (PHASES.md 2.7). */
	private static final long BACKUP_INTERVAL_MS = 60L * 60_000L;

	private static ClaimMap INSTANCE;

	public static ClaimMap instance() {
		return INSTANCE;
	}

	public static MinecraftServer getServer() {
		return INSTANCE == null ? null : INSTANCE.server;
	}

	/** Login gate, used by the command mixin to decide whether a command is allowed. */
	public AuthGate authGate() {
		return authGate;
	}

	private volatile MinecraftServer server;
	private ClaimMapConfig config;
	private RegionManager regions;
	private MapRenderer maps;
	private Protection protection;
	private LinkManager links;
	private AccountManager accounts;
	private EconomyManager economy;
	private PriceService prices;
	private AuthGate authGate;
	private WebServer web;
	private ScheduledExecutorService backupExecutor;
	private ScheduledExecutorService priceExecutor;

	@Override
	public void onInitialize() {
		INSTANCE = this;

		config = new ClaimMapConfig();
		config.load();
		regions = new RegionManager(config);
		regions.load();
		maps = new MapRenderer(config);
		links = new LinkManager(config);
		links.load();
		accounts = new AccountManager(config);
		accounts.load();
		economy = new EconomyManager(config.dataDir(), config, System::currentTimeMillis);
		economy.load();
		authGate = new AuthGate(config, accounts, links, economy);
		prices = new PriceService(config.dataDir(), config, System::currentTimeMillis);
		prices.loadCache();
		protection = new Protection(regions);
		web = new WebServer(config, regions, maps, links, accounts, economy, prices);

		backupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "claimmap-backup");
			t.setDaemon(true);
			return t;
		});
		economy.startBackups(backupExecutor, BACKUP_INTERVAL_MS);
		priceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "claimmap-prices");
			t.setDaemon(true);
			return t;
		});
		prices.start(priceExecutor);

		new ClaimCommands(regions, links, accounts, config, economy, authGate).register();
		protection.register();
		authGate.registerHooks();

		// Draw a map of exactly the chunks that are actually loaded / explored.
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> maps.onChunkLoaded(level, chunk));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			this.server = server;
			ItemRegistryAdapter.init(server);
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
			economy.backupNow();
			prices.stop();
			if (backupExecutor != null) backupExecutor.shutdownNow();
			if (priceExecutor != null) priceExecutor.shutdownNow();
			this.server = null;
		});
	}
}
