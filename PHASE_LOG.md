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

---

### เฟส 3 — AuthGate + PBKDF2 (2026-11-14)

**ผล spike dialog (3.1) — สรุป: ใช้ทางสำรอง (คำสั่งแชท)**
- ตรวจซอร์ส 26.3 และ 26.2 (`genSources`) พบว่ามีระบบ dialog จริง: `net.minecraft.server.dialog.{Dialog,MultiActionDialog,SimpleDialog,ActionButton}`, `input.TextInput`, `DialogAction.WAIT_FOR_RESPONSE`, แพ็กเก็ต `ClientboundShowDialogPacket`, `ServerboundCustomClickActionPacket`, และ `ServerPlayer.openDialog(Holder<Dialog>)` (26.3 บรรทัด 1363 · 26.2 บรรทัด 1309)
- ค่าจากช่องพิมพ์ถูกส่งกลับผ่าน `dynamic/custom` action → `ClickEvent.Custom` → `ServerboundCustomClickActionPacket` → `MinecraftServer.handleCustomClickAction(...)` ซึ่งเป็น **no-op** (26.3 บรรทัด 2043) และ **ไม่มี Fabric event** ให้ดัก (ตรวจ jar ทุกตัวแล้ว) → ต้องเขียน mixin เพิ่ม
- ❗ ทดสอบกับไคลเอนต์จริงไม่ได้ในสภาพแวดล้อมนี้ (ไม่มีจอ/ไคลเอนต์เกม) → ตาม A3 เลือก **ทางสำรอง** `/login` `/register` และบันทึกว่า dialog ยังเป็นงานที่ทำต่อได้ในอนาคต
- ข้อสังเกตเพิ่ม: `TextInput` ไม่มีตัวเลือก mask รหัสผ่าน (`TextInput.java` มีแค่ width/label/initial/maxLength/multiline) → ข้อดีด้านความปลอดภัยของ dialog จึงมีจำกัด (แต่รหัสจะไม่ไปอยู่ใน chat log)

**ไฟล์ที่เพิ่ม**
- `PasswordHasher.java` — `PBKDF2WithHmacSHA256`, 210000 รอบ (config), salt 16 ไบต์, เก็บ `pbkdf2$<iter>$<saltB64>$<hashB64>`, `MessageDigest.isEqual`, รองรับ SHA-256 เดิม
- `AuthGate.java` — state machine (`NEED_LOGIN`/`NEED_REGISTER`/`AUTHED`), ผิด 3 ครั้ง/เงียบ 60 วิ → kick, ตรึงตำแหน่ง, บล็อกทุกการกระทำ, ตรวจรหัสบน worker thread แล้วกลับมา `server.execute`
- `mixin/CommandsMixin.java` — บล็อกทุกคำสั่งก่อนล็อกอิน ยกเว้น `/login` `/register`
- `mixin/ServerPlayerDropMixin.java` — บล็อกการดรอปของ
- `src/main/resources/claimmap.mixins.json`
- `src/test/java/.../{PasswordHasherTest,AuthGateTest}.java` (10 เทสต์)

**ไฟล์ที่แก้**
- `ClaimMapConfig.java` — `pbkdf2Iterations` (ค่าเริ่ม 210000, validate 10000–2000000)
- `AccountManager.java` — `setPassword` ใช้ PBKDF2 + กติกา 6–64 ตัวและไม่ซ้ำชื่อ, เพิ่ม `setPasswordHash` (รับ hash ที่คำนวณแล้ว), `verify` รองรับของเก่าและ **rehash เป็น PBKDF2 อัตโนมัติเมื่อล็อกอินสำเร็จ**
- `LinkManager.java` — `revokeAllForUuid` (ใช้ตอนเปลี่ยนรหัส)
- `ClaimCommands.java` — `/login <รหัส>`, `/register <รหัส> <ยืนยัน>`, `/pass <รหัสเดิม> <รหัสใหม่>`
- `ClaimMap.java` — สร้าง/ผูก `AuthGate`, register hooks, `authGate()` accessor
- `fabric.mod.json` — เพิ่ม `"mixins": ["claimmap.mixins.json"]`

**การตัดสินใจ**
- เพิ่ม mixin 2 ตัวเล็ก ๆ แม้ไม่อยู่ในรายการไฟล์ของเฟส เพราะ Fabric ไม่มี hook สำหรับบล็อกคำสั่ง/ดรอปของ และ 3.3 อนุญาตให้ใช้ mixin ขนาดเล็ก
- ไม่บล็อกการ "ล็อกเป้าโดย mob" แยก เพราะ `ALLOW_DAMAGE` กันดาเมจได้แล้ว — บันทึกเป็นข้อจำกัด
- เงินเริ่มต้น/แจ้งยอดทำตอนผ่านรหัส (`finishAuth`) ตาม 3.2

