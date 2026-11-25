package com.synctra.app.ui.i18n

import com.synctra.app.domain.model.ConfidenceLevel
import com.synctra.app.domain.model.DriftCauseKey
import com.synctra.app.domain.model.FlagReason
import java.util.Locale
import kotlin.math.abs

enum class AppLanguage(val code: String, val badge: String) {
    ID("id", "ID"),
    EN("en", "EN");

    companion object {
        fun fromCode(code: String): AppLanguage = if (code == "en") EN else ID
    }
}

data class AppStrings(
    val language: AppLanguage,
    val subtitleTagline: String,
    val filmLabel: String,
    val filmPlaceholder: String,
    val probingAudio: String,
    val audioTrackLabel: String,
    val availableCount: (Int) -> String,
    val subtitleLabel: String,
    val subtitlePlaceholder: String,
    val pickAction: String,
    val changeAction: String,
    val dialogsMeta: (Int) -> String,
    val filteredMeta: (Int) -> String,
    val checkSyncCta: String,
    val recheckSyncCta: String,
    val cancelAction: String,
    val closeAction: String,
    val offsetTitle: String,
    val syncedStatus: String,
    val manualNudgeInfo: (String, String) -> String,
    val guidanceSynced: (String) -> String,
    val guidanceTooEarly: (String, String) -> String,
    val guidanceTooLate: (String, String) -> String,
    val copySecAction: (String) -> String,
    val copyMsAction: String,
    val saveSrtAction: String,
    val copiedToast: (String) -> String,
    val savedSrtToast: (Int, Int) -> String,
    val driftSevereTitle: String,
    val driftMildTitle: String,
    val driftSpreadInfo: (String) -> String,
    val testListenTitle: String,
    val testListenAudioMeta: (String) -> String,
    val loadingAudio: String,
    val pressPlayHint: String,
    val stopContentDesc: String,
    val playContentDesc: String,
    val resetLabel: String,
    val historyTitle: String,
    val clearHistory: String,
    val exportTitle: String,
    val exportModeLabel: String,
    val exportModeConstant: (String) -> String,
    val exportModeDrift: String,
    val exportAdsSectionTitle: (Int) -> String,
    val exportAdsHint: String,
    val exportNoAdsDetected: String,
    val exportSelectAllAds: String,
    val exportKeepAllAds: String,
    val exportSdhToggleTitle: (Int) -> String,
    val exportSdhToggleSubtitle: String,
    val exportSummary: (Int, Int) -> String,
    val exportConfirmCta: String,
    val stepPreparing: String,
    val stepPreparingDetail: String,
    val stepSampling: (Int, Int) -> String,
    val stepCorrelating: String,
    val stepCorrelatingDetail: String,
    val stepExpanding: String,
    val stepExpandingDetail: String,
    val stepExtraSampling: String,
    val stepExtraSamplingDetail: String,
    val encodingLabel: String,
    val encodingPreviewLabel: String,
    val pooledEstimateBadge: String,
    val copyDiagnosticsAction: String,
    val diagnosticsCopiedLabel: String,
    val verifySyncedBadge: (String) -> String,
    val verifyOffsetBadge: (String) -> String,
    val verifyDriftBadge: String,
    val verifyRecheckAction: String,
) {
    fun confidenceLabel(level: ConfidenceLevel): String = when (language) {
        AppLanguage.ID -> when (level) {
            ConfidenceLevel.HIGH -> "Tinggi"
            ConfidenceLevel.MEDIUM -> "Sedang"
            ConfidenceLevel.LOW -> "Rendah"
        }
        AppLanguage.EN -> when (level) {
            ConfidenceLevel.HIGH -> "High"
            ConfidenceLevel.MEDIUM -> "Medium"
            ConfidenceLevel.LOW -> "Low"
        }
    }

    fun driftCauseLabel(key: DriftCauseKey?, fallback: String?): String? {
        if (key == null) return fallback
        return when (language) {
            AppLanguage.ID -> when (key) {
                DriftCauseKey.FPS_23976_VS_25 -> "Kemungkinan beda FPS (23.976 vs 25 fps)"
                DriftCauseKey.FPS_23976_VS_24 -> "Kemungkinan beda FPS tipis (23.976 vs 24 fps)"
                DriftCauseKey.DIFFERENT_CUT -> "Kemungkinan beda versi rilis (Theatrical vs Extended / cut berbeda)"
                DriftCauseKey.GRADUAL_SHIFT -> "Offset bergeser bertahap — cek beberapa titik di Test dengar"
            }
            AppLanguage.EN -> when (key) {
                DriftCauseKey.FPS_23976_VS_25 -> "Likely FPS mismatch (23.976 vs 25 fps)"
                DriftCauseKey.FPS_23976_VS_24 -> "Likely slight FPS mismatch (23.976 vs 24 fps)"
                DriftCauseKey.DIFFERENT_CUT -> "Likely different release cut (Theatrical vs Extended)"
                DriftCauseKey.GRADUAL_SHIFT -> "Offset shifts gradually — check multiple scenes in Audio check"
            }
        }
    }

    fun flagReasonLabel(reason: FlagReason): String = when (language) {
        AppLanguage.ID -> when (reason) {
            FlagReason.URL -> "URL / Situs"
            FlagReason.CREDIT -> "Kredit / Iklan"
            FlagReason.LONG_BANNER -> "Banner panjang"
            FlagReason.SDH -> "SDH / Musik"
        }
        AppLanguage.EN -> when (reason) {
            FlagReason.URL -> "URL / Site"
            FlagReason.CREDIT -> "Credit / Ad"
            FlagReason.LONG_BANNER -> "Long banner"
            FlagReason.SDH -> "SDH / Music"
        }
    }

    fun windowShortLabel(index: Int, total: Int): String {
        if (total > 3) return "#${index + 1}"
        if (total <= 1) return if (language == AppLanguage.ID) "Tengah" else "Middle"
        if (total == 2) {
            return when (index) {
                0 -> if (language == AppLanguage.ID) "Awal" else "Early"
                else -> if (language == AppLanguage.ID) "Akhir" else "Late"
            }
        }
        return when (index) {
            0 -> if (language == AppLanguage.ID) "Awal" else "Early"
            1 -> if (language == AppLanguage.ID) "Tengah" else "Mid"
            else -> if (language == AppLanguage.ID) "Akhir" else "Late"
        }
    }

    fun formatGuidance(effectiveOffsetMs: Long, formattedSec: String, formattedMs: String): String {
        if (abs(effectiveOffsetMs) <= 150L) return guidanceSynced(formattedMs)
        val absSecStr = String.format(Locale.US, "%.2f", abs(effectiveOffsetMs) / 1000.0)
        return if (effectiveOffsetMs > 0) {
            guidanceTooEarly(absSecStr, formattedSec)
        } else {
            guidanceTooLate(absSecStr, formattedSec)
        }
    }
}

