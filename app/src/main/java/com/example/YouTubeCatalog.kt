package com.example

data class YouTubeVideoItem(
  val videoId: String,
  val title: String,
  val thumbnailUrl: String? = null
)

object YouTubeCatalog {
  val default: List<YouTubeVideoItem> = listOf(
    YouTubeVideoItem(
      videoId = YouTubePlayerManager.PRESET_MAHA_PIRITHA_ID,
      title = "මහා පිරිත් දේශනාව"
    ),
    YouTubeVideoItem(
      videoId = YouTubePlayerManager.PRESET_BANA_ID,
      title = "සද්ධර්ම දේශනාව"
    ),
    YouTubeVideoItem(
      videoId = "2Vv-BfVoq4g",
      title = "ශ්‍රී ලංකා සම්භාව්‍ය ගීත"
    ),
    YouTubeVideoItem(
      videoId = "UWAfwzjqfus",
      title = "විශේෂ දේශනාව"
    )
  )
}
