package top.michubil.musictag.data.network

data class FingerprintSuggestion(
    val title: String,
    val artists: List<String>,
    val score: Double,
    val recordingId: String? = null,
)
