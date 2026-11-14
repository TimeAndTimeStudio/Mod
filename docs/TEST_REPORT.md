# รายงานทดสอบรวม (เฟส 9)

วันที่: 2026-11-14 · สภาพแวดล้อม: Linux, JDK 25, ไม่มีไคลเอนต์ Minecraft และไม่มีเบราว์เซอร์ในเครื่อง
สถานะรวม: **ผ่านสิ่งที่รันได้ในสภาพแวดล้อมนี้** — รายการที่ต้องใช้เกม/เบราว์เซอร์จริงระบุว่า "ยังไม่ได้รัน" ด้านล่าง

## 1. Build และ unit test

| สิ่งที่ทดสอบ | คำสั่ง | ผล |
|---|---|---|
| Build 26.3 | `./gradlew build` | PASS |
| Build 26.2 | `./gradlew build -Pminecraft_version=26.2 -Pfabric_api_version=0.161.0+26.2` | PASS |
| Unit test ทั้งหมด | `./gradlew test` | PASS 84 เทสต์ |

## 2. เงิน / ledger

| สิ่งที่ทดสอบ | คำสั่ง/วิธี | ผล |
|---|---|---|
| replay ledger = snapshot, invariant = 0 | `java tools/CheckLedger.java run/config/claimmap` | PASS `accounts=2 escrowPots=2 lastSeq=113 systemNet=-1966 invariant=0` |
| กู้คืนจาก backup | คัดลอก backup ล่าสุดไป `/tmp/restore-check` แล้วรัน CheckLedger | PASS ยอดตรงกัน |
| รีสตาร์ทเซิร์ฟเวอร์ | GET `/api/wallet` ก่อน/หลัง restart | PASS Bob = 859 ทั้งก่อนและหลัง |
| โอนเงินพร้อมกัน 100 ครั้ง | `tools/StressPay.java` (ทำในเฟส 2) | PASS `ok=100 failed=0` ผลรวมคงที่ — เฟสนี้ไม่ได้รันซ้ำเพราะไม่ทราบรหัสผ่านของบัญชีทดสอบ |

## 3. API fuzz

`java tools/ApiFuzz.java http://127.0.0.1:8085 <session ของผู้เล่น>` → **PASS 16/16**

ครอบ: ไม่มี session, token ผิด, path ไม่มี, method ผิด, JSON พัง, body เกิน 16 KB (413), pay 0/ติดลบ/ทศนิยม/สตริง/เลขยกกำลัง, buy qty 0/ทศนิยม, item id ผิด, bid รายการไม่มี, player เรียก admin (403) — ไม่มี 500

## 4. หน้าเว็บ

| สิ่งที่ทดสอบ | วิธี | ผล |
|---|---|---|
| static + content type | curl `/`, `/css/app.css`, `/js/main.js`, `/js/apps/admin.js` | PASS 200 ชนิดถูกต้อง |
| path traversal | `/js/../../config/claimmap/accounts.json`, `/js/%2e%2e/...`, `/js//main.js`, `/icons/%2e%2e/app.css` | PASS 404 ทั้งหมด |
| ไฟล์อยู่ใน jar | `jar tf build/libs/claimmap-mc26.3-1.0.0.jar` | PASS ครบทุกไฟล์ |
| login → home → wallet | jsdom smoke test | PASS |
| XSS จากข้อมูลเซิร์ฟเวอร์ | ชื่อผู้เล่น `<img onerror>` ในประวัติ | PASS แสดงเป็นข้อความ ไม่มี `<img>` ถูกสร้าง |
| non-admin เข้า `#admin` | jsdom | PASS ถูกพากลับ `#home` |
| session หมดอายุ (401) | jsdom + mock 401 | PASS ล้าง session กลับหน้าล็อกอิน |
| ไม่มีคำขอภายนอก | `grep -RIn "https\?://" src/main/resources/claimmap-web` | PASS ไม่พบ |
| `innerHTML` กับข้อมูลเซิร์ฟเวอร์ | grep | PASS ไม่มี (เหลือแต่คอมเมนต์) |

## 5. ตรวจทั้ง repo

| สิ่งที่ทดสอบ | ผล |
|---|---|
| ไม่มี `double`/`float` ในโค้ดเงิน | PASS — ที่พบอยู่ใน `AuthGate` (พิกัดผู้เล่น) ไม่ใช่เงิน |
| ไม่มี `MYTOKEN` | PASS |
| ไม่มีคีย์ JSONBin ใน repo | PASS — พบเฉพาะชื่อ header ในโค้ดและตัวอย่างในเอกสาร |
| ไม่มีคีย์/รหัสผ่านใน log | ตรวจโค้ดแล้วไม่มี (log บอกแค่มี/ไม่มี) |

