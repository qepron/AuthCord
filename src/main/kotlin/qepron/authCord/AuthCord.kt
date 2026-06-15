package qepron.authCord

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.requests.GatewayIntent
import org.bukkit.event.EventHandler
import org.bukkit.plugin.java.JavaPlugin
import io.papermc.paper.event.player.AsyncChatEvent
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.text.TextInput
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle
import net.dv8tion.jda.api.interactions.modals.Modal
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.BanList
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.event.Listener
import org.bukkit.event.player.*
import org.bukkit.event.entity.*
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared MiniMessage instance.
 * Previously instantiated separately in AuthCord's companion AND again inline
 * inside DiscordListener.onMessageReceived. One stateless instance is enough.
 */
val mm: MiniMessage = MiniMessage.miniMessage()

class AuthCord : JavaPlugin(), CommandExecutor, TabCompleter {
    companion object {
        lateinit var instance: AuthCord
            private set

        var discordToken = ""; var serverId = ""; var channelId = ""
        var memberId = ""; var adminId = ""
        var authTimeout: Long = 30; var maxAttempts = 3; var banDuration: Long = 24
        var trustLocalhost = false; var maxAccountsMember = 1; var maxAccountsAdmin = 3

        private val messages = ConcurrentHashMap<String, String>()

        /**
         * Discord-side messages (plain text / Discord markdown, NOT MiniMessage).
         * These were previously scattered as inline `conf.getString("messages.xxx") ?: "..."`
         * defaults across DiscordListener, ChatListener and GameEventListener.
         * Centralized here so every Discord-facing string is configurable from
         * config.yml via getDiscordMsg(), the same way getMsg() works for in-game text.
         * Placeholders here use %tag% syntax and are substituted with plain
         * String.replace - safe because this output goes to Discord, not MiniMessage.
         */
        private val discordMessages = ConcurrentHashMap<String, String>()

        /**
         * Vanilla Minecraft username rules: 3-16 characters, ASCII letters,
         * digits, and underscores only. No spaces, no accented/Unicode
         * characters (e.g. "ç", "ğ", "ş"), no other punctuation. Used to
         * reject /register submissions with invalid usernames before they
         * ever reach the database.
         */
        val VALID_USERNAME_REGEX = Regex("^[A-Za-z0-9_]{3,16}$")

        init {
            // Placeholders use MiniMessage <tag> syntax (e.g. <code>) instead of
            // %tag% strings. getMsg() resolves these with Placeholder.unparsed(),
            // which inserts the value as literal text - it can NEVER be parsed as
            // a MiniMessage tag, no matter what it contains. This matters because
            // some values (Discord message content, usernames, etc.) aren't fully
            // trusted.
            messages["system-activated"] = "<green>[AuthCord] System successfully loaded and protection is active!</green>"
            messages["ip-banned-broadcast"] = "<red>Security Alert: Connection from IP <ip> has been blocked due to safety violations.</red>"
            messages["banned-kick-screen"] = "<red>You are permanently banned from this network.</red>\n<gray>Remaining Time: </gray><yellow><time> Minutes</yellow>\n<gray>Reason: <reason></gray>"
            messages["not-registered"] = "<red>Access Denied! Your account is not whitelisted. Please verify via Discord: /register</red>"
            messages["account-deactivated"] = "<red>Administrative Action: Your account has been disabled by an administrator.</red>"
            messages["secure-login"] = "<green>[AuthCord] </green><gray>Recognized secure IP signature. Direct login authorized.</gray>"
            messages["otp-prompt"] = "<gold>[AuthCord] </gold><yellow>Security Challenge: </yellow><gray>Please enter the verification code sent to your Discord Direct Messages. </gray><red>(<timeout> Seconds remaining)</red>"
            messages["otp-discord-dm"] = "Your verification code to access the server: <code>"
            messages["verification-timeout"] = "<red>Session expired! You failed to verify your identity within the safety window.</red>"
            messages["pre-verification-command-error"] = "<red>Command execution rejected! Complete the safety verification process first.</red>"
            messages["verification-success"] = "<green><bold>Verified! </bold></green><gray>Identity confirmed, welcome to the network.</gray>"
            messages["wrong-code"] = "<red>Invalid token entered! </red><yellow>(Remaining Attempts: <attempts>)</yellow>"
            messages["too-many-failed-attempts"] = "<red>Security Lockdown: Too many invalid authorization tokens submitted.</red>"

            // New entries: previously the in-game /authcord command bypassed
            // the message system entirely with hardcoded mm.deserialize(...)
            // calls. Centralized here for consistency / configurability.
            messages["no-permission"] = "<red>You do not have permission to use this command.</red>"
            messages["cmd-usage"] = "<red>Usage: /authcord (ban / unban / ipban / unbanip) [target]</red>"
            messages["cmd-account-deactivated"] = "<green>Account <player> deactivated.</green>"
            messages["cmd-account-reactivated"] = "<green>Account <player> reactivated and bans cleared.</green>"
            messages["cmd-ip-banned"] = "<green>IP <ip> banned permanently.</green>"
            messages["cmd-ip-unbanned"] = "<green>IP <ip> unbanned.</green>"

            // New entry: the Discord -> Minecraft chat bridge format. Replaces
            // the old "messages.prefix" config key, which only covered the
            // prefix and still concatenated raw Discord content afterwards.
            messages["discord-bridge-format"] = "<aqua>[Discord] </aqua><white><author>: <message></white>"

            // NOTE: the old "jda-error" and "user-not-found" entries were
            // removed - they were never actually referenced by getMsg(); the
            // real call sites use AuthCord.log(...) with their own inline
            // strings instead. Dead entries removed rather than left to confuse
            // future readers/admins editing config.yml.

            // ===== Discord-side messages (plain text / Discord markdown) =====
            discordMessages["discord-no-permission"] = "You do not have permission to use this command."
            discordMessages["discord-register-success"] = "Your account has been registered successfully!"
            discordMessages["discord-register-failed"] = "Registration failed. That username may already be registered."
            discordMessages["discord-limit-reached"] = "You have reached the maximum number of linked accounts."
            discordMessages["discord-invalid-username"] = "Invalid Minecraft username: **%input%**. Usernames may only contain letters (a-z, A-Z), numbers, and underscores (_), and must be 3-16 characters long. No spaces or special/accented characters (e.g. ç, ğ, ş, ü) are allowed."
            discordMessages["discord-ban-success"] = "Account **%player%** has been banned."
            discordMessages["discord-unban-success"] = "Account **%player%** has been unbanned and bans cleared."
            discordMessages["discord-ipban-success"] = "IP **%ip%** has been banned permanently."
            discordMessages["discord-unbanip-success"] = "IP **%ip%** has been unbanned."

            // NEW: previously this was hardcoded as "**${player.name}**: $message"
            // directly in ChatListener.onChat with no way to customize it.
            discordMessages["discord-chat-format"] = "**%player%**: %message%"

            // Game event logs sent to Discord. Previously had inline defaults
            // in GameEventListener; centralized here.
            discordMessages["discord-death-log"] = "\uD83D\uDC80 **%player%** died: %message%"
            discordMessages["discord-advancement-log"] = "\uD83C\uDFC6 **%player%** made advancement [%advancement%]"
        }

        /**
         * Looks up a message template (config override first, falling back to
         * the built-in default) and resolves <placeholder> tags with the given
         * values via Placeholder.unparsed - see note above on injection safety.
         *
         * NOTE: previously this checked the hardcoded `messages` map FIRST and
         * only fell back to config.getString(...) if absent. Since every key
         * used here always exists in `messages`, the config fallback was
         * unreachable dead code - admins could never override these strings via
         * config.yml. Config now takes precedence as originally intended.
         */
        fun getMsg(path: String, vararg placeholders: Pair<String, String>): Component {
            val raw = instance.config.getString("messages.$path")
                ?: messages[path]
                ?: "<red>Missing string: $path</red>"
            val resolvers: List<TagResolver> = placeholders.map { (key, value) -> Placeholder.unparsed(key, value) }
            return mm.deserialize(raw, TagResolver.resolver(resolvers))
        }

        /**
         * Looks up a Discord-facing message (config override first, falling back
         * to the discordMessages defaults) and substitutes %placeholder% tokens
         * with plain String.replace. Returns a plain String suitable for JDA's
         * sendMessage/reply - NOT a MiniMessage Component, since Discord doesn't
         * understand MiniMessage tags (it has its own markdown).
         */
        fun getDiscordMsg(path: String, vararg placeholders: Pair<String, String>): String {
            var raw = instance.config.getString("messages.$path")
                ?: discordMessages[path]
                ?: "Missing string: $path"
            placeholders.forEach { (key, value) -> raw = raw.replace("%$key%", value) }
            return raw
        }

        fun log(msg: String) {
            val thread = if (Bukkit.isPrimaryThread()) "Sync" else "Async"
            Bukkit.getConsoleSender().sendMessage(mm.deserialize("<dark_gray>[AuthCord] [<gray>$thread<dark_gray>] <white>$msg"))
        }
    }

