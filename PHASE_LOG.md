# PHASE_LOG — บันทึกการทำงานทีละเฟส (Claim Map)

> เอกสารส่งต่อสำหรับ AI/คนทำงานตัวถัดไป — **ห้ามลบประวัติเดิม** (A7)
> อ้างอิงสเปก: `PHASES.md`

---

## โครงโค้ดจริง (ตรวจเมื่อเฟส 0 · 2026-11-14)

ไฟล์ทั้งหมดอยู่ที่ `src/main/java/studio/timeandtime/claimmap/` (1722 บรรทัดรวมหน้าเว็บ)

| ไฟล์ | บทบาท |
|---|---|
| `ClaimMap.java` | `ModInitializer` สร้าง manager ทั้งหมด, ผูก `ServerChunkEvents.CHUNK_LOAD`, `ServerLifecycleEvents.SERVER_STARTED/SERVER_STOPPING`, เก็บ `MinecraftServer` แบบ `volatile` + `getServer()` |
| `ClaimMapConfig.java` | อ่าน/เขียน `config/claimmap/claimmap.properties` ผ่าน `Properties`; ค่า: `port`, `bindAddress`, `token`, `cellSize`, `maxCellsPerPlayer`, `spawnProtectedCells`, `renderScale`, `saveDelayMs`, `linkCodeMinutes`, `sessionHours` |
| `AccountManager.java` | บัญชีเว็บ; **คีย์ด้วย UUID**; เก็บ `config/claimmap/accounts.json` |
| `LinkManager.java` | session token (บันทึก `sessions.json`) + โค้ด 6 หลัก (`issueCode`/`exchange`, เก็บในหน่วยความจำเท่านั้น) |
| `RegionManager.java` | ช่อง `cellSize` (500) → `regions.json`; `claim/unclaim/addMember/removeMember/ownerMap` |
| `WebServer.java` | `HttpServer` context เดียว `"/"` + switch บน `method + " " + path`; executor `newCachedThreadPool()` |
| `ClaimCommands.java` | `/pass`, `/claim`, `/claimmap here|reload` (Brigadier, `CommandRegistrationCallback`) |
| `Protection.java` | กันทุบ/วาง/ใช้บล็อก/ถังน้ำ+ไฟ ในช่องที่ไม่มีสิทธิ์ — ผูกด้วย **Fabric callbacks ล้วน ไม่มี mixin** |
| `MapRenderer.java` | วาด PNG ต่อช่องจาก chunk ที่โหลดแล้ว, flush ตาม `saveDelayMs` |
| `src/main/resources/claimmap-web/index.html` | หน้าเว็บหน้าเดียว (ฝังใน jar ที่ `/claimmap-web/index.html`) |

### ตาราง endpoint เดิม (U1) — ก่อนแก้เฟส 1

ทุก endpoint เดิมตอบ error เป็น **text/plain ธรรมดา ไม่ใช่ JSON** (จะเปลี่ยนเป็นรูปแบบหัวข้อ E ในเฟส 1)

| Method | Path | Auth เดิม | Request | Response เดิม |
|---|---|---|---|---|
| GET | `/` , `/index.html` , `/claim-map.html` | ไม่ต้อง | – | `text/html` หน้าเว็บ โดยแทน `MYTOKEN` ด้วย master token |
| POST | `/api/login` | **ต้องมี bearer (master หรือ session)** | `{"name","password"}` | สำเร็จ `{"token","name"}` · รหัสผิด `403` ข้อความ text · ไม่มี bearer `401` |
| POST | `/api/link` | bearer ที่ใช้ได้ | `{"code":"123456"}` | `{"token","name"}` · โค้ดผิด/หมดอายุ `403` |
| GET | `/api/me` | bearer | – | master → `{"name":"admin"}` · session → `{"name":"<ชื่อ>"}` |
| GET | `/api/cells` | bearer ที่ใช้ได้ | – | `{"cx,cz":"ownerName",...}` |
| GET | `/api/map/{cx},{cz}.png` | bearer ที่ใช้ได้ | – | `image/png` หรือ `204` เมื่อยังไม่สำรวจ |
| POST | `/api/cells/{cx},{cz}/claim` | session (master ไม่ได้) | – | `{"ok":true}` · 409 จองแล้ว · 403 พื้นที่ส่วนกลาง/เกินโควตา |
| POST | `/api/cells/{cx},{cz}/members` | session | `{"uuid","name"}` | `{"ok":true|false}` |
| DELETE | `/api/cells/{cx},{cz}` | session หรือ master | – | `{"ok":true|false}` |
| POST | `/api/command` | **master เท่านั้น** | `{"command"}` | `{"ok":true}` · 503 เมื่อเซิร์ฟเวอร์ไม่ทำงาน |
| – | path อื่น | – | – | `404` text |

