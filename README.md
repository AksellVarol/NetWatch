# NetWatch — مانیتور وضعیت اینترنت ایران

اپلیکیشن اندروید (Kotlin) برای بررسی وضعیت اینترنت، تست پینگ و کیفیت اتصال، مانیتورینگ گیمینگ و تشخیص نوع اختلال — با چهار موتور داخلی مستقل: اندازه‌گیری، مانیتورینگ، تحلیل و تاریخچه.

- **نسخه فعلی: v2.8** (versionCode 23) — پکیج `com.aksell.netwatch` — حداقل اندروید ۵.۰ (API 21)، هدف اندروید ۱۴
- **دانلود مستقیم APK (نسخه ۲.۸):**

  [`NetWatch.apk`](https://raw.githubusercontent.com/AksellVarol/NetWatch/main/NetWatch.apk)

- حجم APK: **2,956,686 بایت**
- SHA-256 کامل فایل:

  ```
  461f182429f64e209d80d98db862e7f94a91c9ea712a31175a60d38e56057a12
  ```

- سورس کامل نسخه ۲.۸ (۶۶ فایل، با ساختار پوشه‌ها):
  - [`NetWatch-source-v2.8.zip`](https://raw.githubusercontent.com/AksellVarol/NetWatch/main/NetWatch-source-v2.8.zip) — درخت کامل سورس (Kotlin + Manifest + layouts + values + drawable XML)
  - [`NetWatch-source-v2.8.txt`](https://raw.githubusercontent.com/AksellVarol/NetWatch/main/NetWatch-source-v2.8.txt) — همه فایل‌ها در یک فایل متنی برای خواندن آنلاین

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

## تغییرات نسخه ۲.۷ به ۲.۸

| فایل | تغییر |
|---|---|
| `History.kt` | enum جدید `HistoryRange` (مدت هر بازه فقط به میلی‌ثانیه، یک منبع حقیقت) + مستندسازی `samples()` به‌عنوان سازگاری قدیمی (فقط Triple، بدون Ping/Jitter/Loss) |
| `MainActivity.kt` | `refreshHistory(HistoryRange)` — رفع باگ فیلتر: «۷ روز» قبلاً فقط ۷ ساعت و «۳۰ روز» فقط ۳۰ ساعت نشان می‌داد؛ حذف پرچم محلی `checking` و استفاده از گارد مشترک |
| `Probes.kt` | `runAll`/`runLight` دیگر Pool تازه نمی‌سازند؛ همه پروب‌ها روی NetExecutor مشترک با مهلت مشترک و لغو واقعی اجرا می‌شوند |
| `NetworkEngine.kt` | `submitSingle` بازنویسی شد (FutureTask + putIfAbsent): ثبت اتمی، حذف امن، برگرداندن `Future?` به‌جای Boolean؛ `cancelSingle` اول ورودی را حذف می‌کند |
| `MonitoringEngine.kt` | گارد واحد برای همه مسیرها (دستی، خودکار، سرویس) از طریق `submitSingle`؛ حذف AtomicBooleanهای جدا |
| `MonitorService.kt` | چرخه پس‌زمینه و Live Ping از همان گارد مشترک استفاده می‌کنند؛ حذف `finally` دستی |
| `Lab.kt` | ۷ تست جدید (۵۶ تست) + بازنویسی تست‌های Single-flight برای API جدید |
| `LabActivity.kt` | تست On-Device فیلتر بازه با Timestamp ثابت (۴ ردیف) |
| `AndroidManifest.xml` | versionCode 23 / versionName 2.8 |

حجم APK از ۲٬۹۴۰٬۳۰۲ بایت (۲.۷) به ۲٬۹۵۶٬۶۸۶ بایت (۲.۸) رسید: ‎+۱۶٬۳۸۴ بایت (‎+۰٫۵۵٪).

**نکته صادقانه درباره راه‌اندازی مجدد با دکمه Check:** اگر در حین اجرای یک بررسی، دکمه Check دوباره زده شود، بررسی قبلی لغو و بررسی جدید شروع می‌شود (همان رفتار نسخه‌های قبل). گارد مشترک جلوی اجرای هم‌زمان دو بررسی مستقل را می‌گیرد، نه جایگزینی دستی یک بررسی با بررسی دیگر.

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

**تست‌های Unit روی JVM: ۵۶ از ۵۶ PASS (FAIL 0)** — شامل ۴۹ تست نسخه ۲.۷ و ۷ تست جدید نسخه ۲.۸: مدت و cutoff هر سه بازه با زمان ثابت، انتخاب داده با Timestamp ثابت (۲۴h=۱، ۷d=۲، ۳۰d=۳)، Race Condition در `submitSingle` (۸ نخ هم‌زمان → دقیقاً ۱ برنده)، عدم هم‌زمانی Activity و Service روی گارد مشترک، ۵ چرخه متوالی بدون Pool اضافی (نخ‌های `netwatch-net` همان ۴ تا)، و Cancellation با آزادسازی گارد. تست Unit جایگزین تست واقعی شبکه نیست.

نکته: تست چرخه‌ها در JVM پروب‌ها را با شبکه واقعی اجرا می‌کند، ولی در این محیط سندباکس پروب‌ها سریع ناموفق می‌شوند؛ آن‌چه تست می‌سنجد فقط تعداد نخ‌هاست، نه موفقیت پروب‌ها — و نتیجه آن PASS است.

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
