package com.musicmod.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.musicmod.common.Playlist;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.*;

/**
 * All /music commands.
 *
 * Commands now accept YouTube & Spotify URLs anywhere a song name/URL is expected.
 * Resolution happens asynchronously via LinkResolver + yt-dlp.
 */
public class MusicCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register(MusicCommands::build);
    }

    private static void build(CommandDispatcher<ServerCommandSource> dispatcher,
                              CommandRegistryAccess access,
                              CommandManager.RegistrationEnvironment env) {

        dispatcher.register(literal("music")

            // ── /music play <name|url> ───────────────────────────────────────────
            .then(literal("play")
                .requires(s -> s.hasPermissionLevel(2))
                .then(argument("target", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String target = StringArgumentType.getString(ctx, "target");
                        PlaylistManager pm = PlaylistManager.get();

                        // Try library first
                        Playlist.Song song = pm.resolveSong(target);
                        if (song == null) {
                            ctx.getSource().sendError(Text.literal(
                                "Unknown song or invalid URL: " + target));
                            return 0;
                        }

                        // For YouTube/Spotify links not yet in library, add them on the fly
                        MusicSessionController.get().playSongAsync(song);
                        ctx.getSource().sendFeedback(
                            () -> Text.literal("▶ Playing: " + song.getDisplayName()), true);
                        return 1;
                    })
                )
            )

            // ── /music stop ──────────────────────────────────────────────────────
            .then(literal("stop")
                .requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> {
                    MusicSessionController.get().stopAll();
                    ctx.getSource().sendFeedback(() -> Text.literal("⏹ Music stopped."), true);
                    return 1;
                })
            )

            // ── /music skip ──────────────────────────────────────────────────────
            .then(literal("skip")
                .requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> {
                    MusicSessionController.get().skipSong();
                    ctx.getSource().sendFeedback(() -> Text.literal("⏭ Skipped."), true);
                    return 1;
                })
            )

            // ── /music nowplaying ────────────────────────────────────────────────
            .then(literal("nowplaying")
                .executes(ctx -> {
                    MusicSessionController ctrl = MusicSessionController.get();
                    if (!ctrl.isPlaying() || ctrl.getCurrentSong() == null) {
                        ctx.getSource().sendFeedback(
                            () -> Text.literal("Nothing is playing."), false);
                    } else {
                        Playlist.Song s = ctrl.getCurrentSong();
                        String pl = ctrl.getActivePlaylist() != null
                            ? " [" + ctrl.getActivePlaylist().getName() + "]" : "";
                        ctx.getSource().sendFeedback(
                            () -> Text.literal("♪ " + s.getDisplayName() + pl), false);
                    }
                    return 1;
                })
            )

            // ── /music add <name> <url> ──────────────────────────────────────────
            // url can be YouTube, Spotify, or direct audio
            .then(literal("add")
                .requires(s -> s.hasPermissionLevel(2))
                .then(argument("name", StringArgumentType.word())
                    .then(argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            String url  = StringArgumentType.getString(ctx, "url");

                            // Validate it looks like a URL
                            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                ctx.getSource().sendError(Text.literal(
                                    "URL must start with http:// or https://"));
                                return 0;
                            }

                            ServerCommandSource src = ctx.getSource();
                            PlaylistManager.get().addSongAsync(url, null,
                                src.getPlayer(),
                                msg -> src.sendFeedback(() -> Text.literal(msg), true)
                            );

                            ctx.getSource().sendFeedback(
                                () -> Text.literal("⏳ Resolving: " + url + " …"), true);
                            return 1;
                        })
                    )
                )
            )

            // ── /music addurl <url>  (no explicit name — auto-derived) ───────────
            .then(literal("addurl")
                .requires(s -> s.hasPermissionLevel(2))
                .then(argument("url", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String url = StringArgumentType.getString(ctx, "url");
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            ctx.getSource().sendError(Text.literal("Invalid URL."));
                            return 0;
                        }
                        ServerCommandSource src = ctx.getSource();
                        PlaylistManager.get().addSongAsync(url, null,
                            src.getPlayer(),
                            msg -> src.sendFeedback(() -> Text.literal(msg), true)
                        );
                        ctx.getSource().sendFeedback(
                            () -> Text.literal("⏳ Adding: " + url), true);
                        return 1;
                    })
                )
            )

            // ── /music remove <name> ─────────────────────────────────────────────
            .then(literal("remove")
                .requires(s -> s.hasPermissionLevel(2))
                .then(argument("name", StringArgumentType.word())
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name");
                        boolean ok = PlaylistManager.get().removeSong(name);
                        if (!ok) {
                            ctx.getSource().sendError(Text.literal("Song not found: " + name));
                            return 0;
                        }
                        ctx.getSource().sendFeedback(
                            () -> Text.literal("✔ Removed: " + name), true);
                        return 1;
                    })
                )
            )

            // ── /music list ──────────────────────────────────────────────────────
            .then(literal("list")
                .executes(ctx -> {
                    var songs = PlaylistManager.get().getAllSongs();
                    if (songs.isEmpty()) {
                        ctx.getSource().sendFeedback(
                            () -> Text.literal("Library is empty."), false);
                    } else {
                        StringBuilder sb = new StringBuilder("── Song Library ──\n");
                        for (var s : songs) {
                            sb.append("  ").append(s.getDisplayName())
                              .append(" → ").append(s.getSourceUrl()).append("\n");
                        }
                        ctx.getSource().sendFeedback(
                            () -> Text.literal(sb.toString()), false);
                    }
                    return 1;
                })
            )

            // ── /music playlist … ────────────────────────────────────────────────
            .then(literal("playlist")

                .then(literal("create")
                    .requires(s -> s.hasPermissionLevel(2))
                    .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            boolean ok = PlaylistManager.get().createPlaylist(name);
                            ctx.getSource().sendFeedback(() -> Text.literal(
                                ok ? "✔ Created: " + name : "⚠ Already exists: " + name), true);
                            return ok ? 1 : 0;
                        })
                    )
                )

                .then(literal("delete")
                    .requires(s -> s.hasPermissionLevel(2))
                    .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            boolean ok = PlaylistManager.get().deletePlaylist(name);
                            ctx.getSource().sendFeedback(() -> Text.literal(
                                ok ? "✔ Deleted: " + name : "⚠ Not found: " + name), true);
                            return ok ? 1 : 0;
                        })
                    )
                )

                .then(literal("add")
                    .requires(s -> s.hasPermissionLevel(2))
                    .then(argument("playlist", StringArgumentType.word())
                        .then(argument("song", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String pl   = StringArgumentType.getString(ctx, "playlist");
                                String song = StringArgumentType.getString(ctx, "song");

                                // If it's a URL, add it to library+playlist in one step
                                if (song.startsWith("http://") || song.startsWith("https://")) {
                                    ServerCommandSource src = ctx.getSource();
                                    PlaylistManager.get().addSongAsync(song, pl,
                                        src.getPlayer(),
                                        msg -> src.sendFeedback(() -> Text.literal(msg), true)
                                    );
                                    ctx.getSource().sendFeedback(
                                        () -> Text.literal("⏳ Adding to " + pl + ": " + song), true);
                                    return 1;
                                }

                                boolean ok = PlaylistManager.get().addSongToPlaylist(pl, song);
                                ctx.getSource().sendFeedback(() -> Text.literal(
                                    ok ? "✔ Added '" + song + "' → " + pl
                                       : "⚠ Failed. Check playlist and song exist."), true);
                                return ok ? 1 : 0;
                            })
                        )
                    )
                )

                .then(literal("remove")
                    .requires(s -> s.hasPermissionLevel(2))
                    .then(argument("playlist", StringArgumentType.word())
                        .then(argument("song", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String pl   = StringArgumentType.getString(ctx, "playlist");
                                String song = StringArgumentType.getString(ctx, "song");
                                boolean ok = PlaylistManager.get().removeSongFromPlaylist(pl, song);
                                ctx.getSource().sendFeedback(() -> Text.literal(
                                    ok ? "✔ Removed." : "⚠ Not found."), true);
                                return ok ? 1 : 0;
                            })
                        )
                    )
                )

                .then(literal("play")
                    .requires(s -> s.hasPermissionLevel(2))
                    .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            Playlist pl = PlaylistManager.get().getPlaylist(name);
                            if (pl == null) {
                                ctx.getSource().sendError(Text.literal("Not found: " + name));
                                return 0;
                            }
                            MusicSessionController.get().playPlaylist(pl);
                            ctx.getSource().sendFeedback(
                                () -> Text.literal("▶ Playing playlist: " + name), true);
                            return 1;
                        })
                    )
                )

                .then(literal("shuffle")
                    .requires(s -> s.hasPermissionLevel(2))
                    .then(argument("name", StringArgumentType.word())
                        .then(argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> {
                                String name = StringArgumentType.getString(ctx, "name");
                                boolean val = BoolArgumentType.getBool(ctx, "enabled");
                                Playlist pl = PlaylistManager.get().getPlaylist(name);
                                if (pl == null) {
                                    ctx.getSource().sendError(Text.literal("Not found: " + name));
                                    return 0;
                                }
                                pl.setShuffle(val);
                                PlaylistManager.get().save();
                                ctx.getSource().sendFeedback(
                                    () -> Text.literal("Shuffle for '" + name + "': " + val), true);
                                return 1;
                            })
                        )
                    )
                )

                .then(literal("list")
                    .executes(ctx -> {
                        var all = PlaylistManager.get().getAllPlaylists();
                        if (all.isEmpty()) {
                            ctx.getSource().sendFeedback(
                                () -> Text.literal("No playlists."), false);
                        } else {
                            StringBuilder sb = new StringBuilder("── Playlists ──\n");
                            for (Playlist pl : all) {
                                sb.append("  ").append(pl.getName())
                                  .append(" (").append(pl.size()).append(" songs")
                                  .append(pl.isShuffle() ? ", shuffle" : "").append(")\n");
                            }
                            ctx.getSource().sendFeedback(
                                () -> Text.literal(sb.toString()), false);
                        }
                        return 1;
                    })
                )

                .then(literal("info")
                    .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            Playlist pl = PlaylistManager.get().getPlaylist(name);
                            if (pl == null) {
                                ctx.getSource().sendError(Text.literal("Not found: " + name));
                                return 0;
                            }
                            StringBuilder sb = new StringBuilder("── " + pl.getName() + " ──\n");
                            for (int i = 0; i < pl.getSongs().size(); i++) {
                                Playlist.Song s = pl.getSongs().get(i);
                                sb.append("  ").append(i + 1).append(". ")
                                  .append(s.getDisplayName())
                                  .append("\n      ").append(s.getSourceUrl()).append("\n");
                            }
                            ctx.getSource().sendFeedback(
                                () -> Text.literal(sb.toString()), false);
                            return 1;
                        })
                    )
                )
            )

            // ── /music config ─────────────────────────────────────────────────────
            .then(literal("config")
                .requires(s -> s.hasPermissionLevel(4))
                .then(literal("ytdlp")
                    .then(argument("path", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String path = StringArgumentType.getString(ctx, "path");
                            com.musicmod.common.MusicConfig.get().ytDlpPath = path;
                            com.musicmod.common.MusicConfig.get().save();
                            ctx.getSource().sendFeedback(
                                () -> Text.literal("yt-dlp path set to: " + path), true);
                            return 1;
                        })
                    )
                )
            )
        );
    }
}
