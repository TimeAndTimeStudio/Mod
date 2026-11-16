# Time SMP (Fabric Mod) 🗺️

Fabric **server-only** mod + web map ตามแผนใน `plan.md` รองรับ **Minecraft 26.2 และ 26.3** (Java 25, Fabric API)

- โลกแบ่งเป็นช่อง 500×500 บล็อก จองได้ทางหน้าเว็บ `timesmp.html`
- แผนที่แสดงเฉพาะส่วนที่โหลด/สำรวจแล้ว (ไม่ pre-generate): น้ำ = ฟ้า, ทราย = เหลือง, หิมะ/น้ำแข็ง = ขาว, ที่เหลือ = เขียว, ยังไม่สำรวจ = เทาเข้ม
- ช่องของเรา = ส้ม, ช่องคนอื่น = แดง (ดูชื่อได้ แต่จองไม่ได้)
- เขตที่จองแล้ว: คนนอกวาง/ทุบ/เปิดหีบ/ใช้ถังน้ำ-ลาวา/จุดไฟไม่ได้
- ล็อกอินผูกบัญชี: ตั้ง `/pass <รหัสผ่าน>` ในเกม → ล็อกอินบนเว็บด้วยชื่อผู้เล่น + รหัสผ่าน ได้ session token

## Build

```bash
# Minecraft 26.3 (ค่าเริ่มต้น)
./gradlew build

# Minecraft 26.2
./gradlew build -Pminecraft_version=26.2 -Pfabric_api_version=0.161.0+26.2
```

ผลลัพธ์อยู่ที่ `build/libs/timesmp-mc<เวอร์ชัน>-1.0.0.jar`
GitHub Actions (`.github/workflows/build.yml`) จะ build ทั้ง 26.2 และ 26.3 ให้อัตโนมัติทุกครั้งที่ push

## ติดตั้ง

1. วาง jar ลงโฟลเดอร์ `mods/` ของเซิร์ฟเวอร์ Fabric (ต้องลง Fabric API 0.161+ ด้วย)
2. รันเซิร์ฟเวอร์ → เปิด config ได้ที่ `config/timesmp/timesmp.properties`
   - `token` จะถูกสุ่มให้ครั้งแรก (ตั้งเป็น `Authorization: Bearer <token>` ในการเรียก API จากสคริปต์)
   - ปรับได้: `port` (8085), `bindAddress` (0.0.0.0), `cellSize` (500), `freeCellsPerPlayer` (4), `maxCellsPerPlayer` (0 = ซื้อได้ไม่จำกัด), `spawnProtectedCells`, `renderScale`, `saveDelayMs`, `linkCodeMinutes`, `sessionHours`
3. ม็อด **host หน้าเว็บและ API ในตัวโดยตรง** ที่พอร์ต 8085 — เปิดผ่านเบราว์เซอร์ที่ `http://<IP เซิร์ฟเวอร์>:8085/` ได้ทันที โดยไม่ต้องติดตั้งเว็บเซิร์ฟเวอร์ภายนอก (ไม่ใช้ Nginx หรือ Node.js)
4. ข้อมูลทั้งหมดเก็บเป็น **JSON เท่านั้น**: `accounts.json`, `regions.json`, `sessions.json`, `economy.json`
            
## ข้อมูลที่เก็บ (JSON only)

| ไฟล์ | เนื้อหา |
|---|---|
| `config/timesmp/accounts.json` | บัญชีผู้เล่น + รหัสผ่านแบบ PBKDF2 (ไม่เก็บ plain text) |
| `config/timesmp/regions.json` | ช่องที่จองแล้ว: เจ้าของ/สมาชิก |
| `config/timesmp/sessions.json` | session token ของเว็บ (หมดอายุตาม `sessionHours`) |
| `config/timesmp/economy.json` | ข้อมูลระบบเศรษฐกิจและยอดเงินผู้เล่น |

## API

| Method | Path | ทำอะไร | Token |
|---|---|---|---|
| GET | `/` | หน้าเว็บ (ฝังใน jar, แทรก master token อัตโนมัติ) | ไม่ต้อง |
| GET | `/api/me` | `{name}` ผู้เล่นที่ล็อกอิน | ใดก็ได้ |
| GET | `/api/cells` | `{ "x,z": "เจ้าของ" }` | ใดก็ได้ |
| GET | `/api/map/{x},{z}.png` | PNG ของช่อง (204 ถ้ายังไม่สำรวจ) | ใดก็ได้ |
| POST | `/api/login` | `{"name","password"}` → session token (รหัสตั้งด้วย `/pass`) | master |
| POST | `/api/cells/{x},{z}/claim` | จองช่อง | session |
| DELETE | `/api/cells/{x},{z}` | ยกเลิก (เจ้าของ/แอดมิน) | session/master |
| POST | `/api/cells/{x},{z}/members` | เพิ่มสมาชิก | session(เจ้าของ) |
| POST | `/api/command` | รันคำสั่ง | master เท่านั้น |