    override fun onEnable() {
        instance = this
        saveDefaultConfig()
        loadConfig()

        if (discordToken.isBlank() || channelId.isBlank() || serverId.isBlank()) {
            log("<red>CRITICAL ERROR: Discord Token or IDs are missing in config.yml!")
            Bukkit.getPluginManager().disablePlugin(this)
            return
        }

        DiscordManager.startBot(discordToken)
        SQLManager.connectDB()

        getCommand("authcord")?.setExecutor(this)
        getCommand("authcord")?.tabCompleter = this

        Bukkit.getScheduler().runTaskAsynchronously(this, Runnable {
            try {
                log("Registering Discord Admin Commands...")
                DiscordManager.jda?.awaitReady()
                DiscordManager.jda?.getGuildById(serverId)?.updateCommands()?.addCommands(
                    Commands.slash("register", "Register your account"),
                    Commands.slash("ban", "Ban a player account").addOption(OptionType.STRING, "player", "Username", true),
                    Commands.slash("unban", "Unban a player and clear bans").addOption(OptionType.STRING, "player", "Username", true),
                    Commands.slash("ipban", "Ban an IP address").addOption(OptionType.STRING, "ip", "IP", true),
                    Commands.slash("unbanip", "Unban an IP address").addOption(OptionType.STRING, "ip", "IP", true)
                )?.queue()
                log("Discord commands synchronized.")
            } catch (e: Exception) { log("<red>Discord sync error: ${e.message}") }
        })

        val pm = server.pluginManager
        pm.registerEvents(ChatListener(), this)
        pm.registerEvents(AuthListener(), this)
        pm.registerEvents(GameEventListener(), this)

        Bukkit.getConsoleSender().sendMessage(getMsg("system-activated"))
    }