object AppStringsProvider {

    fun get(language: AppLanguage): AppStrings = when (language) {
        AppLanguage.ID -> indonesian
        AppLanguage.EN -> english
    }

    private val indonesian = AppStrings(
        language = AppLanguage.ID,
        subtitleTagline = "Cek selisih detik subtitle & film",
        filmLabel = "Film",
        filmPlaceholder = "Pilih video (.mp4, .mkv, .avi, .webm)",
        probingAudio = "Membaca info audio...",
        audioTrackLabel = "Jalur audio",
        availableCount = { n -> "$n tersedia" },
        subtitleLabel = "Subtitle",
        subtitlePlaceholder = "Pilih subtitle (.srt, .ass, .vtt)",
        pickAction = "Pilih",
        changeAction = "Ganti",
        dialogsMeta = { n -> "$n dialog" },
        filteredMeta = { n -> "$n iklan/SDH difilter" },
        checkSyncCta = "Cek sinkronisasi",
        recheckSyncCta = "Cek ulang",
        cancelAction = "Batalkan",
        closeAction = "Tutup",
        offsetTitle = "Offset subtitle",
        syncedStatus = "Sinkron",
        manualNudgeInfo = { nudge, orig -> "Manual ${nudge}s (awal ${orig}s)" },
        guidanceSynced = { ms -> "Sudah cocok dengan audio (selisih $ms ms)." },
        guidanceTooEarly = { absSec, signed -> "Subtitle terlalu cepat ${absSec}s. Atur delay ${signed}s di VLC / MX Player / Nova." },
        guidanceTooLate = { absSec, signed -> "Subtitle terlambat ${absSec}s. Atur delay ${signed}s di VLC / MX Player / Nova." },
        copySecAction = { sec -> "Salin ${sec}s" },
        copyMsAction = "Salin ms",
        saveSrtAction = "Simpan .srt",
        copiedToast = { v -> "Disalin: $v" },
        savedSrtToast = { kept, removed ->
            if (removed > 0) "Tersimpan ($kept baris, $removed baris dihapus)" else "Tersimpan ($kept baris)"
        },
        driftSevereTitle = "Drift / beda FPS terdeteksi",
        driftMildTitle = "Ada sedikit pergeseran antar bagian",
        driftSpreadInfo = { spread -> "Selisih antar titik sampel ${spread}s. Gunakan Simpan .srt untuk koreksi drift." },
        testListenTitle = "Test dengar",
        testListenAudioMeta = { track -> "Audio: $track · 10 detik" },
        loadingAudio = "Memuat audio...",
        pressPlayHint = "Tekan play untuk cocokkan suara & teks",
        stopContentDesc = "Hentikan",
        playContentDesc = "Putar",
        resetLabel = "Reset",
        historyTitle = "Riwayat terakhir",
        clearHistory = "Hapus",
        exportTitle = "Simpan subtitle (.srt)",
        exportModeLabel = "Mode koreksi waktu",
        exportModeConstant = { sec -> "Offset tetap (${sec}s)" },
        exportModeDrift = "Offset + koreksi drift FPS",
        exportAdsSectionTitle = { n -> "Terdeteksi iklan / kredit ($n)" },
        exportAdsHint = "Centang baris yang ingin dihapus. Hilangkan centang jika itu dialog asli.",
        exportNoAdsDetected = "Tidak ada baris iklan atau kredit yang terdeteksi.",
        exportSelectAllAds = "Hapus semua",
        exportKeepAllAds = "Pertahankan semua",
        exportSdhToggleTitle = { n -> "Hapus teks efek suara / SDH ($n)" },
        exportSdhToggleSubtitle = "Contoh: [musik], (tertawa), ♪ ... ♪ (nonaktif secara default)",
        exportSummary = { kept, removed -> "$kept baris disimpan · $removed dihapus" },
        exportConfirmCta = "Pilih lokasi & simpan .srt",
        stepPreparing = "Menyiapkan analisis",
        stepPreparingDetail = "Membaca metadata audio & menyaring iklan subtitle",
        stepSampling = { cur, total -> "Sampel audio $cur / $total" },
        stepCorrelating = "Mencocokkan pola dialog",
        stepCorrelatingDetail = "Korelasi suara vs subtitle & deteksi drift FPS",
        stepExpanding = "Memperluas pencarian (±2 menit)",
        stepExpandingDetail = "Mencari kemungkinan selisih besar hingga ±120 detik",
        stepExtraSampling = "Mengambil sampel tambahan",
        stepExtraSamplingDetail = "Akurasi awal rendah — menambah titik cek agar lebih pasti",
        encodingLabel = "Encoding",
        encodingPreviewLabel = "Pratinjau 3 baris awal",
        pooledEstimateBadge = "Estimasi gabungan",
        copyDiagnosticsAction = "Diagnostik",
        diagnosticsCopiedLabel = "Diagnostik",
        verifySyncedBadge = { ms -> "✓ File .srt terverifikasi (sisa $ms ms)" },
        verifyOffsetBadge = { sec -> "⚠ File .srt masih meleset ${sec}s" },
        verifyDriftBadge = "⚠ Drift masih terdeteksi di file .srt",
        verifyRecheckAction = "Cek ulang",
    )

