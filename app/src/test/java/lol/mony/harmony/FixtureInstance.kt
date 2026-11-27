package lol.mony.harmony

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A Harmony instance, faked well enough to draw the real screens.
 *
 * The redesign's screens all read from an instance: the account page, the notification inbox, the
 * feed, the drive browser, server management. That is why the first round of evidence was a
 * gallery of tokens instead of the pages themselves -- there was no instance to draw them from,
 * and a capture of a spinner proves nothing about a redesign.
 *
 * This answers the same PostgREST and GoTrue endpoints the app calls, with hand-written rows, so
 * every screen renders **its real composables with real data**, offline, in a fraction of a
 * second, and the same rows every run. A capture taken through it is a page, not a palette.
 *
 * It is deliberately not a Harmony emulator: it knows the handful of endpoints the redesigned
 * screens read, answers everything else with an empty list, and says so in the request log so a
 * screen that starts reading something new fails visibly instead of silently drawing nothing.
 */
internal class FixtureInstance : AutoCloseable {
    private val server = MockWebServer()
    private val unhandled = mutableListOf<String>()

    /** The base URL the app should be pointed at, e.g. `http://127.0.0.1:PORT/`. */
    val baseUrl: String get() = server.url("/").toString()

    /** Every request the fixture did not have an answer for, so a gap is a test failure. */
    fun unhandled(): List<String> = synchronized(unhandled) { unhandled.toList() }

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
    }

    override fun close() = server.shutdown()

    /**
     * Routes on the path and the table, not on the exact query: PostgREST filters are the app's
     * business, and a fixture that only answered one filter combination would be a fixture of
     * the app's query strings rather than of its data.
     */
    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val body = when {
            path.startsWith("/auth/v1/token") -> AUTH_TOKEN
            path.startsWith("/auth/v1/user") -> AUTH_USER
            path.startsWith("/auth/v1/signup") -> AUTH_TOKEN

            path.startsWith("/rest/v1/rpc/get_user_notifications") -> NOTIFICATIONS
            path.startsWith("/rest/v1/rpc/get_user_permissions") -> PERMISSIONS
            path.startsWith("/rest/v1/rpc/get_user_highest_role_position") -> "0"
            // Drive's reads are RPCs, and they answer two different shapes: the usage and the
            // context tree are objects, the file and folder lists are arrays. Answering all of
            // them with `[]` is what made the first Drive capture an error banner.
            path.startsWith("/rest/v1/rpc/get_my_drive_usage") -> DRIVE_USAGE
            path.startsWith("/rest/v1/rpc/drive_context_tree") -> DRIVE_CONTEXTS
            path.startsWith("/rest/v1/rpc/drive_list_files") -> DRIVE_LIST
            path.startsWith("/rest/v1/rpc/drive_list_folders") -> "[]"
            // The feed reads timeline RPCs, not the `posts` table: `get_home_timeline_page` and
            // its siblings return the rows the feed draws. Answering only `/posts` left the feed
            // empty, which is what the first Social capture showed.
            path.startsWith("/rest/v1/rpc/get_home_timeline_page") -> SOCIAL_POSTS
            path.startsWith("/rest/v1/rpc/get_enhanced_timeline_posts") -> SOCIAL_POSTS
            path.startsWith("/rest/v1/rpc/get_federated_timeline") -> SOCIAL_POSTS
            path.startsWith("/rest/v1/rpc/get_trending_posts") -> SOCIAL_POSTS
            path.startsWith("/rest/v1/rpc/get_user_bookmarks") -> SOCIAL_POSTS
            path.startsWith("/rest/v1/rpc/") -> "[]"

            path.contains("/user_servers") -> USER_SERVERS
            path.contains("/server_settings") -> SERVER_SETTINGS
            // The profiles table is the one the app reads by *filter* and expects one row from
            // (`auth_user_id=eq.…`), so it is the one the fixture has to filter. Answering every
            // profile request with every row made `singleOrNull()` return null and the account
            // page report that the profile could not be found -- a fixture bug that reads like
            // an app bug.
            path.contains("/profiles") -> profilesFor(path)
            path.contains("/notifications") -> NOTIFICATIONS
            path.contains("/posts") -> POSTS
            path.contains("/drive_") || path.contains("/drive-") -> DRIVE_FILES
            path.contains("/servers") -> SERVERS
            path.contains("/channel_categories") -> CHANNEL_CATEGORIES
            // The same is true of one channel: the room reads its own row by `id=eq.…` and takes
            // the first, so answering with every channel titled the room "welcome" while it was
            // reading "general" -- a fixture bug that reads like a navigation bug.
            path.contains("/channels") -> rowsFor(path, CHANNELS)
            path.contains("/conversation_participants") -> CONVERSATION_PARTICIPANTS
            path.contains("/conversations") -> CONVERSATIONS
            path.contains("/messages") -> MESSAGES
            // The channel's threads. Three rows, in the three states a real channel's threads
            // are in: one still open, one the instance archived, and one whose own auto-archive
            // clock has run out before any sweep archived it. The Threads list has to draw all
            // three -- the sidebar nests only the first -- and a client that applies the
            // sidebar's rule to the list claims the channel has none, which is the report this
            // fixture is here to keep fixed.
            //
            // All three belong to `general`; the read is filtered by `channel_id` like the
            // app's own, so a channel with no threads is answered with none rather than with
            // another channel's -- which is what a path-routed fixture did, and what made a
            // column that nests every thread it read under every channel look right.
            path.contains("/threads") -> threadsFor(path)
            path.contains("/roles") -> ROLES
            path.contains("/members") -> MEMBERS
            path.contains("/invites") -> INVITES
            path.contains("/bans") -> BANS

            else -> {
                synchronized(unhandled) { unhandled += "${request.method} $path" }
                "[]"
            }
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    /**
     * The profile rows a request actually asked for.
     *
     * PostgREST filters live in the query string (`auth_user_id=eq.…`, `id=in.(a,b)`), so this
     * reads them and answers with the rows that match. Anything it does not understand gets every
     * row, which is what a path-routing fixture did for all of them before there was more than
     * one person in it.
     */
    private fun profilesFor(path: String): String {
        val rows = PROFILES.trim().removePrefix("[").removeSuffix("]")
            .split(Regex("(?<=\\}),\\s*(?=\\{)"))
        val eq = Regex("auth_user_id=eq\\.([^&]+)").find(path)?.groupValues?.get(1)
        val idEq = Regex("[?&]id=eq\\.([^&]+)").find(path)?.groupValues?.get(1)
        val idIn = Regex("[?&]id=in\\.\\(([^)]*)\\)").find(path)?.groupValues?.get(1)
        val wanted: Set<String>? = when {
            eq != null -> setOf(eq)
            idEq != null -> setOf(idEq)
            idIn != null -> idIn.split(",").map { it.trim() }.toSet()
            else -> null
        }
        val selected = if (wanted == null) rows else rows.filter { row ->
            wanted.any { key -> row.contains("\"id\":\"$key\"") || row.contains("\"auth_user_id\":\"$key\"") }
        }
        return "[" + selected.joinToString(",") + "]"
    }

    /**
     * The rows of [rows] a request actually asked for by `id`, or all of them.
     *
     * A read that names one row (`id=eq.…`) has to get one row back. Answering every such read
     * with the whole table made a room that was reading "general" title itself "welcome" -- the
     * first row -- which is a fixture bug that looks like a navigation bug.
     */
    private fun rowsFor(path: String, rows: String): String {
        val eq = Regex("[?&]id=eq\\.([^&]+)").find(path)?.groupValues?.get(1) ?: return rows
        val wanted = rows.trim().removePrefix("[").removeSuffix("]")
            .split(Regex("(?<=\\}),\\s*(?=\\{)"))
            .filter { row -> row.contains("\"id\":\"$eq\"") }
        return "[" + wanted.joinToString(",") + "]"
    }

    /**
     * The thread rows of the channel a request asked for.
     *
     * The app reads a channel's threads with `channel_id=eq.…`, and the Threads sheet and the
     * channel column draw different subsets of one answer. Answering every channel with the
     * same rows would make a column that nests everything it read look correct, so the filter is
     * honoured here the way the instance honours it.
     */
    private fun threadsFor(path: String): String {
        val channel = Regex("[?&]channel_id=eq\\.([^&]+)").find(path)?.groupValues?.get(1) ?: return THREADS
        val rows = THREADS.trim().removePrefix("[").removeSuffix("]")
            .split(Regex("(?<=\\}),\\s*(?=\\{)"))
            .filter { row -> row.contains("\"channel_id\":\"$channel\"") }
        if (rows.isEmpty()) return "[]"
        return "[" + rows.joinToString(",") + "]"
    }

    private companion object {
        const val PROFILE_ID = "11111111-1111-4111-8111-111111111111"
        const val AUTH_ID = "22222222-2222-4222-8222-222222222222"

        val AUTH_TOKEN = """
            {"access_token":"fixture-access","refresh_token":"fixture-refresh",
             "expires_in":3600,"token_type":"bearer",
             "user":{"id":"$AUTH_ID","email":"ada@example.test",
                     "user_metadata":{"display_name":"Ada Lovelace"}}}
        """.trimIndent()

        val AUTH_USER = """
            {"id":"$AUTH_ID","email":"ada@example.test",
             "user_metadata":{"display_name":"Ada Lovelace"}}
        """.trimIndent()

        /**
         * The signed-in account, with an appearance the account itself stores -- the JSON shape
         * the web client writes -- so a capture shows the app honouring it.
         */
        /**
         * The account first, then the other people it talks to.
         *
         * The order matters: the app resolves *its own* profile by asking for the row that
         * carries its auth user id, and a fixture that put somebody else first made the
         * signed-in account Grace -- which is a fixture bug that looked exactly like a
         * navigation bug, because every screen then drew the wrong person.
         */
        val PROFILES = """
            [{"id":"$PROFILE_ID","auth_user_id":"$AUTH_ID","username":"ada",
              "display_name":"Ada Lovelace","bio":"Mathematics, engines, and the occasional channel.",
              "pronouns":"she/her","color":"#0EA5E9",
              "avatar_url":null,"banner_url":null,
              "profile_fields":[{"name":"Website","value":"example.test"}],
              "appearance_settings":{"theme":"dark","fontSize":15,"zoomLevel":100,
                                     "compactMode":false,"reduceMotion":false},
              "manually_approves_followers":false,"dm_policy":"everyone",
              "created_at":"2024-01-02T03:04:05Z"},
             {"id":"p2","auth_user_id":"33333333-3333-4333-8333-333333333333","username":"grace",
              "display_name":"Grace Hopper","bio":"Compilers, and the occasional channel.",
              "pronouns":null,"color":"#A855F7","avatar_url":null,"banner_url":null,
              "profile_fields":[],"appearance_settings":null,
              "manually_approves_followers":false,"dm_policy":"everyone",
              "created_at":"2024-01-03T03:04:05Z"},
             {"id":"p3","auth_user_id":"44444444-4444-4444-8444-444444444444","username":"alan",
              "display_name":"Alan Turing","bio":"Machines that think.",
              "pronouns":null,"color":"#22C55E","avatar_url":null,"banner_url":null,
              "profile_fields":[],"appearance_settings":null,
              "manually_approves_followers":false,"dm_policy":"everyone",
              "created_at":"2024-01-04T03:04:05Z"}]
        """.trimIndent()

        /**
         * Three kinds of activity on three days, read and unread, so the inbox has shape.
         *
         * The actor lives *inside* `data`, because that is the payload the instance stores and
         * the only place `Notification.actor` reads: a top-level `actor` key would decode but
         * never be looked at, and the row would say "Someone mentioned you".
         */
        val NOTIFICATIONS = """
            [{"id":"n1","user_id":"$PROFILE_ID","type":"mention",
              "data":{"channel_name":"design","server_name":"E2E Server",
                      "actor":{"id":"p2","username":"grace","display_name":"Grace Hopper","avatar_url":null},
                      "message":{"content_preview":"the new frame is in, have a look"}},
              "is_read":false,"is_clicked":false,"created_at":"2026-11-20T09:12:00Z"},
             {"id":"n2","user_id":"$PROFILE_ID","type":"reply",
              "data":{"channel_name":"general",
                      "actor":{"id":"p3","username":"alan","display_name":"Alan Turing","avatar_url":null},
                      "message":{"content_preview":"agreed, the list should stay put"}},
              "is_read":false,"is_clicked":false,"created_at":"2026-11-20T08:40:00Z"},
             {"id":"n3","user_id":"$PROFILE_ID","type":"activitypub_follow",
              "data":{"actor":{"id":"p4","username":"katherine","display_name":"Katherine Johnson","avatar_url":null}},
              "is_read":true,"is_clicked":false,"created_at":"2026-11-19T17:02:00Z"},
             {"id":"n4","user_id":"$PROFILE_ID","type":"dm",
              "data":{"conversation":{"name":"Grace Hopper"},
                      "actor":{"id":"p2","username":"grace","display_name":"Grace Hopper","avatar_url":null},
                      "message":{"content_preview":"do you have a minute to look at the composer?"}},
              "is_read":true,"is_clicked":false,"created_at":"2026-11-18T11:30:00Z"}]
        """.trimIndent()

        /** A feed with a post, a reply and a boost, so the card has something to draw. */
        val POSTS = """
            [{"id":"post1","author_id":"p2","content":"Shipped the new frame this morning. The list stays put while you read now, which is the whole point of a wide window.",
              "created_at":"2026-11-20T09:00:00Z","visibility":"public",
              "author":{"id":"p2","name":"Grace Hopper","username":"grace","avatar_url":null},
              "reply_count":3,"boost_count":7,"like_count":21,"liked":false,"boosted":false},
             {"id":"post2","author_id":"p3","content":"A settings page with one scrolling column beats five tabs on a phone. Fewer places to be lost in.",
              "created_at":"2026-11-19T16:20:00Z","visibility":"public",
              "author":{"id":"p3","name":"Alan Turing","username":"alan","avatar_url":null},
              "reply_count":1,"boost_count":2,"like_count":9,"liked":true,"boosted":false}]
        """.trimIndent()

        /**
         * The same two posts in the shape a timeline RPC returns.
         *
         * The counters are the names `SocialPost` reads (`favorites_count`, `reblogs_count`,
         * `replies_count`) and the author is a `SocialProfile` (`display_name`, not `name`), so
         * the cards draw their counts and handles rather than zeroes.
         */
        val SOCIAL_POSTS = """
            [{"id":"post1","author_id":"p2",
              "content":[{"type":"text","text":"Shipped the new frame this morning. The list stays put while you read now, which is the whole point of a wide window."}],
              "created_at":"2026-11-20T09:00:00Z","visibility":"public","language":"en",
              "author":{"id":"p2","username":"grace","display_name":"Grace Hopper","is_local":true},
              "replies_count":3,"reblogs_count":7,"favorites_count":21,
              "is_favorited":false,"is_reblogged":false,"is_bookmarked":false},
             {"id":"post2","author_id":"p3",
              "content":[{"type":"text","text":"A settings page with one scrolling column beats five tabs on a phone. Fewer places to be lost in."}],
              "created_at":"2026-11-19T16:20:00Z","visibility":"public","language":"en",
              "author":{"id":"p3","username":"alan","display_name":"Alan Turing","is_local":true},
              "replies_count":1,"reblogs_count":2,"favorites_count":9,
              "is_favorited":true,"is_reblogged":false,"is_bookmarked":false}]
        """.trimIndent()

        val DRIVE_FILES = """
            [{"id":"d1","owner_id":"$PROFILE_ID","name":"frame-sketch.png","kind":"image",
              "size":184320,"created_at":"2026-11-18T10:00:00Z","updated_at":"2026-11-18T10:00:00Z"},
             {"id":"d2","owner_id":"$PROFILE_ID","name":"design-notes.md","kind":"document",
              "size":4211,"created_at":"2026-11-17T09:00:00Z","updated_at":"2026-11-17T09:00:00Z"},
             {"id":"d3","owner_id":"$PROFILE_ID","name":"voice-check.m4a","kind":"audio",
              "size":920144,"created_at":"2026-11-16T20:00:00Z","updated_at":"2026-11-16T20:00:00Z"}]
        """.trimIndent()

        /** The quota line the app bar shows. */
        val DRIVE_USAGE = """
            {"enabled":true,"used_bytes":1108675,"quota_bytes":5368709120,
             "ephemeral_bytes":0,"ephemeral_recent_bytes":0,"ephemeral_daily_bytes":0,
             "ephemeral_days":7,"trash_bytes":0,"file_count":3,"link_sharing":true}
        """.trimIndent()

        /** No server or conversation contexts: this account's Drive is its own files. */
        val DRIVE_CONTEXTS = """
            {"servers":[],"conversations":[],"fediverse":0,"shared":0}
        """.trimIndent()

        /** Three files, each wrapped with its owner and the total, as `drive_list_files` returns. */
        val DRIVE_LIST = """
            [{"file":{"id":"d1","owner_id":"$PROFILE_ID","bucket":"drive","path":"d1/frame-sketch.png",
                      "name":"frame-sketch.png","mime_type":"image/png","kind":"image","size_bytes":184320,
                      "state":"drive","created_at":"2026-11-18T10:00:00Z","updated_at":"2026-11-18T10:00:00Z"},
              "owner":{"id":"$PROFILE_ID","username":"ada","display_name":"Ada Lovelace"},"total_count":3},
             {"file":{"id":"d2","owner_id":"$PROFILE_ID","bucket":"drive","path":"d2/design-notes.md",
                      "name":"design-notes.md","mime_type":"text/markdown","kind":"document","size_bytes":4211,
                      "state":"drive","created_at":"2026-11-17T09:00:00Z","updated_at":"2026-11-17T09:00:00Z"},
              "owner":{"id":"$PROFILE_ID","username":"ada","display_name":"Ada Lovelace"},"total_count":3},
             {"file":{"id":"d3","owner_id":"$PROFILE_ID","bucket":"drive","path":"d3/voice-check.m4a",
                      "name":"voice-check.m4a","mime_type":"audio/mp4","kind":"audio","size_bytes":920144,
                      "state":"drive","created_at":"2026-11-16T20:00:00Z","updated_at":"2026-11-16T20:00:00Z"},
              "owner":{"id":"$PROFILE_ID","username":"ada","display_name":"Ada Lovelace"},"total_count":3}]
        """.trimIndent()

        val SERVERS = """
            [{"id":"s1","name":"E2E Server","icon":null,"description":"Where the client is built.",
              "owner":"$PROFILE_ID","public":true,"rules":["Be kind.","Stay on topic."],
              "is_local_server":true,"member_count":2,"federation_enabled":false,
              "federation_discoverable":false,"created_at":"2024-02-01T00:00:00Z"}]
        """.trimIndent()

        /** The account's membership of that server: an owner is an accepted member too. */
        val USER_SERVERS = """
            [{"id":"us1","server_id":"s1","user_id":"$PROFILE_ID","status":"accepted","nickname":null}]
        """.trimIndent()

        /** Server settings: the invite policy the management screen reads. */
        val SERVER_SETTINGS = """
            [{"id":"ss1","server_id":"s1","invite_permissions":{"allowTemporary":true,"maxUses":25,"maxExpiration":7}}]
        """.trimIndent()

        /** Every permission granted, so the screen draws its management affordances. */
        val PERMISSIONS = """
            {"ADMINISTRATOR":true,"MANAGE_SERVER":true,"MANAGE_CHANNELS":true,"MANAGE_ROLES":true,
             "MANAGE_MEMBERS":true,"KICK_MEMBERS":true,"BAN_MEMBERS":true,"CREATE_INVITES":true,
             "MANAGE_INVITES":true}
        """.trimIndent()

        /**
         * The categories the channel list groups by.
         *
         * The channel list reads this table before the channels, so a fixture without it drew a
         * permanent "Loading channels" -- which is a picture of the fixture, not of the app.
         */
        val CHANNEL_CATEGORIES = """
            [{"id":"cat1","server_id":"s1","name":"Text channels","order":0}]
        """.trimIndent()

        /**
         * `channels.type` is a smallint -- 0 text, 1 voice -- not the word. A fixture that wrote
         * `"type":"text"` failed to decode the whole tree, so the channel list sat on its
         * spinner forever while every request returned 200.
         */
        val CHANNELS = """
            [{"id":"c1","server_id":"s1","name":"welcome","description":"Start here","type":0,
              "order":0,"category":"cat1"},
             {"id":"c2","server_id":"s1","name":"general","description":"Everything and nothing","type":0,
              "order":1,"category":"cat1"},
             {"id":"c3","server_id":"s1","name":"design","description":"Type, colour and motion","type":0,
              "order":2,"category":"cat1"}]
        """.trimIndent()

        /**
         * Both people in the direct conversation.
         *
         * The DM list reads this table twice -- once for the account's own rows, which is what
         * lists a conversation at all, and once for the other people in it, which is what gives
         * the row its title. The fixture answers both with both rows, because it routes on the
         * path and not on the query: one row drew a list whose every conversation was nameless.
         */
        val CONVERSATION_PARTICIPANTS = """
            [{"id":"cp1","conversation_id":"cv1","user_id":"$PROFILE_ID","joined_at":"2026-01-01T00:00:00Z",
              "left_at":null,"hidden_at":null},
             {"id":"cp2","conversation_id":"cv1","user_id":"p2","joined_at":"2026-01-01T00:00:00Z",
              "left_at":null,"hidden_at":null}]
        """.trimIndent()

        val CONVERSATIONS = """
            [{"id":"cv1","name":null,"is_group":false,"created_at":"2026-01-01T00:00:00Z",
              "participants":[{"id":"p2","name":"Grace Hopper","username":"grace","avatar_url":null}]}]
        """.trimIndent()

        /**
         * The channel's history, in the shape the app actually reads.
         *
         * `messages.content` is an array of parts, not a sentence: a fixture that wrote
         * `"content":"text"` failed to decode the whole page, and the room drew its own error
         * banner over an empty history -- a picture of the fixture, not of the app.
         */
        val MESSAGES = """
            [{"id":"m1","channel_id":"c2","conversation_id":null,"thread_id":null,"user_id":"p2",
              "content":[{"type":"text","text":"The redesign is in. Have a look at the frame on a wide window."}],
              "created_at":"2026-11-20T09:12:00Z"},
             {"id":"m2","channel_id":"c2","conversation_id":null,"thread_id":null,"user_id":"p3",
              "content":[{"type":"text","text":"The list stays put while I read now, which is the whole point."}],
              "created_at":"2026-11-20T09:14:00Z"}]
        """.trimIndent()

        /**
         * The channel's threads, in the three states a real one's are in.
         *
         * The dates are relative to the run, not fixed in the past: a thread is only *open*
         * while its own auto-archive clock has not run out, so a fixture dated last week would
         * have every thread closed by the time it was read -- which is the bug this fixture is
         * here to catch, not a way to catch it. `t-open` was replied to half an hour ago inside
         * its day; `t-expired` two days ago with the same 1440-minute window this client asks
         * for on create, so its clock ran out before any sweep archived it; `t-archived` was
         * archived by the instance. The Threads list must draw all three, and the channel
         * sidebar must nest only the first.
         */
        private fun minutesAgo(minutes: Long): String =
            java.time.Instant.now().minusSeconds(minutes * 60).toString()

        val THREADS: String
            get() = """
            [{"id":"t-open","channel_id":"c2","parent_message_id":"m1","name":"Release planning",
              "message_count":4,"archived":false,"locked":false,"auto_archive_duration":1440,
              "created_at":"${minutesAgo(2880)}","last_message_at":"${minutesAgo(30)}"},
             {"id":"t-expired","channel_id":"c2","parent_message_id":"m2","name":"Frame sketches",
              "message_count":2,"archived":false,"locked":false,"auto_archive_duration":1440,
              "created_at":"${minutesAgo(4320)}","last_message_at":"${minutesAgo(2880)}"},
             {"id":"t-archived","channel_id":"c2","parent_message_id":"m1","name":"Buried thread",
              "message_count":9,"archived":true,"locked":false,"auto_archive_duration":1440,
              "created_at":"${minutesAgo(10080)}","last_message_at":"${minutesAgo(4320)}"}]
        """.trimIndent()

        val ROLES = """
            [{"id":"r1","server_id":"s1","name":"Member","color":"#9CA3AF","position":0,
              "permissions":{}},
             {"id":"r2","server_id":"s1","name":"Designer","color":"#0EA5E9","position":1,
              "permissions":{}}]
        """.trimIndent()

        val MEMBERS = """
            [{"id":"m-1","server_id":"s1","profile_id":"$PROFILE_ID","role_ids":["r1"],
              "profile":{"id":"$PROFILE_ID","name":"Ada Lovelace","username":"ada","avatar_url":null}},
             {"id":"m-2","server_id":"s1","profile_id":"p2","role_ids":["r1","r2"],
              "profile":{"id":"p2","name":"Grace Hopper","username":"grace","avatar_url":null}}]
        """.trimIndent()

        const val INVITES = "[]"
        const val BANS = "[]"
    }
}