## คำสั่งในเกม

- `/pass <รหัสผ่าน>` — ตั้ง/เปลี่ยนรหัสผ่านสำหรับล็อกอินบนเว็บ (4–64 ตัวอักษร)
- `/claim` — จองช่องที่ยืนอยู่
- `/timesmp here` — เช็กช่องปัจจุบัน
- `/timesmp reload` — โหลด config + regions (operator)

## หลายโลก (Automatic Multi-World)

วางโลกที่สมบูรณ์ (มี `level.dat`) เป็นโฟลเดอร์ลูกใน `worlds/` ของ server root:

```text
server/
├── world/              # โลกหลัก (ม็อดไม่ปิด/ไม่ลบ)
└── worlds/
    ├── adventure/
    ├── survival/
    └── minigame/
```

ม็อดจะสแกน `worlds/` ตอนเริ่มเซิร์ฟเวอร์และซ้ำทุก `worldScanSeconds` วินาที (หรือกดสแกนในหน้าแอดมิน) แล้วลงทะเบียนโลกที่ถูกต้อง โลกจะถูกโหลดเมื่อมีคนขอครั้งแรก และปิดเมื่อว่างครบ `worldIdleUnloadSeconds` (0 = ไม่ปิดอัตโนมัติ)

- ผู้เล่นใช้ `/<ชื่อโฟลเดอร์>` เช่น `/adventure`, `/survival`, `/minigame`
- แอดมินใช้ `/worlds list|scan|load <name>|unload <name>|tp <name> [player]` หรือแท็บ **โลก** ในหน้าแอดมิน
- ชื่อโฟลเดอร์ต้องเป็น `a-z`, `0-9`, `_`, `-` ยาวไม่เกิน 32 ตัว; ชื่อที่ชนกับคำสั่งอื่นจะไม่ถูกเขียนทับ (ใช้ `/worlds tp` แทน)
- **กระเป๋าไอเท็มใช้ร่วมกันทุกโลก** (inventory, ender chest, exp, health, effects, advancements) เพราะสลับมิติโดยไม่สร้าง player object ใหม่
- โลกที่โหลดอยู่จะไม่ถูกปิดถ้ายังมีผู้เล่น; โลกที่โฟลเดอร์หายจะไม่ถูกถอดออกจากหน่วยความจำทันที

ค่าที่ปรับได้ใน `config/timesmp/timesmp.properties`: `worldDirectory` (`worlds`), `worldScanSeconds` (30), `worldIdleUnloadSeconds` (300), `worldPlayerCommands` (`true`)

> ออกแบบจากพฤติกรรมของ [Wroud/mc-worlds](https://github.com/Wroud/mc-worlds) (MIT) และตรวจ API จริงของ Minecraft 26.2/26.3; โค้ดในโปรเจกต์นี้เขียนแยก ไม่ได้คัดลอกไฟล์จากโปรเจกต์อ้างอิง

## โครงสร้างโค้ด

```
src/main/java/studio/timeandtime/timesmp/
  ClaimMap        # ModInitializer: ติดตั้ง events/commands, เปิด-ปิด WebServer
  ClaimMapConfig  # config/timesmp/timesmp.properties + สุ่ม master token
  RegionManager   # ช่อง 500×500 -> เจ้าของ/สมาชิก เก็บ regions.json
  AccountManager  # บัญชี + รหัสผ่าน (salted hash) เก็บ accounts.json
  MapRenderer     # ดัก CHUNK_LOAD แล้ววาด PNG ต่อช่อง (แคชที่ config/timesmp/tiles/)
  Protection      # อีเวนต์กันวาง/ทุบ/เปิดหีบ/ถังน้ำ-ลาวา/ไฟ ในเขตของคนอื่น
  LinkManager     # session token ผูก UUID เก็บ sessions.json (login ผ่าน /pass)
  WebServer       # HttpServer ในตัว (0.0.0.0:8085): host หน้าเว็บและ API ทั้งหมดโดยตรง
  ClaimCommands   # /pass /claim /timesmp ...
src/main/resources/fabric.mod.json
src/main/resources/timesmp-web/    # หน้าเว็บ UI ฝังใน jar (HTML/CSS/JS)
```