## 6. รายการที่ยังไม่ได้รัน (ต้องมีไคลเอนต์/เบราว์เซอร์จริง)

| รายการ | เหตุผล |
|---|---|
| AuthGate ในเกม: หน้าต่าง/คำสั่งตั้งรหัส, kick, บล็อกการกระทำทุกชนิด | ไม่มีไคลเอนต์ Minecraft |
| ขาย/ซื้อ/รับของในเกม, `/sell all`, `/mail claim`, inventory เต็ม | ไม่มีไคลเอนต์ Minecraft |
| `/ah sell` จากมือ, การปิดประมูลตอนผู้เล่นออฟไลน์ | ไม่มีไคลเอนต์ Minecraft |
| `/eco` ด้วยสิทธิ์ OP จริง | ไม่มีไคลเอนต์ Minecraft |
| กดปุ่มเงินรัว ๆ บนเบราว์เซอร์แล้วเทียบ ledger | ไม่มีเบราว์เซอร์ |
| ทดสอบ UI บนมือถือ/เดสก์ท็อปจริงและถ่ายภาพหน้าจอ | ไม่มีเบราว์เซอร์ |
| kill -9 กลางธุรกรรมจริง | ทำได้แค่ unit test จำลองการดับในเฟส 2/6 |
| ใช้งาน 2 บัญชีพร้อมกันในเกม | ไม่มีไคลเอนต์ Minecraft |

## 7. ข้อควรทำก่อนเปิดใช้จริง

1. ตั้ง `players` และ `admins` ให้ครบ และ `bindAddress=0.0.0.0`
2. หมุนคีย์ JSONBin เป็น read-only ใหม่ และเก็บเฉพาะใน config
3. ทดสอบวงจรในเกมจริงตามรายการข้อ 6 (อย่างน้อย: เข้าเกม → ตั้ง/ใส่รหัส → ซื้อ → ขาย → `/ah sell` → bid → ปิดประมูล)
4. คัดลอก `config/claimmap/backups/` ไปเก็บนอกเครื่องเป็นระยะ

---

## 8. รอบข้อกำหนดเพิ่มเติม: migration + AuthGate แผน A

วันที่ 2026-11-14 · สถานะ: ผ่านสิ่งที่รันได้ในเครื่องนี้

| สิ่งที่ทดสอบ | คำสั่ง/วิธี | ผล |
|---|---|---|
| unit test ทั้งหมด | `./gradlew test` | PASS 92 เทสต์ |
| build 26.3 | `./gradlew build` | PASS |
| build 26.2 | `./gradlew build -Pminecraft_version=26.2 -Pfabric_api_version=0.161.0+26.2` | PASS |
| dialog serialize ได้จริง | encode ด้วย `Dialog.CONTEXT_FREE_STREAM_CODEC` (login/register/error) | PASS |
| accounts.json รูปแบบเก่า SHA-256 | โหลด + verify + rehash | PASS |
| accounts.json ไม่มี uuid | โหลดได้ + `bindUuid` ตอนเข้าเกม | PASS |
| accounts.json คีย์ด้วยชื่อ | โหลดได้ + pending อยู่รอดหลัง save | PASS |
| backup pre-upgrade | เขียนไฟล์เก่า → `DataMigration.backupBeforeUpgrade` | PASS คัดลอกครบและทำครั้งเดียว |
| properties เดิม | `bindAddress=127.0.0.1` และค่าอื่นไม่ถูกเขียนทับ | PASS |
| เปิดเซิร์ฟเวอร์จริง | mixin + backup อัตโนมัติ | PASS ไม่มี injection error |

### ยังไม่ได้ทดสอบ (ต้องมีไคลเอนต์ Minecraft)

- หน้าต่างล็อกอิน/ตั้งรหัสขึ้นก่อนเข้าโลกจริง
- กด Esc / ปิดเกมกลางคัน / ส่งแพ็กเก็ตตอบปลอม
- ผิดรหัส 3 ครั้ง และเงียบ 60 วินาที แล้วถูก disconnect
- `/pass` ในเกมจริง

โค้ดฝั่งเซิร์ฟเวอร์กันครบ (ConfigurationFinishMixin + ConfigurationClickMixin) แต่ไม่มีไคลเอนต์ในสภาพแวดล้อมนี้จึงยืนยันพฤติกรรมจริงไม่ได้