**ผลทดสอบ (ทำได้จริงในเครื่องนี้)**
- [x] `./gradlew test` 34 เทสต์ผ่าน (PBKDF2 round-trip/legacy, state machine, กติการหัส)
- [x] เปิดเซิร์ฟเวอร์ 26.3 → mixin subsystem โหลด `claimmap.mixins.json` ไม่มี injection error (defaultRequire=1 จะล้มทันทีถ้า inject ไม่ได้) `ServerPlayer`/`Commands` ถูกโหลดตอนสตาร์ท
- [x] ตรวจลายเซ็น 26.2: `performPrefixedCommand`, `drop(boolean)`, `teleportTo(double,double,double)`, `openDialog` มีเหมือน 26.3 → mixin ใช้ได้ทั้งสองเวอร์ชัน
- [x] บัญชีเก่า (SHA-256) ล็อกอินเว็บสำเร็จ → `accounts.json` เปลี่ยนเป็น `pbkdf2$210000$...` และ `salt=""`; ล็อกอินซ้ำได้ 200; รหัสผิดได้ 401
- [x] build 26.3 ✅ · 26.2 ✅

**⚠️ Gate 3 ยังไม่ผ่านครบ — ต้องทดสอบในเกมจริง**
ข้อที่ต้องมีไคลเอนต์ Minecraft จึงจะยืนยันได้: ผู้เล่นใหม่เห็นคำสั่งตั้งรหัส, ผู้เล่นเดิมเห็นคำสั่งใส่รหัส, kick ชื่อนอก whitelist, การ 블็อกขยับ/ทุบ/วาง/ดรอป/เปิดหีบ/แชท/ตี/โดนทำร้าย/คำสั่งอื่น, kick เมื่อผิด 3 ครั้งหรือเงียบ 60 วิ, `/pass` ของผู้ไม่ผ่านรหัส, รหัสเดียวใช้ได้ทั้งเกมและเว็บ
→ โค้ดและ unit test พร้อมแล้ว แต่ **ห้ามถือว่า Gate 3 ผ่าน** จนกว่าจะรันกับไคลเอนต์จริง (เฟส 9)

---

### เฟส 4 — ราคา PriceService (2026-11-14)

**ไฟล์ที่เพิ่ม**
- `PriceMath.java` — `sellPrice = max(1, floor(base*sellPercent/100))`, `buyPrice = max(sell+1, ceil(base*buyPercent/100))`, `validPercents`
- `PriceService.java` — ตารางราคาแบบ volatile ทั้งก้อน, cache/override/blocked, refresh จาก JSONBin ด้วย `java.net.http`, เขียนไฟล์อะตอมมิก
- `ItemRegistryAdapter.java` — snapshot `BuiltInRegistries.ITEM.keySet()` ตอน `SERVER_STARTED`
- `src/test/java/.../{PriceMathTest,PriceServiceTest}.java` (16 เทสต์)

**ไฟล์ที่แก้**
- `ClaimMapConfig.java` — `pricesUrl`, `pricesAccessKey`, `priceRefreshSeconds` (5–86400), `sellPercent`, `buyPercent`, `blockedItems`; ถ้าเปอร์เซ็นต์ไม่ผ่าน `PriceMath.validPercents` → WARN และใช้ 70/130
- `ClaimMap.java` — สร้าง/โหลด cache, scheduler แยก (`claimmap-prices`, daemon), `ItemRegistryAdapter.init(server)` ก่อนเปิดเว็บ, หยุด scheduler ตอนปิด
- `WebServer.java` — `GET /api/prices` (ต้องมี session) กรองไอเท็มที่ registry ไม่รู้จักและไอเท็มที่ถูกบล็อก ไม่ส่ง url/คีย์/base

**การตัดสินใจ**
- ใช้ `record.prices` ตามที่รู้จาก `prices.html` และรองรับ response ที่ห่อด้วย `record` เผื่อ `X-Bin-Meta` ไม่ทำงาน
- ราคา cache และราคาสดใช้การปัดเดียวกัน (`BigDecimal` + `HALF_UP` เป็นจำนวนเต็ม) — แก้บั๊กที่เจอตอนทดสอบ (เดิม cache ตัดเศษทิ้ง)
- ไฟล์ cache/override เสีย → log WARN แล้วใช้ตารางว่าง/ค่าเริ่มต้น **ไม่ปิดตลาด** (R6 ใช้กับไฟล์เงิน/ของเท่านั้น)
- `stale` ใน `/api/prices` = ไม่มีข้อมูลสำเร็จเกิน 1 ชั่วโมง; `usingStaleCache()` = การดึงครั้งล่าสุดล้มเหลว

