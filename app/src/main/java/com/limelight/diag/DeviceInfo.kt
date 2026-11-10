package com.limelight.diag

/**
 * What a diagnostic report says about the device and the setup. Only facts that help to reproduce a problem: no PC
 * names, addresses, tokens, API keys, account names or game list ever enter this class (see [DeviceInfoCollector]).
 */
data class DeviceInfo(
    val delosVersion: String,
    val buildType: String,
    val versionCode: Int,
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val sdk: Int,
    val securityPatch: String,
    val buildId: String,
    val soc: String,
    val abi: String,
    /** The GL renderer the stream window last saw, "unknown" before the first stream. */
    val gpu: String,
    val screen: String,
    val network: String,
    val locale: String,
    val timeZone: String,
    /** Stream settings in a fixed order: label to value. */
    val settings: List<Pair<String, String>>,
) {
    /** The block at the top of every report, one `Label: value` line per fact. */
    fun format(): String = buildString {
        appendLine("Delos: $delosVersion ($buildType), version code $versionCode")
        appendLine("Device: $manufacturer $model ($device)")
        appendLine("Android: $androidRelease (API $sdk), security patch ${securityPatch.ifBlank { "unknown" }}, build $buildId")
        appendLine("SoC: $soc ($abi)")
        appendLine("GPU: $gpu")
        appendLine("Screen: $screen")
        appendLine("Network: $network")
        appendLine("Locale: $locale, time zone $timeZone")
        appendLine("Stream settings:")
        settings.forEach { (label, value) -> appendLine("  $label: $value") }
    }.trimEnd()

    /** A one-line version for the title of an issue and the card: "Odin 2 Portal Max, Android 13, Delos 0.1.0". */
    fun shortLine(): String = "${model.ifBlank { device }}, Android $androidRelease, Delos $delosVersion"
}
