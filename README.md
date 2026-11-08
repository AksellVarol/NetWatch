# NetWatch — مانیتور وضعیت اینترنت ایران

اپلیکیشن اندروید (Kotlin) برای بررسی وضعیت اینترنت، تست پینگ و کیفیت اتصال، مانیتورینگ گیمینگ و تشخیص نوع اختلال.

- **دانلود مستقیم APK (نسخه ۲.۰):**
  [`NetWatch.apk`](https://raw.githubusercontent.com/AksellVarol/NetWatch/main/NetWatch.apk)
- پکیج: `com.aksell.netwatch` — حداقل اندروید ۵.۰ (API 21)، هدف اندروید ۱۴
- نسخه فعلی: **v2.0** (versionCode 12)

## قابلیت‌ها

- بررسی ۴۰+ پروب: HTTPS، DoH، DoT، UDP DNS، ICMP Ping، TCP:443، TLS، IPv4/IPv6 — داخلی (آپارات، دیجی‌کالا، دیوار، تلوبیون، ایرنا) و بین‌المللی
- Network Quality Score با **Coverage Gate ۷۰٪**: اگر پوشش داده کمتر از ۷۰٪ باشد، عدد کل نمایش داده نمی‌شود (Insufficient Data) — مؤلفه ناقص N/A است، نه صفر
- مانیتورینگ گیمینگ: پینگ واقعی با باینری سیستمی، Jitter، Packet Loss، Spike و Burst، مقایسه ساعت‌ها و روزها
- DNS Benchmark با دورهای قابل تنظیم (۵/۲۰/۵۰)، Median و P95
- Multi-Host با سه روش ICMP + TCP + DNS برای هر مقصد
- تشخیص نوع اختلال (DNS / Gateway / SNI-TLS / مسیر بین‌الملل / مقصد خاص) فقط با شواهد کافی، وگرنه Unknown
- مانیتورینگ پس‌زمینه (Foreground Service)، تاریخچه قطعی‌ها، خروجی CSV/JSON/PDF فارسی، Backup/Restore
- NETWATCH LAB: تست‌های Unit و Integration داخل خود اپ — نتیجه ۲۳/۲۳ PASS در تست JVM منطق (تست Unit جایگزین تست واقعی شبکه نیست)

## محدودیت‌های صادقانه

- IP سرور واقعی بازی‌ها (مثل CODM) منتشر نمی‌شود؛ تست گیمینگ روی مقصدهای عمومی (Anycast) انجام می‌شود، نه سرور بازی.
- QUIC واقعی (Handshake کامل) پیاده نشده؛ وضعیت QUIC نامشخص (Unknown) گزارش می‌شود.
- NetBlocks API عمومی رایگان ندارد؛ فقط سایت آن بررسی می‌شود.

## نصب

APK را دانلود و نصب کنید. برای مانیتورینگ پس‌زمینه، در تنظیمات گوشی NetWatch را از بهینه‌سازی باتری (Battery Optimization) و Sleeping Apps سامسونگ مستثنا کنید.
