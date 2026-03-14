package com.musicmod.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Playlist {

    private final String name;
    private final List<Song> songs = new ArrayList<>();
    private int currentIndex = 0;
    private boolean shuffle = false;
    private boolean loop    = true;

    public Playlist(String name) { this.name = name; }

    public String getName()           { return name; }
    public List<Song> getSongs()      { return Collections.unmodifiableList(songs); }
    public boolean isShuffle()        { return shuffle; }
    public boolean isLoop()           { return loop; }
    public void setShuffle(boolean v) { this.shuffle = v; }
    public void setLoop(boolean v)    { this.loop    = v; }

    public void addSong(Song song)         { songs.add(song); }
    public boolean removeSong(String name) { return songs.removeIf(s -> s.getDisplayName().equalsIgnoreCase(name)); }
    public void reset()                    { currentIndex = 0; }
    public int size()                      { return songs.size(); }

    public Song current() {
        return songs.isEmpty() ? null : songs.get(currentIndex);
    }

    public Song next() {
        if (songs.isEmpty()) return null;
        currentIndex = shuffle
            ? (int)(Math.random() * songs.size())
            : (currentIndex + 1) % songs.size();
        return songs.get(currentIndex);
    }

    // ─── Song ────────────────────────────────────────────────────────────────────

    public static class Song {

        /** User-visible label (auto-derived from YouTube title if not supplied). */
        private String displayName;

        /**
         * Original URL the user pasted — YouTube, Spotify, or direct audio link.
         * Stored persistently.
         */
        private final String sourceUrl;

        /**
         * Resolved direct audio URL (e.g. YouTube CDN URL from yt-dlp).
         * Cached in-memory only; re-resolved when expired or null.
         */
        private transient String resolvedUrl;
        private transient long   resolvedAt = 0; // epoch millis

        public Song(String displayName, String sourceUrl) {
            this.displayName = displayName;
            this.sourceUrl   = sourceUrl;
        }

        public String getDisplayName()          { return displayName; }
        public void   setDisplayName(String n)  { this.displayName = n; }
        public String getSourceUrl()            { return sourceUrl; }
        public String getResolvedUrl()          { return resolvedUrl; }

        /** Returns true if the cached resolved URL is still within TTL. */
        public boolean isResolved(int ttlSeconds) {
            if (resolvedUrl == null || resolvedUrl.isBlank()) return false;
            return (System.currentTimeMillis() - resolvedAt) < (ttlSeconds * 1000L);
        }

        public void setResolvedUrl(String url) {
            this.resolvedUrl = url;
            this.resolvedAt  = System.currentTimeMillis();
        }

        /** True when the source link requires yt-dlp to obtain a playback URL. */
        public boolean needsResolution() {
            return isYouTube(sourceUrl) || isSpotify(sourceUrl);
        }

        public static boolean isYouTube(String url) {
            return url != null && (url.contains("youtube.com") || url.contains("youtu.be"));
        }

        public static boolean isSpotify(String url) {
            return url != null && url.contains("open.spotify.com");
        }

        /** The URL clients actually stream from. */
        public String getPlaybackUrl() {
            return (resolvedUrl != null && !resolvedUrl.isBlank()) ? resolvedUrl : sourceUrl;
        }

        @Override
        public String toString() { return displayName + " [" + sourceUrl + "]"; }
    }
}
