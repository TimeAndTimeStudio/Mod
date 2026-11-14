# PHASES.md — สเปกงานสำหรับ AI ผู้พัฒนา
## Claim Map → เว็บศูนย์รวมแบบ "แอปมือถือ" + เงิน / ร้านค้า / ประมูล / แอดมิน

> **เอกสารนี้เขียนให้ AI (หรือคนอื่น) ที่ไม่เคยเห็นโปรเจกต์นี้มาก่อนอ่านแล้วลงมือทำได้เลย**
> ทุกเฟสมี: เป้าหมาย · ไฟล์ที่สร้าง/แก้ · สัญญา (signature/โครงข้อมูล) · ขั้นตอนเรียงลำดับ · กรณีขอบ · ข้อห้าม · เกณฑ์ผ่าน (Gate) ที่ตรวจได้จริง
> ทำตามลำดับ เฟสไหน Gate ยังไม่ผ่าน **ห้าม** ไปเฟสต่อไป

สัญลักษณ์: ⚠️ = ความเสี่ยง/ต้องระวัง · ❓ = **ยังไม่ได้ยืนยัน ห้ามเดา** (ต้องตรวจจากซอร์สหรือทดลอง ดูหัวข้อ A3) · ✅ = ตกลงแล้ว ไม่ต้องถามซ้ำ

---

## A. โปรโตคอลการทำงานของ AI (อ่านก่อนเริ่มทุกครั้ง)

**A1. อ่านก่อนเขียน**
อ่านหัวข้อ A–F ให้ครบ + เฟสที่กำลังจะทำ + เฟสก่อนหน้า และอ่าน **โค้ดจริงของทุกไฟล์ที่เฟสนั้นระบุว่าต้องแตะ ทั้งไฟล์** ก่อนแก้ ห้ามเขียนจากความจำว่าโค้ดหน้าตาแบบไหน

**A2. ทำทีละเฟส**
ห้ามเริ่มเฟสถัดไปจนกว่า Gate ของเฟสปัจจุบันผ่านทุกข้อ **พร้อมหลักฐาน** (ผลคำสั่ง/ผลเทสต์ที่รันจริง) ห้ามเขียนว่า "น่าจะผ่าน"

**A3. จัดการสิ่งที่ไม่รู้ (❓)**
ลำดับ: (1) ค้นจากซอร์ส — รัน `./gradlew genSources` (หรือ task เทียบเท่า) แล้วอ่านซอร์ส Minecraft/Fabric API ของเวอร์ชันที่ใช้จริง (2) เขียนโปรแกรมทดลองเล็ก ๆ (3) ถ้ายังไม่ได้ ใช้ **ทางเลือกสำรอง** ที่เอกสารระบุไว้ (4) ถ้าไม่มีทางสำรอง **หยุดและถามเจ้าของ** — ห้ามเดาชื่อคลาส/เมธอด/รูปแบบ JSON แล้วเขียนต่อ
ทุกอย่างที่ตรวจแล้วให้จดลง `PHASE_LOG.md` (ดู A7)

**A4. ขอบเขตงาน**
- ทำเฉพาะที่เฟสระบุ ไม่เพิ่มฟีเจอร์ ไม่ refactor ไม่เปลี่ยนชื่อ package/คลาส/เมธอดเดิมที่ไม่เกี่ยว
- **ห้ามเพิ่ม dependency ใหม่** ยกเว้น JUnit (scope `testImplementation`) ถ้ายังไม่มี
- API/ไฟล์ข้อมูลเดิมต้อง **เข้ากันได้ย้อนหลัง** (เพิ่ม field ได้ ห้ามลบ/เปลี่ยนความหมาย) — ถ้าไม่รู้รูปแบบ response เดิม ให้อ่านจากโค้ด ไม่ใช่เดา
- ห้ามใช้: Nginx, Node.js, HTTPS, ฐานข้อมูลภายนอก, ไลบรารี HTTP/JSON ตัวใหม่ (ใช้ `com.sun.net.httpserver`, `java.net.http`, Gson ที่มีอยู่)

**A5. หยุดและถามเจ้าของเมื่อ**
- ต้องตัดสินใจที่กระทบเงิน/ความปลอดภัยแต่เอกสารไม่ได้ระบุ
- ❓ ข้อใดข้อหนึ่งไม่มีทางสำรอง
- build ผ่านแค่เวอร์ชันเดียวและแก้ให้ผ่านทั้งสองไม่ได้ภายในขอบเขตเฟส
- ต้องแก้ไฟล์นอกรายการที่เฟสระบุเกินกว่าการเพิ่มบรรทัดเล็กน้อย

**A6. หลักฐานที่ต้องแนบเมื่อจบเฟส**
(ก) ผล `./gradlew build` ทั้ง 2 เวอร์ชัน (ข) ผลทดสอบทีละข้อของ Gate (คำสั่งที่รัน + ผลที่ได้) (ค) รายการไฟล์ที่เพิ่ม/แก้ (ง) สิ่ง ❓ ที่ยืนยันแล้วและคำตอบ (จ) สิ่งที่ทำไม่ได้/เบี่ยงจากสเปก พร้อมเหตุผล — ใช้แม่แบบในภาคผนวก E

**A7. `PHASE_LOG.md` (สร้างที่ root ของ repo ตอนเฟส 0)**
บันทึกต่อท้ายทุกเฟส: วันที่ · เฟส · สิ่งที่ทำ · การตัดสินใจ · ผลยืนยัน ❓ · ปัญหาค้าง ใช้ส่งต่องานให้ AI ตัวถัดไป ห้ามลบประวัติเดิม

**A8. Git**
commit อย่างน้อยเฟสละ 1 ครั้ง รูปแบบข้อความ `phase-N: <สรุปสั้น>` (เช่น `phase-2: add EconomyManager + ledger`) ห้าม commit ไฟล์ `config/` จริง, คีย์, token, รหัสผ่าน (ตรวจ `.gitignore`)

---

## B. ข้อกำหนดที่ตกลงแล้ว (✅ ไม่ต้องถามซ้ำ)

| เรื่อง | ข้อสรุป |
|---|---|
| โฮสต์เว็บ | ม็อดโฮสต์เอง ด้วย Java `com.sun.net.httpserver` (มีอยู่แล้วใน `WebServer.java`) — **ไม่ใช้ Nginx ไม่ใช้ Node.js** |
| โปรโตคอล | **HTTP เท่านั้น** ไม่ทำ HTTPS ความปลอดภัยเครือข่ายเจ้าของเซิร์ฟเวอร์ดูแลเอง |
| ผู้ใช้ | 5 คน (whitelist ชื่อใน config) |
| หน้าตา | เว็บหน้าเดียวแบบมือถือ: โฮมเป็นกริดไอคอน แตะเปิดเป็น "แอป" ใช้ vanilla JS ไม่มี build step ไม่โหลดอะไรจากภายนอก (ไม่มี CDN/ฟอนต์ออนไลน์) |
| แอปหลัก | แผนที่ · ขาย · ซื้อ · ประมูล |
| แอปเสริม | กระเป๋าเงิน · กล่องของ · ราคา · แอดมิน (เห็นเฉพาะ admin) |
| เก็บข้อมูล | ไฟล์ JSON/JSONL ใน `config/claimmap/` (เหมือนเดิม) |
| ราคา | ดึงจาก JSONBin (รายละเอียดเฟส 4) |
| ภาษา UI/ข้อความ | ไทย |
| Java / เกม | JDK 25 · Minecraft 26.2 และ 26.3 (build ผ่านทั้งสอง) · Fabric |

### ค่าตั้งต้นของตัวเลข (✅ AI ใช้ค่านี้ได้เลย ไม่ต้องถามเจ้าของ — เจ้าของแก้ใน `claimmap.properties` ทีหลังได้)

| คีย์ config | ค่าตั้งต้น | ความหมาย |
|---|---|---|
| `currencyName` | `เหรียญ` | ชื่อหน่วยเงินที่แสดง |
| `startingBalance` | `1000` | เงินเริ่มต้นผู้เล่นใหม่ (หน่วยเต็ม ไม่มีทศนิยม) |
| `sellPercent` | `70` | ขายให้ร้านได้กี่ % ของราคากลาง (**จำนวนเต็ม**) |
| `buyPercent` | `130` | ซื้อจากร้านจ่ายกี่ % ของราคากลาง (**จำนวนเต็ม**) |
| `auctionFeePercent` | `5` | ค่าธรรมเนียมหักจากผู้ขายตอนขายสำเร็จ |
| `auctionListFeePercent` | `0` | ค่าลงประกาศ (% ของราคาเริ่ม) |
| `auctionMinIncrementPercent` | `5` | ขั้นต่ำเพิ่มราคาประมูล |
| `auctionMaxActivePerPlayer` | `10` | รายการประมูลที่เปิดพร้อมกันต่อคน |
| `auctionDefaultHours` / `auctionMaxHours` | `24` / `72` | ระยะเวลาประมูล |
| `auctionAntiSnipeSeconds` | `120` | ถ้ามีคนเสนอในช่วงท้าย ต่อให้เหลืออย่างน้อยเท่านี้ (0 = ปิด) |
| `shopMaxQtyPerTx` | `2304` | จำนวนสูงสุดต่อครั้ง (36 ช่อง × 64) |
| `shopDailyBuyQty` | `0` | โควตาซื้อ/วัน/ไอเท็ม/คน (0 = ไม่จำกัด) |

> ⚠️ **แก้ข้อผิดพลาดจากเอกสารเดิม:** เอกสารเดิมให้ตรวจ `sellRatio × buyMarkup < 1` ซึ่ง **ไม่ใช่เงื่อนไขกันกำไรจากการซื้อ-ขายวน** (เช่น sell=0.9, buy=1.2 ผลคูณ 1.08 ไม่มีกำไรจริงแต่ถูกปฏิเสธ; sell=0.9, buy=0.8 ผลคูณ 0.72 ผ่านแต่ซื้อ 0.8 ขายคืน 0.9 ได้กำไร) เงื่อนไขที่ถูกต้องคือ **ราคาซื้อต่อชิ้นต้องมากกว่าราคาขายต่อชิ้นเสมอ หลังปัดเศษแล้ว** (ดูสูตรเฟส 4) และเปลี่ยนเป็นเปอร์เซ็นต์จำนวนเต็มเพื่อไม่ต้องใช้ `double`

---

## C. โครงเดิมที่ต้องรู้ (ตรวจกับ repo จริงอีกครั้งตอนเฟส 0)

```
src/main/java/studio/timeandtime/claimmap/
  ClaimMap.java        ModInitializer ผูกทุกอย่าง (สร้าง manager แล้วส่งเข้า WebServer/Commands)
  ClaimMapConfig.java  อ่าน/เขียน config/claimmap/claimmap.properties (port, bindAddress, token, ...)
  AccountManager.java  บัญชี + รหัสผ่าน (SHA-256 + salt) → accounts.json
  LinkManager.java     session token (24 ชม.) → sessions.json
  RegionManager.java   ช่อง 500x500 → regions.json
  WebServer.java       HttpServer: GET / (หน้าเว็บ) + /api/*
  ClaimCommands.java   /pass /claim /claimmap
  Protection.java      กันวาง/ทุบ/เปิดหีบ
  MapRenderer.java     วาด PNG แผนที่
src/main/resources/claimmap-web/index.html   หน้าเว็บฝังใน jar (claim-map.html ที่ root เป็นสำเนา)
```

**ปัญหาเดิมที่เกี่ยวกับงานนี้ (เจอจากการอ่านโค้ด)**
1. `WebServer.sendWeb()` แทนคำว่า `MYTOKEN` ในหน้าเว็บด้วย **master token** → ใครเปิดหน้าเว็บได้ = ได้ token แอดมิน และ `POST /api/command` รันคำสั่งเซิร์ฟเวอร์ได้ (แก้เฟส 1)
2. `bindAddress` ค่าเริ่ม `127.0.0.1` → เครื่องอื่นเข้าไม่ได้ (แก้เฟส 1)
3. `/api/login` ต้องแนบ master token (แก้เฟส 1)
4. `/pass` ในเกมยังไม่ผูกกับสถานะล็อกอิน — ถ้าเซิร์ฟเวอร์เป็น offline-mode ใครสวมชื่อเหยื่อเข้าเกมแล้วพิมพ์ `/pass` เพื่อรีเซ็ตรหัสได้ (แก้เฟส 3 — **จนกว่าเฟส 3 จะเสร็จ ห้ามเปิดให้ใช้ระบบเงินจริง**)

**สิ่งที่ต้องตรวจจากโค้ดจริงตอนเฟส 0 แล้วจดลง `PHASE_LOG.md`** (❓):
- รูปแบบ request/response เดิมของทุก endpoint ใน `WebServer` (โดยเฉพาะ `/api/login`, `/api/me`, `/api/cells`)
- `AccountManager` ใช้ **ชื่อหรือ UUID** เป็นคีย์? เก็บ UUID ไว้หรือไม่? (เงินต้องผูกกับ UUID — ดูเฟส 2)
- `Protection` ผูกอีเวนต์ด้วยวิธีไหน (Fabric callbacks / mixin)
- `AccountManager.save()` เขียนไฟล์แบบ tmp → `ATOMIC_MOVE` จริงไหม (จะใช้เป็นแบบอย่าง)
- มีเทสต์/JUnit ใน `build.gradle` หรือยัง

---

## D. กฎวิศวกรรมที่ใช้ทุกเฟส (R1–R15)

**R1. เซิร์ฟเวอร์ตัดสินเสมอ** ยอดเงิน ราคา สิทธิ์ ผู้ขาย/ผู้ซื้อ ตรวจฝั่งเซิร์ฟเวอร์ทั้งหมด ไม่เชื่อค่าราคา/ชื่อ/ยอดที่หน้าเว็บส่งมา (ผู้ใช้ระบุได้แค่ *ว่าจะทำอะไรกับอะไร* เช่น item id, จำนวน, id ประมูล, จำนวนเสนอราคา)

**R2. เงินเป็น `long` ทั้งระบบ** ห้าม `double`/`float` กับเงิน · ค่าคงที่ `MAX_MONEY = 1_000_000_000_000L` (จำนวนต่อรายการสูงสุด) และเพดานยอดคงเหลือ `10^15` · ใช้ `Math.addExact/multiplyExact` · ราคาจากเว็บภายนอก parse ด้วย `BigDecimal` ครั้งเดียวแล้วปัดเป็น `long` (เฟส 4) · เปอร์เซ็นต์เป็น `int` คำนวณ `amount * pct / 100` ด้วย `long` (คูณก่อนหาร, ตรวจ overflow)

**R3. งานที่แตะผู้เล่น/inventory/โลก ต้องอยู่บน server thread** ห้ามแตะ `ServerPlayer`, inventory, `ServerLevel` จาก thread ของ HTTP ตรง ๆ ใช้ตัวช่วยเดียวทั้งโปรเจกต์:
```java
public final class GameBridge {
  // รันบน server thread แล้วรอผลไม่เกิน timeoutMs; เซิร์ฟเวอร์กำลังปิด/หมดเวลา → throw GameUnavailableException
  public static <T> T call(MinecraftServer server, Supplier<T> task, long timeoutMs) throws GameUnavailableException;
}
```
ภายในใช้ `server.execute(...)` + `CompletableFuture` (❓ ยืนยันชื่อเมธอดจากซอร์ส) timeout ค่าตั้งต้น 5000 ms · handler ที่เรียก GameBridge map `GameUnavailableException` → HTTP 503 `game_unavailable`

**R4. ล็อกเดียวสำหรับตลาดทั้งหมด**
```java
public final class Market { public static final Object LOCK = new Object(); }
```
`EconomyManager`, `MailboxManager`, `AuctionManager`, `ShopManager` ทุกเมธอดที่เปลี่ยนสถานะ ต้อง `synchronized (Market.LOCK)` (reentrant โดยธรรมชาติ ซ้อนกันได้)
**ห้ามเด็ดขาด:** ถือ `Market.LOCK` แล้วรอ `GameBridge.call(...)` (เสี่ยง deadlock) · server thread เรียกเมธอดที่ใช้ `Market.LOCK` ได้ปกติ (ล็อกถูกถือสั้น ๆ และไม่มีใครรอ server thread ขณะถือ) · ภายในล็อกห้ามทำ network I/O · เขียนไฟล์เล็ก ๆ ได้

**R5. เขียนไฟล์แบบอะตอมมิก** tmp ในโฟลเดอร์เดียวกัน → `Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE)` (แบบเดียวกับ `AccountManager.save()`) · ไฟล์ append-only (`ledger.jsonl`, `audit.jsonl`) เปิดด้วย `FileChannel` โหมด APPEND แล้ว `force(false)` หลังเขียน · เขียน 1 บรรทัด = 1 `write` เดียว ลงท้าย `\n`

