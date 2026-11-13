# Claim Map (Fabric Mod) 🗺️

Fabric **server-only** mod + web map ตามแผนใน `plan.md` รองรับ **Minecraft 26.2 และ 26.3** (Java 25, Fabric API)

- โลกแบ่งเป็นช่อง 500×500 บล็อก จองได้ทางหน้าเว็บ `claim-map.html`
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

ผลลัพธ์อยู่ที่ `build/libs/claimmap-mc<เวอร์ชัน>-1.0.0.jar`
GitHub Actions (`.github/workflows/build.yml`) จะ build ทั้ง 26.2 และ 26.3 ให้อัตโนมัติทุกครั้งที่ push

## ติดตั้ง

1. วาง jar ลงโฟลเดอร์ `mods/` ของเซิร์ฟเวอร์ Fabric (ต้องลง Fabric API 0.161+ ด้วย)
2. รันเซิร์ฟเวอร์ → เปิด config ได้ที่ `config/claimmap/claimmap.properties`
   - `token` จะถูกสุ่มให้ครั้งแรก (ตั้งเป็น `Authorization: Bearer <token>` ในการเรียก API)
   - ปรับได้: `port` (8085), `cellSize` (500), `maxCellsPerPlayer`, `spawnProtectedCells`, `renderScale`, `saveDelayMs`, `linkCodeMinutes`, `sessionHours`
3. ม็อด **host หน้าเว็บเอง** ที่พอร์ตเดียวกับ API — เปิด `http://127.0.0.1:8085/` ได้เลย (ม็อดบังคับ bind `127.0.0.1` เพื่อความปลอดภัย ดังนั้นถ้าต้องการเข้าจากนอกเครื่องให้ใช้ Nginx/tunnel ต่อไปที่พอร์ตนี้)
4. ข้อมูลทั้งหมดเก็บเป็น **JSON เท่านั้น**: `account.json` account/รหัสผ่าน, `regions.json` ช่องที่จอง, `sessions.json` session token
            
## ข้อมูลที่เก็บ (JSON only)

| ไฟล์ | เนื้อหา |
|---|---|
| `config/claimmap/accounts.json` | บัญชีผู้เล่น + รหัสผ่านแบบ salted SHA-256 (ไม่เก็บ plain text) |
| `config/claimmap/regions.json` | ช่องที่จองแล้ว: เจ้าของ/สมาชิก |
| `config/claimmap/sessions.json` | session token ของเว็บ (หมดอายุตาม `sessionHours`) |

## เชื่อมต่อ Nginx (HTTPS)

```nginx
server {
  listen 443 ssl;
  server_name map.example.com;
  # ssl_certificate ...; ssl_certificate_key ...;

  root /var/www/claimmap;           # วาง claim-map.html ที่นี่
  index claim-map.html;

  location /api/ {
    proxy_pass http://127.0.0.1:8085;
    proxy_set_header Host $host;
  }
}
```

*ระวัง:* อย่าเปิดพอร์ต 8085 ตรงสู่สาธารณะ (ม็อดบังคับ bind 127.0.0.1) ใช้ Nginx + TLS + token เสมอ

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
- `/claimmap here` — เช็กช่องปัจจุบัน
- `/claimmap reload` — โหลด config + regions (operator)

## โครงสร้างโค้ด

```
src/main/java/studio/timeandtime/claimmap/
  ClaimMap        # ModInitializer: ติดตั้ง events/commands, เปิด-ปิด WebServer
  ClaimMapConfig  # config/claimmap/claimmap.properties + สุ่ม master token
  RegionManager   # ช่อง 500×500 -> เจ้าของ/สมาชิก เก็บ regions.json
  AccountManager  # บัญชี + รหัสผ่าน (salted hash) เก็บ accounts.json
  MapRenderer     # ดัก CHUNK_LOAD แล้ววาด PNG ต่อช่อง (แคชที่ config/claimmap/tiles/)
  Protection      # อีเวนต์กันวาง/ทุบ/เปิดหีบ/ถังน้ำ-ลาวา/ไฟ ในเขตของคนอื่น
  LinkManager     # session token ผูก UUID เก็บ sessions.json (login ผ่าน /pass)
  WebServer       # HttpServer 127.0.0.1:8085: host หน้าเว็บเอง + API ด้านบน
  ClaimCommands   # /pass /claim /claimmap ...
src/main/resources/fabric.mod.json
src/main/resources/claimmap-web/index.html   # หน้าเว็บฝังใน jar (สิ้นเปลือง Nginx)
claim-map.html    # หน้าเว็บสำเนา (สำหรับ deploy นอก jar เช่น Nginx)
```