    override fun onDisable() {
        DiscordManager.jda?.shutdownNow()
    }

    fun loadConfig() {
        reloadConfig()
        val conf = config
        // Replaced conf.getString(path, "")!! with ?: "" - getString(path, def)
        // is a nullable platform type in Kotlin, so the !! assertions could in
        // theory NPE-crash the plugin on enable if the underlying API ever
        // returned null despite a non-null default.
        discordToken = conf.getString("settings.discord-token") ?: ""
        serverId = conf.getString("settings.server-id") ?: ""
        channelId = conf.getString("settings.channel-id") ?: ""
        authTimeout = conf.getLong("settings.auth-timeout", 30)
        maxAttempts = conf.getInt("settings.max-attempts", 3)
        banDuration = conf.getLong("settings.ban-duration", 24)
        trustLocalhost = conf.getBoolean("settings.trust-localhost", false)
        memberId = conf.getString("roles.member-id") ?: ""
        adminId = conf.getString("roles.admin-id") ?: ""
        maxAccountsMember = conf.getInt("settings.max-accounts-member", 1)
        maxAccountsAdmin = conf.getInt("settings.max-accounts-admin", 3)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("authcord.admin")) {
            sender.sendMessage(getMsg("no-permission"))
            return true
        }

        if (args.size < 2) {
            sender.sendMessage(getMsg("cmd-usage"))
            return true
        }

        val action = args[0].lowercase()
        val target = args[1]

