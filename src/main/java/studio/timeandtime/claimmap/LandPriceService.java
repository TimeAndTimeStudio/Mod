package studio.timeandtime.claimmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages dynamic land/cell pricing with natural market fluctuations.
 * Floor price: 100,000 coins (minimum price when nobody has bought for a long time).
 * Ceiling price: 1,000,000 coins.
 * When a cell is purchased, price increases (+30,000 coins).
 * Over time without purchases, price gradually decays (15,000 coins per hour) down to 100,000 coins.
 */
public class LandPriceService {
	public static final long MIN_PRICE = 100_000L;
	public static final long MAX_PRICE = 1_000_000L;
	public static final long PURCHASE_INCREMENT = 30_000L;
	public static final long DECAY_PER_HOUR = 15_000L; // 15,000 coins drop per hour of inactivity
	public static final double DECAY_PER_MS = (double) DECAY_PER_HOUR / (60.0 * 60.0 * 1000.0);

	private final Path file;
	private long currentPrice = MIN_PRICE;
	private long lastUpdateTime = System.currentTimeMillis();

	public LandPriceService(Path file, int initialClaimedCount) {
		this.file = file;
		load(initialClaimedCount);
	}

	public synchronized long currentPrice() {
		applyDecay(System.currentTimeMillis());
		return currentPrice;
	}

	public synchronized void onCellPurchased() {
		long now = System.currentTimeMillis();
		applyDecay(now);
		currentPrice = Math.min(MAX_PRICE, currentPrice + PURCHASE_INCREMENT);
		lastUpdateTime = now;
		save();
	}

	private void applyDecay(long now) {
		if (now <= lastUpdateTime) return;
		long elapsedMs = now - lastUpdateTime;
		if (currentPrice > MIN_PRICE) {
			long decay = (long) (elapsedMs * DECAY_PER_MS);
			if (decay > 0) {
				currentPrice = Math.max(MIN_PRICE, currentPrice - decay);
				lastUpdateTime = now;
				save();
			}
		} else {
			lastUpdateTime = now;
		}
	}

	private void load(int initialClaimedCount) {
		if (file == null || !Files.exists(file)) {
			// Seed initial price based on current claimed cells if starting fresh
			currentPrice = Math.min(MAX_PRICE, Math.max(MIN_PRICE, 100_000L + ((long) initialClaimedCount * 15_000L)));
			lastUpdateTime = System.currentTimeMillis();
			save();
			return;
		}
		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
			if (obj.has("price")) currentPrice = Math.min(MAX_PRICE, Math.max(MIN_PRICE, obj.get("price").getAsLong()));
			if (obj.has("lastUpdateTime")) lastUpdateTime = obj.get("lastUpdateTime").getAsLong();
			applyDecay(System.currentTimeMillis());
		} catch (Exception e) {
			ClaimMap.LOGGER.warn("[timesmp] Could not read land-price.json: {}", e.getMessage());
		}
	}

	public synchronized void save() {
		if (file == null) return;
		try {
			JsonObject obj = new JsonObject();
			obj.addProperty("price", currentPrice);
			obj.addProperty("lastUpdateTime", lastUpdateTime);
			if (file.getParent() != null) Files.createDirectories(file.getParent());
			Files.writeString(file, obj.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			ClaimMap.LOGGER.warn("[timesmp] Could not save land-price.json: {}", e.getMessage());
		}
	}
}