**R6. ไฟล์เสีย ≠ รีเซ็ตเงียบ ๆ** ถ้าอ่านไฟล์เงิน/ของ/ประมูลแล้ว parse ไม่ได้: เปลี่ยนชื่อเป็น `<ชื่อ>.corrupt-<epochMs>` · log ระดับ SEVERE · **ปิดฟีเจอร์ตลาดทั้งหมด** (API ตอบ 503 `market_disabled`) จนกว่าเจ้าของแก้ — ห้ามสร้างไฟล์ใหม่ว่าง ๆ ทับเด็ดขาด (ไฟล์ *ไม่มีอยู่* = เริ่มใหม่ได้ปกติ)

**R7. ลำดับ เงิน กับ ของ** ซื้อ: ตรวจทุกอย่าง → **หักเงิน** → ใส่ของลงกล่องรับของ · ขายให้ร้าน: ตรวจทุกอย่าง (รวมโดน freeze/overflow) → **ลบของ** → ฝากเงิน → ถ้าฝากเงินล้มเหลวผิดคาด **คืนของ** (ผ่านกล่องรับของถ้าใส่ inventory ไม่ได้) · ห้ามมีสถานะที่ของและเงินหายพร้อมกัน

**R8. ทุกขั้นที่ข้ามหลายไฟล์ต้องทำซ้ำได้โดยไม่เสียหาย (idempotent)** เพราะเซิร์ฟเวอร์อาจดับระหว่างเขียนไฟล์ที่ 1 กับ 2 → ใช้ `ref`/`id` ที่ **กำหนดแน่นอน** (เช่น mailbox entry id = `mb_au_<auctionId>_winner`) และเช็กก่อนทำซ้ำ (รายละเอียดเฟส 6)

**R9. แกนของ manager ห้ามพึ่งคลาส Minecraft** `EconomyManager`, `MailboxManager`, `AuctionManager`, `PriceService` รับ `Path dataDir`, `ClaimMapConfig`, `Clock`/`LongSupplier now` ผ่าน constructor · ไอเท็มเก็บเป็น **สตริง JSON ทึบแสง** (`itemJson`) + ฟิลด์แสดงผล (`itemId`, `count`, `label`) · การแปลง `ItemStack ⇄ itemJson` ทำใน `ItemCodecs` (คลาสเดียว ใช้ Minecraft) ผลคือเขียน JUnit ทดสอบแกนได้โดยไม่ต้องเปิดเกม

**R10. ตรวจ input ทุกตัว** ดูมาตรฐานหัวข้อ E · `item id` ต้องผ่าน regex `^[a-z0-9_.\-]+(:[a-z0-9_.\-/]+)?$` และมีอยู่ใน registry จริง · `uuid`/`name` ของผู้เล่นต้องมีบัญชี

**R11. Log** ห้าม log รหัสผ่าน, session token, master token, คีย์ JSONBin (log ได้แค่ว่า "มี/ไม่มี") · log เหตุการณ์เงินที่ระดับ INFO ผ่าน ledger อยู่แล้ว ไม่ต้อง log ซ้ำ · error ภายในให้ log stack trace ฝั่งเซิร์ฟเวอร์ แต่ตอบลูกค้าแค่ `internal_error`

**R12. manager คืนผลเป็น enum/record ไม่ throw ตอนเงื่อนไขธุรกิจไม่ผ่าน** (เช่น `INSUFFICIENT`) throw เฉพาะบั๊ก/IO · handler เป็นคน map ผลเป็น HTTP status + error code

**R13. build ผ่านทั้งสองเวอร์ชันก่อนจบทุกเฟส**
```
./gradlew build
./gradlew build -Pminecraft_version=26.2 -Pfabric_api_version=0.161.0+26.2
```
ชื่อคลาส/เมธอดของ Minecraft 26.2 และ 26.3 อาจต่างกัน → ถ้าต่าง ให้จำกัดโค้ดที่ต่างไว้ในคลาสบาง ๆ (adapter) แล้วบันทึกใน `PHASE_LOG.md` ว่าต่างตรงไหน

**R14. เวลาเป็น epoch มิลลิวินาที (`long`)** ผ่าน `Clock` ที่ฉีดเข้ามา (เทสต์ปรับเวลาได้) ไม่เรียก `System.currentTimeMillis()` กระจายในตรรกะธุรกิจ

**R15. JSON** ใช้ Gson เดิม · ฟิลด์ที่ไม่รู้จักให้ข้าม · ไฟล์ใหม่ที่ยังไม่มีให้สร้างค่าเริ่มต้น · ทุกไฟล์ข้อมูลมี `"version": 1` ที่ root (ยกเว้น `.jsonl`) เพื่อ migrate ภายหลัง

---

## E. มาตรฐาน API กลาง (ใช้กับทุก endpoint ใหม่ และปรับของเดิมเท่าที่ไม่ทำให้ย้อนหลังพัง)

**ทั่วไป**
- Content-Type ตอบ `application/json; charset=utf-8` · ใส่ `Cache-Control: no-store` และ `X-Content-Type-Options: nosniff` กับ `/api/*` ทุกตัว
- เช็ก HTTP method ทุก endpoint ผิด → 405 `method_not_allowed`
- รับ body JSON เป็นอ็อบเจ็กต์เท่านั้น ขนาดสูงสุด **16 KB** (เกิน → 413 `body_too_large`) อ่านแบบจำกัดขนาดจริง (อย่าอ่านทั้งก้อนแล้วค่อยเช็ก)
- ตั้ง `httpServer.setExecutor(Executors.newFixedThreadPool(8))` (ถ้ายังใช้ default) และห่อทุก handler ด้วย try/catch ใหญ่ตัวเดียวที่ตอบ 500 `internal_error`
- เส้นทางที่ไม่รู้จัก → 404 `not_found`

**การยืนยันตัวตน** `Authorization: Bearer <session token>` · ไม่มี/หมดอายุ/ไม่ถูกต้อง → 401 `unauthorized` · ล็อกอินแล้วแต่ไม่ใช่ admin เรียก `/api/admin/*` → 403 `forbidden` · เทียบ master token ด้วย `MessageDigest.isEqual` (constant-time)

**รูปแบบ error** (ทุกสถานะที่ไม่ใช่ 2xx)
```json
{ "error": "insufficient_funds", "message": "ยอดเงินไม่พอ" }
```
`error` = รหัสคงที่ภาษาอังกฤษ (ให้โค้ดหน้าเว็บ switch) · `message` = ข้อความไทยอ่านง่าย (แสดงผู้ใช้ได้ตรง ๆ) · ห้ามมี stack trace/ชื่อไฟล์/ชื่อคลาส

**ตัวเลขใน body**
- จำนวนเงิน/จำนวนชิ้น ต้องเป็น JSON **จำนวนเต็ม** (ปฏิเสธ `1.5`, `"10"`, `1e3`, `null`, ค่าที่เกิน `long`) → 400 `invalid_amount` / `invalid_quantity`
- `amount` ต้อง `1..MAX_MONEY` · `qty` ต้อง `1..shopMaxQtyPerTx`
- ชื่อผู้เล่นใน body ต้องไม่ว่าง ยาว ≤ 32 ตัวอักษร

**แค็ตตาล็อกรหัส error (ใช้ตามนี้ทุกเฟส อย่าตั้งชื่อใหม่ถ้ามีอันที่ตรงอยู่แล้ว)**

| HTTP | `error` | ใช้เมื่อ |
|---|---|---|
| 400 | `bad_request` | JSON พัง/ขาดฟิลด์/ชนิดผิด |
| 400 | `invalid_amount` / `invalid_quantity` | จำนวนไม่ผ่านเงื่อนไขข้างบน |
| 400 | `invalid_item` | item id รูปแบบผิดหรือไม่มีใน registry |
| 401 | `unauthorized` / `invalid_credentials` | ไม่มี session / ชื่อ-รหัสผิด (**ข้อความเดียวกันทั้งกรณีไม่มีชื่อ ไม่อยู่ใน whitelist หรือรหัสผิด** กันการเดาชื่อ) |
| 403 | `forbidden` | ไม่ใช่ admin / ไม่ใช่เจ้าของรายการ |
| 404 | `not_found` | ไม่พบรายการ/ผู้เล่น/ประมูล |
| 405 | `method_not_allowed` | method ผิด |
| 409 | `insufficient_funds` | เงินไม่พอ |
| 409 | `not_enough_items` | ของใน inventory ไม่พอ |
| 409 | `player_offline` | ต้องออนไลน์ในเกม |
| 409 | `item_blocked` / `no_price` | ไอเท็มต้องห้าม / ไม่มีราคา |
| 409 | `account_frozen` | ถูกระงับการค้า |
| 409 | `auction_closed` / `auction_has_bids` / `bid_too_low` / `own_auction` / `already_highest` / `use_buyout` / `limit_reached` | ประมูล (เฟส 6) |
| 413 | `body_too_large` | body > 16 KB |
| 429 | `too_many_attempts` | ล็อกอินผิดเกินกำหนด (ใส่ `retryAfterSeconds` ใน body และ header `Retry-After`) |
| 500 | `internal_error` | บั๊ก/IO ผิดพลาด |
| 503 | `game_unavailable` / `market_disabled` | server thread ไม่ตอบ / ไฟล์ตลาดเสีย (R6) |

---

## F. ลำดับเฟสและการพึ่งพากัน

```
เฟส 0 เตรียมฐาน
  └─ เฟส 1 เว็บ HTTP + ล็อกอิน/สิทธิ์
       ├─ เฟส 2 ระบบเงิน ─┬─ เฟส 4 ราคา ─┐
       │                   │              ├─ เฟส 5 กล่องของ + ร้านค้า ── เฟส 6 ประมูล ── เฟส 7 แอดมิน ┐
       │                   └──────────────┘                                                         │
       └─ เฟส 3 ล็อกอินในเกม (AuthGate) ← ต้องเสร็จก่อนเปิดใช้เงินจริง                              │
                                                              เฟส 8 หน้าเว็บมือถือ ← เขียนไล่ตามเฟส 2-7 ┘
                                                              เฟส 9 ทดสอบรวม
```
หมายเหตุ: เฟส 8 (UI) ทำเป็นก้อนสุดท้ายตามลำดับตัวเลข แต่ละเฟสก่อนหน้า **ต้องทดสอบ API ด้วย `curl`/สคริปต์ให้ผ่านเอง** ไม่ต้องรอ UI

---

## เฟส 0 — เตรียมฐาน

**เป้าหมาย:** โปรเจกต์ build ผ่านก่อนแก้อะไร · AI รู้โครงโค้ดจริงและจดไว้ · มีเครื่องมือทดสอบพร้อม

**ไฟล์ที่สร้าง:** `PHASE_LOG.md` · (ถ้ายังไม่มี) บล็อก JUnit ใน `build.gradle` · `.gitignore` เพิ่ม `config/`, `run/`, `*.corrupt-*`
**ไฟล์ที่แก้:** ไม่แก้โค้ดม็อด

ขั้นตอน
1. ติดตั้ง/ยืนยัน JDK 25 · clone repo · รัน `./gradlew build` ทั้ง 26.2 และ 26.3 (คำสั่งเต็มใน R13) — ถ้าตัวใดล้ม ให้บันทึกข้อความ error และหยุดถามเจ้าของ (นี่คือฐานก่อนแก้ ต้องผ่านก่อน)
2. อ่านโค้ดทั้งไฟล์ของ `ClaimMap`, `ClaimMapConfig`, `AccountManager`, `LinkManager`, `WebServer`, `ClaimCommands`, `Protection` แล้วตอบ ❓ ในหัวข้อ C ลง `PHASE_LOG.md` (ตาราง: endpoint เดิมทุกตัว + รูปแบบ request/response, คีย์ของ AccountManager, วิธีผูกอีเวนต์ของ Protection)
3. รัน `./gradlew genSources` (หรือ task เทียบเท่าของ Loom) เพื่อให้ค้นซอร์ส Minecraft/Fabric ได้ในเฟสถัดไป · จดตำแหน่งซอร์สไว้
4. ตั้งค่า JUnit 5 ถ้ายังไม่มี: `testImplementation 'org.junit.jupiter:junit-jupiter:<เวอร์ชันเสถียรล่าสุดที่ resolve ได้>'` + `test { useJUnitPlatform() }` · เขียนเทสต์เปล่า 1 ตัวให้ `./gradlew test` ผ่าน
5. รันเซิร์ฟเวอร์ทดสอบ (วาง jar + Fabric API) → ตรวจว่า `http://127.0.0.1:8085/` เปิดหน้าเว็บเดิมได้ · สำรองโฟลเดอร์ `config/claimmap/` ไว้ที่อื่น
6. สร้าง `PHASE_LOG.md` มีหัวข้อ: "โครงโค้ดจริง", "รายการ ❓ ที่ยืนยันแล้ว", "ความต่าง 26.2 vs 26.3", "บันทึกรายเฟส"

**ผ่านเมื่อ (Gate 0)**
- [ ] `./gradlew build` ผ่านทั้ง 26.2 และ 26.3 (แนบผล)
- [ ] `./gradlew test` ผ่าน
- [ ] `PHASE_LOG.md` มีตาราง endpoint เดิมครบทุกตัวและคำตอบ ❓ ของหัวข้อ C
- [ ] เปิดหน้าเว็บเดิมได้ในเครื่อง

---

## เฟส 1 — เว็บเซิร์ฟเวอร์ HTTP + ล็อกอิน/สิทธิ์ (รากฐานของทุกอย่าง)

**เป้าหมาย:** เปิดจากเครื่องอื่นได้ · ไม่ฝัง token ในหน้าเว็บ · role `player`/`admin` · จำกัดผู้ใช้ 5 คน · กันเดารหัส

**ไฟล์ที่แก้:** `ClaimMapConfig.java`, `WebServer.java`, `ClaimCommands.java`, `index.html` (+ `claim-map.html` ถ้ายังเป็นสำเนา) 
**ไฟล์ที่สร้าง:** `LoginThrottle.java`, `Json.java` (ตัวช่วยอ่าน body จำกัดขนาด + ส่ง JSON/error ตามหัวข้อ E — ถ้า `WebServer` มีตัวช่วยแบบนี้อยู่แล้วให้ใช้ของเดิม)

### 1.1 Config
เพิ่มคีย์ใน `claimmap.properties` (อ่านใน `load()` เขียนใน `save()` และ **เขียนค่าตั้งต้นลงไฟล์เมื่อยังไม่มี** ให้เจ้าของเห็น):

| คีย์ | ตัวอย่าง | ความหมาย |
|---|---|---|
| `players` | `Alice,Bob,Carol,Dave,Eve` | whitelist ชื่อคั่น `,` · **ว่าง = ไม่จำกัด** |
| `admins` | `Alice` | ชื่อแอดมิน คั่น `,` |
| `bindAddress` | `0.0.0.0` | ค่าตั้งต้นใหม่ |

เมธอดใน `ClaimMapConfig`:
```java
public boolean isAdmin(String name);   // เทียบ case-insensitive, trim; ข้ามรายการว่าง
public boolean isAllowed(String name); // admin ผ่านเสมอ; players ว่าง = true; ไม่งั้นต้องอยู่ใน players
```
กรณีขอบ: ชื่อ `null`/ว่าง → `false` · แยก `,` แล้ว trim ทุกตัว ทิ้งตัวว่าง · เปรียบเทียบด้วย `toLowerCase(Locale.ROOT)`
> ผู้ที่มี config เก่า: ค่า `bindAddress=127.0.0.1` เดิมยังอยู่ในไฟล์ → ม็อด **ไม่เขียนทับเอง** ให้ log INFO ตอนสตาร์ทว่า "bindAddress=127.0.0.1 เครื่องอื่นจะเข้าไม่ได้" และบอกใน `PHASE_LOG.md`/รายงานให้เจ้าของแก้บรรทัดนี้เอง

### 1.2 เลิกฝัง token
- `WebServer.sendWeb()`: ลบการแทน `MYTOKEN` → ส่งไฟล์ตามที่เป็น
- `index.html`: ลบ `const TOKEN = 'MYTOKEN'`; `authHeaders()` แนบ `Authorization: Bearer <session>` **เฉพาะ**เมื่อมี session token · ค้นทั้งไฟล์ว่าไม่เหลือคำว่า `MYTOKEN` หรือที่อ้างถึง `TOKEN`
- master token ใน config ยังอยู่ ใช้เฉพาะ `/api/command` จากสคริปต์ (ดู 1.5)

