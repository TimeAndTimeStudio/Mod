package studio.timeandtime.timesmpclient;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TimeSmpClient implements ClientModInitializer {
	public static final String MOD_ID = "timesmp-client";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		LOGGER.info("[timesmp-client] Initialized TimeSMP Skin & Feature Client Mod");
	}
}