### คำตอบ ❓ หัวข้อ C

| # | คำถาม | คำตอบ (ยืนยันจากโค้ด/ซอร์ส) |
|---|---|---|
| U1 | รูปแบบ request/response เดิม | ตามตารางด้านบน — error เป็น text/plain, สำเร็จเป็น JSON บางตัวประกอบสตริงเองด้วย `quote()` |
| U2 | `AccountManager` คีย์ด้วยอะไร มี uuid ไหม | **คีย์ด้วย UUID** (`Map<String,Account> accounts` คีย์ = `a.uuid`) และมีฟิลด์ `uuid` อยู่แล้ว → เฟส 2 ไม่ต้องเพิ่มฟิลด์; `verify(name,pass)` วนหาชื่อแบบ case-insensitive; ยังไม่มี `uuidOf`/`nameOf` (ต้องเพิ่มเฟส 2) |
| U2b | รูปแบบรหัสผ่านเดิม | `sha256(saltHex + plain)`, `salt = ClaimMapConfig.randomHex(8)` (8 ไบต์ = 16 hex), รหัสยาว 4–64 (**เฟส 3 ต้องเปลี่ยนเป็น PBKDF2 + รองรับของเก่า**) |
| U3 | เมธอดรันงานบน server thread | `MinecraftServer` สืบทอด `BlockableEventLoop` → มี `void execute(Runnable)` (บรรทัด 98), `<V> CompletableFuture<V> submit(Supplier<V>)` (62), `CompletableFuture<Void> submit(Runnable)` (74), `boolean isSameThread()` · `MinecraftServer.registryAccess()` คืน `RegistryAccess.Frozen` (1965) · ซอร์ส: `.gradle/mc-src-26.3/net/minecraft/...` จาก `./gradlew genSources` |
| U5 | ชื่ออีเวนต์ Fabric ที่ต้องใช้ | **มีครบทั้ง 26.2 และ 26.3** (ตรวจจาก class ใน jar ทั้งสองชุด): `net.fabricmc.fabric.api.event.player.{AttackBlockCallback,AttackEntityCallback,UseBlockCallback,UseItemCallback,PlayerBlockBreakEvents}`, `net.fabricmc.fabric.api.event.lifecycle.v1.{ServerTickEvents,ServerEntityEvents,ServerLifecycleEvents,ServerChunkEvents}`, `net.fabricmc.fabric.api.entity.event.v1.{ServerLivingEntityEvents,ServerPlayerEvents}`, `net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents`, `net.fabricmc.fabric.api.message.v1.ServerMessageEvents`, `net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback` |
| U7 | Codec ของ ItemStack | `ItemStack.CODEC` และ `ItemStack.OPTIONAL_CODEC` มีจริง (บรรทัด 123/124 ของ `ItemStack.java`) เป็น codec ที่ต้องใช้ `RegistryOps` — ต้องตรวจรายละเอียดตอนเฟส 5 |
| – | `AccountManager.save()` อะตอมมิกไหม | **ใช่**: เขียน `accounts.json.tmp` → `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` (ใช้เป็นแบบอย่าง R5 ได้) · `RegionManager` และ `LinkManager` ทำแบบเดียวกัน |
| – | `Protection` ผูกอีเวนต์อย่างไร | Fabric callbacks 4 ตัว (ดูตารางไฟล์) ไม่มี mixin → เฟส 3 ใช้วิธีเดียวกันได้ |
| – | มี JUnit หรือยัง | **ไม่มี** ก่อนเฟส 0 — เพิ่ม `org.junit.jupiter:junit-jupiter:5.13.4` + `test { useJUnitPlatform() }` ใน `build.gradle` แล้ว |