### 1.3 `/api/login` เป็น public
- ลบ `requireBearer`/`authorized` ออกจาก `handleLogin`
- ลำดับตรวจ: method=POST → อ่าน body → ชื่อ/รหัสไม่ว่าง → throttle (1.4) → `config.isAllowed(name)` → `AccountManager.verify`
- **รูปแบบ response สำเร็จ: คงของเดิม** (ดู `PHASE_LOG.md` ที่เฟส 0 จดไว้) เพิ่มฟิลด์ `"admin": true|false` ได้
- ล้มเหลวทุกสาเหตุ (ไม่อยู่ใน whitelist / ไม่มีบัญชี / รหัสผิด) → **401 `invalid_credentials` ข้อความเดียวกันเป๊ะ** และใช้เวลาใกล้เคียงกัน (ถ้าไม่มีบัญชี ให้ทำ hash หลอกหนึ่งครั้ง)

### 1.4 ล็อกเมื่อรหัสผิด (`LoginThrottle`)
```java
final class LoginThrottle {
  // key = ip + "|" + name.toLowerCase(ROOT)
  // value = long[]{ failCount, lockedUntilEpochMs, lastFailEpochMs }
  ConcurrentHashMap<String,long[]> map;
  long retryAfterMs(String key, long now);   // >0 = ยังโดนล็อก
  void fail(String key, long now);           // ครบ 5 ครั้ง → lockedUntil = now+60_000, failCount=0
  void success(String key);                  // ลบคีย์
}
```
- ผิด 5 ครั้งติด → ตอบ **429** `too_many_attempts` + `retryAfterSeconds` + header `Retry-After` นาน 60 วินาที · ระหว่างโดนล็อก แม้ใส่รหัสถูกก็ยัง 429 (ไม่เรียก verify)
- ล้างคีย์เก่าอัตโนมัติ: เมื่อ `map.size() > 1000` หรือทุกการเรียก `fail` ให้ลบรายการที่ `lastFailEpochMs` เก่ากว่า 15 นาที (กัน memory โต)
- IP ใช้ `exchange.getRemoteAddress().getAddress()` เท่านั้น **ห้ามเชื่อ** header `X-Forwarded-For` (ไม่มี proxy)

### 1.5 Role และจุดตรวจสิทธิ์
- `/api/me` → `{ "name": "...", "admin": true|false }` (`admin = config.isAdmin(name)`) คงฟิลด์เดิมไว้ด้วยถ้ามี
- เมธอดช่วยใน `WebServer`: `Session requireSession(exchange)` (คืน session หรือส่ง 401 แล้วคืน null) และ `boolean isAdminSession(Session)`
- แก้ทุกจุดที่เคยเช็ก `isMaster(token)` ให้ผ่านเมื่อ **master token หรือ admin session** (เช่น `DELETE /api/cells/{id}`)
- `POST /api/command`: **คงไว้เฉพาะ master token** (สำหรับสคริปต์) admin session ธรรมดาเรียกไม่ได้ → 403 · (เจ้าของจะลบ endpoint ทิ้งก็ได้ ให้เขียนใน PHASE_LOG ว่าคงไว้)
- session ที่ชื่อไม่ผ่าน `isAllowed` อีกต่อไป (เจ้าของเอาชื่อออกจาก whitelist) → ถือว่า 401 ทุกคำขอ (เช็กทุกครั้งที่ `requireSession`)

### 1.6 อื่น ๆ
- ข้อความ error 500 ตอบแค่ `internal_error` (log รายละเอียดฝั่งเซิร์ฟเวอร์)
- ใส่ header ความปลอดภัยพื้นฐานตามหัวข้อ E · ตั้ง executor แบบ fixed pool
- `ClaimCommands.runPass`: ถ้า `!config.isAllowed(name)` → แจ้ง "ชื่อนี้ไม่อยู่ในรายชื่อผู้เล่น" และไม่ตั้งรหัส (⚠️ ช่องโหว่ takeover ของ `/pass` ไว้แก้เฟส 3)
- หน้าเว็บ: เรียก `/me` และ `/cells` **เฉพาะเมื่อมี session** · หลังล็อกอินเรียก `tiles.clear()` · เมื่อได้ 401 จากที่ใดก็ตาม → ลบ session ในเบราว์เซอร์ → กลับหน้าล็อกอิน

### 1.7 API หลังเฟสนี้

| Method | Path | ต้องมี |
|---|---|---|
| GET | `/` | ไม่ต้อง |
| POST | `/api/login` | ไม่ต้อง (body `{name,password}`) |
| GET | `/api/me` และ endpoint อื่นทั้งหมด | `Authorization: Bearer <session>` |
| POST | `/api/command` | master token เท่านั้น |

**ผ่านเมื่อ (Gate 1)** (ทดสอบด้วย `curl` แนบคำสั่งและผล)
- [ ] `curl -s http://<ip>:8085/ | grep -i -E "token|MYTOKEN"` ไม่พบ master token ใด ๆ
- [ ] ยังไม่ล็อกอิน `curl /api/cells` → 401
- [ ] ชื่อที่ไม่อยู่ใน `players` ล็อกอินไม่ได้ (401 เหมือนรหัสผิดทุกประการ) และ `/pass` ตั้งรหัสไม่ได้
- [ ] ใส่รหัสผิด 5 ครั้งติด ครั้งที่ 6 ได้ 429 + `Retry-After`; รอ 60 วินาทีแล้วล็อกอินถูกได้; ใส่ถูกระหว่างโดนล็อกยัง 429
- [ ] บัญชีใน `admins` เห็น `admin:true` ที่ `/api/me`, คนอื่น `false`; session ของ player เรียก `/api/command` ได้ 403
- [ ] เปิดจากเครื่องอื่นในเครือข่ายด้วย `http://<ip>:8085/` ได้
- [ ] build 2 เวอร์ชันผ่าน + เทสต์ JUnit ของ `isAllowed`/`isAdmin`/`LoginThrottle` ผ่าน (ครอบคลุมกรณีขอบ: ช่องว่าง ตัวพิมพ์ ชื่อว่าง)

---

## เฟส 2 — ระบบเงิน (EconomyManager)

**เป้าหมาย:** ยอดเงินต่อผู้เล่นที่เชื่อถือได้ · ประวัติทุกรายการ · รองรับ escrow สำหรับประมูล · กู้คืนได้หลังดับกลางคัน

**ไฟล์ที่สร้าง:** `Market.java` (ล็อกตาม R4), `Acct.java`, `Move.java`, `TxResult.java`, `EconomyManager.java`, `GameBridge.java` (ตาม R3 — สร้างตอนนี้เพราะเฟส 5 ใช้)
**ข้อมูล:** `config/claimmap/economy.json`, `config/claimmap/ledger.jsonl`, `config/claimmap/backups/`
**ไฟล์ที่แก้:** `ClaimMapConfig` (เพิ่ม `startingBalance`, `currencyName`), `ClaimMap` (สร้าง/ส่ง manager), `ClaimCommands`, `WebServer`, `AccountManager` (เฉพาะถ้าต้องเก็บ UUID — ดู 2.1)

### 2.1 ผูกเงินกับ UUID
เงินเก็บตาม **UUID** ถ้า `AccountManager` คีย์ด้วยชื่ออย่างเดียว:
- เพิ่มฟิลด์ `uuid` ในระเบียนบัญชี บันทึกตอนผู้เล่นเข้าเกม/ตั้งรหัสครั้งแรก (บัญชีเก่าที่ยังไม่มี `uuid` จะถูกเติมเมื่อผู้เล่นเข้าเกมครั้งถัดไป — ไฟล์เก่าอ่านได้ต่อ)
- เพิ่ม `Optional<UUID> uuidOf(String name)` และ `Optional<String> nameOf(UUID)`
- ผู้ที่ยังไม่มี uuid (ไม่เคยเข้าเกมหลังอัปเดต) → โอนเงินให้/เรียกกระเป๋าเงินไม่ได้ ตอบ 404 `not_found` พร้อมข้อความ "ผู้เล่นยังไม่เคยเข้าเกม"
- ❓ การเปลี่ยนชื่อ Minecraft (online-mode) อยู่นอกขอบเขต — จดเป็นข้อจำกัดใน `PHASE_LOG.md`

### 2.2 โครงบัญชี (Acct) และธุรกรรม (Move)
```java
public record Acct(String kind, String id) {          // toString: "P:<uuid>" | "SYSTEM" | "E:<escrowId>"
  public static Acct player(UUID u);
  public static final Acct SYSTEM;                     // แหล่งเงินเกิด/ที่เงินหาย (แอดมินให้/หัก, ค่าธรรมเนียม, เงินเริ่มต้น)
  public static Acct escrow(String id);                // เงินที่ถูกกันไว้ (ประมูล)
}
public record Move(Acct from, Acct to, long amount, String reason, String ref) {}
public enum TxResult { OK, INSUFFICIENT, INVALID_AMOUNT, FROZEN, NO_ACCOUNT, OVERFLOW, SAME_ACCOUNT, IO_ERROR }
```

### 2.3 EconomyManager API
```java
public final class EconomyManager {
  public EconomyManager(Path dir, ClaimMapConfig cfg, LongSupplier now);
  public void load();                                   // อ่าน snapshot แล้ว replay ledger (2.5); พังตาม R6
  public boolean ensureAccount(UUID u);                 // ไม่มี → สร้าง + ให้ startingBalance (reason "signup"); true = เพิ่งสร้าง; idempotent
  public long balance(UUID u);                          // ไม่มีบัญชี = 0
  public boolean hasAccount(UUID u);
  public boolean isFrozen(UUID u);
  public void setFrozen(UUID u, boolean frozen);        // ใช้โดยเฟส 7
  public long escrowBalance(String escrowId);
  public TxResult apply(List<Move> moves, boolean bypassFreeze);   // อะตอมมิก: ทั้งชุดสำเร็จหรือไม่ทำเลย
  // ตัวช่วยบาง ๆ ครอบ apply:
  public TxResult deposit(UUID to, long amt, String reason, String ref);              // SYSTEM → player
  public TxResult withdraw(UUID from, long amt, String reason, String ref);           // player → SYSTEM
  public TxResult transfer(UUID from, UUID to, long amt, String reason, String ref);  // player → player
  public List<TxView> history(UUID u, int limit);       // ใหม่→เก่า จาก index ในหน่วยความจำ
  public long sumPlayerBalances();  public long sumEscrow();  public long systemNet();  // ใช้ตรวจ invariant
}
```
ทุกเมธอดที่เปลี่ยนสถานะ `synchronized (Market.LOCK)`

### 2.4 กฎของ `apply(moves, bypassFreeze)` (ตามลำดับ — ห้ามสลับ)
1. `moves` ว่าง → `OK` โดยไม่ทำอะไร
2. ตรวจทุก move: `1 ≤ amount ≤ MAX_MONEY` ไม่งั้น `INVALID_AMOUNT` · `from != to` ไม่งั้น `SAME_ACCOUNT` · บัญชี player ที่เป็นปลายทางต้องมีบัญชี (`NO_ACCOUNT`) ยกเว้นผู้ถูกสร้างใน `ensureAccount`
3. freeze (ถ้า `!bypassFreeze`): ถ้า `from` เป็น player ที่ถูก freeze → `FROZEN`; ถ้าเป็น player→player และ `to` ถูก freeze → `FROZEN` · (การคืนเงินจาก escrow ให้ผู้ที่ถูก freeze **ไม่ถูกบล็อก** เพราะเป็นการกระทำของระบบ)
4. **จำลอง** ทั้งชุดบนแผนที่ delta ชั่วคราว (`Math.addExact` ตรวจ overflow → `OVERFLOW`; ยอด player/escrow ติดลบ → `INSUFFICIENT`) — ห้ามแตะสถานะจริงจนกว่าจะผ่านทั้งชุด
5. สร้างบรรทัด ledger ทั้งชุด (กำหนด `seq` ต่อเนื่อง, `batch` id เดียวกัน) → **append ลง `ledger.jsonl` ก่อน** ล้มเหลว → `IO_ERROR` (ยังไม่แตะหน่วยความจำ)
6. ค่อย commit delta ลงหน่วยความจำ + อัปเดต index ประวัติ + `lastSeq`
7. เขียน `economy.json` แบบอะตอมมิก (ล้มเหลวให้ log ERROR แต่ **ยังตอบ OK** เพราะ ledger คือความจริง และจะถูกบันทึกใหม่ในการเปลี่ยนครั้งถัดไป/ตอนปิด)

### 2.5 โครงข้อมูลและการกู้คืน
```jsonc
// economy.json  (เขียนอะตอมมิก)
{ "version": 1, "lastSeq": 1234,
  "balances": { "<uuid>": 1000 },
  "escrow":   { "au_xxx": 500 },
  "frozen":   ["<uuid>"],
  "systemNet": -2500 }          // = ผลรวมที่ SYSTEM จ่ายออก(ลบ)/รับเข้า(บวก)
// ledger.jsonl (ต่อท้ายทีละบรรทัด)
{"seq":1234,"id":"tx_1234","batch":"b_...","t":1731560000000,"from":"SYSTEM","to":"P:<uuid>","amount":1000,"reason":"signup","ref":""}
```
- invariant: `sumPlayerBalances + sumEscrow + systemNet == 0` ตลอดเวลา
- **ตอน `load()`:** อ่าน `economy.json` (ไม่มี = เริ่มจาก 0 และ `lastSeq=0`) → อ่าน `ledger.jsonl` ทุกบรรทัด: บรรทัดที่ `seq > lastSeq` ให้ **replay** ลงยอด (นี่คือกรณีดับหลัง append ก่อนบันทึก snapshot) · บรรทัดสุดท้ายที่ JSON ไม่ครบ (ดับกลางเขียน) → ข้ามและ log WARN · บรรทัดกลางไฟล์ที่เสีย → ถือว่าไฟล์เสียตาม R6 · เสร็จแล้วตรวจ invariant ถ้าไม่ตรง → R6 · สร้าง index ประวัติ 50 รายการล่าสุดต่อ player (ในหน่วยความจำ)
- รหัส `reason` มาตรฐาน (ใช้ตามนี้เท่านั้น เฟสหลัง ๆ จะเพิ่มต่อ): `signup`, `pay`, `shop.buy:<itemId>`, `shop.sell:<itemId>`, `auction.listfee:<id>`, `auction.bid:<id>`, `auction.refund:<id>`, `auction.sale:<id>`, `auction.fee:<id>`, `auction.buyout:<id>`, `auction.cancel:<id>`, `admin.give`, `admin.take`, `admin.set`

### 2.6 คำสั่งเกมและ API
- `/bal` — แสดงยอด · `/pay <ผู้เล่น> <จำนวน>` — ตรวจ: ผู้ส่ง `isAllowed` และล็อกอินแล้ว (หลังเฟส 3) · ผู้รับมีบัญชีและไม่ใช่ตัวเอง · จำนวนเป็นจำนวนเต็ม > 0 · ผล `INSUFFICIENT/FROZEN` → ข้อความไทย
- `GET /api/wallet` → 
  ```json
  { "balance": 1000, "currency": "เหรียญ",
    "history": [ { "id":"tx_12", "t":1731560000000, "dir":"in", "other":"Bob", "amount":50, "reason":"pay" } ] }
  ```
  (`dir` = `in`/`out` มองจากผู้เรียก · `other` = ชื่อผู้เล่น หรือ `"ระบบ"`/`"ประมูล"` สำหรับ SYSTEM/escrow · ล่าสุด 50 รายการ)
- `POST /api/pay` body `{ "to":"<ชื่อ>", "amount":50 }` → `{ "ok":true, "balance":950 }` · ข้อผิดพลาด: 400 `invalid_amount`, 404 `not_found` (ไม่มีผู้รับ), 409 `insufficient_funds`/`account_frozen`, 400 `bad_request` (โอนให้ตัวเอง)
- (ควรทำ) header `X-Request-Id` (≤ 64 ตัวอักษร) บน `POST /api/pay`: เก็บ id ที่สำเร็จแล้วในหน่วยความจำ 10 นาที → ส่งซ้ำได้ผลเดิมโดยไม่หักซ้ำ (กันกดซ้ำ/ลองซ้ำของเครือข่าย)

### 2.7 สำรองข้อมูล
`ScheduledExecutorService` (daemon thread) ทุก 1 ชั่วโมง คัดลอก `economy.json`, `ledger.jsonl`, `accounts.json`, `mailbox.json`, `auctions.json` (เท่าที่มี) ไป `backups/<yyyyMMdd-HHmmss>/` เก็บ 24 ชุดล่าสุด ลบเก่าสุด · ไฟล์ snapshot เขียนอะตอมมิกอยู่แล้วจึงคัดลอกได้ปลอดภัย · คัดลอก `ledger.jsonl` ภายใต้ `Market.LOCK` เพื่อไม่ให้ได้ครึ่งบรรทัด · ตอนเซิร์ฟเวอร์หยุด ให้ shutdown executor

