package com.dewy.musicextractish.spotify

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.dataformat.csv.CsvMapper
import org.apache.poi.ss.usermodel.*
import org.springframework.core.io.InputStreamResource
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties


const val SPOTIFY_API_BASE_URL = "https://api.spotify.com/v1"

@Service
class SpotifyService(val webClient: WebClient) {
    fun getUserInfo(authorizedClient: OAuth2AuthorizedClient): String? {
        val resourceUri = "${SPOTIFY_API_BASE_URL}/me"
        return webClient
            .get()
            .uri(resourceUri)
            .attributes(ServletOAuth2AuthorizedClientExchangeFilterFunction.oauth2AuthorizedClient(authorizedClient))
            .retrieve()
            .bodyToMono(String::class.java)
            .block()
    }

    fun getUserPlaylists(authorizedClient: OAuth2AuthorizedClient, offset: Int, limit: Int): String? {
        val resourceUri = "${SPOTIFY_API_BASE_URL}/me/playlists?offset=$offset&limit=$limit"
        return webClient
            .get()
            .uri(resourceUri)
            .attributes(ServletOAuth2AuthorizedClientExchangeFilterFunction.oauth2AuthorizedClient(authorizedClient))
            .retrieve()
            .bodyToMono(String::class.java)
            .block()
    }

    fun exportAllPlaylists(
        authorizedClient: OAuth2AuthorizedClient,
        exportType: ExportType
    ): List<PlaylistExportResult> {
        val ids = mutableListOf<String>()
        var playlists: JsonNode?
        var next: String? = "${SPOTIFY_API_BASE_URL}/me/playlists"

        while (next != null) {
            playlists = webClient
                .get()
                .uri(next)
                .attributes(ServletOAuth2AuthorizedClientExchangeFilterFunction.oauth2AuthorizedClient(authorizedClient))
                .retrieve()
                .bodyToMono(JsonNode::class.java)
                .block()

            (playlists?.get("items") as? ArrayNode)?.forEach {
                val id = it.get("id")
                if (id != null) {
                    ids.add(id.textValue())
                }
            }

            next = playlists?.get("next")?.textValue()
        }

        return exportPlaylists(authorizedClient, exportType, ids)
    }

    fun exportPlaylists(
        authorizedClient: OAuth2AuthorizedClient,
        exportType: ExportType,
        playlistIds: List<String>
    ): List<PlaylistExportResult> {
        val playlists = mutableListOf<Playlist>()

        playlistIds.forEach {
            val playlist = fetchPlaylist(authorizedClient, it)
            if (playlist != null) {
                playlists.add(playlist)
            }
        }

        return convertToExportType(playlists, exportType)
    }

    fun fetchPlaylist(
        authorizedClient: OAuth2AuthorizedClient,
        id: String
    ): Playlist? {
        val resourceUri = "${SPOTIFY_API_BASE_URL}/playlists/${id}"
        val playlist = webClient
            .get()
            .uri(resourceUri)
            .attributes(ServletOAuth2AuthorizedClientExchangeFilterFunction.oauth2AuthorizedClient(authorizedClient))
            .retrieve()
            .bodyToMono(Playlist::class.java)
            .block()

        var nextUri = playlist?.tracks?.next
        while (nextUri != null) {
            val nextTracks = webClient
                .get()
                .uri(nextUri)
                .attributes(ServletOAuth2AuthorizedClientExchangeFilterFunction.oauth2AuthorizedClient(authorizedClient))
                .retrieve()
                .bodyToMono(Tracks::class.java)
                .block()

            if (nextTracks?.items != null) {
                playlist?.tracks?.items?.addAll(nextTracks.items)
            }
            nextUri = nextTracks?.next
        }
        return playlist
    }

    private fun convertToExportType(playlists: List<Playlist>, exportType: ExportType): List<PlaylistExportResult> {
        return when (exportType) {
            ExportType.JSON -> convertToJson(playlists)
            ExportType.CSV -> convertToCsv(playlists)
            ExportType.XLS, ExportType.XLSX -> convertToExcel(playlists, exportType)
        }
    }

    private fun convertToJson(playlists: List<Playlist>): List<PlaylistExportResult> {
        val results = mutableListOf<PlaylistExportResult>()

        playlists.forEach {
            val tracks = prepareTracks(it)

            val byteArray = ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(tracks)

            results.add(
                PlaylistExportResult(
                    playlistName = it.name,
                    inputStreamResource = InputStreamResource(ByteArrayInputStream(byteArray)),
                    contentLength = byteArray.size.toLong()
                )
            )
        }

        return results
    }

    @Suppress("UNCHECKED_CAST")
    private fun convertToExcel(playlists: List<Playlist>, exportType: ExportType): List<PlaylistExportResult> {
        val results = mutableListOf<PlaylistExportResult>()

        playlists.forEach { playlist ->
            val tracks = prepareTracks(playlist)

            val workbook: Workbook = WorkbookFactory.create(exportType == ExportType.XLSX)

            val sheet = workbook.createSheet()

            val header = sheet.createRow(0)

            TrackFlattened::class.declaredMemberProperties.forEachIndexed { index, property ->
                val headerCell = header.createCell(index)
                headerCell.setCellValue(property.name)
            }

            var rowNum = 1
            tracks.forEach { track ->
                val row = sheet.createRow(rowNum)
                TrackFlattened::class.declaredMemberProperties.forEachIndexed { index, member ->
                    val property = track::class.members.first { member.name == it.name } as? KProperty1<Any, *>
                    val rowCell = row.createCell(index)
                    rowCell.setCellValue(property?.get(track).toString())
                }

                rowNum++
            }

            val byteArrayOutputStream = ByteArrayOutputStream()
            workbook.write(byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()

            results.add(
                PlaylistExportResult(
                    playlistName = playlist.name,
                    inputStreamResource = InputStreamResource(ByteArrayInputStream(byteArray)),
                    contentLength = byteArray.size.toLong()
                )
            )
        }

        return results
    }

    private fun convertToCsv(playlists: List<Playlist>): List<PlaylistExportResult> {
        val results = mutableListOf<PlaylistExportResult>()

        playlists.forEach {
            val tracks = prepareTracks(it)

            val byteArrayOutputStream = ByteArrayOutputStream()

            val mapper = CsvMapper()
            val schema = mapper.schemaFor(TrackFlattened::class.java).withHeader()
            mapper.writer(schema).writeValue(byteArrayOutputStream, tracks)

            val byteArray = byteArrayOutputStream.toByteArray()

            results.add(
                PlaylistExportResult(
                    playlistName = it.name,
                    inputStreamResource = InputStreamResource(ByteArrayInputStream(byteArray)),
                    contentLength = byteArray.size.toLong()
                )
            )
        }

        return results
    }

    private fun prepareTracks(playlist: Playlist): List<TrackFlattened> {
        val tracks = mutableListOf<TrackFlattened>()

        playlist.tracks?.items?.forEach {
            tracks.add(
                TrackFlattened(
                    added_at = it.added_at,
                    name = it.track?.name,
                    artists = it.track?.artists?.foldIndexed("") { index: Int, acc: String, artist: Artist ->
                        if (artist.name != null)
                            return@foldIndexed if (index != 0) "$acc && ${artist.name}" else artist.name
                        else
                            return@foldIndexed acc
                    },
                    album = it.track?.album?.name
                )
            )
        }

        return tracks
    }
}

data class PlaylistExportResult(
    val playlistName: String?,
    val inputStreamResource: InputStreamResource,
    val contentLength: Long
)