**ผลทดสอบ Gate 4**
- [x] `./gradlew test` ผ่าน 50 เทสต์ (เพิ่ม 5 PriceMath + 11 PriceService) — รวมกรณี base 1/2/3/10/99/1000/MAX_MONEY และสุ่ม 100,000 ค่า ไม่มี `buy <= sell`
- [x] ใช้ราคาจำลองใน `prices-cache.json` (ไม่มีคีย์ JSONBin): diamond 10 → buy 13 / sell 7; emerald 2.6 → buy 4 / sell 2; gold_ingot override 8 → buy 11 / sell 5
- [x] `blockedItems=bedrock,...` → `minecraft:bedrock` ไม่มีราคาและไม่ปรากฏใน `/api/prices`; ไอเท็มที่ registry ไม่รู้จัก (`fake:not_an_item`) ถูกกรองออก
- [x] ตั้ง `pricesUrl` เป็นพอร์ตที่ปิด (`127.0.0.1:9`) → log WARN ครั้งเดียว, ตารางเดิมยังใช้ได้, `/api/prices` ยังตอบ 200 และ `stale:true` (cache เก่า 2 ชม.)
- [x] `grep -i SUPERSECRET` ในหน้าเว็บ/`/api/prices`/log → ไม่พบ; `/api/prices` ไม่มี `pricesUrl`/`jsonbin`
- [x] ไม่มี session → `/api/prices` = 401
- [x] build 26.3 ✅ · 26.2 ✅

**❓ ที่ยังค้าง (U6)**
- ยังไม่มีคีย์/ตัวอย่าง response จริงของ JSONBin → parser อิงรูปแบบ `{"prices":{...}}` ตามที่ระบุในสเปก (ทดสอบด้วย fixture เท่านั้น)
- เจ้าของต้องรัน `curl` กับ bin จริงแล้วส่งตัวอย่าง (ตัดคีย์) เพื่อยืนยัน; **ต้องหมุนคีย์ที่เคยอยู่ใน `prices.html` สาธารณะ**
- ต้องยืนยันหน่วยราคา: สเปกตกลงว่า base = เหรียญเต็มต่อ 1 ชิ้น; ถ้าจริงเป็นสตางค์ต้องหยุดถาม

---

### เฟส 5 — กล่องของ + ร้านค้า (2026-11-14)

**ไฟล์ที่เพิ่ม**
- `ItemCodecs.java` — `ItemStack.CODEC` + `RegistryOps.create(JsonOps.INSTANCE, access)`; encode/decode เก็บ component ทั้งหมด, `isPlain` = `getComponentsPatch().isEmpty()`
- `MailboxManager.java` — `mailbox.json`, id ซ้ำไม่เพิ่ม, capacity, replace/remove, ไฟล์เสีย → เปลี่ยนชื่อ + ปิดตลาด
- `ShopManager.java` — buy (เตรียม entry แล้วหักเงิน → เติมกล่อง), sell (ลบของ → ฝากเงิน, คืนของถ้าฝากล้ม), claim, โควตารายวัน
- `src/test/java/.../{MailboxManagerTest,ShopBuyTest}.java` (12 เทสต์)

**ไฟล์ที่แก้**
- `ClaimMapConfig.java` — `shopMaxQtyPerTx=2304`, `shopDailyBuyQty=0`, `mailboxMaxEntries=500`, `mailboxFile()`, `shopUsageFile()`
- `ItemRegistryAdapter.java` — เพิ่มชื่อแสดงผลไอเท็ม (สร้าง `ItemStack` ตอน init บน server thread)
- `ClaimMap.java` — สร้าง `MailboxManager` + `ShopManager`
- `WebServer.java` — `GET /api/shop`, `POST /api/shop/buy`, `POST /api/shop/sell`, `GET /api/mailbox`, `POST /api/mailbox/claim`
- `ClaimCommands.java` — `/sell hand`, `/sell all` (+`confirm` 30 วิ), `/buy <item> <qty>`, `/mail`, `/mail claim`

**การตัดสินใจ**
- ขาย/ซื้อผ่าน HTTP ใช้ `GameBridge` เรียกงานบน server thread (encode/แก้ inventory) — ไม่ถือ `Market.LOCK` ข้าม thread
- ซื้อจ่ายเงินก่อนเติมกล่อง (R7) พร้อม rollback (ลบ entry ที่เพิ่งเพิ่ม + คืนเงิน) ถ้าเติมไม่สำเร็จ; retry ที่ entry มีอยู่แล้ว → ไม่หักเงินซ้ำ
- entry กล่องของ id = `mb_shop_<ref>_<index>` (กำหนดได้, กันซ้ำ)
- ขายรับเฉพาะของ `isPlain` ในกระเป๋าหลัก+แถบด่วน (36 ช่อง) ไม่รวมเกราะ/มือรอง
- ไฟล์ `shop-usage.json` เก็บเฉพาะวันปัจจุบัน (คีย์ขึ้นต้นด้วยวันที่)

**ผลทดสอบ**
- [x] `./gradlew test` ผ่าน 62 เทสต์ (เพิ่ม 6 mailbox + 6 shop buy)
- [x] รันเซิร์ฟเวอร์จริง 26.3: `GET /api/shop` แสดง diamond buy 13/sell 7 พร้อม label, mailboxCount
- [x] `POST /api/shop/buy {"item":"diamond","qty":2}` → `bought:2 spent:26 balance:817 mailboxCount:1`; `/api/mailbox` มี entry count 2; `CheckLedger` PASS (lastSeq 105)
- [x] blocked `bedrock` → 409 `item_blocked`; ไม่มีราคา `stone` → 409 `no_price`; ไอเท็มไม่มีจริง → 400 `invalid_item`; qty 1.5 → 400 `invalid_quantity`; qty 2304 (เงินไม่พอ) → 409 `insufficient_funds`
- [x] ขาย/รับของตอนไม่มีผู้เล่นออนไลน์ → 409 `player_offline`
- [x] build 26.3 ✅ · 26.2 ✅