**เทสต์ JUnit ที่ต้องเขียน (ไม่ต้องใช้ Minecraft)** — `EconomyManagerTest`
- 100 thread ยิง `transfer` สุ่มพร้อมกัน → `sumPlayerBalances + sumEscrow + systemNet == 0` และไม่มียอดติดลบ
- `amount` 0/ติดลบ/`Long.MAX_VALUE`/overflow → ปฏิเสธครบ ยอดไม่เปลี่ยน
- `apply` ชุดหลาย move ที่ move สุดท้ายล้มเหลว → ไม่มี move ใดมีผล และ ledger ไม่ถูกเขียน
- ทำธุรกรรมหลายรายการ แล้วย้อน `economy.json` กลับเป็น snapshot เก่า (lastSeq ต่ำกว่า ledger) เพื่อจำลองดับก่อนบันทึก snapshot → `load()` ใหม่ต้อง replay แล้วยอดตรง
- ledger บรรทัดสุดท้ายถูกตัดครึ่ง → `load()` ข้ามได้และยอดตรง · ไฟล์ `economy.json` เสีย → ตลาดถูกปิด (ไม่สร้างไฟล์ใหม่ทับ)
- `ensureAccount` เรียก 2 ครั้ง ได้เงินเริ่มต้นครั้งเดียว
- freeze: ผู้ถูก freeze โอนออก/รับจาก player ไม่ได้; `bypassFreeze=true` ทำได้; คืนเงินจาก escrow ให้ผู้ถูก freeze ทำได้

**ผ่านเมื่อ (Gate 2)**
- [ ] เทสต์ JUnit ด้านบนผ่านทั้งหมด
- [ ] สคริปต์ยิง `POST /api/pay` พร้อมกัน 100 ครั้ง (เขียนเป็น `tools/StressPay.java` รันด้วย `java tools/StressPay.java`) → ผลรวมยอดทุกคนก่อน-หลังเท่ากัน ไม่มียอดติดลบ
- [ ] `pay` ด้วยยอดไม่พอถูกปฏิเสธ 409 · โอนให้ตัวเอง/จำนวน 0/ติดลบ/ทศนิยม ถูกปฏิเสธ 400
- [ ] รีสตาร์ทเซิร์ฟเวอร์ ยอดไม่เปลี่ยน · `ledger.jsonl` replay ตั้งแต่ศูนย์ได้ยอดเท่ากับ `economy.json` (ใช้ `tools/CheckLedger.java` ซึ่งเขียนในเฟสนี้: อ่าน ledger → คำนวณยอด → เทียบ snapshot → พิมพ์ผ่าน/ไม่ผ่าน)
- [ ] backup ถูกสร้างจริงตามเวลา (ทดสอบโดยตั้งช่วงสั้นชั่วคราวผ่านค่าที่ฉีดเข้ามา แล้วเปลี่ยนกลับเป็น 1 ชม.)
- [ ] build 2 เวอร์ชันผ่าน

---

## เฟส 3 — หน้ากรอกรหัสตอนเข้าเกม (AuthGate)

**เป้าหมาย:** เข้าเกมแล้วต้อง "ใส่รหัส" หรือ "ตั้งรหัส" ก่อน ยังไม่ผ่านทำอะไรไม่ได้ · ปิดช่องโหว่ `/pass` takeover · รหัสเดียวใช้ทั้งเกมและเว็บ

**ไฟล์ที่สร้าง:** `AuthGate.java`, (ถ้าจำเป็น) `PasswordHasher.java`
**ไฟล์ที่แก้:** `ClaimMap`, `Protection` (หรือสร้าง listener ใหม่), `AccountManager`, `ClaimCommands`

> ม็อดเป็น server-only จึงสร้างหน้าจอฝั่งผู้เล่นเองไม่ได้ ต้องใช้สิ่งที่เซิร์ฟเวอร์ส่งให้ไคลเอนต์แสดงได้

### 3.1 เลือกวิธีรับรหัส (ทำ spike ก่อน ไม่เกิน ~1 ชั่วโมง)
1. **Dialog จากเซิร์ฟเวอร์** (มีช่องพิมพ์ข้อความ + ปุ่ม) — ❓ ตรวจในซอร์ส Minecraft/Fabric 26.x ว่ามีแพ็กเก็ตแสดง dialog + รับ action พร้อมค่าจากช่องกลับมาหรือไม่ ทำตัวอย่างเล็ก ๆ ให้เด้งช่องพิมพ์แล้วรับค่าได้ในทั้ง 26.2 และ 26.3 · **ถ้าได้ ทั้งสองเวอร์ชัน** ใช้ทางนี้ (ช่องรหัสแบบ masked ถ้ามี)
2. **ทางสำรอง (ใช้ได้เลยถ้า spike ไม่ผ่าน):** คำสั่งแชท `/login <รหัส>` และ `/register <รหัส> <ยืนยัน>` · ข้อเสีย: รหัสอยู่ในแชท/คำสั่ง อาจติด log เซิร์ฟเวอร์ → ระบุคำเตือนนี้ใน `docs/PLAYER_GUIDE.md` และตอนผู้เล่นเข้าเกมให้แจ้ง "อย่าใช้รหัสซ้ำกับที่อื่น"
- บันทึกผล spike (ได้/ไม่ได้ + เหตุผล + ชื่อแพ็กเก็ต) ลง `PHASE_LOG.md`
- ไม่ว่าใช้วิธีไหน **แกนตรรกะ (AuthGate state machine) เหมือนกัน** — ทำแกนให้เรียกได้จากทั้งสองช่องทาง

### 3.2 State machine
สถานะต่อผู้เล่นในหน่วยความจำ: `Map<UUID, AuthState>` โดย `AuthState{ phase: NEED_LOGIN|NEED_REGISTER|AUTHED, failCount, joinedAtMs, joinPos }` และ `Set<UUID> authed` (ล้างตอน `DISCONNECT`)
1. `ServerPlayConnectionEvents.JOIN`: ชื่อไม่อยู่ใน whitelist (`!config.isAllowed`) → **kick** "ชื่อนี้ไม่อยู่ในรายชื่อผู้เล่น" · มีบัญชี+รหัส → `NEED_LOGIN` · ไม่มี → `NEED_REGISTER` · แสดงหน้า/ข้อความขอรหัส
2. ผ่าน `/login` ถูกต้อง หรือ `/register` สำเร็จ → `AUTHED`, เรียก `economy.ensureAccount(uuid)` (เฟส 2), แจ้งยอดเงิน + จำนวนของในกล่อง (ถ้าเฟส 5 มาแล้ว) แล้วปลดล็อก
3. รหัสผิดเกิน **3 ครั้ง** หรือไม่กรอกเกิน **60 วินาที** → kick พร้อมข้อความ (นับวินาทีจาก tick ไม่ใช่ `Thread.sleep`)
4. `DISCONNECT` → ลบสถานะ

### 3.3 ระหว่างยังไม่ AUTHED ต้องบล็อกทั้งหมดนี้
เคลื่อนที่ (ดึงกลับ `joinPos` ทุก tick หรือ freeze ผ่านกลไกที่ปลอดภัยกว่าที่พบ) · วางบล็อก · ทุบบล็อก · ใช้ของ/ใช้บล็อก (เปิดหีบ ฯลฯ) · โจมตี entity · ดรอปของ · เปิด inventory ของบล็อก · แชททั่วไป · คำสั่งทุกคำสั่งยกเว้น `/login` `/register` · กันผู้เล่นโดนทำร้าย (ยกเลิก damage) และกัน mob ล็อกเป้า
- ชื่ออีเวนต์ของ Fabric API (เช่น `UseBlockCallback`, `AttackBlockCallback`, `UseItemCallback`, `AttackEntityCallback`, `PlayerBlockBreakEvents.BEFORE`, `ServerMessageEvents.ALLOW_CHAT_MESSAGE`, `ServerLivingEntityEvents.ALLOW_DAMAGE`, `ServerTickEvents`) ❓ **ต้องยืนยันจากซอร์สของเวอร์ชันจริง** ก่อนใช้ — อันไหนไม่มี ใช้ทางอื่น (ตรวจใน tick/mixin ขนาดเล็ก) และจดลง `PHASE_LOG.md`
- ดู `Protection` เดิมว่าผูกอีเวนต์วิธีไหนแล้วทำให้สอดคล้อง (อย่าซ้ำซ้อนจนชนกัน)
- คำสั่งที่ไม่ใช่ `/login`/`/register`: ใช้ชั้นตรวจก่อน execute คำสั่ง (ถ้า Fabric/Brigadier มี hook) หรือเช็กในคำสั่งของม็อดเองทุกตัว + บล็อกคำสั่งอื่นด้วยอีเวนต์ ❓

### 3.4 ปิดช่องโหว่ `/pass`
- `/pass` ใช้ได้เฉพาะผู้เล่นที่ `AUTHED` และเป็นการ **เปลี่ยนรหัส**: `/pass <รหัสเดิม> <รหัสใหม่>` (ต้องยืนยันรหัสเดิมถูกต้อง)
- ผู้เล่นใหม่ที่ยังไม่มีบัญชีใช้ `/register` เท่านั้น · **ห้ามมีทางใดที่ผู้ไม่ผ่านรหัสตั้ง/รีเซ็ตรหัสของบัญชีที่มีอยู่แล้วได้**
- กติการหัส: ยาว 6–64 ตัวอักษร · ต้องไม่เท่ากับชื่อผู้เล่น · `/register` ต้องกรอกยืนยันตรงกัน
- เปลี่ยนรหัสสำเร็จ → ยกเลิก session เว็บทั้งหมดของบัญชีนั้น (`LinkManager`)

### 3.5 เปลี่ยนการเก็บรหัสเป็น PBKDF2 (ควรทำในเฟสนี้)
- รูปแบบเก็บ: `pbkdf2$<iterations>$<saltBase64>$<hashBase64>` · `PBKDF2WithHmacSHA256` · iterations **210000** (ค่าใน config `pbkdf2Iterations`) · salt สุ่ม 16 ไบต์ด้วย `SecureRandom` · ความยาวคีย์ 256 บิต
- `verify` ต้องรองรับ **รูปแบบเก่า (SHA-256+salt)** ด้วย: ตรวจรูปแบบจากคำนำหน้า → ถ้าเป็นแบบเก่าและรหัสถูก → **rehash เป็น PBKDF2 แล้วบันทึกทันที** (ผู้ใช้ไม่ต้องทำอะไร)
- เทียบ hash ด้วย `MessageDigest.isEqual` (constant-time)
- PBKDF2 หนัก (~สิบถึงร้อย ms) → **ห้ามรันบน server thread**: ถ้าล็อกอินทางคำสั่งเกม ให้โยนการ verify ไป worker thread แล้วกลับมาปลดล็อกบน server thread ผ่าน `server.execute`

**ผ่านเมื่อ (Gate 3)**
- [ ] ผู้เล่นใหม่เข้าเกมเห็นหน้า/ข้อความตั้งรหัส · ผู้เล่นเดิมเห็นหน้า/ข้อความใส่รหัส · ชื่อนอก whitelist โดน kick
- [ ] ก่อนผ่าน: ขยับ/ทุบ/วาง/ดรอป/เปิดหีบ/แชท/ตีเอนทิตี/โดนทำร้าย/ใช้คำสั่งอื่น ทำไม่ได้ทั้งหมด (ทดสอบทีละรายการ แนบผล)
- [ ] ใส่รหัสผิด 3 ครั้ง และเงียบเกิน 60 วินาที โดน kick
- [ ] ผ่านแล้วเล่นปกติ · แจ้งยอดเงินตอนผ่าน · รหัสเดียวกันล็อกอินเว็บได้
- [ ] ผู้ไม่ผ่านรหัสรัน `/pass` เพื่อรีเซ็ตรหัสบัญชีที่มีอยู่แล้ว **ไม่ได้**
- [ ] บัญชีเก่า (SHA-256) ล็อกอินสำเร็จแล้วไฟล์ถูก rehash เป็น `pbkdf2$...` (ตรวจใน `accounts.json`) และล็อกอินซ้ำได้
- [ ] ✅ ผล spike dialog vs คำสั่งแชท บันทึกใน `PHASE_LOG.md`
- [ ] build 2 เวอร์ชันผ่าน

---

## เฟส 4 — ราคา (PriceService)

**เป้าหมาย:** ม็อดมีตารางราคาล่าสุดพร้อมใช้ในร้านค้า · ทนอินเทอร์เน็ตล่ม · ราคาซื้อมากกว่าราคาขายเสมอ

**ไฟล์ที่สร้าง:** `PriceService.java`, `PriceMath.java` (ฟังก์ชันคำนวณล้วน ๆ เทสต์ง่าย)
**ข้อมูล:** `prices-cache.json`, `price-overrides.json`
**ไฟล์ที่แก้:** `ClaimMapConfig`, `ClaimMap`, `WebServer`
**Config ใหม่:** `pricesUrl`, `pricesAccessKey`, `priceRefreshSeconds=60`, `sellPercent=70`, `buyPercent=130`, `blockedItems` (เช่น `bedrock,command_block,barrier`) · (เดิมเอกสารใช้ `sellRatio`/`buyMarkup` เป็นทศนิยม — เปลี่ยนเป็น `sellPercent`/`buyPercent` จำนวนเต็มตาม R2)

### 4.1 สิ่งที่รู้และยังไม่รู้เกี่ยวกับ JSONBin
ที่รู้จากไฟล์ `prices.html` ใน repo `TEST` ของเจ้าของ (ถ้าหาไฟล์ไม่เจอ ให้ขอจากเจ้าของ):
- โหลดจาก `GET https://api.jsonbin.io/v3/b/<BIN_ID>/latest` พร้อมหัว `X-Access-Key: <คีย์>` และ `X-Bin-Meta: false`
- โค้ดหน้านั้นอ่านผลเป็น `record.prices` = อ็อบเจ็กต์ `{ "<item_id>": <ตัวเลข> }` (เช่น `diamond`) — id ไหนไม่มีราคา = ไม่ขาย
- ❓ **ยังไม่ได้ทดสอบเรียกจริง** → **ขั้นแรกสุด:** ให้เจ้าของ (หรือ AI ถ้ามีคีย์) รัน
  ```
  curl -s -H "X-Access-Key: <คีย์>" -H "X-Bin-Meta: false" "https://api.jsonbin.io/v3/b/<BIN_ID>/latest"
  ```
  แล้วบันทึกตัวอย่าง response (ตัดคีย์ออก) ลง `PHASE_LOG.md` · **ห้ามเขียน parser จากการเดารูปแบบ** · ถ้าไม่มีคีย์/เครือข่ายใช้ไม่ได้ ให้เขียน `PriceService` ให้อ่านจาก `prices-cache.json` ได้ และทดสอบด้วยไฟล์ตัวอย่างที่ปั้นตามรูปแบบข้างบน แล้วตั้ง ❓ ค้างไว้ในรายงาน
- ⚠️ คีย์ JSONBin ปรากฏในไฟล์ HTML สาธารณะ (repo `TEST`) → **เจ้าของต้องหมุนคีย์เป็นแบบ read-only ใหม่** ใช้เฉพาะใน config ของม็อด ไม่ใส่ในหน้าเว็บ ไม่ commit · ใส่คำเตือนนี้ในรายงานเฟส

### 4.2 หน่วยราคาและการปัดเศษ (✅ ตัดสินแล้ว)
- "ราคากลาง" (base) จากเว็บ = **จำนวนเงินต่อ 1 ชิ้นเป็นหน่วยเต็มของระบบ** (ถ้าเว็บเป็นทศนิยม ให้ปัด `HALF_UP` เป็นจำนวนเต็มตอน parse ด้วย `BigDecimal`; base < 1 หลังปัด → ข้ามไอเท็มนั้น)
- ❓ ถ้าเจ้าของใช้หน่วยราคาที่ต่างจาก "เหรียญเต็ม" (เช่นราคาเป็นสตางค์) ให้หยุดถาม — อย่าเดาตัวคูณ
- สูตรใน `PriceMath` (ใช้ `long` ล้วน ใช้ที่เดียวทั้งโปรเจกต์):
  ```
  sellPrice(base) = max(1, floorDiv(base * sellPercent, 100))
  buyPrice(base)  = max(sellPrice(base) + 1, ceilDiv(base * buyPercent, 100))
  ```
  (`multiplyExact` กัน overflow) → **รับประกัน `buyPrice > sellPrice` เสมอ** จึงซื้อแล้วขายคืนไม่มีทางได้กำไร
- ตรวจ config ตอน `load()`: `1 ≤ sellPercent ≤ 100`, `buyPercent ≥ 100`, `sellPercent < buyPercent` ไม่ผ่าน → log SEVERE ใช้ค่าตั้งต้น (70/130) และเตือนชัด
- ราคาสุดท้ายที่ใช้จริงต่อไอเท็ม = `overrides[item]` ถ้ามี (override คือ **base** ใหม่ ไม่ใช่ราคาซื้อ/ขาย) ไม่งั้น `webPrices[item]`