### ปัญหาเดิมที่ยืนยันจากโค้ด (ตรงกับหัวข้อ C ของสเปก)

1. `WebServer.sendWeb()` แทน `MYTOKEN` ด้วย `config.token` → หน้าเว็บมี master token ฝังอยู่จริง
2. `bindAddress` ค่าเริ่มต้น `127.0.0.1`
3. `handleLogin` เรียก `requireBearer` + `authorized(token)` → ล็อกอินต้องมี token ก่อน
4. `ClaimCommands.runPass` ไม่ตรวจสถานะล็อกอิน/whitelist → ใครสวมชื่อก็รีเซ็ตรหัสได้
5. `AccountManager.setPassword` เขียนทับรหัสเดิมได้ทันทีโดยไม่ต้องยืนยันรหัสเก่า
6. ไม่มีแนวคิด role/whitelist; `isMaster(token)` ใช้ `String.equals` (ไม่ constant-time)

### ความต่าง 26.2 vs 26.3

- ยังไม่พบความต่างของคลาส/เมธอดที่โค้ดปัจจุบันใช้ — build ผ่านทั้งสองเวอร์ชันด้วยซอร์สเดียวกัน
- Fabric API ที่ resolve: `0.165.2+26.3` และ `0.161.0+26.2` — ชุดอีเวนต์ที่ต้องใช้มีชื่อเดียวกันทั้งคู่ (ตรวจจาก jar แล้ว)

---

## บันทึกรายเฟส

### เฟส 0 — เตรียมฐาน (2026-11-14)

**สิ่งที่ทำ**
- ยืนยัน JDK 25 (`openjdk 25.0.4.1`)
- `./gradlew build` ผ่านทั้ง 26.3 (default) และ 26.2
- `./gradlew genSources` สำเร็จ → ซอร์สอยู่ที่ `.gradle/loom-cache/minecraftMaven/...-sources.jar` และแตกอ่านที่ `.gradle/mc-src-26.3/` (ใน `.gradle/` จึงไม่ถูก commit)
- เพิ่ม JUnit 5.13.4 (`testImplementation`) + `testRuntimeOnly junit-platform-launcher` + `test { useJUnitPlatform() }` และเทสต์เปล่า `src/test/java/studio/timeandtime/claimmap/SmokeTest.java`
- อ่านโค้ดทั้ง 9 ไฟล์ + `index.html` ครบ และจดตาราง endpoint / คำตอบ ❓ ด้านบน
- `.gitignore`: เพิ่ม `config/` และ `*.corrupt-*` (`run/` มีอยู่แล้ว)
- รันเซิร์ฟเวอร์ทดสอบ (`./gradlew runServer` + `run/eula.txt`) แล้วเปิด `http://127.0.0.1:8085/` ตรวจหน้าเว็บเดิม

**การตัดสินใจ**
- ใช้ JUnit 5.13.4 (เสถียรและ resolve ได้ในสภาพแวดล้อมนี้)
- เก็บซอร์สที่แตกไว้ใน `.gradle/mc-src-26.3/` เพื่อไม่ให้ปนเข้า repo
- ไม่แก้โค้ดม็อดใด ๆ ในเฟสนี้ตามสเปก

**ปัญหาค้าง**
- ไม่มี

**หมายเหตุถึงเฟส 1**: `config/claimmap/claimmap.properties` บนเครื่องจริงอาจยังมี `bindAddress=127.0.0.1` — ม็อดต้องไม่เขียนทับเอง แค่ log เตือน

---