**⚠️ Gate 5 ยังไม่ผ่านครบ — ต้องทดสอบในเกมจริง**
ที่ยังยืนยันไม่ได้เพราะไม่มีไคลเอนต์: ขายของ 64 ชิ้นแล้วของหาย/เงินเพิ่มตรง, ปฏิเสธของมี enchant/ชื่อ/ความเสียหาย, `/sell all` สรุปตรงกับเงินจริง, ซื้อตอน inventory เต็มแล้วของเข้ากล่อง + `/mail claim` รับได้บางส่วน, arbitrage test ในเกม, freeze ในการซื้อขาย
→ logic และ unit test พร้อม; การรับของ (`claim`) และการขายถูกทดสอบเฉพาะเส้นทาง error/offline

---

### เฟส 6 — ตลาดประมูล (2026-11-14)

**ไฟล์ที่เพิ่ม**
- `AuctionManager.java` — `auctions.json` (`version:1`), สถานะ `ACTIVE/SOLD/EXPIRED/CANCELLED`, escrow ต่อรายการ (`Acct.escrow(id)`), `planCreate`/`create`/`bid`/`buyout`/`cancel`/`closeExpired`/`settle`, `validateEscrow`, prune 7 วัน
- `AuctionTicker.java` — `END_SERVER_TICK` ทุก 20 tick → `closeExpired(...)`
- `src/test/java/.../AuctionManagerTest.java` (13 เทสต์)

**ไฟล์ที่แก้**
- `ClaimMapConfig.java` — `auctionMaxActivePerPlayer=5`, `auctionDefaultHours=24`, `auctionMaxHours=72`, `auctionFeePercent=5`, `auctionListFeePercent=0`, `auctionMinIncrementPercent=5`, `auctionAntiSnipeSeconds=60`, `auctionsFile()`
- `ShopManager.java` — `attachAuctions(...)`; `isDisabled()` นับ `auctions.isDisabled()` ด้วย (ไฟล์ประมูลเสีย → ร้านปิดทั้งตลาดตาม R6)
- `ClaimMap.java` — โหลด+ตรวจ escrow ก่อนสร้างร้าน, ticker, settle รอบแรกตอน `SERVER_STARTED`, ส่ง manager ให้ WebServer/commands
- `WebServer.java` — `GET /api/auctions` (ของตัวเอง + ประวัติ 7 วัน), `POST /api/auctions/{id}/bid|buyout|cancel`
- `ClaimCommands.java` — `/ah`, `/ah sell <ราคาเริ่ม> [ซื้อทันที|0] [ชั่วโมง]`, `/ah bid <id> <เงิน>`, `/ah cancel <id>`

**การตัดสินใจ**
- เงินทุกขั้นผ่าน `EconomyManager.apply` ชุดเดียว: bid = คืนเงินผู้นำเดิม + รับเงินใหม่ (atomic), buyout = คืนผู้นำ + รับเงินผู้ซื้อ + จ่ายผู้ขาย + ค่าธรรมเนียม
- `minBid` = ราคาเริ่ม (ยังไม่มี bid) หรือ `highBid + max(1, ceil(highBid*5%))`
- กัน sniping: bid ใน `antiSnipeSeconds` สุดท้าย → `endsAt = now + antiSnipeSeconds`
- `settle` idempotent ด้วย `paidOut`/`itemDelivered`; ตั้ง `status=SOLD` **ก่อน**ส่งของ เพื่อให้ invariant `ACTIVE ⇒ escrow == highBid` ยังจริงถ้าดับกลางทาง; ส่งของไม่สำเร็จ (กล่องเต็ม) → คง `itemDelivered=false` แล้ว tick ถัดไปลองใหม่ ไม่จ่ายซ้ำ
- ลงประมูล: ตรวจ plan → ถอดของจากมือ → `create`; ถ้า `create` ล้มจะใส่ stack เดิมคืนช่องเดิม
- ซื้อทันทีใช้ `buyout` แยก endpoint; ราคาที่ ≥ buyout ส่ง `use_buyout` ให้หน้าเว็บพาไปปุ่มซื้อทันที
- id รายการ = `au_<base36 time>_<counter>`; entry กล่องของ = `mb_au_<id>_winner` / `mb_au_<id>_return` (กันซ้ำ)
- `validateEscrow()` เรียกท้าย `load()`: escrow ใน ledger ต้องเท่ากับ `highBid` ของรายการ ACTIVE ไม่งั้น `.corrupt-<ms>` + ปิดตลาด
- **ข้อสังเกต operational:** 26.x pause เซิร์ฟเวอร์เมื่อว่าง 60 วิ → tick หยุด → settle จะเกิดตอน tick แรกหลังมีคนเข้า (หรือตอน start ถ้าเลยเวลาแล้ว) — ไม่ใช่ข้อมูลหาย