### 4.3 สัญญาของ `PriceService`
```java
public final class PriceService {
  public PriceService(Path dir, ClaimMapConfig cfg, LongSupplier now);
  public void loadCache();                          // อ่าน prices-cache.json + price-overrides.json ตอนสตาร์ท
  public void start(ScheduledExecutorService ex);   // ดึงทุก priceRefreshSeconds บน thread แยก (ห้ามบน server thread)
  public void stop();
  public boolean refreshNow();                      // admin สั่ง (เฟส 7) บล็อกจนเสร็จ; true = สำเร็จ
  public OptionalLong base(String itemId);          // หลัง override; empty = ไม่มีราคา
  public OptionalLong buyPrice(String itemId);      // empty ถ้าไม่มีราคาหรืออยู่ใน blockedItems
  public OptionalLong sellPrice(String itemId);     // เช่นกัน
  public boolean isBlocked(String itemId);
  public Map<String, PricePair> snapshot();         // ทั้งตาราง สำหรับ /api/prices (immutable copy)
  public void setOverride(String itemId, Long baseOrNull);   // เฟส 7; null = ลบ override; เขียนไฟล์อะตอมมิก
  public void setBlocked(String itemId, boolean blocked);    // เฟส 7; เก็บใน price-overrides.json ด้วย (ไม่แก้ properties)
  public long lastSuccessMs();  public boolean usingStaleCache();
}
```
- `itemId` เก็บและเทียบแบบ **มี namespace เสมอ** (`diamond` → `minecraft:diamond`; ถ้ามี `:` อยู่แล้วใช้ตามนั้น) ตัวพิมพ์เล็กทั้งหมด
- ตัวแปรที่เก็บตารางเป็น `volatile` ชี้อ็อบเจ็กต์ immutable ที่สลับทั้งก้อนเมื่อดึงสำเร็จ (อ่านไม่ต้องล็อก)

### 4.4 ขั้นตอน
- [ ] `java.net.http.HttpClient` (timeout เชื่อมต่อ 5 วินาที, คำขอ 10 วินาที) ดึงใน `ScheduledExecutorService` ทุก `priceRefreshSeconds` · ถ้า `pricesUrl`/`pricesAccessKey` ว่าง → ไม่ดึง ใช้ cache อย่างเดียว (log INFO ครั้งเดียว)
- [ ] parse ด้วย Gson: เอา `prices` → `Map<String, Long>` · ค่าที่ไม่ใช่ตัวเลข/ติดลบ/ศูนย์/NaN/ใหญ่เกิน `MAX_MONEY` → **ข้ามรายการนั้น** (log WARN รวมจำนวนที่ข้าม ไม่ log ทีละตัวถ้าเยอะ) · ถ้า response ไม่มี `prices` เป็นอ็อบเจ็กต์ หรือได้ไอเท็มน้อยกว่า 1 รายการ → ถือว่าล้มเหลว (ไม่ทับ cache ดีด้วยตารางว่าง)
- [ ] สำเร็จ → สลับตารางในหน่วยความจำ + เขียน `prices-cache.json` อะตอมมิก (`{version, fetchedAt, prices}`) · ล้มเหลว (เครือข่าย/HTTP ≠ 200/parse ไม่ได้) → ใช้ cache เดิม, log WARN (อย่ากระหน่ำ log: ครั้งแรกที่ล้มและทุก 10 นาทีถัดไป), ตั้ง `usingStaleCache()=true`
- [ ] เกิน 1 ชั่วโมงที่ดึงไม่สำเร็จ → ราคาที่แสดงใน `/api/prices` ใส่ธง `"stale": true` ให้ UI เตือน (ยังซื้อขายต่อได้)
- [ ] ❓ ตรวจ `itemId` กับ registry ของเกมจริงก่อนอนุญาตซื้อ/ขาย (เก็บในฟังก์ชันช่วย `ItemRegistryAdapter.exists(String)` ที่เรียกบน server thread/หลัง `SERVER_STARTED`) — ตารางราคาเองเก็บไอเท็มที่ registry ไม่รู้จักไว้ได้แต่ `/api/prices` ต้อง **ไม่แสดง** ไอเท็มที่ registry ไม่รู้จัก
- [ ] API: `GET /api/prices` → 
  ```json
  { "currency":"เหรียญ", "stale":false, "updatedAt":1731560000000,
    "items": { "minecraft:diamond": { "buy":13, "sell":7 } } }
  ```
  (ไอเท็มใน `blockedItems` ไม่อยู่ในรายการ · หน้าเว็บไม่เห็นคีย์/URL/base)

**เทสต์ JUnit** — `PriceMathTest`: ตาราง base 1,2,3,10,99,1000,`MAX_MONEY` → ตรวจ `buy > sell ≥ 1` ทุกค่า · ค่าสุ่ม 100,000 ค่าไม่มีกรณี `buy ≤ sell` · เปอร์เซ็นต์สุดขอบ (sell=100, buy=100 ต้องถูกปฏิเสธโดย validation) · `PriceServiceTest`: parser ข้ามค่าขยะ, ล้มเหลวแล้วไม่ทับ cache, override ทับเว็บ, blocked ไม่มีราคา

**ผ่านเมื่อ (Gate 4)**
- [ ] ราคาในม็อดตรงกับ `prices.html` (เทียบ 5 ไอเท็มสุ่ม แนบผล) หรือมี ❓ ค้างที่ระบุชัดว่าเพราะไม่มีคีย์
- [ ] ตัดอินเทอร์เน็ต/ใส่ URL ผิด → ยังใช้ cache ได้ ระบบไม่ล้ม และ `/api/prices` ยังตอบ
- [ ] ไอเท็มใน `blockedItems` ไม่มีราคาซื้อ/ขาย และไม่อยู่ใน `/api/prices`
- [ ] เทสต์ JUnit ผ่าน · ไม่พบคีย์ใน log/หน้าเว็บ/`/api/prices` (`grep` แนบผล)
- [ ] build 2 เวอร์ชันผ่าน

---

## เฟส 5 — กล่องรับของ + ร้านค้า (แอป "ขาย" และ "ซื้อ")

**เป้าหมาย:** ซื้อ/ขายกับร้านระบบ · ของไม่หายแม้ inventory เต็มหรือผู้เล่นออฟไลน์

**ไฟล์ที่สร้าง:** `MailboxManager.java`, `ShopManager.java`, `ItemCodecs.java` (แปลง `ItemStack ⇄ itemJson`), `ItemRegistryAdapter.java` (ถ้ายังไม่มีจากเฟส 4)
**ข้อมูล:** `mailbox.json`, `shop-usage.json` (เฉพาะเมื่อ `shopDailyBuyQty > 0`)
**ไฟล์ที่แก้:** `ClaimMap`, `ClaimCommands`, `WebServer`, `AuthGate` (แจ้งของค้างตอนผ่านรหัส)

### 5.1 `ItemCodecs` (ส่วนเดียวที่แตะรูปแบบ ItemStack ของเกม)
```java
final class ItemCodecs {
  static String encode(ItemStack stack, RegistryAccess ra);   // → JSON string (ใช้ codec ของเกม; ไม่ทิ้ง enchant/ชื่อ/component)
  static Optional<ItemStack> decode(String json, RegistryAccess ra);
  static ItemView view(ItemStack stack);                      // {itemId, count, label} สำหรับแสดงผล (label = ชื่อกำหนดเอง หรือ ชื่อไอเท็ม + สรุป enchant)
  static boolean isPlain(ItemStack stack);                    // จริงเมื่อไม่มี component พิเศษใด ๆ (ไม่มีชื่อ/enchant/ความเสียหาย/NBT อื่น)
}
```
- ❓ ยืนยันจากซอร์ส: ชื่อ codec ของ `ItemStack` (เช่น `ItemStack.CODEC` / `OPTIONAL_CODEC`), การสร้าง `RegistryOps` + `JsonOps`, วิธีเข้าถึง `RegistryAccess` (`server.registryAccess()`), และวิธีเช็ก "ไม่มี component ต่างจากค่าเริ่มต้น" — ผลอาจต่างระหว่าง 26.2/26.3 (R13)
- **ห้ามเก็บเฉพาะ item id** (จะทำ enchant/ชื่อหาย)
- เพราะต้องใช้ `RegistryAccess` จึง **โหลด `mailbox.json` ผ่านแกนที่เก็บ `itemJson` เป็นสตริงทึบ** ได้ทันทีตอน mod init ส่วนการ decode จริงทำตอนผู้เล่นรับของ (หลัง `SERVER_STARTED`)
- เมื่อ `decode` ล้มเหลว (เช่นไอเท็มจากม็อดที่ถูกถอด) → **ไม่ลบรายการ** ให้ข้ามพร้อม log WARN และแจ้งผู้เล่นว่ามีรายการที่รับไม่ได้ ติดต่อแอดมิน

### 5.2 MailboxManager (แกนไม่พึ่ง Minecraft ตาม R9)
```jsonc
// mailbox.json
{ "version":1,
  "boxes": { "<uuid>": [ { "id":"mb_...", "itemJson":"{...}", "itemId":"minecraft:diamond", "count":64,
                            "label":"เพชร", "from":"shop|auction:<id>|admin", "t":1731560000000 } ] } }
```
```java
public final class MailboxManager {
  public MailboxManager(Path dir, LongSupplier now, int maxEntriesPerPlayer);   // ค่าตั้งต้น 500
  public void load();
  public boolean add(UUID to, MailEntry entry);          // id ซ้ำ (มีอยู่แล้ว) → คืน false และไม่ทำอะไร  ← ใช้ทำ idempotent (R8)
  public boolean hasCapacity(UUID to, int moreEntries);
  public List<MailEntry> list(UUID to);
  public int count(UUID to);
  public void replace(UUID to, String id, MailEntry updatedOrNull);   // null = ลบรายการ; ใช้ตอนรับของได้บางส่วน
}
```
ทุกเมธอดอยู่ใต้ `Market.LOCK` · เขียนไฟล์อะตอมมิกทุกครั้งที่เปลี่ยน · 1 รายการ = ไม่เกิน 1 stack (เกม) แยกก้อนใหญ่เป็นหลาย entry ตาม `maxStackSize`

### 5.3 การรับของ (`claim`) — ทำบน server thread
- คำสั่งเกม `/mail` (แสดงจำนวน+รายการย่อ) และ `/mail claim`
- `claim(player)`: ภายใต้ `Market.LOCK` ไล่ entry ตามเก่าก่อน → `decode` → ใส่ลง inventory ด้วยวิธีของเกมที่คืนจำนวนที่ใส่ไม่ได้ → ถ้าใส่หมด ลบ entry; ถ้าได้บางส่วน `replace` entry ด้วย `count` ที่เหลือ (และ `itemJson` ที่ปรับ count แล้ว) แล้วหยุด (inventory เต็ม) → **บันทึกไฟล์ทันทีในงานเดียวกันบน server thread**
- ⚠️ ข้อยอมรับ: ถ้าเซิร์ฟเวอร์ดับตรงช่วงมิลลิวินาทีระหว่างให้ของกับบันทึกไฟล์ ของอาจซ้ำ 1 stack (เลือกให้ซ้ำดีกว่าหาย) — จดไว้ใน `PHASE_LOG.md`
- `POST /api/mailbox/claim` ต้องออนไลน์: ผู้เล่นไม่ออนไลน์ → 409 `player_offline` "ต้องเข้าเกมก่อนจึงรับของได้" · เรียกผ่าน `GameBridge.call`
- ตอนผ่านรหัสเข้าเกม (เฟส 3) แจ้ง "มีของรอรับ n ชิ้น พิมพ์ /mail claim"

### 5.4 ShopManager — ขายให้ร้าน
`sell(player, itemId, qty)` ทำเป็นงานเดียวบน server thread (`GameBridge.call` จาก web; คำสั่งเกมอยู่บน server thread อยู่แล้ว):
1. ตรวจ: ผู้เล่น AUTHED · `!economy.isFrozen` · `priceService.sellPrice(itemId)` มี (ไม่ใช่ blocked/no_price) · `1 ≤ qty ≤ shopMaxQtyPerTx`
2. นับของตรงเงื่อนไขใน inventory หลัก+แถบด่วนของผู้เล่น (ไม่รวมเกราะ/มือรอง): `itemId` ตรง **และ `isPlain(stack)`** (ของมี enchant/ชื่อ/ความเสียหายไม่ขายให้ร้าน) · ไม่พอ `qty` → 409 `not_enough_items` (ไม่ขายบางส่วน) ไม่แตะอะไร
3. `total = Math.multiplyExact(sellPrice, qty)` · ตรวจ overflow ยอดผู้เล่นล่วงหน้า (ถ้าฝากแล้วเกินเพดาน → ปฏิเสธก่อนลบของ)
4. **ลบของออกจริง** ตามจำนวน — ถ้าลบได้ไม่ครบ ให้คืนที่ลบไปแล้วและยกเลิกทั้งรายการ
5. `economy.deposit(uuid, total, "shop.sell:"+itemId, ref)` — ถ้าไม่ `OK` ผิดคาด → **คืนของ** (ใส่ inventory, ไม่ได้ก็ใส่กล่องรับของ) แล้วตอบ 500 `internal_error`
6. ผลสำเร็จ: `{ "ok":true, "sold":64, "earned":448, "balance":1448 }`
- `/sell hand` (ขายของในมือหลัก ทั้ง stack) · `/sell all` (ขายทุก stack ที่ขายได้ใน inventory หลัก+แถบด่วน ข้ามของที่ไม่มีราคา/ต้องห้าม/ไม่ plain · สรุปผลรวมเป็นข้อความเดียว) — `/sell all` **ต้องยืนยัน**: พิมพ์ครั้งแรกแสดงสรุปจำนวนและเงินที่จะได้ แล้วให้พิมพ์ `/sell all confirm` ภายใน 30 วินาที

### 5.5 ShopManager — ซื้อจากร้าน (เข้ากล่องเสมอ)
`buy(uuid, itemId, qty)`:
1. ตรวจ: ไม่ถูก freeze · `buyPrice` มี · `1 ≤ qty ≤ shopMaxQtyPerTx` · โควตารายวัน (`shopDailyBuyQty>0`: นับต่อ player+item ตามวันที่ท้องถิ่นของเซิร์ฟเวอร์ ใน `shop-usage.json`; เกิน → 409 `limit_reached`)
2. (ผ่าน `GameBridge`) สร้าง `ItemStack` ของ `itemId` แบ่งตาม `maxStackSize` → `ItemCodecs.encode` เป็นรายการ `MailEntry` ทั้งหมด **ก่อนหักเงิน** (ถ้า encode ไม่ได้ ไม่หักเงิน → 500)
3. ภายใต้ `Market.LOCK`: `mailbox.hasCapacity(uuid, n)` ไม่พอ → 409 `limit_reached` ("กล่องรับของเต็ม รับของก่อน") → `economy.withdraw(uuid, total, "shop.buy:"+itemId, ref)` (`INSUFFICIENT` → 409 `insufficient_funds`, ของไม่เกิด) → `mailbox.add` ทุก entry (id กำหนดแน่นอนจาก `ref`+ลำดับ เพื่อ idempotent) → บันทึก usage โควตา
4. `/buy <item> <จำนวน>` ในเกม: ทำเส้นทางเดียวกัน แล้วเรียก `claim` ให้ทันทีถ้าออนไลน์ (ใส่ได้เท่าที่ได้ ที่เหลืออยู่ในกล่อง)
5. ผลสำเร็จ: `{ "ok":true, "bought":64, "spent":832, "balance":168, "mailboxCount":2 }`
- **ราคาใช้จาก `PriceService` เสมอ** ห้ามรับราคา/ยอดรวมจาก body

### 5.6 API เฟสนี้
| Method | Path | Body | สำเร็จ | ข้อผิดพลาดหลัก |
|---|---|---|---|---|
| GET | `/api/shop` | – | `{currency, stale, items:{id:{buy,sell,label}}, mailboxCount}` | 401 |
| POST | `/api/shop/buy` | `{item, qty}` | ดู 5.5 | 400 `invalid_item/invalid_quantity`, 409 `no_price/item_blocked/insufficient_funds/limit_reached/account_frozen` |
| POST | `/api/shop/sell` | `{item, qty}` | ดู 5.4 | 409 `player_offline/not_enough_items/no_price/item_blocked/account_frozen`, 503 `game_unavailable` |
| GET | `/api/mailbox` | – | `{entries:[{id,itemId,count,label,from,t}]}` | 401 |
| POST | `/api/mailbox/claim` | `{}` | `{claimed:n, remaining:m}` | 409 `player_offline` |
| ข้อมูลเสริม | `GET /api/me/inventory-summary` (ตัวเลือก) | – | จำนวนของที่ขายได้ ใช้แสดงในแอปขาย | – |

