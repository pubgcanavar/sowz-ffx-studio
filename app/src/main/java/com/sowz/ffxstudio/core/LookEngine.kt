package com.sowz.ffxstudio.core

/**
 * FfxParser'in cikardigi efekt ZINCIRINI gercek bir FFmpeg filtre grafigine
 * cevirir. FfxParser.kt'deki "DURUST SINIR" notunun dogal devami:
 *
 *   - Efekt TURU (hangi eklenti, ADBE match-name) .ffx'ten gercekten okunuyor.
 *   - O efektin AE icindeki TAM sayisal deger (ornegin kullanicinin surukledigi
 *     Hue derecesi, Unsharp Mask Amount/Radius/Threshold) .ffx'in kapali/
 *     belgesiz ikili kodlanisindan GUVENILIR sekilde cikarilamiyor (bu zaten
 *     FfxParser'da dogrulandi - "pard" chunk'lari sabit sablon, gercek
 *     surgu degeri degil).
 *
 * Bu yuzden LookEngine, HER BIR TANINAN efekt TURU icin, o efekt ailesinin
 * tipik/makul bir varsayilanini uygular - "klonlanmis" degil, o efekt turune
 * YAKIN bir render. Bu, UI'da ve README'de acikca "yaklasik render" olarak
 * etiketlenir; hicbir yerde "birebir ayni" denmez.
 *
 * Taninmayan/eslenmeyen efektler sessizce atlanmaz - UNMAPPED listesine
 * eklenir, UI bunlari "taninmadi, render'a eklenmedi" diye ayri gosterir.
 */
object LookEngine {

    data class PlannedEffect(
        val matchName: String,
        val displayName: String?,
        val filterDescription: String,
        val ffmpegFilter: String?, // null ise: taninan ama henuz filtre karsiligi yazilmamis efekt turu
    )

    data class Plan(
        val applied: List<PlannedEffect>,
        val unmapped: List<FfxParser.DetectedEffect>,
        val filterGraph: String // applied listesindeki ffmpegFilter'larin virgulle birlestirilmis hali
    )

    /** Bilinen ADBE/ucuncu-taraf match-name -> (aciklama, ffmpeg filtresi).
     *  Degerler kasten ORTA/DOGAL siddette secildi (bu referans .ffx'in adi
     *  "natural_cc" - asiri efekt kullaniciyi yaniltir). */
    private val KNOWN_EFFECTS: Map<String, Pair<String, String>> = mapOf(
        "ADBE HUE SATURATION" to (
            "Doygunluk hafif artirilir (gercek kaydirilan derece .ffx'ten okunamadigi icin dogal/orta bir varsayilan kullanilir)" to
                "eq=saturation=1.12"
        ),
        "ADBE Unsharp Mask2" to (
            "Hafif netlik/keskinlik (Unsharp Mask yaklasimi)" to
                "unsharp=5:5:0.6:5:5:0.0"
        ),
        "S_Sharpen" to (
            "Hafif netlik (Sapphire S_Sharpen yaklasimi - FFmpeg'de birebir karsiligi yok, unsharp ile yaklasilir)" to
                "unsharp=3:3:0.4:3:3:0.0"
        ),
        "ADBE Unsharp Mask" to (
            "Hafif netlik/keskinlik (Unsharp Mask yaklasimi)" to
                "unsharp=5:5:0.6:5:5:0.0"
        ),
        "MB LookSuite3" to (
            "Sinema-tarzi dogal renk dengesi yaklasimi (gercek Magic Bullet Looks LUT/curve verisi .ffx'ten okunamiyor - bu nedenle genel bir kontrast/ton egrisi uygulanir, o look'un BIREBIR ayni degildir)" to
                "eq=contrast=1.06:gamma=0.98:brightness=0.01"
        ),
        "ADBE CurvesCustom" to (
            "Hafif S-egrisi kontrast yaklasimi" to
                "curves=preset=medium_contrast"
        ),
        "ADBE Vibrance" to (
            "Hafif canlilik artisi" to
                "eq=saturation=1.08"
        ),
        "ADBE Color Balance (HLS)" to (
            "Hafif ton dengesi yaklasimi" to
                "eq=gamma=0.99"
        )
    )

    // Grup basligi / konteyner - gercek bir gorsel efekt degil, filtreye eklenmez.
    private val CONTAINER_NAMES = setOf("ADBE Effect Parade", "ADBE Group")

    fun plan(effects: List<FfxParser.DetectedEffect>): Plan {
        val applied = mutableListOf<PlannedEffect>()
        val unmapped = mutableListOf<FfxParser.DetectedEffect>()

        for (effect in effects) {
            if (effect.matchName in CONTAINER_NAMES) continue
            val known = KNOWN_EFFECTS[effect.matchName]
            if (known != null) {
                applied.add(PlannedEffect(effect.matchName, effect.displayName, known.first, known.second))
            } else {
                unmapped.add(effect)
            }
        }

        val filterGraph = if (applied.isEmpty()) {
            "null" // FFmpeg'in "hicbir filtre yok, dosyayi aynen gecir" filtresi
        } else {
            applied.joinToString(",") { it.ffmpegFilter!! }
        }

        return Plan(applied, unmapped, filterGraph)
    }
}