**ผลทดสอบ**
- [x] `./gradlew test` ผ่าน 75 เทสต์ (เพิ่ม 13 auction) — ครอบวงจรเต็ม, outbid คืนเงิน, buyout คืนผู้นำ, ยกเลิก, anti-snipe, settle ซ้ำไม่เปลี่ยนยอด/ไม่เพิ่ม mail, จำลองดับหลังจ่ายก่อนส่งของแล้ว retry, ขอบเขตราคา/ชั่วโมง/จำนวนรายการ, ไฟล์เสียปิดตลาด+ปิดร้าน, escrow ไม่ตรงตอน start, bid พร้อมกัน 24 thread แล้ว escrow = highBid, invariant 0
- [x] รันเซิร์ฟเวอร์จริง (fixture `auctions.json` เพราะลงของในเกมไม่ได้): `GET /api/auctions` → minBid 100/canBid true; bid 100 → Bob 1057, escrow 100, `endsAt` +60 วิ (anti-snipe ทำงาน)
- [x] bid ตัวเอง → 409 `own_auction`; bid ซ้ำตอนเป็นผู้นำ → 409 `already_highest`; id ไม่มี → 404; `POST /api/auctions` → 405; ไม่มี session → 401
- [x] ปล่อยให้หมดเวลา → ticker settle: `status=SOLD`, Alice +95 (หัก 5%), `CheckLedger` PASS, Bob ได้ `mb_au_au_fixture_1_winner` 3 เพชร, escrow 0
- [x] `POST .../buyout` → Bob 857 (−200), Alice +190, Bob ได้มรกต 5, escrow 0
- [x] `POST .../cancel` โดยไม่ใช่เจ้าของ → 403; เจ้าของ → OK และของกลับเข้ากล่อง (`mb_au_..._return`)
- [x] `CheckLedger` PASS หลังทุกธุรกรรม (`lastSeq=111`, invariant 0)
- [x] build 26.3 ✅ · 26.2 ✅

**⚠️ Gate 6 ยังไม่ผ่านครบ — ต้องทดสอบในเกมจริง**
ที่ยังยืนยันไม่ได้เพราะไม่มีไคลเอนต์: `/ah sell` ถอด stack จริงจากมือและคืนของเมื่อล้ม, `/ah` แสดงผลในแชท, `/ah bid`/`/ah cancel` จากในเกม, พฤติกรรมกล่องของเต็มตอนผู้ชนะรับของ, และการที่ผู้เล่นออฟไลน์ตอนปิดประมูล (ของต้องรอในกล่อง)
→ แกนเงิน/escrow/settle และ API ถูกทดสอบครบทั้ง unit + runtime ด้วย fixture

---

### เฟส 7 — ระบบแอดมิน (2026-11-14)

**ไฟล์ที่เพิ่ม**
- `AdminService.java` — รวมทุกการกระทำแอดมิน (เงิน, freeze, reset password, override/block ราคา, ยกเลิกประมูล, reload config, overview/wallet/audit) ใช้ร่วมกันทั้ง `/api/admin/*` และ `/eco`
- `AuditLog.java` — `audit.jsonl` append-only, เขียนทั้งสำเร็จและถูกปฏิเสธ, `tail()` ≤ 200 บรรทัด
- `src/test/java/.../AdminServiceTest.java` (9 เทสต์)

**ไฟล์ที่แก้**
- `AccountManager.java` — `anyUuidOf(name)` (รวมบัญชีที่ยังไม่มีรหัส), `identities()`, `resetPassword(uuid)` (ลบ hash เก็บชื่อไว้ให้ `/register` ใหม่), `hasPassword`/`uuidOf` ไม่คืนบัญชีที่ hash ว่าง
- `AuctionManager.java` — `adminCancel(id)`: คืน highBid จาก escrow (bypass freeze), ของคืนผู้ขายทางกล่อง, `CANCELLED`, เรียกซ้ำไม่คืนเงินซ้ำ
- `EconomyManager.java` — `balancesView()`, `frozenView()`
- `ClaimMapConfig.java` — `reload()` ที่ไม่เปลี่ยน port/bindAddress/token และคืน false เมื่อต้องรีสตาร์ท
- `PriceService.java` — เก็บ `unblocked` ใน `price-overrides.json` เพื่อให้แอดมินปลดไอเท็มที่ config บล็อกไว้ได้ (แก้บั๊กที่เจอจากเทสต์) · รูปแบบไฟล์เดิม (`blocked`) ยังอ่านได้
- `Json.java` — `ok()`
- `ClaimMap.java` — สร้าง `AuditLog`/`AdminService` (predicate online จาก player list), ส่งเข้า `WebServer`/`ClaimCommands`
- `WebServer.java` — `/api/admin/overview|money|wallet/{name}|prices/override|prices/block|prices/refresh|auctions|auctions/{id}/cancel|accounts/{name}/freeze|accounts/{name}/reset-password|config/reload|audit` · ทุกตัว `requireSession` → admin (ไม่ใช่ = 403 + audit `admin.denied`)
- `ClaimCommands.java` — `/eco give|take|set <player> <amount> [reason]` (Brigadier `COMMANDS_ADMIN` + ต้อง AUTHED) เรียก `AdminService` ตัวเดียวกับ API