**เทสต์ JUnit** (แกน): `MailboxManagerTest` — add id ซ้ำไม่เพิ่ม, เกินความจุ, replace/ลบ, restart แล้วข้อมูลอยู่ · ธุรกรรมซื้อ (ทดสอบผ่านฟังก์ชันที่แยกแกนจาก Minecraft): ยอดไม่พอไม่เกิด entry, เงินถูกหักครั้งเดียวเมื่อกล่องรับได้

**ผ่านเมื่อ (Gate 5)** (ทดสอบในเกมจริง แนบผล)
- [ ] ขาย 64 ไอเท็ม: ของหาย 64 และเงินเพิ่มตรง `sellPrice × 64` (เช็กกับ ledger) · ขายของที่มี enchant/ชื่อ/ความเสียหาย ถูกปฏิเสธ
- [ ] `/sell all` ต้องยืนยัน และสรุปตรงกับเงินที่ได้จริง
- [ ] ซื้อตอนยอดไม่พอถูกปฏิเสธ ไม่มีของเกิด ไม่มี ledger เพิ่ม
- [ ] ซื้อตอน inventory เต็ม: ของเข้ากล่อง เงินถูกหักครั้งเดียว · `/mail claim` ได้ของเท่าที่ใส่ได้ ที่เหลืออยู่ในกล่อง · รับซ้ำจนหมด
- [ ] ยิง API ซื้อ/ขายด้วย qty ติดลบ/0/ทศนิยม/ใหญ่มาก, ราคาแปลกใน body (ถูกเมิน), ไอเท็มต้องห้าม, ไอเท็มไม่มีจริง, ผู้เล่นออฟไลน์ตอนขาย → ถูกปฏิเสธด้วยรหัสที่ถูกต้องทั้งหมด
- [ ] ผู้เล่นที่ถูก freeze (ตั้งมือใน `economy.json` ชั่วคราวก่อนเฟส 7) ซื้อ/ขายไม่ได้
- [ ] ซื้อ → ขายคืนทันทีของเดิม: ยอดสุทธิ **ลดลง** เสมอ (arbitrage ไม่ได้) ทดสอบไอเท็มราคา 1, 2, 10, 1000
- [ ] build 2 เวอร์ชันผ่าน

---

## เฟส 6 — ประมูลผู้เล่น (แอป "ประมูล")

**เป้าหมาย:** ลงขาย / เสนอราคา / ซื้อทันที / ปิดอัตโนมัติ โดยของและเงินไม่หลุด และกู้คืนหลังดับได้

**ไฟล์ที่สร้าง:** `AuctionManager.java`, `AuctionTicker.java` (ตัวปิดประมูลบน server tick)
**ข้อมูล:** `auctions.json`
**ไฟล์ที่แก้:** `ClaimMap`, `ClaimCommands` (`/ah`), `WebServer`

### 6.1 สถานะและโครงข้อมูล
สถานะ: `ACTIVE → SOLD | EXPIRED | CANCELLED` (ปลายทาง 3 ตัวแล้วเปลี่ยนไม่ได้อีก)
```jsonc
{ "version":1, "auctions": [
  { "id":"au_...", "seller":"<uuid>", "sellerName":"Alice",
    "itemJson":"{...}", "itemId":"minecraft:diamond_sword", "count":1, "label":"ดาบเพชร (Sharpness V)",
    "startPrice":100, "buyout":1000,            // buyout: null ถ้าไม่มี
    "highBid":0, "highBidder":null, "highBidderName":null,
    "createdAt":173..., "endsAt":173...,
    "status":"ACTIVE",
    "paidOut":false, "itemDelivered":false,     // ธงกัน settle ซ้ำ (R8)
    "closedAt":null, "winner":null, "finalPrice":null } ] }
```
- เก็บ `itemId/count/label` ไว้ในรายการเลย ให้เว็บแสดงได้โดยไม่ต้อง decode
- รายการปลายทางเก็บไว้ดูประวัติ 7 วันหลังปิด แล้วค่อยลบออกจากไฟล์ (ตอนสตาร์ท + ทุกชั่วโมง)

### 6.2 เงินใน escrow (ใช้ `EconomyManager.apply`)
ประมูลแต่ละรายการมีบัญชี `Acct.escrow(auctionId)` ทุกการเคลื่อนไหวเป็น `Move` ใน ledger (ผลรวมเงินในระบบ + escrow คงที่ตลอด)
| เหตุการณ์ | Move(s) (ทั้งชุดเป็น `apply` เดียว) |
|---|---|
| ค่าลงประกาศ | player → SYSTEM (`auction.listfee:<id>`) |
| เสนอราคา `A` (ครั้งแรก) | bidder → E (`auction.bid:<id>`, A) |
| เสนอราคา `A` (มีคนนำอยู่) | E → ผู้นำเดิม (`auction.refund:<id>`, highBid) **และ** bidder → E (`auction.bid:<id>`, A) |
| ซื้อทันที `B` | (ถ้ามีผู้นำเดิม) E → ผู้นำเดิม คืน highBid · buyer → E (B) · E → seller (B − fee) · E → SYSTEM (fee) |
| ขายสำเร็จ (ปิดตามเวลา) | E → seller (highBid − fee, `auction.sale:<id>`) · E → SYSTEM (fee, `auction.fee:<id>`) |
| ยกเลิก/ไม่มีคนประมูล | ไม่มีเงินเคลื่อนไหว (ค่าลงประกาศไม่คืน) |
- `fee = floorDiv(price * auctionFeePercent, 100)` (ผู้ขายได้ `price − fee`) · ค่าลงประกาศ `= ceilDiv(startPrice * auctionListFeePercent, 100)`
- ขั้น "ตรวจ → จำลอง → append ledger → commit" ของ `apply` (2.4) ทำให้ **bid ที่เงินไม่พอล้มโดยไม่ทำอะไรเลย** และ **การคืนเงินผู้นำเดิมกับการกันเงินผู้ใหม่เป็นก้อนเดียว** ไม่มีทางคืนแล้วไม่หัก หรือหักแล้วไม่คืน

### 6.3 กฎของแต่ละการกระทำ (ภายใต้ `Market.LOCK` ทั้งหมด ยกเว้นระบุ)
**ลงขาย** `/ah sell <ราคาเริ่ม> [ซื้อทันที] [ชั่วโมง]` — ทำเป็นงานเดียวบน server thread ถือของในมือหลัก:
1. ตรวจ: AUTHED · ไม่ freeze · ของในมือไม่ว่าง และไม่อยู่ใน `blockedItems` (ของมี enchant/ชื่อลงประมูลได้ — ต่างจากร้านค้า) · `startPrice ≥ 1` · `buyout` (ถ้ามี) `> startPrice` · ชั่วโมงอยู่ `1..auctionMaxHours` (ไม่ระบุ = `auctionDefaultHours`) · จำนวนรายการ ACTIVE ของผู้ขาย `< auctionMaxActivePerPlayer` (`limit_reached`)
2. `ItemCodecs.encode` ก่อน (ล้ม → ไม่ทำอะไร)
3. ในล็อก: ถ้ามีค่าลงประกาศ → `economy.apply([player→SYSTEM])` (`INSUFFICIENT` → ยกเลิก **ก่อนแตะของ**) → **ลบของออกจากมือ** → สร้างรายการ `ACTIVE` + เขียน `auctions.json`; ถ้าเขียนไฟล์ล้มเหลวให้คืนของเข้ามือ/กล่อง (และคืนค่าลงประกาศด้วย `apply([SYSTEM→player])` reason `auction.cancel:<id>`)
4. ของอยู่ใน escrow = ในไฟล์ `auctions.json` เท่านั้น (ไม่ซ้ำกับมือ/กล่อง)
5. ลงขายจากเว็บไม่ได้ (ต้องถือของในเกม) → เว็บแสดงคำแนะนำให้ใช้ `/ah sell`

**เสนอราคา** `bid(uuid, id, amount)` — เกิดจากเว็บหรือคำสั่งก็ได้ (ไม่ต้อง server thread):
1. รายการต้อง `ACTIVE` และ `now < endsAt` (`auction_closed`) · ผู้เสนอ ≠ ผู้ขาย (`own_auction`) · ไม่ freeze · ผู้เสนอ ≠ ผู้นำปัจจุบัน (`already_highest` — ถ้าอยากเพิ่ม ให้รอถูกแซงก่อน; ตัดสินใจนี้เพื่อให้ตรรกะ hold ง่ายและไม่มีกรณีคืน-หักตัวเอง)
2. `minBid`: ไม่มีคนประมูล = `startPrice`; มี = `highBid + max(1, ceilDiv(highBid * auctionMinIncrementPercent, 100))` · `amount < minBid` → 409 `bid_too_low` (ตอบ `minBid` ใน body ให้ UI) · ถ้ามี `buyout` และ `amount ≥ buyout` → 409 `use_buyout`
3. `economy.apply(moves ตามตาราง 6.2)` · `INSUFFICIENT` → 409 `insufficient_funds`
4. อัปเดต `highBid/highBidder/highBidderName` · anti-snipe: ถ้า `auctionAntiSnipeSeconds>0` และ `endsAt − now < ค่านั้น` → `endsAt = now + ค่านั้น` · เขียน `auctions.json`
5. ลำดับการคงอยู่ (R8): ledger (ใน `apply`) → `auctions.json` · ถ้าดับหลัง `apply` ก่อนบันทึก `auctions.json` → ตอนสตาร์ทตรวจพบ escrow ≠ highBid (6.5) และ **หยุดตลาดตาม R6** พร้อมข้อความชัดเจนให้แอดมินแก้ (กรณีหายาก ยอมรับได้ ห้ามเดาแก้เอง)

**ซื้อทันที** `buyout(uuid, id)`: เงื่อนไขเดียวกับข้อ 1 (ยกเว้น `already_highest` ไม่ใช้ — ผู้นำเดิมซื้อทันทีได้ โดยระบบคืน highBid ของเขาแล้วหักเต็ม `buyout` ในชุด `apply` เดียว) · ต้องมี `buyout` ไม่งั้น 400 `bad_request` · `apply` ชุดซื้อทันที (6.2) → ปิดทันทีตามขั้น settle (6.4) โดยผู้ชนะ = ผู้ซื้อ, `finalPrice = buyout`

**ยกเลิก** `cancel(uuid, id)` โดยผู้ขายเท่านั้น (`forbidden` ถ้าไม่ใช่) และเฉพาะ `highBidder == null` (`auction_has_bids`) → สถานะ `CANCELLED`, ของเข้ากล่องรับของผู้ขาย (mailbox id `mb_au_<id>_return`)

### 6.4 ปิดประมูล (settle) — ต้อง idempotent
`AuctionTicker`: ใช้ `ServerTickEvents.END_SERVER_TICK` (❓ ยืนยันชื่อ) นับทุก 20 tick เรียก `auctionManager.closeExpired(now)` (บน server thread — ทำงานเร็ว ไม่มี I/O หนักนอกจากเขียนไฟล์เล็ก)
`settle(auction)` ภายใต้ `Market.LOCK` เรียงตามนี้ **ทุกขั้นเช็กธงก่อนทำ** เพื่อให้เรียกซ้ำ/กู้หลังดับได้:
1. ถ้า `status != ACTIVE` และเสร็จครบแล้ว → ออก (ไม่ทำซ้ำ)
2. มีผู้ชนะ (`highBidder != null`): 
   - ถ้า `!paidOut` → `economy.apply([E→seller (highBid−fee), E→SYSTEM fee])` → ตั้ง `paidOut=true` → เขียน `auctions.json`
   - ถ้า `!itemDelivered` → `mailbox.add(winner, entry id = "mb_au_<id>_winner", from="auction:<id>")` (id ซ้ำ = ไม่เพิ่มซ้ำ) → `itemDelivered=true` → เขียนไฟล์
   - ตั้ง `status=SOLD`, `winner`, `finalPrice`, `closedAt`
3. ไม่มีผู้ประมูล → `mailbox.add(seller, id "mb_au_<id>_return")` → `itemDelivered=true` → `status=EXPIRED`
4. เมื่อ `apply` ใน (2) ได้ `IO_ERROR` → ปล่อยไว้ `ACTIVE` ให้ tick รอบหน้าลองใหม่ (ห้ามข้าม)
5. หลังปิด แจ้งผู้ที่ออนไลน์ (ผู้ขาย/ผู้ชนะ) ด้วยข้อความในเกม (ส่งผ่าน server thread — ตัวทิกเกอร์อยู่บน server thread อยู่แล้ว)

### 6.5 กู้คืนตอนสตาร์ท
หลัง `economy.load()`, `mailbox.load()`, `auctions.load()` และหลัง `SERVER_STARTED`:
1. ตรวจ: สำหรับทุกประมูล `ACTIVE` `economy.escrowBalance(id) == highBid` และสำหรับทุกประมูลที่ปิดแล้ว `escrowBalance(id) == 0` · ไม่ตรง → R6 (ตลาดปิด + SEVERE ระบุ id)
2. ประมูล `ACTIVE` ที่ `endsAt ≤ now` → `settle` ทันที
3. ประมูลที่ `paidOut`/`itemDelivered` ค้างครึ่งทาง (สถานะยัง `ACTIVE` แต่ธงบางตัวเป็น true) → `settle` ต่อให้จบ

### 6.6 API และคำสั่ง
| Method | Path | Body | ผล |
|---|---|---|---|
| GET | `/api/auctions` | – | `{auctions:[ACTIVE ทั้งหมด], mine:[ของฉันที่ปิดใน 7 วัน], minBidHint}` ฟิลด์: `id, sellerName, itemId, count, label, startPrice, buyout, highBid, highBidderName, endsAt, status, canBid, canCancel, minBid` (**ไม่ส่ง `itemJson`/uuid**) |
| POST | `/api/auctions/{id}/bid` | `{amount}` | `{ok, highBid, endsAt, balance}` |
| POST | `/api/auctions/{id}/buyout` | `{}` | `{ok, balance, mailboxCount}` |
| POST | `/api/auctions/{id}/cancel` | `{}` | `{ok}` |
| – | `POST /api/auctions` | – | **ไม่มี** (ลงขายด้วย `/ah sell` เท่านั้น) → ถ้ามีคนเรียก 405 |
- คำสั่งเกม: `/ah` (แสดงที่อยู่เว็บ + สรุปประมูลของฉัน) · `/ah sell …` · `/ah cancel <id>` · `/ah bid <id> <จำนวน>`

**เทสต์ JUnit (ใช้ Clock ปลอม + EconomyManager จริง ไม่ต้องใช้ Minecraft)** — `AuctionManagerTest`
- วงจรเต็ม: ลงขาย → bid → ถูกแซง (คืนเงินครบ) → ปิด → ผู้ชนะได้ entry ในกล่อง ผู้ขายได้ `price−fee` → `sumPlayerBalances + sumEscrow + systemNet == 0` ทุกขั้น
- bid ต่ำกว่าขั้นต่ำ / ผู้ขายประมูลเอง / หลังหมดเวลา / ผู้นำเดิมประมูลซ้ำ / `amount ≥ buyout` → ปฏิเสธด้วยรหัสถูกต้อง สถานะไม่เปลี่ยน
- buyout ขณะมีผู้นำ (รวมกรณีผู้นำเดิมเป็นผู้ซื้อทันที) เงินถูกต้อง
- anti-snipe ต่อเวลาถูกต้อง · cancel ได้เฉพาะไม่มีบิด
- **จำลองดับ:** เรียก `settle` ให้ throw กลางทางทุกจุด (หลัง paidOut ก่อน item, ฯลฯ) แล้วสร้าง manager ใหม่จากไฟล์ + `settle` ซ้ำ → ผลสุดท้ายเหมือนกันและไม่มีการจ่ายเงิน/ส่งของซ้ำ
- bid พร้อมกัน 50 thread บนรายการเดียว → มีผู้ชนะเดียว ยอดรวมคงที่ ไม่มีบิดต่ำกว่าบิดสูงที่ชนะ

**ผ่านเมื่อ (Gate 6)**
- [ ] เทสต์ JUnit ข้างบนผ่านทั้งหมด
- [ ] ในเกมจริง 2 บัญชี: วงจรเต็มทำงาน ของ/เงินถูกต้อง ผลรวมเงิน+escrow คงที่ (ใช้ `tools/CheckLedger.java` ตรวจ)
- [ ] ของชิ้นหนึ่งไม่อยู่สองที่ (มือ / auctions.json / mailbox) ในเวลาเดียวกัน (ตรวจหลังแต่ละขั้น)
- [ ] ปิดเซิร์ฟเวอร์ (kill แบบแรง) กลางประมูล แล้วเปิดใหม่ → รายการและเงินยังถูกต้อง ประมูลที่หมดเวลาระหว่างปิดถูก settle ตอนเปิด
- [ ] ยิง API ด้วยค่าแปลก (amount ติดลบ/ทศนิยม/ใหญ่เกิน, id ไม่มี, session คนอื่นยกเลิกของคนอื่น) ถูกปฏิเสธครบ
- [ ] build 2 เวอร์ชันผ่าน