        Bukkit.getScheduler().runTaskAsynchronously(this, Runnable {
            when (action) {
                "ban" -> {
                    SQLManager.setAccountStatus(target, false)
                    sender.sendMessage(getMsg("cmd-account-deactivated", "player" to target))
                    Bukkit.getScheduler().runTask(this, Runnable { Bukkit.getPlayerExact(target)?.kick(getMsg("account-deactivated")) })
                }
                "unban" -> {
                    SQLManager.setAccountStatus(target, true)
                    SQLManager.getPlayerData(target.lowercase())?.lastIp?.let { SQLManager.unbanIp(it) }
                    Bukkit.getScheduler().runTask(this, Runnable {
                        @Suppress("DEPRECATION")
                        Bukkit.getBanList<org.bukkit.BanList<Any>>(BanList.Type.NAME).pardon(target.lowercase())
                    })
                    sender.sendMessage(getMsg("cmd-account-reactivated", "player" to target))
                }
                "ipban" -> {
                    val expiry = System.currentTimeMillis() + (3650L * 24 * 3600 * 1000)
                    SQLManager.banIp(target, expiry)
                    sender.sendMessage(getMsg("cmd-ip-banned", "ip" to target))
                }
                "unbanip" -> {
                    SQLManager.unbanIp(target)
                    sender.sendMessage(getMsg("cmd-ip-unbanned", "ip" to target))
                }
            }
        })
        return true
    }

    /**
     * Provides tab-complete suggestions for /authcord. Without this, Paper's
     * Brigadier-based command system shows no argument suggestions at all for
     * a plain CommandExecutor, which can make subcommands like "ipban"/"unban"
     * look like they don't exist even though they work fine when typed manually.
     */
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("authcord.admin")) return emptyList()
        return when (args.size) {
            1 -> listOf("ban", "unban", "ipban", "unbanip").filter { it.startsWith(args[0].lowercase()) }
            else -> emptyList()
        }
    }
}

object DiscordManager {
    var jda: JDA? = null

    fun startBot(token: String) {
        if (token.isBlank()) return
        try {
            jda = JDABuilder.createDefault(token)
                // DIRECT_MESSAGES intent removed: the bot only SENDS private
                // messages (OTP codes) via openPrivateChannel().sendMessage(),
                // which doesn't require the intent used for RECEIVING DMs.
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(DiscordListener())
                .build()
        } catch (e: Exception) { AuthCord.log("<red>JDA Error: ${e.message}") }
    }

    /**
     * Sends a private message to a Discord user.
     *
     * Previously the whole body was wrapped in
     * Bukkit.getScheduler().runTaskAsynchronously(...), but JDA's .queue()
     * calls are already asynchronous/non-blocking, so that was an unnecessary
     * extra hop through the Bukkit scheduler.
     */
    fun sendPrivateMessage(userId: String, content: String) {
        if (userId.isBlank()) return
        jda?.retrieveUserById(userId)?.queue({ user ->
            user.openPrivateChannel().queue { it.sendMessage(content).queue() }
        }, { AuthCord.log("<red>User not found: $userId") })
    }
}

class AuthListener : Listener {
    companion object {
        val pendingSessions = ConcurrentHashMap<UUID, AuthSession>()
        val playerDataCache = ConcurrentHashMap<UUID, PlayerData>()
        private val kickAttempts = ConcurrentHashMap<String, Int>()

        fun recordKick(ip: String) {
            val attempts = (kickAttempts[ip] ?: 0) + 1
            kickAttempts[ip] = attempts
            if (attempts >= AuthCord.maxAttempts) {
                kickAttempts.remove(ip)
                val unbanAt = System.currentTimeMillis() + (AuthCord.banDuration * 3600000L)
                Bukkit.getScheduler().runTaskAsynchronously(AuthCord.instance, Runnable { SQLManager.banIp(ip, unbanAt) })
                Bukkit.getScheduler().runTask(AuthCord.instance, Runnable { Bukkit.broadcast(AuthCord.getMsg("ip-banned-broadcast", "ip" to ip)) })
            }
        }

        /**
         * Clears the failed-attempt counter for an IP. Previously never called
         * on success, so a player who failed once or twice and then verified
         * correctly kept a "live" counter forever - a single unrelated failure
         * much later could then trip the IP ban with fewer than maxAttempts
         * genuine failures in that session.
         */
        fun resetAttempts(ip: String) {
            kickAttempts.remove(ip)
        }
    }

