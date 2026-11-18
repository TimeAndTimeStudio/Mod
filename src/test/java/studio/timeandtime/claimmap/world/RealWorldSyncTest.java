package studio.timeandtime.claimmap.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealWorldSyncTest {
	@Test
	void mapsClearCodes() {
		for (int code : new int[]{0, 1, 2, 3, 45, 48}) {
			assertEquals(RealWorldSync.Weather.CLEAR, RealWorldSync.mapWeather(code), "code " + code);
		}
	}

	@Test
	void mapsRainCodes() {
		for (int code : new int[]{51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82,
				71, 73, 75, 77, 85, 86}) {
			assertEquals(RealWorldSync.Weather.RAIN, RealWorldSync.mapWeather(code), "code " + code);
		}
	}

	@Test
	void mapsThunderCodes() {
		for (int code : new int[]{95, 96, 99}) {
			assertEquals(RealWorldSync.Weather.THUNDER, RealWorldSync.mapWeather(code), "code " + code);
		}
	}
}