---

## เฟส 7 — ระบบแอดมิน

**เป้าหมาย:** แอดมินจัดการเงิน ราคา ประมูล บัญชี โดยมีบันทึกทุกครั้ง และไม่มีทางทำให้เงินติดลบ/ของหาย

**ไฟล์ที่สร้าง:** `AdminService.java`, `AuditLog.java`
**ข้อมูล:** `audit.jsonl`
**ไฟล์ที่แก้:** `WebServer`, `ClaimCommands` (`/eco`), `AuctionManager` (เมธอด `adminCancel`), `AccountManager` (เมธอด `resetPassword`), `ClaimMapConfig` (`reload()`)

### 7.1 AuditLog
```java
public final class AuditLog {
  public void write(String actor, String action, Map<String,Object> details, String ip, boolean success);
  public List<String> tail(int limit);          // ล่าสุด limit บรรทัด (สูงสุด 200)
}
```
บรรทัดละ JSON: `{"t":..., "actor":"Alice|CONSOLE", "action":"money.give", "details":{...}, "ip":"192.168.x.x|null", "ok":true}` · เขียนแบบ append ตาม R5 · **เขียนทั้งกรณีสำเร็จและถูกปฏิเสธ** (เช่นพยายามหักเกินยอด) · ห้ามใส่รหัสผ่าน/token ใน `details`

### 7.2 กติกากลางของ `/api/admin/*`
ทุก endpoint: `requireSession` → `isAdminSession` (ไม่ใช่ → **403** `forbidden` และเขียน audit ว่าพยายามเข้าถึง) → ตรวจ input → ทำ → เขียน audit (ทุกครั้ง) · action ที่เปลี่ยนข้อมูลต้องมี `reason` (สตริง 3–200 ตัวอักษร) ไม่งั้น 400 `bad_request`

### 7.3 ความสามารถและ endpoint

| Method | Path | Body | ทำอะไร / ข้อกำหนด |
|---|---|---|---|
| GET | `/api/admin/overview` | – | รายชื่อผู้เล่น {name, balance, frozen, online, mailboxCount}, ผลรวม {balances, escrow, systemNet, invariantOk}, สถานะตลาด/ราคา |
| POST | `/api/admin/money` | `{player, op:"give"\|"take"\|"set", amount, reason}` | ผ่าน `EconomyManager.apply(..., bypassFreeze=true)` · `give`: SYSTEM→player (`admin.give`) · `take`: player→SYSTEM (`admin.take`) เกินยอด → 409 `insufficient_funds` (ห้ามติดลบ) · `set`: คำนวณส่วนต่างแล้ว give/take ด้วยเหตุผล `admin.set` (`amount ≥ 0`) · ledger `ref` = `admin:<ชื่อแอดมิน>` |
| GET | `/api/admin/wallet/{name}?limit=` | – | ประวัติของผู้เล่นใดก็ได้ (limit ≤ 200) |
| POST | `/api/admin/prices/override` | `{item, base\|null, reason}` | ตั้ง/ลบ override ของ base (จำนวนเต็ม ≥ 1) |
| POST | `/api/admin/prices/block` | `{item, blocked, reason}` | บล็อก/ปลดบล็อกไอเท็ม (ห้ามซื้อ/ขาย/ประมูล) |
| POST | `/api/admin/prices/refresh` | `{}` | `priceService.refreshNow()` · ล้มเหลว → ตอบ 200 `{ok:false, message}` และคง cache |
| GET | `/api/admin/auctions` | – | ประมูลทั้งหมด (รวมที่ปิดแล้ว) พร้อม uuid |
| POST | `/api/admin/auctions/{id}/cancel` | `{reason}` | เฉพาะ `ACTIVE` · ในล็อกเดียว: คืนเงิน highBid จาก escrow ให้ผู้นำ (`apply`, bypass freeze), ของคืนผู้ขายผ่านกล่องรับของ (id `mb_au_<id>_return`), สถานะ `CANCELLED` · ใช้ลำดับ idempotent แบบ settle (ธง `paidOut`/`itemDelivered`) |
| POST | `/api/admin/accounts/{name}/freeze` | `{frozen, reason}` | `economy.setFrozen` · ผู้ถูก freeze: ซื้อ/ขาย/ประมูล/โอน (ส่งและรับ) ไม่ได้ แต่ล็อกอิน/ดูข้อมูล/รับของจากกล่องได้ |
| POST | `/api/admin/accounts/{name}/reset-password` | `{reason}` | **ลบ hash รหัสผ่านของบัญชี** + ยกเลิก session ทั้งหมด → ผู้เล่นต้อง `/register` ใหม่ตอนเข้าเกม (ไม่แสดงรหัสใด ๆ) · ⚠️ ระหว่างช่วงรอตั้งรหัสใหม่ใน offline-mode ใครเข้าเกมด้วยชื่อนั้นก่อนจะได้ตั้งรหัส → เตือนในรายงานและ UI ว่า "ควรแจ้งเจ้าของชื่อให้เข้าเกมทันที" |
| POST | `/api/admin/config/reload` | `{}` | `ClaimMapConfig.reload()` (โหลดใหม่เฉพาะค่าที่ปลอดภัย: players, admins, ราคา/ร้าน/ประมูล; **ไม่เปลี่ยน port/bindAddress** ต้องรีสตาร์ท — แจ้งในผลลัพธ์) |
| GET | `/api/admin/audit?limit=` | – | audit ล่าสุด (≤ 200) |

### 7.4 คำสั่งเกม (สำหรับ OP)
`/eco give|take|set <ผู้เล่น> <จำนวน> [เหตุผล…]` · เรียก **เมธอดเดียวกับ API** (`AdminService.money(...)`) ไม่ทำซ้ำตรรกะ · เขียน audit โดย `actor` = ชื่อผู้พิมพ์ หรือ `CONSOLE` · ❓ วิธีตรวจสิทธิ์ OP/permission ในคำสั่ง Brigadier ของเวอร์ชัน 26.x ให้ดูจากซอร์ส (ห้ามเดา) · ต้อง AUTHED ด้วย (เฟส 3) ถ้าเป็นผู้เล่น

**เทสต์ JUnit:** `AdminServiceTest` — give/take/set ถูกต้อง, take เกินยอดถูกปฏิเสธ, ไม่มี `reason` ถูกปฏิเสธ, ทุกการกระทำ (สำเร็จและไม่สำเร็จ) มี audit, `invariant` คงอยู่, adminCancel คืนเงิน+ของครบและเรียกซ้ำไม่คืนซ้ำ

**ผ่านเมื่อ (Gate 7)**
- [ ] ผู้เล่นปกติเรียก `/api/admin/*` ทุกตัวได้ 403 (ทดสอบทีละ endpoint) และมี audit ของความพยายาม
- [ ] ทุกการกระทำแอดมินมีบรรทัดใน `audit.jsonl` (เวลา, ใคร, ทำอะไร, รายละเอียด, IP, สำเร็จหรือไม่)
- [ ] freeze แล้วผู้เล่นนั้นซื้อ/ขาย/ประมูล/โอนไม่ได้ (409 `account_frozen`) · unfreeze แล้วทำได้
- [ ] `adminCancel` ประมูลที่มีผู้เสนอ: เงินคืนผู้เสนอครบ ของกลับผู้ขายครบ ผลรวมเงิน+escrow คงที่
- [ ] reset-password แล้วผู้เล่นต้องตั้งรหัสใหม่ในเกม และ session เว็บเก่าใช้ไม่ได้
- [ ] `/eco` ใช้ได้เฉพาะ OP/คอนโซล และผลตรงกับ API
- [ ] build 2 เวอร์ชันผ่าน

---

## เฟส 8 — หน้าเว็บแบบ "มือถือเปิดแอป"

**เป้าหมาย:** หน้าเว็บเดียว หน้าโฮมเป็นไอคอนแอป แตะเปิดเต็มจอ ใช้ได้ทั้งมือถือและคอม

**ไฟล์:** `src/main/resources/claimmap-web/` (เขียนใหม่) แนะนำโครง
```
claimmap-web/
  index.html            เปลือกหน้า: แถบสถานะ + พื้นที่แอป + ปุ่ม Home/Back
  css/app.css
  js/core.js            api(), router, ui helpers (modal ยืนยัน, toast), session
  js/apps/map.js  sell.js  buy.js  auction.js  wallet.js  mailbox.js  prices.js  admin.js
  icons/<item_id>.png   (ไม่บังคับ) ไอคอนไอเท็ม; ถ้าไม่มีให้ใช้ตัวอักษรแรก/อีโมจิแทน
```
**ไฟล์ Java ที่แก้:** `WebServer` — เพิ่ม static route
- `GET /css/*`, `/js/*`, `/icons/*` อ่านจาก classpath `claimmap-web/...`
- ⚠️ **กัน path traversal:** อนุญาตเฉพาะ path ที่ตรง `^/(css|js|icons)/[A-Za-z0-9_./\-]+$`, ปฏิเสธถ้ามี `..`, `//`, `\`, หรือขึ้นต้นด้วย `.` · ไอคอนไอเท็มตรง `^/icons/[a-z0-9_]+\.png$`
- Content-Type ตามนามสกุล (`.html text/html; charset=utf-8`, `.css text/css`, `.js text/javascript`, `.png image/png`, `.svg image/svg+xml`) · `Cache-Control: no-cache` สำหรับ html/js/css, `max-age=86400` สำหรับ icons · ไม่พบ → 404
- `claim-map.html` ที่ root: ตรวจว่ามีอะไรอ้างถึงหรือไม่ (grep ทั้ง repo) — ไม่มี → ลบ; มี → แทนที่ด้วยหน้า redirect ไป `index.html`; จดการตัดสินใจใน `PHASE_LOG.md`

### 8.1 โครงหน้าจอ
```
┌ แถบสถานะ: ชื่อ · 💰ยอด · 📦กล่องของ(n) ┐
│ [🗺 แผนที่] [🏪 ขาย] [🛒 ซื้อ] [🔨 ประมูล] │  ← หน้าโฮม
│ [👛 กระเป๋า] [📦 กล่องของ] [🏷 ราคา] [⚙ แอดมิน*] │  (*เฉพาะ admin)
└───────── ปุ่ม Home / Back ─────────┘
```
- ธีมส้ม `#e96f00` เหลี่ยม (ไม่มี border-radius) ตาม `prices.html` — ตั้งเป็น CSS variables (`--accent`, `--bg`, `--fg`, `--card`, `--danger`, `--ok`) และรองรับโหมดมืด/สว่างตาม `prefers-color-scheme`
- mobile-first: ปุ่มสูง ≥ 44px · ไอคอนแอปใหญ่ · `<meta name="viewport" content="width=device-width, initial-scale=1">` · ใช้ได้บนคอมได้ (จำกัดความกว้างเนื้อหา ~720px กลางจอ)
- ไม่โหลดสิ่งใดจากภายนอก (ฟอนต์ระบบเท่านั้น `system-ui`, ไม่มี CDN) ไอคอนแอปใช้อีโมจิหรือ SVG ฝังในไฟล์

### 8.2 สถาปัตยกรรมฝั่งหน้าเว็บ
- vanilla JS (ES modules `<script type="module">` ได้) **ไม่มี build step ไม่มี framework**
- Router แบบ hash: `#home`, `#map`, `#sell`, `#buy`, `#auction`, `#wallet`, `#mailbox`, `#prices`, `#admin` · ฟังก์ชัน `render<App>(container)` ต่อแอป (ย้ายแผนที่เดิมมาเป็น `renderMap` โดยไม่ทำให้ฟีเจอร์เดิมหาย) · แต่ละแอปมีฟังก์ชัน `dispose()` ที่หยุด polling/ตัวฟังอีเวนต์ตอนออกจากแอป
- session token เก็บใน `localStorage` (คีย์เดียว) · ทุกคำขอผ่านฟังก์ชัน `api(method, path, body)` ตัวเดียว: แนบ `Authorization` · parse error ตามรูปแบบหัวข้อ E · เมื่อ 401 → ลบ session → ไปหน้าล็อกอิน · เมื่อ 429 → แสดงเวลารอจาก `retryAfterSeconds`
- กลับด้วยปุ่ม Back ของเบราว์เซอร์ได้ · เปลี่ยนแท็บ/รีเฟรชไม่หลุดล็อกอิน (ใช้ session ใน `localStorage`; รีเฟรชแล้วเรียก `/api/me` เช็กอีกครั้ง) · ถ้า hash ไม่รู้จัก/แอปแอดมินแต่ไม่ใช่ admin → กลับ `#home`

### 8.3 ⚠️ ความปลอดภัยฝั่งหน้าเว็บ (บังคับ)
- **ห้ามใช้ `innerHTML`/`insertAdjacentHTML` กับข้อมูลใด ๆ ที่มาจากเซิร์ฟเวอร์หรือผู้ใช้** (ชื่อผู้เล่น, ชื่อไอเท็มที่ตั้งเอง, label, ข้อความ error) ใช้ `textContent`/`createElement` เท่านั้น — ชื่อไอเท็มกำหนดเองคือช่องทาง XSS หลัก
- URL รูปไอคอนประกอบจาก item id ที่ผ่าน regex เท่านั้น (`^[a-z0-9_]+$`) และมี fallback เมื่อโหลดไม่ได้
- ไม่เก็บรหัสผ่านใน `localStorage` · ฟอร์มรหัสผ่านใช้ `type="password"` + `autocomplete`

### 8.4 พฤติกรรมของแต่ละแอป
| แอป | แสดง | เรียก API | หมายเหตุ |
|---|---|---|---|
| ล็อกอิน | ชื่อ + รหัส | `POST /api/login` | แสดง 401/429 ให้อ่านง่าย · ห้ามบอกว่าชื่อไหนมี/ไม่มี |
| แผนที่ | แผนที่เดิม | endpoint เดิม | ย้ายมาโดยพฤติกรรมเหมือนเดิม |
| ขาย | รายการไอเท็มที่ขายได้ + ราคาขาย + ช่องจำนวน | `GET /api/shop`, `POST /api/shop/sell` | ผู้เล่นออฟไลน์ → แสดงข้อความ "ต้องเข้าเกม" (409) · ยืนยันก่อนขาย |
| ซื้อ | ตาราง/การ์ดไอเท็ม + ราคาซื้อ + ค้นหา + ช่องจำนวน | `GET /api/shop`, `POST /api/shop/buy` | หลังซื้อแจ้ง "ของอยู่ในกล่องของ" |
| ประมูล | รายการ ACTIVE + นับถอยหลัง + ปุ่มเสนอ/ซื้อทันที/ยกเลิก | `/api/auctions*` | รีเฟรชทุก 3–5 วินาที (เฉพาะตอนแท็บมองเห็น `document.visibilityState`) · แสดง `minBid` · ข้อความ "ลงขายใช้ `/ah sell` ในเกม" |
| กระเป๋าเงิน | ยอด + ประวัติ + แบบฟอร์มโอน | `GET /api/wallet`, `POST /api/pay` | ส่ง `X-Request-Id` (สุ่มใหม่ต่อการกดยืนยันครั้งหนึ่ง) |
| กล่องของ | รายการของรอรับ + ปุ่มรับ | `GET /api/mailbox`, `POST /api/mailbox/claim` | ออฟไลน์ → ข้อความ 409 |
| ราคา | ตารางจาก `/api/prices` + ช่องค้นหา | `GET /api/prices` | **ไม่ฝังรูป base64 ทั้งหมดในหน้าเดียว** แบบ `prices.html` — โหลดไอคอนทีละไฟล์แบบ lazy (`loading="lazy"`) · แสดงเตือนเมื่อ `stale:true` |
| แอดมิน | แท็บ: ภาพรวม · เงิน · ราคา · ประมูล · บัญชี · audit | `/api/admin/*` | เห็นเฉพาะ `admin:true` · ทุกปุ่มทำลายล้าง/เงินต้องกรอก `reason` |

