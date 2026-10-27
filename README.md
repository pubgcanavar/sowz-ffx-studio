# Sowz FFX Studio

Android uygulaması gerçek bir Adobe After Effects `.ffx` preset dosyasını
(RIFX ikili format) okur, içindeki efekt zincirini çıkarır ve taninan
efektleri seçtiğin bir fotoğrafa veya videoya **gerçek FFmpeg render'i** ile
uygular.

## Ne yapar, ne yapmaz (dürüst sınır)

`.ffx` formatı Adobe tarafından hiç belgelenmemiştir. Bu uygulama elle
incelenmiş gerçek bir örnek dosya (`QWXD_free_natural_cc2_3.ffx` — içinde
Magic Bullet Looks + Hue/Saturation + S_Sharpen + Unsharp Mask bulunan
gerçek bir preset) üzerinden doğrulanmıştır:

- **Gerçekten okunan:** dosyanın RIFX chunk ağacı baytı baytına doğru
  yürünür (`core/FfxParser.kt`) ve efekt ZİNCİRİ - hangi eklentiler, hangi
  sırayla - güvenilir şekilde çıkarılır (`tdsi`/`tdmn`/`tdsn` chunk'ları).
- **GERÇEKTEN okunamayan:** her efektin AE içinde kullanıcının sürüklediği
  TAM sayısal değerleri (örn. tam Hue derecesi, Unsharp Mask Amount/Radius).
  Bu değerlerin ikili kodlanışı Adobe'nin kapalı, belgesiz formatıdır;
  incelenen `pard` chunk'larının sabit arayüz şablonu olduğu (her kopyada
  aynı), gerçek kaydırılmış değer olmadığı doğrulanmıştır.

Bu yüzden `core/LookEngine.kt`, tanıdığı HER efekt TÜRÜ için o ailenin
makul/doğal bir varsayılanını uygular (`eq`, `unsharp`, `curves` gibi gerçek
FFmpeg filtreleri) — **.ffx'in birebir klonu değil, o efekt türüne yakın bir
render**. Uygulama bunu her render öncesi ekranda açıkça gösterir; hiçbir
yerde "birebir aynı sonuç" iddia edilmez.

Tanınmayan efekt türleri sessizce atlanmaz — ekranda ayrı listelenir
("tanınmadı, render'a eklenmedi").

## Akış

1. **`.ffx` seç** — gerçek parse edilir, tespit edilen efektler listelenir.
2. **Fotoğraf veya video seç** — ikisi de desteklenir.
3. **Render Al** — gerçek `FFmpegKit` çağrısı (video: ses korunur, H.264/MP4;
   foto: tek kare JPEG). Çıktı `Pictures/SowzFfxStudio/` veya
   `Movies/SowzFfxStudio/` altına MediaStore üzerinden kaydedilir, oradan
   doğrudan paylaşılabilir.

## Mimari

- `core/FfxParser.kt` — RIFX chunk-ağacı okuyucusu (bağımsız, [rioam2/rifx](https://github.com/rioam2/rifx)'in genel RIFX yaklaşımıyla çapraz doğrulandı; AE'ye özgü `tdsi/tdmn/tdsn` semantiği bu projede ayrıca çözüldü).
- `core/LookEngine.kt` — tespit edilen efekt adlarını gerçek FFmpeg filtrelerine eşler.
- `core/RenderEngine.kt` — FFmpegKit çağrısını sarmalar (foto/video, ilerleme, iptal).
- `ui/MainActivity.kt` — tek ekranlı akış.

## Bağımlılıklar

- `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9` (GPL v3 — H.264/libx264
  encode için gerekli "-gpl" varyantı; orijinal `com.arthenica` paketi Maven
  Central'dan kaldırıldığı için topluluk forku kullanılıyor).

## Derleme

```
./gradlew assembleDebug
```

Release için imzalama yapılandırılmamıştır (bu uygulamada login/OAuth yok,
bu yüzden sabit bir keystore'a ihtiyaç yok) — CI, debug APK üretir.
