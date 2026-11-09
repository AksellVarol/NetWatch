# NetWatch — مانیتور وضعیت اینترنت ایران

اپلیکیشن اندروید (Kotlin) برای بررسی وضعیت اینترنت، تست پینگ و کیفیت اتصال، مانیتورینگ گیمینگ و تشخیص نوع اختلال — با چهار موتور داخلی مستقل: اندازه‌گیری، مانیتورینگ، تحلیل و تاریخچه.

- **نسخه فعلی: v2.7** (versionCode 22) — پکیج `com.aksell.netwatch` — حداقل اندروید ۵.۰ (API 21)، هدف اندروید ۱۴
- **دانلود مستقیم APK (نسخه ۲.۷):**

  [`NetWatch.apk`](https://raw.githubusercontent.com/AksellVarol/NetWatch/main/NetWatch.apk)

- حجم APK: **2,940,302 بایت**
- SHA-256 کامل فایل:

  ```
  a21d9804d3c89aef196b6da428634e2e374ec11c0eb47310da4f7fdc8b552e52
  ```

- سورس کامل نسخه ۲.۷ (۶۶ فایل، با ساختار پوشه‌ها):
  - [`NetWatch-source-v2.7.zip`](https://raw.githubusercontent.com/AksellVarol/NetWatch/main/NetWatch-source-v2.7.zip) — درخت کامل سورس (Kotlin + Manifest + layouts + values + drawable XML)
  - [`NetWatch-source-v2.7.txt`](https://raw.githubusercontent.com/AksellVarol/NetWatch/main/NetWatch-source-v2.7.txt) — همه فایل‌ها در یک فایل متنی برای خواندن آنلاین

## قابلیت‌ها

- بررسی ۴۰+ پروب: HTTPS، DoH، DoT واقعی (TLS Handshake به پورت 853 + کوئری DNS روی همان اتصال)، UDP DNS، ICMP Ping، TCP:443، TLS، IPv4/IPv6 — داخلی (آپارات، دیجی‌کالا، دیوار، تلوبیون، ایرنا) و بین‌المللی
- Network Quality Score با **Coverage Gate ۷۰٪**: اگر پوشش داده کمتر از ۷۰٪ باشد، عدد کل نمایش داده نمی‌شود (Insufficient Data) — مؤلفه ناقص N/A است، نه صفر
- تاریخچه در **SQLite** (نگهداری ۳۰ روزه، مهاجرت داده قدیمی) با فیلترهای ۲۴ ساعت / ۷ روز / ۳۰ روز روی داده و آمار؛ نمونه ناموفق در نمودار شکاف است، نه صفر
- مانیتورینگ پس‌زمینه سبک (Foreground Service): فقط ۸ پروب سبک در هر چرخه؛ تست کامل ۴۰+ پروب فقط دستی است
- Live Ping: یک پینگ واقعی ICMP هر ۵ ثانیه فقط وقتی مانیتورینگ روشن است؛ برچسب Live فقط وقتی Heartbeat سرویس تازه است (۱۵ ثانیه اخیر)
- مانیتورینگ گیمینگ: پینگ واقعی با باینری سیستمی، Jitter، Packet Loss، Spike و Burst، مقایسه ساعت‌ها و روزها؛ امتیاز گیمینگ بدون Bufferbloat برابر null است (امتیاز فرضی ندارد)؛ Bufferbloat فقط با دکمه خودش و هشدار داده (سقف ۵۰ مگابایت)
- DNS Benchmark با دورهای قابل تنظیم (۵/۲۰/۵۰)، Median و P95
- Multi-Host با سه روش ICMP + TCP + DNS برای هر مقصد (برای IP مستقیم، DNS مقدار N/A دارد، نه صفر)
- تشخیص نوع اختلال (DNS / Gateway / SNI-TLS / مسیر بین‌الملل / مقصد خاص) فقط با شواهد کافی، وگرنه Unknown
- خروجی CSV/JSON/PDF فارسی، Backup/Restore نسخه‌دار و معتبر (فایل خراب با دلیل رد می‌شود و داده قبلی را از بین نمی‌برد)
- NETWATCH LAB: تست‌های Unit و Integration داخل خود اپ

## معماری موتورها (نسخه ۲.۷)

| موتور | مسئولیت |
|---|---|
| NetworkEngine | تنها API اندازه‌گیری (ping ،tcp ،tls ،dot ،dns ،quic ،traceroute) + گارد Single-flight |
| MonitoringEngine | سیاست زمان‌بندی چرخه و Live Ping (توابع خالص و تست‌پذیر) + گارد Single-flight مشترک Activity و سرویس |
| AnalyticsEngine | تنها جای امتیاز و آمار (Quality ،Gaming Score ،Burst/Chart Stats ،Bufferbloat Grade) |
| HistoryEngine | تنها API ذخیره و بازیابی تاریخچه (SQLite) |

بدون وابستگی جدید: بدون Coroutines، بدون Room، بدون Chromium، بدون موتور بازی یا هوش مصنوعی. همه عملیات شبکه روی یک Executor مدیریت‌شده (۴ نخ) اجرا می‌شوند؛ هیچ عملیات شبکه‌ای روی Main Thread نیست.

## تغییرات نسخه ۲.۶ به ۲.۷

| فایل | تغییر |
|---|---|
| `NetworkEngine.kt` (جدید) | موتور اندازه‌گیری + Single-flight؛ جلوگیری از اجرای تکراری و قاطی‌شدن ICMP با TCP |
| `MonitoringEngine.kt` (جدید) | موتور زمان‌بندی + گاردها؛ یک منبع حقیقت برای «چه وقت اجرا شود» |
| `AnalyticsEngine.kt` (جدید) | موتور امتیاز و آمار؛ یک‌جا شدن امتیازها و آمارها |
| `HistoryEngine.kt` (جدید) | موتور تاریخچه؛ یک فراخوانی برای ثبت هر بررسی |
| `Gaming.kt` | دانلود Bufferbloat از Thread خام به Executor مدیریت‌شده (قابل لغو) |
| `GamingActivity.kt` ،`MainActivity.kt` ،`MonitorService.kt` ،`NetWatchWidget.kt` ،`LabActivity.kt` | همه فراخوانی‌ها از طریق موتورها؛ `busy` سرویس به گارد `MonitoringEngine` منتقل شد |
| `NetExecutor.kt` | ساخت Handler اصلی Lazy شد (تست‌های JVM می‌توانند از Pool استفاده کنند) |
| `Lab.kt` | ۸ تست جدید موتورها |
| `AndroidManifest.xml` | versionCode 22 / versionName 2.7 |

حجم APK از ۲٬۹۲۳٬۹۱۸ بایت (۲.۶) به ۲٬۹۴۰٬۳۰۲ بایت (۲.۷) رسید: ‎+۱۶٬۳۸۴ بایت (‎+۰٫۵۶٪) — فقط چهار فایل نازک موتور.

## گزارش تست

**تست‌های Unit روی JVM: ۴۹ از ۴۹ PASS (FAIL 0)** — شامل ۴۱ تست نسخه ۲.۶ و ۸ تست جدید موتورها (Single-flight، زمان‌بندی چرخه و Live Ping، برابری AnalyticsEngine با توابع مستقیم). تست Unit جایگزین تست واقعی شبکه نیست.

**تست‌های On-Device (روی گوشی واقعی): NOT TESTED — صادقانه اعلام می‌شود.** موارد زیر روی دستگاه واقعی اجرا نشده‌اند و نباید به‌عنوان تست‌شده تلقی شوند:

- نصب APK روی گوشی کاربر؛ Live Ping روی گوشی؛ به‌روزرسانی ویجت روی لانچر واقعی
- همه تست‌های On-Device در LAB (History Round-trip، Backup Round-trip، Corrupt Restore، Widget Consistency) و بخش‌های Integration / Cross / Stress روی دستگاه
- استرس ۱/۶/۲۴ ساعته؛ مصرف باتری و CPU درازمدت؛ نشت حافظه؛ رندر PDF فارسی روی گوشی
- DoT ،IPv6 و Captive Portal روی شبکه واقعی ایران؛ رسم نمودار و چرخش صفحه روی گوشی واقعی
- مهاجرت داده قدیمی ۳ فیلدی به SQLite بعد از به‌روزرسانی از نسخه ۲.۵

## محدودیت‌های صادقانه

- IP سرور واقعی بازی‌ها (مثل CODM) منتشر نمی‌شود؛ تست گیمینگ روی مقصدهای عمومی (Anycast) انجام می‌شود، نه سرور بازی.
- QUIC واقعی (Handshake کامل) پیاده نشده؛ وضعیت QUIC نامشخص (Unknown) گزارش می‌شود.
- NetBlocks API عمومی رایگان ندارد؛ فقط سایت آن بررسی می‌شود.
- نوع NAT و ترتیب بسته‌ها (Packet Reordering) بدون STUN یا دسترسی Root قابل اندازه‌گیری نیست.
- ضبط ترافیک بازی نیاز به VPNService دارد و فعال نیست.

## نصب

APK را دانلود و نصب کنید. برای مانیتورینگ پس‌زمینه، در تنظیمات گوشی NetWatch را از بهینه‌سازی باتری (Battery Optimization) و Sleeping Apps سامسونگ مستثنا کنید.
