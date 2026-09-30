# BP Gateway (Android)

แอป Android สำหรับรับค่าความดันโลหิตจาก **Yuwell YE680B** ผ่าน Bluetooth LE
แล้วบันทึกลง CSV และส่ง HTTP POST เข้า Cloud/HIS API
พอร์ตมาจากโปรเจกต์ Python เดิม (`collector.py`, `parser.py`, `api_client.py`, `storage.py`)
ใช้ payload JSON รูปแบบเดิม จึงไม่ต้องแก้ฝั่ง server

## Build เป็น APK

1. ติดตั้ง **Android Studio** (รุ่น Koala 2024.1 ขึ้นไป)
2. เปิด Android Studio แล้วเลือก **File > Open** จากนั้นเลือกโฟลเดอร์ `BPGateway`
3. รอให้ Gradle sync เสร็จ ครั้งแรกจะดาวน์โหลด Gradle และ SDK ใช้เวลาหลายนาที ถ้ามีข้อความให้ติดตั้ง SDK 34 ให้กดติดตั้ง
4. เลือกเมนู **Build > Build App Bundle(s) / APK(s) > Build APK(s)**
5. ได้ไฟล์ที่ `app/build/outputs/apk/debug/app-debug.apk`

ถ้าจะ build ผ่าน command line (ต้องมี JDK 17 และตั้งค่า `ANDROID_HOME` ไว้แล้ว):

```bash
./gradlew assembleDebug        # Windows ใช้ gradlew.bat assembleDebug
./gradlew testDebugUnitTest    # รัน unit test ของ parser
```

## ติดตั้งและใช้งาน

1. ติดตั้ง APK ลงเครื่อง โดยต้องเปิดสิทธิ์ "ติดตั้งแอปจากแหล่งที่ไม่รู้จัก" ก่อน รองรับ Android 8.0 ขึ้นไป
2. เปิดแอป ตรวจค่า API URL และ MAC เครื่องวัด (ค่าเริ่มต้นเหมือนใน `config.py` เดิม)
3. กด **เริ่มบริการ** แล้วอนุญาตสิทธิ์ Bluetooth (Nearby devices) และสิทธิ์การแจ้งเตือน
4. เปิดเครื่อง Yuwell แล้ววัดความดัน แอปจะเชื่อมต่อและรับค่าเอง มีเสียงบี๊บเมื่อได้รับค่า
5. แอปจะทำงานต่อเนื่องเป็น Foreground Service (มีไอคอนค้างที่แถบแจ้งเตือน)
   และจะเปิดตัวเองอัตโนมัติหลังรีสตาร์ทเครื่อง ถ้าไม่ได้กด **หยุดบริการ** ไว้

ไฟล์ข้อมูลอยู่ที่ `Android/data/th.bms.bpgateway/files/` ประกอบด้วย

- `data.csv` ใช้คอลัมน์เดียวกับของเดิม
- `pending.jsonl` เก็บรายการที่ส่ง API ไม่สำเร็จ ระบบจะส่งซ้ำอัตโนมัติทุก 60 วินาที

## เทียบกับเวอร์ชัน Python

| Python (Windows) | Android |
|---|---|
| `bleak` + WinRT | Android BLE API (`BluetoothGatt`) |
| console `print()` | หน้าจอแอป + แถบแจ้งเตือน + Log |
| `winsound.Beep` | `ToneGenerator` |
| ปุ่ม R / Q บนคีย์บอร์ด | ปุ่มเริ่ม / หยุดบริการ |
| `config.py` / `.env` / argument | หน้าตั้งค่าในแอป |
| API ล่ม = บันทึก CSV อย่างเดียว | บันทึก CSV + เข้าคิวไว้ส่งซ้ำอัตโนมัติ |

ส่วนที่ปรับปรุงจากของเดิม:

- **Parser อ่านค่าแบบ IEEE-11073 SFLOAT ตามมาตรฐาน** ทำให้แปลงหน่วย kPa ถูกต้อง
  ทดสอบกับข้อมูลจริงทั้ง 202 แถวใน data.csv เดิมแล้ว ได้ค่าตรงกันทุกแถว
- **Timestamp มี offset เวลาเสมอ** เช่น `2026-09-16T14:36:40+07:00` ไม่ว่าเวลาจะมาจากตัวเครื่องวัดหรือจากมือถือ
  หมายเหตุ: ของเดิมส่งเวลาจากตัวเครื่องแบบไม่มี offset ถ้า server รับรูปแบบนี้ไม่ได้ ให้แก้ที่ `BpParser.kt`
- **กันข้อมูลซ้ำ** ถ้าเครื่องวัดส่งผลเดิมซ้ำตอนเชื่อมต่อรอบใหม่ แอปจะไม่บันทึกและไม่ POST ซ้ำ
- **รองรับ API Token** ถ้ากรอก จะส่ง header `Authorization: Bearer <token>` ไปด้วย

## ทดสอบกับ mock_server.py

1. รัน `python mock_server.py` บนคอมพิวเตอร์
2. ให้มือถือเชื่อมต่อ Wi-Fi วงเดียวกับคอมพิวเตอร์
3. ตั้ง API URL ในแอปเป็น `http://<IP ของคอมพิวเตอร์>:8000/api/blood-pressure`
   ห้ามใช้ `localhost` เพราะบนมือถือ localhost จะหมายถึงตัวมือถือเอง
4. ถ้าเชื่อมต่อไม่ได้ ให้ตรวจว่า Windows Firewall อนุญาตพอร์ต 8000 แล้ว

## ข้อควรรู้

- ถ้ากำหนด **MAC** ไว้ แอปจะสแกนแบบกรองอุปกรณ์ ซึ่งทำงานได้แม้ปิดหน้าจอ
  ถ้าเว้น MAC ว่าง แอปจะสแกนหาอุปกรณ์ทุกตัว ในกรณีนี้ Android จะหยุดสแกนเมื่อปิดหน้าจอ ควรเปิดโหมด Kiosk ค้างหน้าจอไว้
- ควรปิด **Battery optimization** ของแอปนี้ ที่ Settings > Apps > BP Gateway > Battery > Unrestricted
  โดยเฉพาะมือถือ Xiaomi, OPPO, vivo และ Samsung ที่มักปิดแอปเบื้องหลังเอง
- ตอนนี้ APK เซ็นด้วย debug key ถ้าจะแจกจ่ายจริงให้สร้าง keystore ของตัวเอง (**Build > Generate Signed App Bundle / APK**)
- ตั้งค่า `usesCleartextTraffic=true` ไว้เพื่อให้ทดสอบกับ http ได้ ถ้าใช้ https อย่างเดียว สามารถลบออกจาก `AndroidManifest.xml` ได้

## โครงสร้างโค้ด

```
app/src/main/java/th/bms/bpgateway/
├── BpCollectorService.kt  # สแกน / เชื่อมต่อ / subscribe 0x2A35 / RACP (แทน collector.py)
├── BpParser.kt            # ถอดรหัส 0x2A35 (แทน parser.py)
├── ApiClient.kt           # HTTP POST JSON (แทน api_client.py)
├── CsvStorage.kt          # CSV + คิวค้างส่ง (แทน storage.py)
├── AppSettings.kt         # ค่าตั้งค่า (แทน config.py)
├── MainActivity.kt        # หน้าจอหลัก + ขอสิทธิ์
├── CollectorBus.kt        # ส่งสถานะ/log จาก service ไปหน้าจอ
├── Permissions.kt
└── BootReceiver.kt        # เปิดบริการอัตโนมัติหลังเปิดเครื่อง
```
