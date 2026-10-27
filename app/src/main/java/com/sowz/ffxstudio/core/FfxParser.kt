package com.sowz.ffxstudio.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GERCEK bir RIFX (big-endian RIFF) chunk-ağacı okuyucusu. Adobe'nin .ffx/.aep
 * dosya formatı Adobe tarafından hiç belgelenmemiştir; bu parser, gerçek bir
 * örnek .ffx dosyası (bu projenin referansı: "QWXD_free_natural_cc2_3.ffx",
 * icinde MB LookSuite3 + Hue/Saturation + S_Sharpen + Unsharp Mask2 bulunan
 * gercek bir preset) hex/chunk seviyesinde elle incelenerek dogrulanmıştır.
 *
 * DÜRÜST SINIR (bilerek, gizlenmeden): bu parser efekt ZİNCİRİNİ (hangi
 * eklentiler, hangi sırayla) güvenilir şekilde çıkarır - "tdsi/tdmn" chunk'ları
 * her efektin ADBE match-name'ini (ör. "ADBE HUE SATURATION") taşır ve bu
 * doğrulanmıştır. AMA her efektin kullanıcının gerçekte sürüklediği SAYISAL
 * değerlerini (ör. tam Hue derecesi, tam Unsharp Mask Amount/Radius/Threshold)
 * ÇIKARMAZ - bu değerlerin ikili kodlanışı Adobe'nin kendi kapalı, belgesiz
 * formatıdır; "pard" chunk'ları incelendiğinde bunların efektin SABİT arayüz
 * şablonu (her kopyada aynı) olduğu, gerçek kaydırılan değer olmadığı
 * görüldü. Bu yüzden LookEngine, her efekt TÜRÜ için kendi makul/ayarlanabilir
 * varsayılanlarını kullanır - .ffx'ten "klonlanmış" değil, o efekt türüne
 * YAKIN bir render üretir. Bu sınır UI'da ve README'de açıkça yazılacak.
 */
object FfxParser {

    data class DetectedEffect(
        val matchName: String,
        val displayName: String?
    )

    data class ParseResult(
        val isValidRifx: Boolean,
        val formType: String?,
        val effects: List<DetectedEffect>,
        val rawEffectCount: Int,
        val errorMessage: String? = null
    )

    private const val TAG_RIFX = "RIFX"
    private const val TAG_LIST = "LIST"
    private const val TAG_TDSI = "tdsi" // tek bir efekt/adim tanimlayici grup
    private const val TAG_TDMN = "tdmn" // match name (ör. "ADBE HUE SATURATION")
    private const val TAG_TDSN = "tdsn" // display name (Utf8 ön ekli)

    // AE'nin kendi "ADBE Effect Parade" yolu bir grup başlığıdır, gerçek efekt değildir.
    private val IGNORED_MATCH_NAMES = setOf("ADBE Effect Parade", "ADBE Group")

    fun parse(stream: InputStream): ParseResult {
        val bytes = try {
            stream.readBytes()
        } catch (e: Exception) {
            return ParseResult(false, null, emptyList(), 0, "Dosya okunamadi: ${e.message}")
        }
        return parse(bytes)
    }