    @EventHandler
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        val ip = event.address.hostAddress
        val unbanAt = SQLManager.getUnbanTime(ip)
        if (unbanAt != null) {
            val now = System.currentTimeMillis()
            if (unbanAt > now) {
                val rem = (unbanAt - now) / 60000
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, AuthCord.getMsg("banned-kick-screen", "time" to rem.toString(), "reason" to "Security"))
                return
            } else SQLManager.unbanIp(ip)
        }

        val playerData = SQLManager.getPlayerData(event.name)
        if (playerData == null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, AuthCord.getMsg("not-registered"))
        } else if (!playerData.isActive) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, AuthCord.getMsg("account-deactivated"))
        } else {
            playerDataCache[event.uniqueId] = playerData
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val ip = player.address?.address?.hostAddress ?: ""
        val data = playerDataCache[player.uniqueId] ?: return

        val trusted = data.lastIp == ip ||
                (AuthCord.trustLocalhost && (ip == "127.0.0.1" || ip == "0:0:0:0:0:0:0:1"))

        // Restructured: previously a session/OTP was always created and
        // inserted into pendingSessions, then immediately removed again if the
        // IP turned out to be trusted. Checking trust first avoids that
        // unnecessary work entirely.
        if (trusted) {
            resetAttempts(ip)
            Bukkit.getScheduler().runTaskAsynchronously(AuthCord.instance, Runnable { SQLManager.updateLastLogin(player.name) })
            player.sendMessage(AuthCord.getMsg("secure-login"))
            return
        }

        val otpCode = (100000..999999).random().toString()
        val session = AuthSession(otpCode, UUID.randomUUID())
        pendingSessions[player.uniqueId] = session

        player.sendMessage(AuthCord.getMsg("otp-prompt", "timeout" to AuthCord.authTimeout.toString()))
        val otpMsg = PlainTextComponentSerializer.plainText().serialize(AuthCord.getMsg("otp-discord-dm", "code" to otpCode))
        DiscordManager.sendPrivateMessage(data.discordId, otpMsg)

        Bukkit.getScheduler().runTaskLater(AuthCord.instance, Runnable {
            val current = pendingSessions[player.uniqueId]
            if (current != null && current.sessionId == session.sessionId) {
                player.kick(AuthCord.getMsg("verification-timeout"), PlayerKickEvent.Cause.PLUGIN)
                recordKick(ip)
            }
        }, AuthCord.authTimeout * 20L)
    }

    @EventHandler fun onQuit(e: PlayerQuitEvent) { playerDataCache.remove(e.player.uniqueId); pendingSessions.remove(e.player.uniqueId) }
    @EventHandler fun onCommand(e: PlayerCommandPreprocessEvent) { if (pendingSessions.containsKey(e.player.uniqueId)) { e.isCancelled = true; e.player.sendMessage(AuthCord.getMsg("pre-verification-command-error")) } }
    @EventHandler fun onMove(e: PlayerMoveEvent) { if (pendingSessions.containsKey(e.player.uniqueId) && e.hasChangedPosition()) e.isCancelled = true }
    @EventHandler fun onDamage(e: EntityDamageEvent) { if (e.entity is org.bukkit.entity.Player && pendingSessions.containsKey(e.entity.uniqueId)) e.isCancelled = true }
    @EventHandler fun onDrop(e: PlayerDropItemEvent) { if (pendingSessions.containsKey(e.player.uniqueId)) e.isCancelled = true }
    @EventHandler fun onBlockBreak(e: org.bukkit.event.block.BlockBreakEvent) { if (pendingSessions.containsKey(e.player.uniqueId)) e.isCancelled = true }
    @EventHandler fun onBlockPlace(e: org.bukkit.event.block.BlockPlaceEvent) { if (pendingSessions.containsKey(e.player.uniqueId)) e.isCancelled = true }
    @EventHandler fun onInteract(e: PlayerInteractEvent) { if (pendingSessions.containsKey(e.player.uniqueId)) e.isCancelled = true }
}

class GameEventListener : Listener {
    @EventHandler
    fun onDeath(e: PlayerDeathEvent) {
        // Skip if death messages are disabled (gamerule) - previously this
        // would post "💀 Player died: " with an empty message body.
        val deathComponent = e.deathMessage() ?: return
        val msg = PlainTextComponentSerializer.plainText().serialize(deathComponent)
        val text = AuthCord.getDiscordMsg("discord-death-log", "player" to e.entity.name, "message" to msg)
        DiscordManager.jda?.getTextChannelById(AuthCord.channelId)?.sendMessage(text)?.queue()
    }

    @EventHandler
    fun onAdv(e: PlayerAdvancementDoneEvent) {
        if (e.advancement.key.key.startsWith("recipes/")) return
        val title = e.advancement.display?.title() ?: return
        val titlePlain = PlainTextComponentSerializer.plainText().serialize(title)
        val text = AuthCord.getDiscordMsg("discord-advancement-log", "player" to e.player.name, "advancement" to titlePlain)
        DiscordManager.jda?.getTextChannelById(AuthCord.channelId)?.sendMessage(text)?.queue()
    }
}

