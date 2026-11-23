package studio.timeandtime.timesmpclient.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import studio.timeandtime.timesmpclient.ui.TimesmpUi;

/**
 * Paints a TimeSMP top over a chest menu's vanilla background. The vanilla
 * player-inventory half and the dimmed world stay untouched, so the result
 * looks like DeluxeMenus/ItemsAdder menus. Drawing at TAIL keeps the shared
 * {@code blit(Identifier, ...)} overload, which exists in both 26.2 and 26.3
 * (the RenderPipeline class moved between those versions).
 */
@Mixin(ContainerScreen.class)
public abstract class ContainerScreenMixin {
	@Shadow
	@Final
	private int containerRows;

	@Inject(method = "extractBackground", at = @At("TAIL"))
	private void timesmp$topBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick, CallbackInfo ci) {
		if (containerRows != 3 && containerRows != 6) return;

		ContainerScreen screen = (ContainerScreen) (Object) this;
		String key = TimesmpUi.keyFor(screen.getTitle().getString(), containerRows);
		if (key == null) return;

		Identifier texture =
				Identifier.fromNamespaceAndPath("timesmp", "textures/gui/" + key + ".png");
		int left = (screen.width - 176) / 2;
		int top = (screen.height - (114 + containerRows * 18)) / 2;
		int topHeight = containerRows * 18 + 17;

		// The Identifier overload takes normalized UVs (the pipeline overload
		// takes texture pixels, but RenderPipeline moved between 26.2 and 26.3).
		graphics.blit(texture, left, top, 176, topHeight,
				0.0F, 0.0F, 176.0F / 256.0F, (float) topHeight / 256.0F);
	}
}