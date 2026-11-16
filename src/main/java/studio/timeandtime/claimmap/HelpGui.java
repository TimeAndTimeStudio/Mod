package studio.timeandtime.claimmap;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

public final class HelpGui {
	private HelpGui() {}

	public static void open(ServerPlayer player, RegionManager regions, ClaimMapConfig config,
			EconomyManager economy, PriceService prices, ShopManager shop,
			AuctionManager auctions, MailboxManager mailbox) {
		SimpleContainer container = new SimpleContainer(54);

		// Header / Welcome info (Slot 4)
		ItemStack banner = GuiButtons.button(
				GuiButtons.BRAND,
				Component.literal("§6§lTime SMP §e- ศูนย์ช่วยเหลือและคำสั่ง"),
				List.of(
						Component.literal("§7คู่มือแนะนำการใช้งานระบบและคำสั่งทั้งหมดในเซิร์ฟเวอร์"),
						Component.literal("§7--------------------------------"),
						Component.literal("§fคลิกที่แต่ละหมวดหมู่เพื่อดูคำสั่ง หรือเปิดใช้งานทันที!"),
						Component.literal("§eกดปุ่ม Esc บนคีย์บอร์ดเพื่อปิดหน้าต่าง")
				)
		);
		container.setItem(4, banner);

		// Slot 10: /claim - ระบบจับจองที่ดิน
		ItemStack claimItem = GuiButtons.button(
				GuiButtons.CLAIM,
				Component.literal("§a/claim §f- ระบบจับจองที่ดิน"),
				List.of(
						Component.literal("§7ปกป้องพื้นที่สิ่งก่อสร้างและกล่องของคุณ (64x64 บล็อก)"),
						Component.literal("§7--------------------------------"),
						Component.literal("§eคำสั่งที่ใช้งานได้:"),
						Component.literal("§a/claim §7- ยืนยันจับจองแปลงที่ดิน ณ จุดที่คุณยืน"),
						Component.literal("§a/claim add <ชื่อ> §7- ให้สิทธิ์เพื่อนสร้าง/เปิดกล่องในแปลง"),
						Component.literal("§a/claim remove <ชื่อ> §7- ยกเลิกสิทธิ์เพื่อนในแปลง"),
						Component.literal("§a/claim tf <ชื่อ> §7- โอนกรรมสิทธิ์แปลงนี้ให้เพื่อน"),
						Component.literal("§a/claim at <ราคา> [ชม.] §7- นำโฉนดที่ดินขึ้นประมูล"),
						Component.literal("§a/claim tp <cx,cz> §7- วาร์ปไปยังแปลงที่ระบุ (รอ 5 วิ)"),
						Component.literal("§7--------------------------------"),
						Component.literal("§b[คลิก] §7เพื่อเปิดดูแผนที่ที่ดินรอบตัว (/map)")
				)
		);
		container.setItem(10, claimItem);

		// Slot 12: /map - แผนที่เรดาร์สำรวจที่ดิน
		ItemStack mapItem = GuiButtons.button(
				GuiButtons.MAP,
				Component.literal("§b/map §f- แผนที่เรดาร์ Mini Grid"),
				List.of(
						Component.literal("§7ตรวจสอบที่ดิน 7x5 แปลงรอบตำแหน่งปัจจุบัน"),
						Component.literal("§7--------------------------------"),
						Component.literal("§eคุณสมบัติ:"),
						Component.literal("§f- ดูสถานะแปลง: ว่าง, ของคุณ, ของผู้เล่นอื่น"),
						Component.literal("§f- ปุ่มเลื่อนสำรวจทิศ: เหนือ, ใต้, ออก, ตก"),
						Component.literal("§f- คลิกแปลงเพื่อดูข้อมูล, วาร์ป, หรือตั้งราคาประมูล"),
						Component.literal("§7--------------------------------"),
						Component.literal("§b[คลิก] §7เพื่อเปิดหน้าต่างแผนที่ทันที")
				)
		);
		container.setItem(12, mapItem);

		// Slot 14: /ah - ตลาดประมูลซื้อขาย
		ItemStack ahItem = GuiButtons.button(
				GuiButtons.GAVEL,
				Component.literal("§6/ah §f- ตลาดประมูลระหว่างผู้เล่น"),
				List.of(
						Component.literal("§7ศูนย์กลางซื้อขายไอเท็มและโฉนดที่ดินระหว่างผู้เล่น"),
						Component.literal("§7--------------------------------"),
						Component.literal("§eคำสั่งที่ใช้งานได้:"),
						Component.literal("§6/ah §7- เปิดดูตลาดประมูลทั้งหมด"),
						Component.literal("§6/ah sell <ราคาเริ่ม> [ชม.] §7- นำไอเท็มในมือขึ้นประมูล"),
						Component.literal("§6/ah cancel <รหัส> §7- เจ้าของกดยกเลิกการประมูล"),
						Component.literal("§7--------------------------------"),
						Component.literal("§b[คลิก] §7เพื่อเปิดดูตลาดประมูลทันที")
				)
		);
		container.setItem(14, ahItem);

		// Slot 16: /sell - ขายไอเท็มให้เซิร์ฟเวอร์
		ItemStack sellItem = GuiButtons.button(
				GuiButtons.CART,
				Component.literal("§a/sell §f- ขายไอเท็มทันที"),
				List.of(
						Component.literal("§7ขายไอเท็มให้เซิร์ฟเวอร์เป็นเงินเข้ากระเป๋าทันที"),
						Component.literal("§7--------------------------------"),
						Component.literal("§eวิธีใช้งาน:"),
						Component.literal("§f1. พิมพ์ §a/sell §fเพื่อเปิดหน้าต่างวางของ"),
						Component.literal("§f2. วางไอเท็มที่ต้องการขายในช่อง 1-45"),
						Component.literal("§f3. กดปุ่ม §eEsc §fเพื่อปิดหน้าต่าง ระบบจะขายและคิดเงินให้"),
						Component.literal("§7--------------------------------"),
						Component.literal("§b[คลิก] §7เพื่อเปิดหน้าต่างขายของทันที")
				)
		);
		container.setItem(16, sellItem);

		// Slot 28: /price - เช็คราคากลาง
		ItemStack priceItem = GuiButtons.button(
				GuiButtons.TAG,
				Component.literal("§e/price §f- ตรวจสอบราคากลาง"),
				List.of(
						Component.literal("§7เช็คราคารับซื้อของเซิร์ฟเวอร์สำหรับไอเท็มทุกชิ้น"),
						Component.literal("§7--------------------------------"),
						Component.literal("§eคำสั่งที่ใช้งานได้:"),
						Component.literal("§e/price §7- เปิดเมนูดูราคากลางแยกตามหมวดหมู่"),
						Component.literal("§e/price hand §7- เช็คราคาไอเท็มที่ถือในมือ"),
						Component.literal("§7--------------------------------"),
						Component.literal("§b[คลิก] §7เพื่อเปิดหน้าต่างเช็คราคาทันที")
				)
		);
		container.setItem(28, priceItem);

		// Slot 30: /bal & /pay - การเงิน
		long bal = economy != null ? economy.balance(player.getUUID()) : 0L;
		String cur = config != null ? config.currencyName : "Coin";
		ItemStack ecoItem = GuiButtons.button(
				GuiButtons.COIN,
				Component.literal("§6/bal & /pay §f- ระบบการเงิน"),
				List.of(
						Component.literal("§7ยอดเงินปัจจุบันของคุณ: §a" + bal + " " + cur),
						Component.literal("§7--------------------------------"),
						Component.literal("§eคำสั่งที่ใช้งานได้:"),
						Component.literal("§f/bal §7(หรือ /balance) - เช็คยอดเงินคงเหลือ"),
						Component.literal("§f/pay <ชื่อผู้เล่น> <จำนวน> §7- โอนเงินให้เพื่อน"),
						Component.literal("§7ตัวอย่าง: §b/pay Steve 1000")
				)
		);
		container.setItem(30, ecoItem);

		// Slot 32: /mail - กล่องรับของ
		ItemStack mailItem = GuiButtons.button(
				GuiButtons.PARCEL,
				Component.literal("§d/mail §f- กล่องพัสดุและจดหมาย"),
				List.of(
						Component.literal("§7รับไอเท็มที่คุณชนะการประมูล หรือไอเท็มที่ขายไม่ออก"),
						Component.literal("§7--------------------------------"),
						Component.literal("§eวิธีใช้งาน:"),
						Component.literal("§f1. พิมพ์ §d/mail §fเพื่อเปิดกล่อง"),
						Component.literal("§f2. หยิบไอเท็มเข้ากระเป๋า แล้วกด Esc เพื่อปิด"),
						Component.literal("§7--------------------------------"),
						Component.literal("§b[คลิก] §7เพื่อเปิดกล่องรับของทันที")
				)
		);
		container.setItem(32, mailItem);

		// Slot 34: /tpa - ระบบวาร์ป
		ItemStack tpaItem = GuiButtons.button(
				GuiButtons.WARP,
				Component.literal("§c/tpa §f- การวาร์ปและเดินทาง"),
				List.of(
						Component.literal("§7วาร์ปไปหาเพื่อน หรือกลับจุดเริ่มต้น (รอ 5 วิ)"),
						Component.literal("§7--------------------------------"),
						Component.literal("§eคำสั่งที่ใช้งานได้:"),
						Component.literal("§f/tpa §7- เปิดเมนูเลือกผู้เล่นและตอบรับคำขอ"),
						Component.literal("§f/tpa <ชื่อผู้เล่น> §7- ส่งคำขอวาร์ปไปหาเพื่อน"),
						Component.literal("§f/tpa accept §7- กดยอมรับคำขอวาร์ป (รอ 5 วิ)"),
						Component.literal("§f/tpa deny §7- ปฏิเสธคำขอวาร์ป"),
						Component.literal("§f/tpa cancel §7- ยกเลิกคำขอวาร์ป"),
						Component.literal("§f/spawn §7หรือ §f/main §7- วาร์ปกลับจุดเกิด/โลกหลัก (รอ 5 วิ)"),
						Component.literal("§7--------------------------------"),
						Component.literal("§b[คลิก] §7เพื่อเปิดเมนูวาร์ปและเลือกผู้เล่นทันที")
				)
		);
		container.setItem(34, tpaItem);

		// Slot 38: /home - ระบบบ้านส่วนตัว
		ItemStack homeItem = GuiButtons.button(
				GuiButtons.WARP,
				Component.literal("§6/home §f- ระบบบ้านส่วนตัว"),
				List.of(
						Component.literal("§7บันทึกตำแหน่งบ้านและวาร์ปกลับได้อย่างสะดวก"),
						Component.literal("§7--------------------------------"),
						Component.literal("§eคำสั่งที่ใช้งานได้:"),
						Component.literal("§6/home §7- เปิดหน้าต่างเลือกบ้านที่จะวาร์ป"),
						Component.literal("§6/home <ชื่อ> §7- วาร์ปไปบ้านที่ระบุทันที (รอ 5 วิ)"),
						Component.literal("§6/sethome [ชื่อ] §7- บันทึกตำแหน่งบ้าน ณ จุดที่ยืน"),
						Component.literal("§6/delhome <ชื่อ> §7- ลบจุดเซฟบ้าน"),
						Component.literal("§7--------------------------------"),
						Component.literal("§b[คลิก] §7เพื่อเปิดหน้าต่างบ้านของคุณ")
				)
		);
		container.setItem(38, homeItem);

		// Slot 49: Help Tip
		ItemStack tipItem = GuiButtons.button(
				GuiButtons.HELP,
				Component.literal("§fคำแนะนำ: ปิดเมนูด้วย §eEsc"),
				List.of(
						Component.literal("§7ทุกเมนู GUI ในเซิร์ฟเวอร์สามารถกดปุ่ม §eEsc §7บนคีย์บอร์ดเพื่อปิดได้ทันที"),
						Component.literal("§7พิมพ์ §b/help §7หรือ §b/smphelp §7เพื่อเปิดหน้านี้ได้ตลอดเวลา")
				)
		);
		container.setItem(49, tipItem);

		VirtualChestMenu.open(
				player,
				Component.literal("§0คู่มือการใช้งานคำสั่ง (Help)"),
				6,
				true,
				container,
				(slotIndex, button, clickedStack, sp) -> {
					if (slotIndex == 10 || slotIndex == 12) { // /map or /claim
						if (regions != null && config != null && economy != null) {
							int pcx = Math.floorDiv(sp.getBlockX(), config.cellSize);
							int pcz = Math.floorDiv(sp.getBlockZ(), config.cellSize);
							ClaimMapGui.open(sp, regions, config, economy, pcx, pcz);
						}
					} else if (slotIndex == 14) { // /ah
						if (auctions != null && economy != null && config != null) {
							AuctionGui.open(sp, auctions, economy, config.currencyName, 0, AuctionGui.ViewMode.ALL);
						}
					} else if (slotIndex == 16) { // /sell
						if (prices != null && shop != null && config != null && auctions != null && economy != null) {
							SellGui.open(sp, shop, auctions, prices, economy, config.currencyName);
						}
					} else if (slotIndex == 28) { // /price
						if (prices != null && shop != null && config != null) {
							PriceGui.open(sp, prices, shop, config.currencyName, 0, 0);
						}
					} else if (slotIndex == 32) { // /mail
						sp.closeContainer();
						var server = ClaimMap.getServer();
						if (server != null) {
							server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), "mail");
						}
					} else if (slotIndex == 34) { // /tpa
						sp.closeContainer();
						if (ClaimMap.instance() != null) {
							TpaGui.open(sp, ClaimMap.instance().teleports());
						}
					} else if (slotIndex == 38) { // /home
						sp.closeContainer();
						if (ClaimMap.instance() != null) {
							HomeGui.open(sp, ClaimMap.instance().homeManager(), ClaimMap.instance().teleports());
						}
					}
				},
				null
		);
	}
}
