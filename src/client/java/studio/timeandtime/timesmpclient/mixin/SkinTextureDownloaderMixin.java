package studio.timeandtime.timesmpclient.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import studio.timeandtime.timesmpclient.TimeSmpClient;

@Mixin(SkinTextureDownloader.class)
public class SkinTextureDownloaderMixin {

	@Inject(method = "downloadSkin", at = @At("HEAD"), cancellable = true)
	private void handleCustomAndDataUriSkin(Path cachePath, String url, CallbackInfoReturnable<NativeImage> cir) {
		if (url == null) return;

		// 1. Handle data:image/png;base64,... URI
		if (url.startsWith("data:")) {
			try {
				int commaIndex = url.indexOf(',');
				if (commaIndex != -1) {
					String base64Data = url.substring(commaIndex + 1).trim();
					byte[] imageBytes = Base64.getDecoder().decode(base64Data);
					TimeSmpClient.LOGGER.info("[timesmp-client] Decoded data-URI skin ({} bytes)", imageBytes.length);
					try (InputStream in = new ByteArrayInputStream(imageBytes)) {
						NativeImage image = NativeImage.read(in);
						if (cachePath != null) {
							if (cachePath.getParent() != null) Files.createDirectories(cachePath.getParent());
							Files.write(cachePath, imageBytes);
						}
						cir.setReturnValue(image);
						return;
					}
				}
			} catch (Exception e) {
				// Fall through if parsing data URI fails
			}
		}

		// 2. Handle HTTP/HTTPS URLs (including non-Mojang domains)
		if (url.startsWith("http://") || url.startsWith("https://")) {
			// If cached locally, standard method handles it, but if downloading,
			// ensure non-Mojang connections with proper User-Agent
			if (cachePath != null && Files.isRegularFile(cachePath)) {
				try (InputStream in = Files.newInputStream(cachePath)) {
					cir.setReturnValue(NativeImage.read(in));
					return;
				} catch (Exception ignored) {
				}
			}

			try {
				URL skinUrl = URI.create(url).toURL();
				HttpURLConnection conn = (HttpURLConnection) skinUrl.openConnection();
				conn.setRequestProperty("User-Agent", "TimeSMP-Client/1.0 (Mozilla/5.0)");
				conn.setConnectTimeout(6000);
				conn.setReadTimeout(12000);
				conn.setDoInput(true);
				conn.connect();

				if (conn.getResponseCode() == 200) {
					try (InputStream in = conn.getInputStream()) {
						byte[] data = in.readAllBytes();
						if (cachePath != null) {
							if (cachePath.getParent() != null) Files.createDirectories(cachePath.getParent());
							Files.write(cachePath, data);
						}
						cir.setReturnValue(NativeImage.read(new ByteArrayInputStream(data)));
						return;
					}
				}
			} catch (Exception e) {
				// Fall through to vanilla handler if network fails
			}
		}
	}
}