### เฟส 1 — เว็บ HTTP + ล็อกอิน/สิทธิ์ (2026-11-14)

**ไฟล์ที่เพิ่ม**
- `src/main/java/studio/timeandtime/claimmap/Json.java` — อ่าน body จำกัด 16 KB จริงระหว่างอ่าน, `ApiError` (status/error/message/header/extra), ส่ง JSON/error ตามหัวข้อ E, `integer()` ที่ปฏิเสธทศนิยม/สตริง/เลขยกกำลัง
- `src/main/java/studio/timeandtime/claimmap/LoginThrottle.java` — 5 ครั้งติดล็อก 60 วิ, คีย์ `ip|name.toLowerCase`, purge เมื่อ map > 1000, `fail` ไม่ต่อเวลา lock
- `src/test/java/studio/timeandtime/claimmap/ClaimMapConfigTest.java` (6 เทสต์)
- `src/test/java/studio/timeandtime/claimmap/LoginThrottleTest.java` (5 เทสต์)

**ไฟล์ที่แก้**
- `ClaimMapConfig.java` — เพิ่ม `players`, `admins`; `bindAddress` ค่าเริ่ม `0.0.0.0`; เขียนคีย์ใหม่ลงไฟล์เมื่อยังไม่มี (ไม่ทับค่าเดิม); `isAdmin`/`isAllowed` (trim, case-insensitive ด้วย `Locale.ROOT`, ข้ามรายการว่าง, ชื่อว่าง/null = false); log เตือนเมื่อ bindAddress เป็น loopback; เพิ่ม constructor `ClaimMapConfig(Path)` สำหรับเทสต์ และ path ต่าง ๆ อิงโฟลเดอร์ของไฟล์ config
- `WebServer.java` — เขียนใหม่: `/api/login` เป็น public, session auth, role admin, 405/404/413/500 ตามหัวข้อ E, `X-Content-Type-Options: nosniff` + `Cache-Control: no-store` + `Referrer-Policy: no-referrer` บน `/api/*`, executor `newFixedThreadPool(8)`, master token เทียบด้วย `MessageDigest.isEqual`, ไม่ฝัง token ในหน้าเว็บ, `/api/command` รับเฉพาะ master และรันผ่าน `server.execute` (server thread ตาม R3)
- `AccountManager.java` — เทียบ hash ด้วย `MessageDigest.isEqual`, เพิ่ม `burnVerify()` เพื่อให้เวลาตอบใกล้เคียงกันเมื่อไม่มีบัญชี
- `ClaimCommands.java` — `runPass` ตรวจ `config.isAllowed(name)` ก่อนตั้งรหัส
- `src/main/resources/claimmap-web/index.html` + `claim-map.html` (สำเนา) — ลบ `MYTOKEN`/`TOKEN`, เก็บ session ใน `localStorage`, แนบ Authorization เฉพาะเมื่อมี session, `/me`+`/cells`+tile เฉพาะเมื่อมี session, `tiles.clear()` หลังล็อกอิน, 401 ที่ไหนก็ตาม → `clearSession()`

**การตัดสินใจ / เบี่ยงจากสเปกเล็กน้อย**
- เพิ่มฟิลด์ `session` ใน response ของ `/api/login` และ `/api/link` **นอกเหนือจาก `token` เดิม** เพื่อให้หน้าเว็บไม่ต้องมีคำว่า `token` เลย (Gate ข้อ 1 ใช้ `grep -i token` ตรง ๆ) — ของเดิมยังใช้ `token` ได้ (เข้ากันได้ย้อนหลัง)
- ใช้รหัส error ใหม่ `already_claimed` (409) เพราะแค็ตตาล็อก E ไม่มีตัวที่ตรง และเป็นพฤติกรรมเดิมของ endpoint จองช่อง
- `/api/link` คงไว้ (ไม่ลบ) — ยังใช้ master/session เป็น bearer เหมือนเดิม
- `/api/command` ยิงคำสั่งแบบ fire-and-forget บน server thread; จะย้ายมาใช้ `GameBridge` ในเฟส 2