### 8.5 ปุ่มที่ใช้เงิน (ทุกปุ่ม ไม่มียกเว้น)
ขึ้นหน้ายืนยัน (modal) แสดง: สิ่งที่จะทำ + ราคารวม + **ยอดคงเหลือหลังทำรายการ** → ปุ่ม "ยืนยัน" ปิดทันทีที่กดและค้างสถานะ "กำลังดำเนินการ…" จนได้ผล (กันกดซ้ำ) → แสดงผลสำเร็จ/ข้อความ error จากเซิร์ฟเวอร์ → รีเฟรชยอดและรายการ · ตัวเลขทั้งหมดที่แสดงมาจากเซิร์ฟเวอร์ (หน้าเว็บคำนวณเพื่อแสดงตัวอย่างได้ แต่ห้ามส่งยอด/ราคาไปให้เซิร์ฟเวอร์เชื่อ)

### 8.6 แถบสถานะและ polling
แถบสถานะรีเฟรชยอด/จำนวนของทุก ~5 วินาที ผ่าน `GET /api/wallet` (หรือ endpoint สรุปเล็ก ๆ ถ้าเพิ่ม) และ `GET /api/mailbox` (เอาแค่ `count` — ถ้าอยากลดโหลดให้เพิ่มฟิลด์ `mailboxCount` ใน `/api/wallet`) · หยุด polling เมื่อแท็บถูกซ่อน · polling ล้มเหลวติดกัน 3 ครั้ง → แสดงแถบ "เชื่อมต่อไม่ได้" แล้วลองใหม่ถอยหลังแบบ backoff

**ผ่านเมื่อ (Gate 8)**
- [ ] ใช้ครบทุกแอปบนมือถือจริงได้ (แนบภาพหน้าจอหรือบันทึกผลทีละแอป) และบนคอม
- [ ] เปลี่ยนแท็บ/รีเฟรช/กดกลับ ไม่หลุดล็อกอิน · session หมดอายุ → กลับหน้าล็อกอินพร้อมข้อความ
- [ ] ผู้เล่นปกติไม่เห็นแอปแอดมิน และพิมพ์ `#admin` เองก็เข้าไม่ได้ (และ API ตอบ 403)
- [ ] ชื่อไอเท็ม/ชื่อผู้เล่นที่เป็น `<img src=x onerror=alert(1)>` แสดงเป็นข้อความธรรมดา ไม่รันสคริปต์ (ทดสอบจริงด้วยไอเท็มที่เปลี่ยนชื่อในประมูล)
- [ ] `curl` ทดสอบ path traversal (`/js/../../config/claimmap/accounts.json`, `/icons/%2e%2e/...`) ได้ 404 ทั้งหมด
- [ ] ปุ่มเงินทุกปุ่มมี modal ยืนยันและกดซ้ำไม่ได้ (ทดสอบกดรัว ๆ แล้ว ledger มีรายการเดียว)
- [ ] ไม่มีคำขอไปโดเมนภายนอกเลย (เปิด DevTools → Network ตรวจ)
- [ ] build 2 เวอร์ชันผ่าน และหน้าเว็บอยู่ใน jar ที่ build ออกมา (เปิดจาก jar จริง ไม่ใช่จากโฟลเดอร์ dev)

---

## เฟส 9 — ทดสอบรวมก่อนใช้จริง (ผู้เล่น 5 คน)

**ไฟล์ที่สร้าง:** `tools/CheckLedger.java` (ถ้ายังไม่ได้ทำในเฟส 2 ให้ทำ/ขยายตอนนี้), `tools/ApiFuzz.java`, `docs/PLAYER_GUIDE.md`, `docs/TEST_REPORT.md`

รายการทดสอบ (ทุกข้อต้องมีผลบันทึกใน `docs/TEST_REPORT.md`: ทำอะไร → ผลที่ได้ → ผ่าน/ไม่ผ่าน)
- [ ] 2 บัญชีพร้อมกัน: ประมูลแข่งกัน, โอนเงินข้ามกัน, ซื้อ-ขายพร้อมกัน
- [ ] ปิด/เปิดเซิร์ฟเวอร์กลางธุรกรรม (ประมูลกำลังนับถอยหลัง; kill -9 ระหว่างโอน/ซื้อ) แล้วตรวจยอดรวมด้วย `CheckLedger`
- [ ] `tools/CheckLedger.java` ตรวจ: replay `ledger.jsonl` ตั้งแต่ต้น = `economy.json` · `sumPlayerBalances + sumEscrow + systemNet == 0` · escrow ของประมูลที่ปิดแล้ว = 0 · escrow ของ ACTIVE = `highBid` · พิมพ์ผ่าน/ไม่ผ่านพร้อมส่วนต่าง (รันด้วย `java tools/CheckLedger.java <โฟลเดอร์ config/claimmap>`)
- [ ] `tools/ApiFuzz.java` ยิง API ตรง ๆ ด้วยค่าแปลก: จำนวนติดลบ/0/ทศนิยม/สตริง/ใหญ่มาก/`null`, id ไอเท็มไม่มี/ผิดรูป, ราคาปลอมใน body, body ใหญ่เกิน 16 KB, JSON พัง, method ผิด, ไม่มี session / session ของคนอื่น / session หมดอายุ / session ของคนที่ถูกเอาออกจาก whitelist → ต้องได้รหัสตามหัวข้อ E ทั้งหมด ไม่มี 500 และ `CheckLedger` ยังผ่าน
- [ ] inventory เต็ม, ผู้เล่นออฟไลน์ตอนประมูลปิด, ผู้เล่นตายขณะถือของที่กำลังจะลงขาย, ผู้เล่นหลุดกลางพิมพ์รหัส
- [ ] AuthGate: เข้า-ออกซ้ำเร็ว ๆ ไม่ทำให้สถานะค้าง (ไม่ผ่านรหัสแล้วออก → เข้ามาต้องใส่รหัสอีก)
- [ ] ผู้เล่นปกติเรียก `/api/admin/*` และ `/api/command` ไม่ได้ · master token ไม่ปรากฏในหน้าเว็บ/ log
- [ ] backup ทำงานตามเวลา และ **กู้คืนจาก backup ได้จริง** (ทดสอบกู้ไปโฟลเดอร์ใหม่แล้วเปิดเซิร์ฟเวอร์ทดสอบ + `CheckLedger` ผ่าน)
- [ ] ทดสอบ 26.2 และ 26.3 ทั้งสองเวอร์ชัน (เปิดเกมจริงอย่างน้อยวงจร: เข้าเกม→รหัส→ซื้อ→ขาย→ประมูล)
- [ ] ค้นโค้ดทั้ง repo: ไม่มี `double`/`float` ในโค้ดเงิน (`grep`) · ไม่มี `MYTOKEN`/คีย์ JSONBin ใน repo · ไม่มี `innerHTML` ที่ใช้กับข้อมูลภายนอก
- [ ] เขียน `docs/PLAYER_GUIDE.md` สั้น ๆ (ไทย): ตั้ง/ใส่รหัสตอนเข้าเกม · คำสั่งหลัก (`/bal /pay /sell /buy /mail /ah /claim`) · วิธีเปิดเว็บ (`http://<ip>:8085/`) · คำเตือนเรื่องรหัสผ่านและ HTTP
- [ ] เขียน `docs/OWNER_NOTES.md` สั้น ๆ ให้เจ้าของ: ค่าที่ต้องตั้งใน config, ต้องหมุนคีย์ JSONBin, วิธีสำรอง/กู้คืน, วิธีแก้เมื่อตลาดถูกปิดเพราะไฟล์เสีย

**ผ่านเมื่อ (Gate 9):** ทุกข้อด้านบนมีบันทึกผ่านใน `docs/TEST_REPORT.md` · `CheckLedger` ผ่านหลังการทดสอบทั้งหมด · เจ้าของลงชื่อรับทราบรายการ ⚠️ ที่ค้าง

---

## ภาคผนวก A — ตารางไฟล์ข้อมูลทั้งหมด (`config/claimmap/`)

| ไฟล์ | เฟส | เนื้อหา |
|---|---|---|
| `claimmap.properties` | เดิม + 1,2,3,4,5,6 | ตั้งค่าทั้งหมด (ดูภาคผนวก D) |
| `accounts.json` / `sessions.json` / `regions.json` | เดิม (3 แก้รูปแบบรหัส) | บัญชี / session / ช่องที่จอง |
| `economy.json`, `ledger.jsonl` | 2 | ยอดเงิน+escrow+freeze / ประวัติทุกธุรกรรม (ความจริงสูงสุด) |
| `backups/<เวลา>/` | 2 | สำรองรายชั่วโมง 24 ชุด |
| `prices-cache.json`, `price-overrides.json` | 4 | ราคาล่าสุดจากเว็บ / override + บล็อกที่แอดมินตั้ง |
| `mailbox.json` | 5 | ของรอรับ |
| `shop-usage.json` | 5 (เมื่อมีโควตา) | จำนวนที่ซื้อต่อวัน |
| `auctions.json` | 6 | รายการประมูล |
| `audit.jsonl` | 7 | บันทึกการกระทำแอดมิน |
| `*.corrupt-<ms>` | – | ไฟล์ที่อ่านไม่ได้ ถูกกันไว้ (R6) |

## ภาคผนวก B — API ทั้งหมดเมื่อครบทุกเฟส

| Method | Path | ใคร | เฟส |
|---|---|---|---|
| GET | `/` , `/css/*`, `/js/*`, `/icons/*` | ทุกคน | เดิม / 8 |
| POST | `/api/login` | ทุกคน | 1 |
| GET | `/api/me` | player | 1 |
| GET/POST/DELETE | `/api/cells...`, `/api/map/...` | player (DELETE บางตัว admin) | เดิม |
| POST | `/api/command` | master token เท่านั้น | 1 |
| GET | `/api/wallet` · POST `/api/pay` | player | 2 |
| GET | `/api/prices` | player | 4 |
| GET | `/api/shop` · POST `/api/shop/buy` · POST `/api/shop/sell` | player | 5 |
| GET | `/api/mailbox` · POST `/api/mailbox/claim` | player | 5 |
| GET | `/api/auctions` · POST `/api/auctions/{id}/bid` · `/buyout` · `/cancel` | player | 6 |
| ทั้งหมดใต้ `/api/admin/*` | admin | 7 |

## ภาคผนวก C — ความเสี่ยงที่ต้องรู้ (ไม่ต้องแก้ตอนนี้ แต่ห้ามลืมแจ้งเจ้าของ)

- ⚠️ **HTTP ธรรมดา:** รหัสผ่านและ session token วิ่งเป็นข้อความเปล่า — ใช้ในวงที่ไว้ใจ (LAN/VPN/ไฟร์วอลล์) และอย่าใช้รหัสซ้ำกับที่อื่น
- ⚠️ **offline-mode:** ใครก็สวมชื่อคนอื่นเข้าเกมได้ → หน้ากรอกรหัสเฟส 3 จึงสำคัญ · ช่วงที่บัญชีไม่มีรหัส (ผู้เล่นใหม่/แอดมินรีเซ็ตรหัส) ใครเข้าด้วยชื่อนั้นก่อนจะตั้งรหัสได้
- ⚠️ master token ใน config ยังทำงานเป็น "กุญแจแอดมินสำหรับสคริปต์" — เก็บให้ดี ห้ามใส่ในหน้าเว็บ
- ⚠️ คีย์ JSONBin ที่อยู่ใน `prices.html` สาธารณะ ควรหมุนเปลี่ยนเป็นแบบ read-only
- ⚠️ ข้อมูลเงินทั้งหมดอยู่ในไฟล์บนดิสก์เดียว — สำรองรายชั่วโมงอยู่ในเครื่องเดียวกัน ถ้าดิสก์พังหายหมด แนะนำให้เจ้าของคัดลอก `backups/` ออกไปเครื่องอื่นเป็นระยะ
- ⚠️ การรับของจากกล่องมีโอกาสซ้ำ 1 stack หากเซิร์ฟเวอร์ดับในช่วงมิลลิวินาทีที่ให้ของแต่ยังไม่บันทึก (ตั้งใจเลือกซ้ำดีกว่าหาย)
- ⚠️ การเปลี่ยนชื่อ Minecraft (ในโหมด online) อยู่นอกขอบเขต — บัญชี/เงินผูก UUID แต่ whitelist/ล็อกอินเว็บผูกชื่อ
- ⚠️ รหัสที่พิมพ์ผ่านคำสั่งแชท (ถ้าใช้ทางสำรองในเฟส 3) อาจติด log เซิร์ฟเวอร์

## ภาคผนวก D — ตาราง config ทั้งหมดที่เพิ่ม (`claimmap.properties`)

| คีย์ | ค่าตั้งต้น | เฟส |
|---|---|---|
| `players` / `admins` | (ว่าง) / (ว่าง) | 1 |
| `bindAddress` | `0.0.0.0` | 1 |
| `currencyName` / `startingBalance` | `เหรียญ` / `1000` | 2 |
| `pbkdf2Iterations` | `210000` | 3 |
| `pricesUrl` / `pricesAccessKey` / `priceRefreshSeconds` | (ว่าง) / (ว่าง) / `60` | 4 |
| `sellPercent` / `buyPercent` / `blockedItems` | `70` / `130` / `bedrock,command_block,barrier` | 4 |
| `shopMaxQtyPerTx` / `shopDailyBuyQty` / `mailboxMaxEntries` | `2304` / `0` / `500` | 5 |
| `auctionFeePercent` / `auctionListFeePercent` / `auctionMinIncrementPercent` | `5` / `0` / `5` | 6 |
| `auctionMaxActivePerPlayer` / `auctionDefaultHours` / `auctionMaxHours` / `auctionAntiSnipeSeconds` | `10` / `24` / `72` / `120` | 6 |

ทุกค่าตัวเลขต้อง validate ตอน `load()` (ช่วงที่สมเหตุสมผล) ค่าผิด → log WARN แล้วใช้ค่าตั้งต้น ไม่ล้มทั้งม็อด

## ภาคผนวก E — แม่แบบรายงานท้ายเฟส (AI ต้องส่งทุกเฟส)

```
## รายงานเฟส N — <ชื่อเฟส>
สถานะ: ผ่าน Gate / ไม่ผ่าน (ระบุข้อที่ไม่ผ่าน)
Commit: <hash หรือรายการ>
ไฟล์ที่เพิ่ม: ...
ไฟล์ที่แก้: ... (สรุปการแก้สั้น ๆ ต่อไฟล์)
build: 26.3 ✅/❌ · 26.2 ✅/❌ · test ✅/❌   (แนบข้อความท้ายผลคำสั่ง)
ผล Gate ทีละข้อ: [x] ข้อ ... — คำสั่ง/วิธีทดสอบ → ผลที่ได้
❓ ที่ยืนยันแล้ว: <เรื่อง> → <คำตอบ + ที่มา: ไฟล์ซอร์ส/การทดลอง>
❓ ที่ยังค้าง: ...
เบี่ยงจากสเปก/ข้อสันนิษฐาน: ... เหตุผล ...
ความเสี่ยง ⚠️ ที่เจ้าของต้องรู้/ต้องทำเอง: ...
ความต่าง 26.2 vs 26.3 ที่พบ: ...
```

## ภาคผนวก F — รายการ ❓ ที่ต้องยืนยันจากซอร์ส/การทดลอง (รวมไว้ที่เดียว)

| # | เรื่อง | เฟส | ทางสำรองถ้าไม่ได้ |
|---|---|---|---|
| U1 | รูปแบบ request/response เดิมของ endpoint ทุกตัว | 0 | – (อ่านจากโค้ดได้เสมอ) |
| U2 | คีย์ของ `AccountManager` (ชื่อ/UUID) และมี uuid ไหม | 0,2 | เพิ่มฟิลด์ uuid ตาม 2.1 |
| U3 | ชื่อเมธอดรัน task บน server thread (`server.execute`) และการรอผล | 2 | – (ต้องมีอยู่) |
| U4 | Dialog จากเซิร์ฟเวอร์ที่รับค่าจากช่องพิมพ์ได้ใน 26.2/26.3 | 3 | คำสั่งแชท `/login` `/register` |
| U5 | ชื่ออีเวนต์ Fabric API สำหรับบล็อกการกระทำ/แชท/damage/คำสั่ง | 3 | ตรวจใน tick / mixin ขนาดเล็ก |
| U6 | รูปแบบ response จริงของ JSONBin | 4 | ขอ sample จากเจ้าของ ห้ามเดา |
| U7 | Codec ของ `ItemStack`, `RegistryAccess`, การเช็ก "ไอเท็มไม่มี component พิเศษ" | 5 | – (ถ้าไม่ได้ ให้หยุดถาม) |
| U8 | ชื่ออีเวนต์ tick ของเซิร์ฟเวอร์ / วิธีส่งข้อความให้ผู้เล่น | 6 | – |
| U9 | วิธีตรวจสิทธิ์ OP ในคำสั่ง | 7 | ผูกกับ config `admins` + คอนโซล |
| U10 | ความต่างของคลาส/เมธอด Minecraft 26.2 vs 26.3 | ทุกเฟส | adapter บาง ๆ (R13) |