class ChatListener : Listener {
    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        val player = event.player; val uuid = player.uniqueId
        val message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()

        if (AuthListener.pendingSessions.containsKey(uuid)) {
            val session = AuthListener.pendingSessions[uuid] ?: return
            event.isCancelled = true
            if (session.expectedCode == message) {
                val ip = player.address?.address?.hostAddress ?: ""
                val cache = AuthListener.playerDataCache[uuid]
                if (cache != null) AuthListener.playerDataCache[uuid] = cache.copy(lastIp = ip)
                AuthListener.resetAttempts(ip)
                Bukkit.getScheduler().runTaskAsynchronously(AuthCord.instance, Runnable { SQLManager.updatePlayerLogin(player.name, ip) })
                AuthListener.pendingSessions.remove(uuid)
                player.sendMessage(AuthCord.getMsg("verification-success"))
            } else {
                session.wrongAttempts++
                if (session.wrongAttempts < AuthCord.maxAttempts) {
                    player.sendMessage(AuthCord.getMsg("wrong-code", "attempts" to (AuthCord.maxAttempts - session.wrongAttempts).toString()))
                } else {
                    val ip = player.address?.address?.hostAddress ?: ""
                    AuthListener.pendingSessions.remove(uuid)
                    Bukkit.getScheduler().runTask(AuthCord.instance, Runnable {
                        player.kick(AuthCord.getMsg("too-many-failed-attempts"), PlayerKickEvent.Cause.PLUGIN)
                        AuthListener.recordKick(ip)
                    })
                }
            }
            return
        }

        // Previously hardcoded as "**${player.name}**: $message" with no way to
        // customize it. Now reads "messages.discord-chat-format" from config,
        // falling back to the same default ("**%player%**: %message%").
        if (AuthCord.channelId.isNotEmpty()) {
            val text = AuthCord.getDiscordMsg("discord-chat-format", "player" to player.name, "message" to message)
            DiscordManager.jda?.getTextChannelById(AuthCord.channelId)?.sendMessage(text)?.queue()
        }
    }
}

class DiscordListener : ListenerAdapter() {
    override fun onMessageReceived(e: MessageReceivedEvent) {
        if (e.author.isBot || e.channel.id != AuthCord.channelId) return

        // SECURITY FIX: previously this did:
        //   val prefix = ... config string ...
        //   MiniMessage.miniMessage().deserialize("$prefix<white>${e.author.name}: ${e.message.contentDisplay}")
        // i.e. raw, fully attacker-controlled Discord message content was
        // concatenated straight into a MiniMessage string and deserialized,
        // then broadcast to every player. A Discord user could send something
        // like <click:open_url:'https://evil.example'>click here</click> and
        // it would render as a live clickable component in Minecraft chat.
        //
        // getMsg() now resolves <author> and <message> via Placeholder.unparsed,
        // which inserts them as literal text - they cannot be interpreted as
        // MiniMessage tags regardless of content.
        val comp = AuthCord.getMsg("discord-bridge-format", "author" to e.author.name, "message" to e.message.contentDisplay)
        Bukkit.getScheduler().runTask(AuthCord.instance, Runnable { Bukkit.broadcast(comp) })
    }