**ผลทดสอบ Gate 1** (รันเซิร์ฟเวอร์จริง MC 26.3, config `players=Alice,Bob`, `admins=Alice`, `bindAddress=0.0.0.0`)
- [x] `curl -s / | grep -i -E "token|MYTOKEN"` → ไม่พบอะไร (OK)
- [x] `/api/cells` ไม่มี session → `401 {"error":"unauthorized"}`
- [x] Eve (มีบัญชี แต่ไม่อยู่ใน players) → `401 invalid_credentials` ข้อความเดียวกับรหัสผิดของ Bob เป๊ะ
- [x] Alice → `{"token":...,"session":...,"name":"Alice","admin":true}`; Bob → `admin:false`; `/api/me` ตรงกัน
- [x] Bob session เรียก `/api/command` → 403; master token → 200
- [x] รหัสผิด 5 ครั้ง → 401 ทั้ง 5; ครั้งที่ 6 → `429` + `Retry-After: 60` + `retryAfterSeconds`; ใส่รหัสถูกระหว่างล็อก → 429; รอ 62 วิ → 200
- [x] `GET /api/login` → 405 · `GET /api/nope` → 404 · body 17 KB → 413
- [x] bind `0.0.0.0:8085` (log + `http://164.1.1.200:8085/` → 200 จาก IP ของเครื่อง)
- [x] `./gradlew test` 12 เทสต์ผ่าน (6 config + 5 throttle + 1 smoke)
- [x] build 26.3 ✅ · 26.2 ✅

**สิ่งที่ทดสอบไม่ได้ในเฟสนี้ (ข้อจำกัด)**
- `/pass` ในเกมจริง (ต้องมีไคลเอนต์ Minecraft): ตรวจได้แค่โค้ดที่มี guard `config.isAllowed(name)` + unit test ของ `isAllowed` — พฤติกรรมในเกมจะยืนยันในเฟส 3/9
- "เปิดจากเครื่องอื่น" ทดสอบได้ถึงระดับ bind `0.0.0.0` + เรียกผ่าน LAN IP ของเครื่องเอง ไม่มีเครื่องที่สองในสภาพแวดล้อมนี้

**❓ ที่ยืนยันแล้วเพิ่ม**
- Fabric API ทั้ง 26.2/26.3 ให้ `HttpServer` ทำงานร่วมกับ server thread ปกติ; `server.execute` มีจริง (ใช้ใน `/api/command`)

**ปัญหาค้าง**
- ไม่มี (ช่องโหว่ `/pass` takeover ยังอยู่ตามแผน — ปิดในเฟส 3 และห้ามใช้เงินจริงก่อนเฟส 3)

---

### เฟส 2 — ระบบเงิน EconomyManager (2026-11-14)

**ไฟล์ที่เพิ่ม**
- `Market.java` (`Market.LOCK`), `Acct.java`, `Move.java`, `TxResult.java`, `EconomyManager.java`, `GameBridge.java`
- `src/test/java/studio/timeandtime/claimmap/EconomyManagerTest.java` (12 เทสต์)
- `tools/CheckLedger.java` (ตรวจ ledger/snapshot/invariant/escrew, ไม่มี dependency)
- `tools/StressPay.java` (ยิง `/api/pay` พร้อมกัน 100 ครั้ง ตรวจผลรวม)

**ไฟล์ที่แก้**
- `ClaimMapConfig.java` — เพิ่ม `currencyName`, `startingBalance`, `dataDir()`; เขียนคีย์ใหม่ลงไฟล์เมื่อยังไม่มี; validate ช่วงค่า
- `AccountManager.java` — เพิ่ม `uuidOf(name)` / `nameOf(uuid)` (คีย์ยังเป็น UUID ตามเดิม)
- `ClaimCommands.java` — เพิ่ม `/bal`, `/pay <ผู้เล่น> <จำนวน>` (ข้อความไทยตามผล `TxResult`)
- `WebServer.java` — เพิ่ม `GET /api/wallet`, `POST /api/pay` (ตรวจทุกอย่างฝั่งเซิร์ฟเวอร์, ปฏิเสธทศนิยม/สตริง/0/ติดลบ, `X-Request-Id` idempotent 10 นาที, 503 `market_disabled`)
- `ClaimMap.java` — สร้าง/โหลด `EconomyManager`, scheduler สำรองรายชั่วโมง (daemon), backup ตอนปิดเซิร์ฟเวอร์

