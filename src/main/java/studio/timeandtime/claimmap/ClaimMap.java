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

	public studio.timeandtime.claimmap.world.WorldManager worldManager() {
		return worldManager;
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
	private MailboxManager mailbox;
	private ShopManager shop;
	private AuctionManager auctions;
	private PlayerMarket playerMarket;
	private ConsignmentManager consignments;
	private PlayerItemStore playerItems;
	private AuditLog audit;
	private AdminService admin;
	private SalesTracker salesTracker;
	private studio.timeandtime.claimmap.world.WorldManager worldManager;
	private AuthGate authGate;
	private WebServer web;
	private ScheduledExecutorService backupExecutor;
	private ScheduledExecutorService priceExecutor;

	@Override
	public void onInitialize() {
		INSTANCE = this;

		config = new ClaimMapConfig();
		config.load();
		worldManager = new studio.timeandtime.claimmap.world.WorldManager(config);
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
		seedDefaultPricesIfMissing(config.dataDir().resolve("prices-cache.json"));
		prices = new PriceService(config.dataDir(), config, System::currentTimeMillis);
		prices.loadCache();
		mailbox = new MailboxManager(config.mailboxFile(), System::currentTimeMillis, config.mailboxMaxEntries);
		mailbox.load();
		auctions = new AuctionManager(config.dataDir(), config, economy, mailbox, System::currentTimeMillis);
		auctions.load();
		auctions.validateEscrow();
		consignments = new ConsignmentManager(config.dataDir(), System::currentTimeMillis);
		consignments.load();
		auctions.attachConsignments(consignments);
		auctions.attachRegions(regions);
		playerItems = new PlayerItemStore(config.playerItemsDir());
		playerMarket = new PlayerMarket(config.dataDir(), config, economy, mailbox, System::currentTimeMillis);
		playerMarket.load();
		salesTracker = new SalesTracker(config.dataDir(), System::currentTimeMillis);
		shop = new ShopManager(config, economy, mailbox, prices, System::currentTimeMillis);
		shop.attachAuctions(auctions);
		shop.attachSalesTracker(salesTracker);
		shop.attachAccounts(accounts);
		audit = new AuditLog(config.dataDir(), System::currentTimeMillis);
		admin = new AdminService(config, economy, accounts, links, prices, auctions, mailbox, shop, audit,
				consignments,
				uuid -> {
					MinecraftServer s = server;
					return s != null && s.getPlayerList().getPlayer(uuid) != null;
				});
		admin.attachSalesTracker(salesTracker);
		protection = new Protection(regions);
		PlayerPositions.init(config.dataDir());
		web = new WebServer(config, regions, maps, links, accounts, economy, prices, mailbox, shop, auctions,
				playerMarket, consignments, admin, playerItems, worldManager, salesTracker);

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(srv -> {
			if (worldManager != null) worldManager.tick();
			if (srv.getTickCount() % 20 == 0) {
				for (net.minecraft.server.level.ServerPlayer p : srv.getPlayerList().getPlayers()) {
					PlayerPositions.update(p);
					if (p.level() instanceof net.minecraft.server.level.ServerLevel sl) {
						maps.onPlayerPosition(sl, p.getBlockX(), p.getBlockZ());
					}
				}
			}
			// Keep the per-player item JSON warm when items are used or picked up.
			// Every 5 seconds, only carried stacks are re-read; container rows stay
			// until the next full /api/sources scan.
			if (srv.getTickCount() % 100 == 0) {
				for (net.minecraft.server.level.ServerPlayer p : srv.getPlayerList().getPlayers()) {
					try {
						playerItems.mergeCarried(p.getUUID(), p.getGameProfile().name(),
								ItemSources.listCarried(p));
					} catch (RuntimeException e) {
						LOGGER.warn("[claimmap] Could not refresh item cache for {}: {}",
								p.getGameProfile().name(), e.getMessage());
					}
				}
			}
		});

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

		new ClaimCommands(regions, links, accounts, config, economy, authGate, shop, mailbox, auctions, prices,
				admin).register();
		new AuctionTicker(auctions, playerMarket).register();
		protection.register();
		authGate.registerHooks();

		// Draw a map of exactly the chunks that are actually loaded / explored.
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> maps.onChunkLoaded(level, chunk));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			this.server = server;
			worldManager.start(server);
			ItemRegistryAdapter.init(server);
			auctions.closeExpired(System.currentTimeMillis());
			maps.start();
			web.start();
			LOGGER.info("[claimmap] Started. Web map/API on {}:{} (see config/claimmap/claimmap.properties)",
					config.bindAddress, config.port);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (worldManager != null) worldManager.shutdown();
			web.stop();
			PlayerPositions.save();
			maps.flush();
			maps.stop();
			regions.save();
			economy.backupNow();
			prices.stop();
			if (backupExecutor != null) backupExecutor.shutdownNow();
			if (priceExecutor != null) priceExecutor.shutdownNow();
			this.server = null;
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			if (worldManager != null) worldManager.closeStorage();
		});
	}

	private static void seedDefaultPricesIfMissing(java.nio.file.Path cacheFile) {
		if (java.nio.file.Files.exists(cacheFile)) return;
		try (var in = ClaimMap.class.getResourceAsStream("/default-prices.json")) {
			if (in != null) {
				java.nio.file.Files.createDirectories(cacheFile.getParent());
				java.nio.file.Files.copy(in, cacheFile);
				LOGGER.info("[claimmap] Seeded initial prices-cache.json from default-prices.json");
			}
		} catch (Exception e) {
			LOGGER.warn("[claimmap] Could not seed default-prices.json: {}", e.getMessage());
		}
	}
}
