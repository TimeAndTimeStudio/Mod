package studio.timeandtime.claimmap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Time SMP: server-only Fabric mod that divides the world into 500x500 cells
 * which players claim and manage through the in-game GUI, and protects those
 * claimed cells (see plan.md).
 */
public class ClaimMap implements ModInitializer {
	public static final String MOD_ID = "timesmp";
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

	public ClaimMapConfig config() {
		return config;
	}

	public studio.timeandtime.claimmap.world.WorldManager worldManager() {
		return worldManager;
	}

	public LandPriceService landPrices() {
		return landPrices;
	}

	public RegionManager regions() {
		return regions;
	}

	public EconomyManager economy() {
		return economy;
	}

	public AuctionManager auctions() {
		return auctions;
	}

	public TeleportManager teleports() {
		return teleports;
	}

	public HomeManager homeManager() {
		return homeManager;
	}

	public PlayerMarket playerMarket() {
		return playerMarket;
	}

	public WorldSpawnProtection spawnProtection() {
		return spawnProtection;
	}

	public studio.timeandtime.claimmap.world.EndArenaManager endArena() {
		return endArena;
	}

	public ServerSettings serverSettings() {
		return serverSettings;
	}

	private volatile MinecraftServer server;
	private ClaimMapConfig config;
	private ServerSettings serverSettings;
	private RegionManager regions;
	private Protection protection;
	private WorldSpawnProtection spawnProtection;
	private studio.timeandtime.claimmap.world.EndArenaManager endArena;
	private ZoneAnnouncer zoneAnnouncer;
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
	private TeleportManager teleports;
	private HomeManager homeManager;
	private studio.timeandtime.claimmap.world.WorldManager worldManager;
	private LandPriceService landPrices;
	private AuthGate authGate;
	private ScheduledExecutorService backupExecutor;
	private ScheduledExecutorService priceExecutor;

