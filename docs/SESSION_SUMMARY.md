# สรุปงานเซิร์ฟเวอร์ Time SMP — 2026-11-18

เอกสารสรุปงานที่ทำในเซสชันนี้: การลบระบบพื้นที่ส่วนกลาง (common/spawn area),
การทำให้เซิร์ฟเวอร์เปิด (ไม่มี whitelist), การจัดการโลกทั้งสาม (main/lobby/find)
พร้อมข้อมูลผู้เล่นแบบร่วม และการเคลียข้อมูลผู้เล่น

---

## 1. ลบระบบพื้นที่ส่วนกลาง (Common/Spawn Area)

**เป้าหมาย:** ผู้เล่นจอง/ซื้อที่ดินได้**ทุกจุด** ไม่มีการกันพื้นที่เกิดส่วนกลางอีกต่อไป

**การเปลี่ยนแปลงโค้ด:**
| ไฟล์ | การเปลี่ยนแปลง |
| :--- | :--- |
| `ClaimMapConfig.java` | ลบ field `spawnProtectedCells`, การ load/save และเพิ่ม `props.remove("spawnProtectedCells")` ใน `load()` เพื่อล้างค่าเก่าออกจากไฟล์ config |
| `RegionManager.java` | ลบ `isCommonArea()` และค่า `COMMON_AREA` ใน enum `ClaimResult` |
| `AuctionManager.java` | ลบเงื่อนไขกันประมูลในพื้นที่ส่วนกลาง |
| `ClaimCommands.java` | ลบข้อความ "พื้นที่เกิดส่วนกลาง ไม่สามารถซื้อได้" |
| `ClaimMapGui.java` | ลบ UI/ข้อความ "พื้นที่ส่วนกลาง" (ทั้งใน grid และ lore) |

**Commit:** `da59742` (ลบฟีเจอร์) + `e4ca878` (แก้ compile error ที่เหลือ)

---

## 2. เซิร์ฟเวอร์เปิด (Open) + ไม่มีพื้นที่ส่วนกลาง

**เป้าหมาย:** ลบผู้เล่นที่ hardcode (TAT1654) และทำให้เซิร์ฟเป็นพื้นที่ส่วนกลาง (ใครก็เข้าได้)

**การเปลี่ยนแปลง (`setup.sh`):**
- ลบ `PLAYER_NAME="TAT1654"` และ `PLAYER_UUID` พร้อมส่วนสร้าง `whitelist.json`
- `server.properties` ที่ generate: `white-list=false`, `enforce-whitelist=false`, `spawn-protection=0`
- mod: `spawnProtectedCells` default = 0 (ไม่มีการกันพื้นที่) → ผู้เล่นจองได้ทุกจุด

**Commit:** `e55a55a`

---

## 3. โลกทั้งสาม + ข้อมูลผู้เล่นแบบร่วม

**โลกทั้งสาม:**
| โลก | ตำแหน่ง | สถานะ |
| :--- | :--- | :--- |
| main | `world/` | โหลดตลอดเวลา |
| lobby | `worlds/lobby` | autoload (เป็น lobby เริ่มต้น) |
| find | `worlds/find` | พบแล้ว โหลดเมื่อใช้ `/find` |

**ข้อมูลผู้เล่น (player data) แบบร่วม:**
- `WorldManager` ใช้ policy **shared inventory** — เมื่อผู้เล่นย้ายโลก (เปลี่ยน dimension)
  ออบเจกต์ผู้เล่นไม่ถูกแทนที่ ดังนั้น inventory, ender chest, XP, health, effects,
  advancements และ stats **เหมือนกันทุกโลก**
- ข้อมูลผู้เล่นถูกเก็บใน **โลก main** (`world/players/`) เป็นแหล่งเดียว — lobby/find
  ไม่มี player data แยก → "ใช้ข้อมูลจากแมพ main" เป็นจริงอยู่แล้ว

**หมายเหตุ:** ลบโลก `test` ที่เหลืออยู่ (เหลือแค่สามโลกหลัก)

---

## 4. เคลียข้อมูลผู้เล่น (บนเครื่องนี้)

- ลบ `world/players/data/`, `world/players/advancements/`, `world/players/stats/`
  (ข้อมูลผู้เล่น 2 คน: `4c0b35fb…` และ `9db07e0d…` = TAT1654)
- ลบ `world/session.lock`
- ผล: `world/players/` ว่าง → เริ่มใหม่สะอาด

---

## 5. ผลการทดสอบ

### CI (GitHub Actions)
| Run | Commit | ผล |
| :--- | :--- | :--- |
| `44228901372` | `e55a55a` | ✅ success (26.2 / 26.3 / release) |
| `44230863201` | `e4ca878` | ✅ success (26.2 / 26.3 / release) |

### Runtime (เซิร์ฟเวอร์จริง `~/mcserver`, RAM=1536M)
```
- lithostitched 2.0.6
- tectonic 3.0.32
- sit-oth3r 1.2.6.4+26.3
- otterlib 0.4.0.2+26.3-fabric
[worlds] Loaded lobby from worlds/lobby
[worlds] Found 'lobby' world - loaded and ready as starting lobby!
[worlds] Using worlds (2 world(s), shared inventory)   <- lobby + find
[realworld] Real time source: JSON timeapi.io
[realworld] Weather at 15.656812,103.588737: code 1 -> CLEAR
```
- **error count: 0**
- lobby clock = เวลาจริง UTC+7 (ตรงเวลา)
- โปート 25565 เปิด / 8085 ปิด
- config: `spawnProtectedCells` ถูกลบออกแล้ว (ค่าเก่าถูกล้าง)
- player data ว่าง (เริ่มใหม่)
- ปิดเซิร์ฟเวอร์เรียบร้อย ไม่มี process/พอร์ตค้าง

---

## สถานะสุดท้าย
- **Git:** working tree clean, HEAD = `e4ca878` (push ไป main แล้ว)
- **Mods:** fabric-api, lithostitched, tectonic, sit, otterlib, timesmp
- **Worlds:** main, lobby, find (สามโลก)
- **เซิร์ฟเวอร์:** เปิด (ไม่มี whitelist), ไม่มีพื้นที่ส่วนกลาง, ข้อมูลผู้เล่นร่วมจาก main
- **Player data:** เคลียแล้ว (เริ่มใหม่)
