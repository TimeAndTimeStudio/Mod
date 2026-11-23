package studio.timeandtime.timesmpclient.ui;

/** Maps the server's container titles to the TimeSMP top background. */
public final class TimesmpUi {
	private TimesmpUi() {
	}

	/**
	 * @param title the container title ({@code Component#getString()})
	 * @param rows the chest rows (3 or 6)
	 * @return the PNG name without extension, or {@code null} for a vanilla menu
	 */
	public static String keyFor(String title, int rows) {
		if (title == null) return null;
		if (title.contains("ตลาดประมูล")) return rows == 6 ? "auction" : null;
		if (title.contains("ไอเท็มประมูล")) return rows == 6 ? "auction_items" : null;
		if (title.contains("ประมูลที่ดิน")) return rows == 6 ? "auction_cells" : null;
		if (title.contains("เสนอราคา:")) return rows == 3 ? "auction_detail" : null;
		if (title.contains("รายการประมูลของฉัน")) return rows == 6 ? "auction_mine" : null;
		if (title.contains("ตลาดซื้อสินค้าผู้เล่น")) return rows == 6 ? "buy" : null;
		if (title.contains("สินค้าของฉัน")) return rows == 6 ? "buy_mine" : null;
		if (title.contains("ยืนยันการซื้อสินค้า") || title.contains("จัดการสินค้าของคุณ")) {
			return rows == 3 ? "buy_detail" : null;
		}
		if (title.contains("ลากไอเท็มที่ต้องการขาย")) return rows == 6 ? "sell" : null;
		if (title.contains("ระบบเทเลพอร์ต")) return rows == 6 ? "tpa" : null;
		if (title.contains("คำขอวาร์ป")) return rows == 6 ? "tpa_requests" : null;
		if (title.contains("แผนที่เขต")) return rows == 6 ? "claimmap" : null;
		if (title.contains("จัดการเขต")) return rows == 3 ? "claim_manage" : null;
		if (title.contains("กล่องรับของ")) return rows == 6 ? "mail" : null;
		if (title.contains("เช็คราคาไอเท็ม")) return rows == 6 ? "price" : null;
		if (title.contains("ราคา:")) return rows == 3 ? "price_detail" : null;
		if (title.contains("จุดเซฟบ้าน")) return rows == 6 ? "home" : null;
		if (title.contains("คู่มือการใช้งานคำสั่ง")) return rows == 6 ? "help" : null;
		return null;
	}
}