**การตัดสินใจ**
- `economy.json` เป็นเพียง snapshot; `ledger.jsonl` คือความจริง — replay เฉพาะบรรทัดที่ `seq > lastSeq`; ประวัติ 50 รายการต่อคนสร้างใหม่จาก ledger ตอน `load()`
- บรรทัด ledger สุดท้ายที่ JSON ไม่ครบ → ข้าม + WARN; บรรทัดกลางที่เสีย/ไฟล์ snapshot เสีย → เปลี่ยนชื่อเป็น `.corrupt-<ms>` + ปิดตลาด (ไม่สร้างไฟล์ว่างทับ)
- `deposit`/SYSTEM→ผู้เล่น ยังทำได้แม้ผู้รับถูก freeze (เป็นการกระทำของระบบ) แต่ player→player และการถอนถูกบล็อก
- `/api/wallet` และ `/api/pay` เรียก `ensureAccount` (idempotent) — ก่อนเฟส 3 ที่จะย้ายการสร้างบัญชีไปตอนเข้าเกม
- ใช้ `server.submit(...)` ใน `GameBridge` (ยืนยันจากซอร์ส) พร้อม timeout 5 วิ

**ผลทดสอบ Gate 2**
- [x] `./gradlew test` ผ่าน 24 เทสต์ (12 ของ `EconomyManagerTest`: concurrency 100 เธรด, amount ผิด, batch atomic, replay จาก snapshot เก่า, ledger ท้ายตัดครึ่ง, ไฟล์เสีย, ensureAccount ซ้ำ, freeze, backup)
- [x] `java tools/StressPay.java ... 100` → `ok=100 failed=0`, Alice 943→843, Bob 1057→1157, ผลรวมคงที่, ไม่มียอดติดลบ
- [x] `pay` ยอดไม่พอ → 409 `insufficient_funds`; โอนตัวเอง → 400; 0/-5/1.5/"10" → 400 `invalid_amount`; ผู้รับไม่มี → 404
- [x] `X-Request-Id: test-abc-1` ส่งซ้ำ → ได้ผลเดิม ยอดไม่ถูกหักซ้ำ (ledger มี 1 บรรทัด)
- [x] `java tools/CheckLedger.java run/config/claimmap` → PASS (accounts=2, lastSeq=104, systemNet=-2000, invariant=0) ทั้งก่อนและหลังรีสตาร์ท
- [x] รีสตาร์ทเซิร์ฟเวอร์ → Alice 843 / Bob 1157 เท่าเดิม
- [x] backup: เขียนตอนปิดเซิร์ฟเวอร์ (`backups/20261114-140611/` มี economy.json, ledger.jsonl, accounts.json) + เทสต์ JUnit ของ `backupNow`
- [x] จำลองไฟล์เสีย: `economy.json` เป็นขยะ → log SEVERE, ย้ายเป็น `economy.json.corrupt-<ms>`, `/api/wallet` และ `/api/pay` → 503 `market_disabled`, ไม่มี `economy.json` ใหม่ถูกสร้าง
- [x] build 26.3 ✅ · 26.2 ✅

**ข้อจำกัด/ค้าง**
- `/bal` และ `/pay` ในเกมไม่ได้ทดสอบกับไคลเอนต์จริง (ทดสอบเฉพาะผ่าน API + unit test ของ `EconomyManager`)
- ยังไม่ผูก auth กับเกม (เฟส 3) — ห้ามใช้เงินจริงก่อนเฟส 3 ตามสเปก