**การตัดสินใจ**
- ให้แอดมิน "ปลดบล็อก" ไอเท็มที่อยู่ใน `blockedItems` ได้ โดยเก็บ `unblocked` แยกใน `price-overrides.json` (ไม่เขียนทับ properties)
- `take` เกินยอด → 409 `insufficient_funds` (ไม่ยอมให้ติดลบ) · `set` คำนวณส่วนต่างด้วย `give`/`take` reason `admin.set`
- `/api/admin/wallet` ตอบ `player_not_found` (404) เมื่อไม่มีบัญชี — เป็นรหัสที่ยังไม่มีในแค็ตตาล็อก E แต่ตรงความหมายกว่า `not_found`
- `admin.overview` และ `audit` เขียน audit ทุกครั้งที่ถูกเรียก

**ผลทดสอบ**
- [x] `./gradlew test` ผ่าน 84 เทสต์ (เพิ่ม 9 AdminService)
- [x] runtime 26.3: Bob (player) เรียก `/api/admin/*` ทั้ง 12 endpoint → 403 `forbidden` ทุกตัว และมีบรรทัด `admin.denied` ใน audit
- [x] Alice (admin): overview ได้ `invariantOk:true`, `players` 3 คน; `give` Bob 7 → 200 balance 864; `take` 999999 → 409; audit มีทั้ง `ok:true` และ `ok:false` พร้อม IP
- [x] freeze Bob → `POST /api/pay` 409 `account_frozen`; unfreeze → 200
- [x] `reset-password` ของ Eve → hash ถูกลบ, session ถูกยกเลิก, ชื่อยังอยู่
- [x] `prices/block` ปลด `bedrock` → `isBlocked=false` (ไฟล์มี `unblocked:["minecraft:bedrock"]`)
- [x] build 26.3 ✅ · 26.2 ✅
- [ ] `/eco` ในเกมจริง (ต้องมีไคลเอนต์ + OP) — unit test ของ `AdminService.money` ครอบตรรกะแล้ว
- [ ] adminCancel ที่มีผู้เสนอราคาใน runtime — unit test `adminCancelRefundsTheBidderAndReturnsTheItemOnce` ครอบ (ลงประมูลในเกมไม่ได้ในสภาพแวดล้อมนี้)

**⚠️ ข้อจำกัด**
- reset-password ระหว่างที่ผู้เล่นยังไม่ได้ตั้งรหัสใหม่ ใน offline-mode ใครสวมชื่อนั้นเข้าเกมก่อนจะตั้งรหัสได้ → ต้องแจ้งเจ้าของชื่อให้เข้าเกมทันที (จะเตือนใน UI เฟส 8)

---

### เฟส 8 — หน้าเว็บแบบ "มือถือเปิดแอป" (2026-11-14)

**ไฟล์ที่เพิ่ม**
- `src/main/resources/claimmap-web/css/app.css` — ธีมส้ม `#e96f00`, เหลี่ยม, mobile-first, ปุ่ม ≥44px, รองรับ dark mode
- `src/main/resources/claimmap-web/js/core.js` — session, `api()`, router แบบ hash, confirm modal, toast, แถบสถานะ + polling (หยุดเมื่อแท็บซ่อน, backoff เมื่อล้มติดกัน)
- `src/main/resources/claimmap-web/js/main.js` — ผูก route ทั้งหมด
- `src/main/resources/claimmap-web/js/apps/{home,map,sell,buy,auction,wallet,mailbox,prices,admin}.js`
- แทน `index.html` เดิมด้วย shell (status bar / `#app` / Home-Back / toast)

**ไฟล์ที่แก้**
- `WebServer.java` — static route `/css/*` `/js/*` `/icons/*` อ่านจาก classpath, ตรวจ path ด้วย regex + ปฏิเสธ `..`, `//`, `\`, `%`, segment ขึ้นต้นด้วย `.` · Content-Type ตามนามสกุล · no-cache สำหรับ html/js/css, max-age=86400 สำหรับ icons · เพิ่ม `mailboxCount` ใน `/api/wallet` (เข้ากันได้ย้อนหลัง)
- `claim-map.html` (root) — เปลี่ยนเป็นหน้า redirect ไป `/` (ยังมี README อ้างถึงเป็นไฟล์ deploy เดิม จึงไม่ลบ)

**การตัดสินใจ**
- ใช้ ES modules ไม่มี build step และไม่โหลดทรัพยากรภายนอกเลย
- ข้อมูลจากเซิร์ฟเวอร์ทุกจุดผ่าน `textContent`/`createElement` (ไม่มี `innerHTML`) — ตรวจด้วย grep แล้วเหลือแต่คอมเมนต์
- แผนที่เดิมถูกย้ายมาเป็น `apps/map.js` พร้อม `dispose()` (หยุด timer/observer/event ตอนออกจากแอป)
- ปุ่มที่ใช้เงินทุกปุ่มเปิด confirm modal แสดงราคา/ยอดคงเหลือ และ disable ระหว่างทำรายการ
- แอปแอดมินเห็นเฉพาะ `admin:true`; พิมพ์ `#admin` เองจะถูกพากลับ `#home`