    private val english = AppStrings(
        language = AppLanguage.EN,
        subtitleTagline = "Find the exact subtitle delay for any movie",
        filmLabel = "Video",
        filmPlaceholder = "Select video (.mp4, .mkv, .avi, .webm)",
        probingAudio = "Reading audio tracks...",
        audioTrackLabel = "Audio track",
        availableCount = { n -> "$n tracks" },
        subtitleLabel = "Subtitle",
        subtitlePlaceholder = "Select subtitle (.srt, .ass, .vtt)",
        pickAction = "Choose",
        changeAction = "Change",
        dialogsMeta = { n -> "$n lines" },
        filteredMeta = { n -> "$n ads/SDH ignored" },
        checkSyncCta = "Check sync",
        recheckSyncCta = "Check again",
        cancelAction = "Cancel",
        closeAction = "Dismiss",
        offsetTitle = "Subtitle offset",
        syncedStatus = "In sync",
        manualNudgeInfo = { nudge, orig -> "Manual ${nudge}s (detected ${orig}s)" },
        guidanceSynced = { ms -> "Matches audio closely ($ms ms difference)." },
        guidanceTooEarly = { absSec, signed -> "Subtitles appear ${absSec}s too early. Set delay to ${signed}s in VLC / MX Player / Nova." },
        guidanceTooLate = { absSec, signed -> "Subtitles appear ${absSec}s too late. Set delay to ${signed}s in VLC / MX Player / Nova." },
        copySecAction = { sec -> "Copy ${sec}s" },
        copyMsAction = "Copy ms",
        saveSrtAction = "Save .srt",
        copiedToast = { v -> "Copied: $v" },
        savedSrtToast = { kept, removed ->
            if (removed > 0) "Saved ($kept lines, $removed removed)" else "Saved ($kept lines)"
        },
        driftSevereTitle = "Drift / FPS mismatch detected",
        driftMildTitle = "Slight shift across movie sections",
        driftSpreadInfo = { spread -> "Spread across checkpoints: ${spread}s. Use Save .srt to correct drift." },
        testListenTitle = "Audio check",
        testListenAudioMeta = { track -> "Track: $track · 10s" },
        loadingAudio = "Loading audio...",
        pressPlayHint = "Press play to verify speech against subtitles",
        stopContentDesc = "Stop",
        playContentDesc = "Play",
        resetLabel = "Reset",
        historyTitle = "Recent checks",
        clearHistory = "Clear",
        exportTitle = "Save synced subtitle (.srt)",
        exportModeLabel = "Timing adjustment",
        exportModeConstant = { sec -> "Constant offset (${sec}s)" },
        exportModeDrift = "Offset + FPS drift stretch",
        exportAdsSectionTitle = { n -> "Detected ads / credits ($n)" },
        exportAdsHint = "Checked lines will be removed. Uncheck any line that is real dialogue.",
        exportNoAdsDetected = "No ad or translator credit lines detected.",
        exportSelectAllAds = "Remove all",
        exportKeepAllAds = "Keep all",
        exportSdhToggleTitle = { n -> "Remove SDH / sound cues ($n)" },
        exportSdhToggleSubtitle = "e.g. [music], (laughs), ♪ ... ♪ (off by default)",
        exportSummary = { kept, removed -> "$kept lines kept · $removed removed" },
        exportConfirmCta = "Choose folder & save .srt",
        stepPreparing = "Preparing analysis",
        stepPreparingDetail = "Probing audio tracks & filtering subtitle ads",
        stepSampling = { cur, total -> "Sampling audio $cur / $total" },
        stepCorrelating = "Matching dialogue rhythm",
        stepCorrelatingDetail = "Correlating speech vs subtitles & checking FPS drift",
        stepExpanding = "Expanding search (±2 min)",
        stepExpandingDetail = "Scanning wider offsets up to ±120 seconds",
        stepExtraSampling = "Sampling extra checkpoints",
        stepExtraSamplingDetail = "Low initial confidence — adding checkpoints for accuracy",
        encodingLabel = "Encoding",
        encodingPreviewLabel = "First 3 lines preview",
        pooledEstimateBadge = "Estimated (pooled)",
        copyDiagnosticsAction = "Diagnostics",
        diagnosticsCopiedLabel = "Diagnostics",
        verifySyncedBadge = { ms -> "✓ Exported .srt verified ($ms ms residual)" },
        verifyOffsetBadge = { sec -> "⚠ Exported .srt still off by ${sec}s" },
        verifyDriftBadge = "⚠ Drift still detected in exported .srt",
        verifyRecheckAction = "Re-verify",
    )
}