    override fun onSlashCommandInteraction(e: SlashCommandInteractionEvent) {
        val member = e.member ?: return; val isAdmin = member.roles.any { it.id == AuthCord.adminId }

        when (e.name) {
            "register" -> {
                // min/max length is enforced client-side by Discord as a UX nicety,
                // but the character set (letters/digits/underscore only) can't be
                // restricted here - that's validated server-side in onModalInteraction.
                val input = TextInput.create("username", "Minecraft Username", TextInputStyle.SHORT)
                    .setRequired(true)
                    .setMinLength(3)
                    .setMaxLength(16)
                    .build()
                e.replyModal(Modal.create("register-modal", "Registration").addComponents(ActionRow.of(input)).build()).queue()
            }
            "ban" -> {
                if (!isAdmin) { e.reply(AuthCord.getDiscordMsg("discord-no-permission")).setEphemeral(true).queue(); return }
                val t = e.getOption("player")?.asString ?: return
                SQLManager.setAccountStatus(t, false)
                e.reply(AuthCord.getDiscordMsg("discord-ban-success", "player" to t)).queue()
                Bukkit.getScheduler().runTask(AuthCord.instance, Runnable { Bukkit.getPlayerExact(t)?.kick(AuthCord.getMsg("account-deactivated")) })
            }
            "unban" -> {
                if (!isAdmin) { e.reply(AuthCord.getDiscordMsg("discord-no-permission")).setEphemeral(true).queue(); return }
                val t = e.getOption("player")?.asString ?: return
                SQLManager.setAccountStatus(t, true)
                SQLManager.getPlayerData(t.lowercase())?.lastIp?.let { SQLManager.unbanIp(it) }
                Bukkit.getScheduler().runTask(AuthCord.instance, Runnable {
                    @Suppress("DEPRECATION")
                    Bukkit.getBanList<org.bukkit.BanList<Any>>(BanList.Type.NAME).pardon(t.lowercase())
                })
                e.reply(AuthCord.getDiscordMsg("discord-unban-success", "player" to t)).queue()
            }
            "ipban" -> {
                if (!isAdmin) { e.reply(AuthCord.getDiscordMsg("discord-no-permission")).setEphemeral(true).queue(); return }
                val ip = e.getOption("ip")?.asString ?: return
                SQLManager.banIp(ip, System.currentTimeMillis() + (3650L * 24 * 3600 * 1000))
                e.reply(AuthCord.getDiscordMsg("discord-ipban-success", "ip" to ip)).queue()
            }
            "unbanip" -> {
                if (!isAdmin) { e.reply(AuthCord.getDiscordMsg("discord-no-permission")).setEphemeral(true).queue(); return }
                val ip = e.getOption("ip")?.asString ?: return
                SQLManager.unbanIp(ip)
                e.reply(AuthCord.getDiscordMsg("discord-unbanip-success", "ip" to ip)).queue()
            }
        }
    }

    override fun onModalInteraction(e: ModalInteractionEvent) {
        if (e.modalId == "register-modal") {
            val user = e.getValue("username")?.asString?.trim() ?: return
            val member = e.member ?: return

            // Reject usernames that don't match vanilla Minecraft naming rules
            // (3-16 chars, ASCII letters/digits/underscore only) BEFORE checking
            // permissions or touching the database. Previously any string -
            // including ones with spaces or accented characters like "ç" - was
            // accepted and inserted straight into the players table.
            if (!AuthCord.VALID_USERNAME_REGEX.matches(user)) {
                e.reply(AuthCord.getDiscordMsg("discord-invalid-username", "input" to user)).setEphemeral(true).queue()
                return
            }

            if (!member.roles.any { it.id == AuthCord.memberId || it.id == AuthCord.adminId }) {
                e.reply(AuthCord.getDiscordMsg("discord-no-permission")).setEphemeral(true).queue(); return
            }
            Bukkit.getScheduler().runTaskAsynchronously(AuthCord.instance, Runnable {
                val limit = if (member.roles.any { it.id == AuthCord.adminId }) AuthCord.maxAccountsAdmin else AuthCord.maxAccountsMember
                if (SQLManager.getAccountCount(member.id) < limit) {
                    if (SQLManager.registerPlayer(user, member.id)) e.reply(AuthCord.getDiscordMsg("discord-register-success")).setEphemeral(true).queue()
                    else e.reply(AuthCord.getDiscordMsg("discord-register-failed")).setEphemeral(true).queue()
                } else e.reply(AuthCord.getDiscordMsg("discord-limit-reached")).setEphemeral(true).queue()
            })
        }
    }
}

data class PlayerData(val username: String, val discordId: String, val lastIp: String?, val registerDate: Long, val lastLogin: Long, val isActive: Boolean)
data class AuthSession(val expectedCode: String, val sessionId: UUID, var wrongAttempts: Int = 0)

/**
 * All methods are now @Synchronized.
 *
 * A single shared java.sql.Connection is accessed concurrently from multiple
 * async Bukkit tasks (auth checks on join, chat verification, admin commands,
 * Discord slash commands/modals). SQLite connections are not safe for
 * concurrent statement execution from multiple threads at once and this could
 * intermittently throw SQLITE_BUSY / SQLITE_MISUSE under load. Synchronizing
 * on the singleton object serializes access cheaply (these are all tiny
 * single-row reads/writes).
 */