**ผลทดสอบ**
- [x] `./gradlew test` ผ่าน 84 เทสต์ · build 26.3 ✅ · 26.2 ✅
- [x] jar มี `claimmap-web/{index.html,css/app.css,js/...}` ครบ (`jar tf`)
- [x] static: `/` 200 html, `/css/app.css` 200 css, `/js/main.js` 200 js, `/js/apps/admin.js` 200 js, `/icons/nope.png` 404
- [x] path traversal: `/js/../../config/claimmap/accounts.json`, `/js/%2e%2e/%2e%2e/...`, `/js//main.js`, `/icons/%2e%2e/app.css` → 404 ทั้งหมด
- [x] jsdom smoke test: login → home tile, admin tile, wallet แสดงยอด, ชื่อผู้เล่นที่มี `<img onerror>` แสดงเป็นข้อความ (ไม่มี `<img>` ถูกสร้าง), non-admin เข้า `#admin` ถูกพากลับ, 401 ล้าง session แล้วกลับหน้าล็อกอิน
- [x] `grep -RIn "https\?://"` ใน `claimmap-web` → ไม่พบคำขอภายนอก
- [x] `node --check` ผ่านทุกไฟล์ JS
- [ ] ทดสอบบนมือถือ/เดสก์ท็อปจริงและกดทุกแอป — สภาพแวดล้อมนี้ไม่มีเบราว์เซอร์/จอ
- [ ] กดรัว ๆ บนปุ่มเงินเทียบ ledger — ต้องมีเบราว์เซอร์จริง

**⚠️ ข้อจำกัด**
- ไม่มี Chrome/Playwright ในเครื่อง จึงทดสอบ UI ระดับ DOM ด้วย jsdom และตรวจ HTTP เท่านั้น
- ไอคอนไอเท็มยังไม่มีไฟล์ PNG; หน้าเว็บใช้ชื่อ/ตัวอักษรแทน (ไม่มีการโหลดภายนอก)

---

### เฟส 9 — ทดสอบรวมก่อนใช้จริง (2026-11-14)

**ไฟล์ที่เพิ่ม**
- `tools/ApiFuzz.java` — ยิง API ด้วยค่าผิดรูป 16 กรณี ตรวจ status ตามหัวข้อ E และห้ามมี 500 (รันด้วย `java tools/ApiFuzz.java <baseUrl> [session token]`)
- `docs/PLAYER_GUIDE.md` — คู่มือผู้เล่นภาษาไทย (ล็อกอิน/คำสั่ง/แอป/คำเตือน)
- `docs/OWNER_NOTES.md` — ค่าที่ต้องตั้ง, ความปลอดภัย, สำรอง/กู้คืน, วิธีแก้เมื่อตลาดถูกปิด
- `docs/TEST_REPORT.md` — ผลทดสอบจริง + รายการที่ยังไม่ได้รันและเหตุผล

**ผลทดสอบ**
- [x] `./gradlew build` 26.3 และ 26.2 ผ่าน
- [x] `./gradlew test` ผ่าน 84 เทสต์
- [x] `java tools/ApiFuzz.java ...` → PASS 16/16 (ไม่มี 500)
- [x] `java tools/CheckLedger.java run/config/claimmap` → PASS `lastSeq=113 invariant=0`
- [x] กู้คืนจาก backup ล่าสุดไปโฟลเดอร์ใหม่ → CheckLedger PASS
- [x] รีสตาร์ทเซิร์ฟเวอร์ → `/api/wallet` ของ Bob = 859 เท่าเดิม
- [x] ตรวจ grep: ไม่มี `double`/`float` ในโค้ดเงิน, ไม่มี `MYTOKEN`, ไม่มีคีย์ JSONBin, ไม่มี `innerHTML` กับข้อมูลเซิร์ฟเวอร์

**⚠️ ยังไม่ได้รัน (ต้องมีไคลเอนต์ Minecraft / เบราว์เซอร์จริง)**
- AuthGate ในเกมทั้งหมด, การซื้อ/ขาย/รับของ/`/ah sell`/`/eco` ในเกม, การแข่งขัน 2 บัญชี, kill -9 กลางธุรกรรม, การกดปุ่มเงินรัว ๆ บนเบราว์เซอร์, ภาพหน้าจอบนมือถือ
- รายละเอียดครบใน `docs/TEST_REPORT.md` ข้อ 6 — **Gate 9 ยังไม่ถือว่าผ่านครบจนกว่าจะทดสอบรายการเหล่านี้กับเกมจริง**

**สรุปโปรเจกต์**
- เฟส 0–8 เสร็จและ commit แล้ว · เฟส 9 ส่งมอบเครื่องมือ + เอกสาร + ผลทดสอบเท่าที่สภาพแวดล้อมทำได้

