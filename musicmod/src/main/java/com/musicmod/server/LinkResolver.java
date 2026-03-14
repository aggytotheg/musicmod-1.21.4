package com.musicmod.server;

import com.musicmod.common.MusicConfig;
import com.musicmod.common.Playlist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Resolves YouTube/Spotify links to publicly accessible mp3 URLs.
 *
 * Flow:
 *  1. Run yt-dlp -x --audio-format mp3 to download + convert
 *  2. Upload the mp3 to 0x0.st (free public file host, no account needed)
 *  3. Return the public URL — works through Essential/any tunnel
 *
 * Direct audio URLs are passed through unchanged.
 */
public class LinkResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("musicmod/LinkResolver");
    private static final Path CACHE_DIR;

    static {
        try {
            CACHE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "musicmod_cache");
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create cache dir", e);
        }
    }

    private static LinkResolver instance;
    public static LinkResolver get() {
        if (instance == null) instance = new LinkResolver();
        return instance;
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "musicmod-resolver");
        t.setDaemon(true);
        return t;
    });

    private LinkResolver() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean resolve(Playlist.Song song) {
        MusicConfig cfg = MusicConfig.get();
        if (song.isResolved(cfg.urlCacheTtlSeconds)) return true;

        String src = song.getSourceUrl();
        try {
            if (!Playlist.Song.isYouTube(src) && !Playlist.Song.isSpotify(src)) {
                song.setResolvedUrl(src);
                return true;
            }

            String targetUrl = src;
            if (Playlist.Song.isSpotify(src)) {
                targetUrl = resolveSpotifyToYoutube(src, cfg);
                if (targetUrl == null) return false;
            }

            return downloadConvertAndUpload(song, targetUrl, cfg);

        } catch (Exception e) {
            LOGGER.error("Resolution failed for {}: {}", src, e.getMessage());
            return false;
        }
    }

    public Future<Boolean> resolveAsync(Playlist.Song song,
                                        java.util.function.Consumer<Boolean> onDone) {
        return pool.submit(() -> {
            boolean ok = resolve(song);
            if (onDone != null) onDone.accept(ok);
            return ok;
        });
    }

    // ── Download, convert, upload ─────────────────────────────────────────────

    private boolean downloadConvertAndUpload(Playlist.Song song, String url, MusicConfig cfg)
            throws Exception {

        String idTemp = url.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (idTemp.length() > 60) idTemp = idTemp.substring(idTemp.length() - 60);
        final String id = idTemp;
        final Path outFile = CACHE_DIR.resolve(id + ".mp3");

        // Check if we have a cached public URL that's still fresh
        Path urlCacheFile = CACHE_DIR.resolve(id + ".url");
        if (Files.exists(urlCacheFile)) {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(urlCacheFile).toMillis();
            if (age < cfg.urlCacheTtlSeconds * 1000L) {
                String cachedUrl = Files.readString(urlCacheFile).trim();
                if (!cachedUrl.isBlank()) {
                    song.setResolvedUrl(cachedUrl);
                    LOGGER.info("Using cached public URL for: {}", song.getDisplayName());
                    return true;
                }
            }
        }

        // Get title
        List<String> titleCmd = List.of(cfg.ytDlpPath, "--get-title", "--no-playlist", url);
        String title = runProcess(titleCmd, cfg.resolveTimeoutSeconds);
        if (title != null && !title.isBlank())
            song.setDisplayName(title.trim());

        // Download and convert to mp3
        LOGGER.info("Downloading mp3 for: {}", song.getDisplayName());
        List<String> dlCmd = List.of(
            cfg.ytDlpPath,
            "-x", "--audio-format", "mp3",
            "--audio-quality", "0",
            "--no-playlist",
            "--ffmpeg-location", cfg.ffmpegPath,
            "-o", outFile.toString().replace(".mp3", ".%(ext)s"),
            url
        );
        runProcess(dlCmd, cfg.resolveTimeoutSeconds * 3);

        // Find the output file
        final Path mp3File;
        if (Files.exists(outFile)) {
            mp3File = outFile;
        } else {
            try (var stream = Files.list(CACHE_DIR)) {
                Optional<Path> found = stream
                    .filter(p -> p.getFileName().toString().startsWith(id)
                              && p.getFileName().toString().endsWith(".mp3"))
                    .findFirst();
                if (found.isEmpty()) {
                    LOGGER.error("yt-dlp did not produce an mp3 file. Is ffmpeg installed?");
                    return false;
                }
                mp3File = found.get();
            }
        }

        // Upload to 0x0.st
        LOGGER.info("Uploading to 0x0.st: {}", song.getDisplayName());
        String publicUrl = uploadTo0x0(mp3File);
        if (publicUrl == null || publicUrl.isBlank()) {
            LOGGER.error("Upload to 0x0.st failed for: {}", song.getDisplayName());
            return false;
        }

        // Cache the public URL
        Files.writeString(urlCacheFile, publicUrl);
        song.setResolvedUrl(publicUrl);
        LOGGER.info("Uploaded '{}' -> {}", song.getDisplayName(), publicUrl);
        return true;
    }

    // ── Upload to 0x0.st ──────────────────────────────────────────────────────

    private String uploadTo0x0(Path file) {
        try {
            String boundary = "----boundary" + System.currentTimeMillis();
            byte[] fileBytes = Files.readAllBytes(file);
            String fileName = file.getFileName().toString();

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            String partHeader = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"" + fileName + "\"\r\n" +
                "Content-Type: audio/mpeg\r\n\r\n";
            body.write(partHeader.getBytes());
            body.write(fileBytes);
            String typeHeader = "\r\n--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"reqtype\"\r\n\r\n" +
                "fileupload\r\n--" + boundary + "--\r\n";
            body.write(typeHeader.getBytes());

            HttpURLConnection conn = (HttpURLConnection)
                    new URL("https://catbox.moe/user/api.php").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("User-Agent", "MinecraftMusicMod/1.0");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toByteArray());
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                LOGGER.error("catbox.moe returned HTTP {}", code);
                return null;
            }

            String response = new String(conn.getInputStream().readAllBytes()).trim();
            return response.startsWith("https://") ? response : null;

        } catch (Exception e) {
            LOGGER.error("Upload error: {}", e.getMessage());
            return null;
        }
    }

    // ── Spotify → YouTube ─────────────────────────────────────────────────────

    private String resolveSpotifyToYoutube(String spotifyUrl, MusicConfig cfg) throws Exception {
        if (cfg.hasSpotifyCredentials()) {
            String trackId = extractSpotifyTrackId(spotifyUrl);
            if (trackId != null) {
                SpotifyTrackInfo info = fetchSpotifyTrackInfo(trackId, cfg);
                if (info != null) {
                    return searchYouTube(info.artist + " - " + info.title, cfg);
                }
            }
        }
        return spotifyUrl;
    }

    private static class SpotifyTrackInfo { String title, artist; }

    private SpotifyTrackInfo fetchSpotifyTrackInfo(String trackId, MusicConfig cfg) {
        try {
            String credentials = cfg.spotifyClientId + ":" + cfg.spotifyClientSecret;
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            HttpURLConnection tokenConn = (HttpURLConnection)
                    new URL("https://accounts.spotify.com/api/token").openConnection();
            tokenConn.setRequestMethod("POST");
            tokenConn.setDoOutput(true);
            tokenConn.setRequestProperty("Authorization", "Basic " + encoded);
            tokenConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            tokenConn.getOutputStream().write("grant_type=client_credentials".getBytes());
            String tokenJson = new String(tokenConn.getInputStream().readAllBytes());
            String accessToken = extractJsonField(tokenJson, "access_token");
            if (accessToken == null) return null;

            HttpURLConnection trackConn = (HttpURLConnection)
                    new URL("https://api.spotify.com/v1/tracks/" + trackId).openConnection();
            trackConn.setRequestProperty("Authorization", "Bearer " + accessToken);
            String trackJson = new String(trackConn.getInputStream().readAllBytes());
            SpotifyTrackInfo info = new SpotifyTrackInfo();
            info.title  = extractJsonField(trackJson, "name");
            info.artist = extractJsonField(trackJson, "name", trackJson.indexOf("\"artists\""));
            return info.title != null ? info : null;
        } catch (Exception e) {
            LOGGER.error("Spotify API error: {}", e.getMessage());
            return null;
        }
    }

    private String searchYouTube(String query, MusicConfig cfg) throws Exception {
        List<String> cmd = List.of(cfg.ytDlpPath, "--get-id", "--no-playlist",
                "--default-search", "ytsearch1", query);
        String videoId = runProcess(cmd, cfg.resolveTimeoutSeconds);
        return (videoId != null && !videoId.isBlank())
                ? "https://www.youtube.com/watch?v=" + videoId.trim() : null;
    }

    // ── Process runner ────────────────────────────────────────────────────────

    private String runProcess(List<String> command, int timeoutSeconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append("\n");
            } catch (Exception ignored) {}
        }, "yt-dlp-reader");
        reader.setDaemon(true);
        reader.start();
        boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) { proc.destroyForcibly(); return null; }
        reader.join(1000);
        return out.toString().trim();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static String placeholderName(String url) {
        if (Playlist.Song.isYouTube(url))
            return "yt:" + url.replaceAll(".*[?&]v=([^&]+).*", "$1");
        if (Playlist.Song.isSpotify(url))
            return "spot:" + extractTrackIdStatic(url);
        try {
            String path = new URL(url).getPath();
            String file = path.substring(path.lastIndexOf('/') + 1);
            return file.isEmpty() ? url : file;
        } catch (Exception e) { return url; }
    }

    private static String extractSpotifyTrackId(String url) {
        try {
            String path = new URL(url).getPath();
            String[] parts = path.split("/");
            return parts.length >= 3 && parts[1].equals("track") ? parts[2] : null;
        } catch (Exception e) { return null; }
    }

    private static String extractTrackIdStatic(String url) {
        try {
            String path = new URL(url).getPath();
            String[] parts = path.split("/");
            return parts.length >= 3 ? parts[2] : url;
        } catch (Exception e) { return url; }
    }

    private static String extractJsonField(String json, String key) {
        return extractJsonField(json, key, 0);
    }

    private static String extractJsonField(String json, String key, int startIndex) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search, Math.max(0, startIndex));
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }
}