object SQLManager {
    private var conn: Connection? = null

    @Synchronized
    fun connectDB() {
        try {
            Class.forName("org.sqlite.JDBC")
            conn = DriverManager.getConnection("jdbc:sqlite:${AuthCord.instance.dataFolder.absolutePath}/authcord.db")
            conn?.createStatement()?.use { it.execute("PRAGMA journal_mode=WAL;") }
            conn?.createStatement()?.use {
                it.executeUpdate("CREATE TABLE IF NOT EXISTS players (username TEXT PRIMARY KEY, discord_id TEXT, last_ip TEXT, register_date BIGINT, last_login BIGINT, is_active INTEGER DEFAULT 1)")
                it.executeUpdate("CREATE TABLE IF NOT EXISTS banned_ips (ip TEXT PRIMARY KEY, unban_at BIGINT)")
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    @Synchronized
    fun setAccountStatus(user: String, active: Boolean) {
        try { conn?.prepareStatement("UPDATE players SET is_active = ? WHERE username = ?")?.use {
            it.setInt(1, if (active) 1 else 0)
            it.setString(2, user.lowercase())
            it.executeUpdate()
        } } catch (e: Exception) { e.printStackTrace() }
    }

    @Synchronized
    fun registerPlayer(user: String, id: String): Boolean {
        return try { conn?.prepareStatement("INSERT INTO players (username, discord_id, register_date, last_login) VALUES (?, ?, ?, ?)")?.use {
            it.setString(1, user.lowercase()); it.setString(2, id); it.setLong(3, System.currentTimeMillis()); it.setLong(4, 0L); it.executeUpdate() > 0
        } ?: false } catch (e: Exception) { false }
    }

    @Synchronized
    fun getPlayerData(user: String): PlayerData? {
        return try { conn?.prepareStatement("SELECT * FROM players WHERE username = ?")?.use {
            it.setString(1, user.lowercase()); val rs = it.executeQuery()
            // discord_id defaulted to "" if the column is somehow null -
            // PlayerData.discordId is a non-null String.
            if (rs.next()) PlayerData(rs.getString(1), rs.getString(2) ?: "", rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getInt(6) == 1) else null
        } } catch (e: Exception) { null }
    }

    @Synchronized
    fun updatePlayerLogin(user: String, ip: String) {
        try { conn?.prepareStatement("UPDATE players SET last_ip = ?, last_login = ? WHERE username = ?")?.use {
            it.setString(1, ip); it.setLong(2, System.currentTimeMillis()); it.setString(3, user.lowercase()); it.executeUpdate()
        } } catch (e: Exception) { e.printStackTrace() }
    }

    @Synchronized
    fun updateLastLogin(user: String) {
        try { conn?.prepareStatement("UPDATE players SET last_login = ? WHERE username = ?")?.use {
            it.setLong(1, System.currentTimeMillis()); it.setString(2, user.lowercase()); it.executeUpdate()
        } } catch (e: Exception) { e.printStackTrace() }
    }

    @Synchronized
    fun getAccountCount(id: String): Int {
        return try { conn?.prepareStatement("SELECT COUNT(*) FROM players WHERE discord_id = ?")?.use {
            it.setString(1, id); val rs = it.executeQuery(); if (rs.next()) rs.getInt(1) else 0
        } ?: 0 } catch (e: Exception) { 0 }
    }

    @Synchronized
    fun banIp(ip: String, time: Long) {
        try { conn?.prepareStatement("REPLACE INTO banned_ips (ip, unban_at) VALUES (?, ?)")?.use {
            it.setString(1, ip); it.setLong(2, time); it.executeUpdate()
        } } catch (e: Exception) { e.printStackTrace() }
    }

    @Synchronized
    fun unbanIp(ip: String) {
        try { conn?.prepareStatement("DELETE FROM banned_ips WHERE ip = ?")?.use {
            it.setString(1, ip); it.executeUpdate()
        } } catch (e: Exception) { e.printStackTrace() }
    }

    @Synchronized
    fun getUnbanTime(ip: String): Long? {
        return try { conn?.prepareStatement("SELECT unban_at FROM banned_ips WHERE ip = ?")?.use {
            it.setString(1, ip); val rs = it.executeQuery(); if (rs.next()) rs.getLong(1) else null
        } } catch (e: Exception) { null }
    }
}