---

### ข้อกำหนดเพิ่มเติม (ทับ PHASES.md) — migration ข้อมูลเก่า + AuthGate แผน A (2026-11-14)

**ก. ข้อมูลเก่า (ไม่ให้เจ้าของแก้เอง)**
- `DataMigration.java` — คัดลอกไฟล์เดิมทุกไฟล์ไป `backups/pre-upgrade-<เวลา>/` ก่อนเขียนครั้งแรกของรุ่นใหม่, ใช้ marker `.pre-upgrade-done` ไม่ทำซ้ำ
- `ClaimMapConfig.load()` — เรียก backup ก่อนอ่าน/เติมคีย์; ค่าเดิมของเจ้าของ (รวม `bindAddress=127.0.0.1`) ไม่ถูกเขียนทับ
- `AccountManager` — อ่าน `accounts.json` ได้ทั้ง `{accounts:[...]}`, map ที่คีย์ด้วย uuid และ map ที่คีย์ด้วยชื่อ; ระเบียนที่ไม่มี `uuid` ถูกเก็บเป็น pending และผูก UUID ตอนเข้าเกมครั้งแรก (`bindUuid`) · การบันทึกยังเก็บ pending ไว้ไม่ให้หาย
- รหัส SHA-256 เดิมยังใช้ได้ และ rehash เป็น PBKDF2 เมื่อล็อกอินสำเร็จ (ทั้งเกมและเว็บ)
- `sessions.json` / `regions.json` ไม่ถูกแตะ; `ensureAccount` ทำงานตอนเข้าสู่เกมครั้งแรกหลังอัปเดต

**ข. AuthGate แผน A (configuration dialog เท่านั้น)**
- ตรวจซอร์สทั้ง 26.2 และ 26.3 พบเส้นทางที่ใช้ได้จริง:
  - `ClientboundShowDialogPacket` ถูกประกาศใน `ConfigurationProtocols` (context-free codec)
  - `ServerboundCustomClickActionPacket` มีใน configuration protocol
  - `ServerConfigurationPacketListenerImpl.handleConfigurationFinished` คือจุดเปลี่ยนเข้าโลก
  - `ServerConfigurationConnectionEvents.BEFORE_CONFIGURE` / `DISCONNECT` (Fabric API)
  - `ServerConfigurationNetworking.isReconfiguring` สำหรับการ reload ระหว่างเล่น
- `AuthGate` เขียนใหม่: แสดง dialog ล็อกอิน/ตั้งรหัสครั้งแรก + ปุ่มเข้าเกม/ออกจากเกม, ผิด 3 ครั้งหรือเกิน 60 วินาที = disconnect, PBKDF2 บน worker thread
- mixin ใหม่: `ConfigurationFinishMixin` (บล็อก finish-configuration จนกว่าจะผ่าน), `ConfigurationClickMixin` (รับ action จาก dialog), `PlayerProfileAccessor` (อ่าน GameProfile ของ connection)
- ลบ `/login` และ `/register` ออกทั้งหมด — ไม่มีทางสำรองแบบคำสั่งแชทหรือ dialog หลังเข้าโลก
- `/pass` ยังอยู่ ใช้ได้เฉพาะผู้ที่ผ่านการล็อกอินแล้วและต้องใส่รหัสเดิม

**ผลทดสอบ**
- [x] `./gradlew test` ผ่าน 92 เทสต์ (เพิ่ม migration, legacy accounts และ dialog encoding)
- [x] ทดสอบ encode dialog ด้วย `Dialog.CONTEXT_FREE_STREAM_CODEC` ผ่านทั้ง login/register/error
- [x] build 26.3 ✅ · 26.2 ✅
- [x] เปิดเซิร์ฟเวอร์จริง: mixin ไม่มี injection error, สร้าง `backups/pre-upgrade-20261114-182839-478/` อัตโนมัติจากข้อมูลเดิม และเซิร์ฟเวอร์ทำงานปกติ
- [x] รัน `./gradlew test` กับโมดูลที่ bootstrap แล้ว (SharedConstants + Bootstrap) ไม่มี error
- [ ] ทดสอบกับไคลเอนต์จริง: หน้าต่างขึ้นก่อนเข้าโลก, กด Esc, ปิดเกมกลางคัน, ส่งแพ็กเก็ตปลอม, ผิด 3 ครั้ง/60 วินาที — เครื่องนี้ไม่มีไคลเอนต์ Minecraft จึงยังยืนยันไม่ได้ (โค้ดฝั่งเซิร์ฟเวอร์กันครบตาม mixin ข้างต้น)

**⚠️ ต้องแจ้งเจ้าของ**
- `TextInput` ของ Minecraft ไม่มีโหมดซ่อนรหัส → ช่องกรอกรหัสจะแสดงข้อความที่พิมพ์ ผมไม่ได้เปลี่ยนวิธีล็อกอินเองตามข้อกำหนด
- หลัง `reset-password` ผู้เล่นต้องเข้าเกมและตั้งรหัสในหน้าต่างให้ทันที