	@Override
	public void onInitialize() {
		INSTANCE = this;
		seedDefaultServerIconIfMissing();
		seedDefaultResourcePackIfMissing();

		config = new ClaimMapConfig();
		config.load();
		serverSettings = new ServerSettings();
		worldManager = new studio.timeandtime.claimmap.world.WorldManager(config);
		regions = new RegionManager(config);
		regions.load();
		links = new LinkManager(config);
		links.load();
		accounts = new AccountManager(config);
		accounts.load();
		economy = new EconomyManager(config.dataDir(), config, System::currentTimeMillis);
		economy.load();
		authGate = new AuthGate(config, accounts, links, economy);
		seedDefaultPricesIfMissing(config.dataDir());
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
		endArena = new studio.timeandtime.claimmap.world.EndArenaManager(config.dataDir());
		endArena.load();
		protection = new Protection(regions, endArena);
		zoneAnnouncer = new ZoneAnnouncer(config, regions);
		studio.timeandtime.claimmap.lod.LodService.instance().registerNetworking();
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
			studio.timeandtime.claimmap.lod.LodService.instance().onPlayerJoin(handler.getPlayer());
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
			studio.timeandtime.claimmap.lod.LodService.instance().onPlayerDisconnect(handler.getPlayer());
		});
		landPrices = new LandPriceService(config.dataDir().resolve("land-price.json"), regions.ownerMap().size());

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(srv -> {
			studio.timeandtime.claimmap.world.PregenManager.instance().tick(srv);
			if (teleports != null) teleports.tick(srv);
			if (zoneAnnouncer != null) zoneAnnouncer.tick(srv);
			if (worldManager != null) worldManager.tick();
			// Keep the per-player item JSON warm when items are used or picked up.
			// Every 5 seconds, only carried stacks are re-read; container rows stay
			// until the next full item scan.
			if (srv.getTickCount() % 100 == 0) {
				for (net.minecraft.server.level.ServerPlayer p : srv.getPlayerList().getPlayers()) {
					try {
						playerItems.mergeCarried(p.getUUID(), p.getGameProfile().name(),
								ItemSources.listCarried(p));
					} catch (RuntimeException e) {
						LOGGER.warn("[timesmp] Could not refresh item cache for {}: {}",
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

		teleports = new TeleportManager();
		homeManager = new HomeManager(config.dataDir().resolve("homes.json"));
		homeManager.load();
		worldManager.attachTeleports(teleports);

		new ClaimCommands(regions, links, accounts, config, economy, authGate, shop, mailbox, auctions, prices,
				admin, teleports, homeManager, worldManager, playerMarket).register();
		new AuctionTicker(auctions, playerMarket).register();
		protection.register();
		new PortalGate(serverSettings).register();
		spawnProtection = new WorldSpawnProtection(config.dataDir());
		spawnProtection.register();
		authGate.registerHooks();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			this.server = server;
			seedDefaultServerIconIfMissing();
			worldManager.start(server);
			ItemRegistryAdapter.init(server);
			auctions.closeExpired(System.currentTimeMillis());
			LOGGER.info("[timesmp] Started (in-game GUI only; see config/timesmp/timesmp.properties)");
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (endArena != null) {
				net.minecraft.server.level.ServerLevel endLevel = server.getLevel(net.minecraft.world.level.Level.END);
				endArena.cleanupPlacedBlocks(endLevel);
			}
			if (homeManager != null) homeManager.save();
			if (worldManager != null) worldManager.shutdown();
			regions.save();
			economy.backupNow();
			prices.stop();
			if (backupExecutor != null) backupExecutor.shutdownNow();
			if (priceExecutor != null) priceExecutor.shutdownNow();

			// Automated world backup before complete shutdown
			try {
				studio.timeandtime.claimmap.world.WorldBackupManager.performBackup(server, serverSettings);
			} catch (Exception e) {
				LOGGER.error("[timesmp] Error during world backup on shutdown", e);
			}

			this.server = null;
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			if (worldManager != null) worldManager.closeStorage();
		});
	}

	private static void seedDefaultPricesIfMissing(java.nio.file.Path dir) {
		java.nio.file.Path pricesJson = dir.resolve("prices.json");
		java.nio.file.Path cacheJson = dir.resolve("prices-cache.json");
		if (java.nio.file.Files.exists(pricesJson)) return;
		try (var in = ClaimMap.class.getResourceAsStream("/default-prices.json")) {
			if (in != null) {
				java.nio.file.Files.createDirectories(dir);
				byte[] bytes = in.readAllBytes();
				java.nio.file.Files.write(pricesJson, bytes);
				java.nio.file.Files.write(cacheJson, bytes);
				LOGGER.info("[timesmp] Seeded initial prices.json and prices-cache.json from default-prices.json");
			}
		} catch (Exception e) {
			LOGGER.warn("[timesmp] Could not seed default-prices.json: {}", e.getMessage());
		}
	}

	private static void seedDefaultServerIconIfMissing() {
		try {
			java.nio.file.Path iconFile = net.fabricmc.loader.api.FabricLoader.getInstance()
					.getGameDir().resolve("server-icon.png");
			if (java.nio.file.Files.exists(iconFile)) return;
			try (var in = ClaimMap.class.getResourceAsStream("/server-icon.png")) {
				if (in != null) {
					java.nio.file.Files.copy(in, iconFile);
					LOGGER.info("[timesmp] Seeded default server-icon.png for server list");
				} else {
					LOGGER.warn("[timesmp] Could not find /server-icon.png in mod resources");
				}
			}
		} catch (Exception e) {
			LOGGER.warn("[timesmp] Could not seed default server-icon.png: {}", e.getMessage());
		}
	}

	private static void seedDefaultResourcePackIfMissing() {
		try {
			java.nio.file.Path rpFile = net.fabricmc.loader.api.FabricLoader.getInstance()
					.getGameDir().resolve("resourcepack.zip");
			if (java.nio.file.Files.exists(rpFile)) return;
			try (var in = ClaimMap.class.getResourceAsStream("/resourcepack.zip")) {
				if (in != null) {
					java.nio.file.Files.copy(in, rpFile);
					LOGGER.info("[timesmp] Seeded default resourcepack.zip for server resource pack");
				} else {
					LOGGER.warn("[timesmp] Could not find /resourcepack.zip in mod resources");
				}
			}
		} catch (Exception e) {
			LOGGER.warn("[timesmp] Could not seed default resourcepack.zip: {}", e.getMessage());
		}
	}
}