    fun parse(data: ByteArray): ParseResult {
        if (data.size < 12) {
            return ParseResult(false, null, emptyList(), 0, "Dosya cok kucuk - gecerli bir .ffx degil.")
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val riffTag = String(data, 0, 4, Charsets.US_ASCII)
        if (riffTag != TAG_RIFX) {
            return ParseResult(false, null, emptyList(), 0, "RIFX imzasi bulunamadi (bulunan: '$riffTag') - bu dosya bir Adobe .ffx/.aep degil.")
        }
        val formType = try {
            String(data, 8, 4, Charsets.US_ASCII)
        } catch (e: Exception) {
            null
        }

        val matchNames = linkedSetOf<String>()
        val displayByMatch = HashMap<String, String?>()
        var rawEffectGroupCount = 0

        // tdsi gruplarini bul: icinde tdmn (match name) olan her grup bir "adim" -
        // bu adim efekt parade'i (grup basligi) veya gercek bir efekt olabilir.
        // Hemen ardindan gelen tdsn (ayni derinlikte, bir sonraki kardes chunk)
        // varsa onu display name olarak eslestiriyoruz - gercek dosyada bu desen
        // (tdsi[tdix,tdmn] ... tdsn) tutarli sekilde gorulmustur.
        walk(buf, 0, data.size) { path, tag, offset, size ->
            if (tag == TAG_TDSI) {
                val groupEnd = minOf(offset + 8 + size, data.size)
                val matchName = findChildTdmn(buf, offset + 12, groupEnd)
                if (matchName != null && matchName !in IGNORED_MATCH_NAMES) {
                    rawEffectGroupCount++
                    matchNames.add(matchName)
                    val display = findFollowingTdsn(buf, offset, data.size)
                    if (display != null) displayByMatch[matchName] = display
                }
            }
        }

        val effects = matchNames.map { DetectedEffect(it, displayByMatch[it]) }
        return ParseResult(true, formType, effects, rawEffectGroupCount)
    }

    /** RIFX/LIST konteynerlerini gercekten yurur (text arama degil, byte-dogru
     *  chunk boyutlariyla ilerler) - cift byte hizalama (RIFF kuralı) dahil. */
    private fun walk(
        buf: ByteBuffer,
        offset: Int,
        limit: Int,
        depth: Int = 0,
        onChunk: (path: String, tag: String, offset: Int, size: Int) -> Unit
    ) {
        if (offset + 8 > limit || depth > 40) return
        val tag = try {
            String(buf.array(), offset, 4, Charsets.US_ASCII)
        } catch (e: Exception) {
            return
        }
        val size = try {
            buf.getInt(offset + 4)
        } catch (e: Exception) {
            return
        }
        if (size < 0) return
        onChunk(tag, tag, offset, size)

        val isContainer = tag == TAG_RIFX || tag == TAG_LIST
        if (isContainer) {
            val bodyStart = offset + 8
            if (bodyStart + 4 > limit) return
            var child = bodyStart + 4 // 4 baytlik "list type"/"form type" atlanir
            val end = minOf(bodyStart + size, limit)
            var guard = 0
            while (child + 8 <= end && guard < 200_000) {
                guard++
                val childSize = try { buf.getInt(child + 4) } catch (e: Exception) { break }
                if (childSize < 0) break
                walk(buf, child, limit, depth + 1, onChunk)
                var advance = 8 + childSize
                if (advance % 2 == 1) advance += 1
                if (advance <= 0) break
                child += advance
            }
        }
    }

    private fun findChildTdmn(buf: ByteBuffer, start: Int, end: Int): String? {
        var offset = start
        var guard = 0
        while (offset + 8 <= end && guard < 1000) {
            guard++
            val tag = try { String(buf.array(), offset, 4, Charsets.US_ASCII) } catch (e: Exception) { break }
            val size = try { buf.getInt(offset + 4) } catch (e: Exception) { break }
            if (size < 0) break
            if (tag == TAG_TDMN) {
                return readCString(buf.array(), offset + 8, size)
            }
            if (tag == TAG_LIST || tag == TAG_RIFX) {
                val nested = findChildTdmn(buf, offset + 12, minOf(offset + 8 + size, end))
                if (nested != null) return nested
            }
            var advance = 8 + size
            if (advance % 2 == 1) advance += 1
            if (advance <= 0) break
            offset += advance
        }
        return null
    }

    /** Bir tdsi grubundan hemen sonraki kardes "tdsn" (Utf8 ön ekli display name)
     *  chunk'ini arar - sadece ayni ebeveyn konteyner icinde, kisa bir pencerede. */
    private fun findFollowingTdsn(buf: ByteBuffer, tdsiOffset: Int, limit: Int): String? {
        val tdsiSize = try { buf.getInt(tdsiOffset + 4) } catch (e: Exception) { return null }
        var advance = 8 + tdsiSize
        if (advance % 2 == 1) advance += 1
        var offset = tdsiOffset + advance
        var guard = 0
        while (offset + 8 <= limit && guard < 5) {
            guard++
            val tag = try { String(buf.array(), offset, 4, Charsets.US_ASCII) } catch (e: Exception) { return null }
            val size = try { buf.getInt(offset + 4) } catch (e: Exception) { return null }
            if (size < 0) return null
            if (tag == TAG_TDSN) {
                return readUtf8Prefixed(buf.array(), offset + 8, size)
            }
            if (tag != TAG_TDSI) return null // beklenmeyen desen, aramayi birak
            var adv = 8 + size
            if (adv % 2 == 1) adv += 1
            offset += adv
        }
        return null
    }

    private fun readCString(data: ByteArray, start: Int, maxLen: Int): String? {
        if (start < 0 || start >= data.size) return null
        var end = start
        val hardLimit = minOf(start + maxLen, data.size)
        while (end < hardLimit && data[end] != 0.toByte()) end++
        if (end <= start) return null
        return try {
            String(data, start, end - start, Charsets.US_ASCII).trim()
        } catch (e: Exception) {
            null
        }
    }

    /** "Utf8\x00\x00\x00<len><bytes>" bicimindeki AE string alanlarini okur. */
    private fun readUtf8Prefixed(data: ByteArray, start: Int, size: Int): String? {
        if (start + 8 > data.size) return null
        val marker = try { String(data, start, 4, Charsets.US_ASCII) } catch (e: Exception) { null }
        if (marker != "Utf8") return null
        val len = try {
            ByteBuffer.wrap(data, start + 4, 4).order(ByteOrder.BIG_ENDIAN).int
        } catch (e: Exception) {
            return null
        }
        val strStart = start + 8
        if (len <= 0 || strStart + len > data.size || strStart + len > start + size) return null
        return try {
            String(data, strStart, len, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
