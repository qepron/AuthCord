# AuthCord 🛡️ 

AuthCord is a high-performance, security-hardened Minecraft server plugin built with Kotlin that bridges server authentication with Discord. It replaces traditional, vulnerable password-based login systems with a robust, Discord-verified Whitelist and One-Time Password (OTP) infrastructure.

---

## 🚀 Key Features

* **Discord-Linked Whitelist & OTP:** Players cannot join unless registered via Discord. When logging in from an unverified IP, a secure 6-digit One-Time Password (OTP) is dispatched directly to their Discord DMs.
* **Anti-Brute Force & Automated IP Ban:** Asynchronously monitors failed authentication sessions. Exceeding the maximum allowed attempts triggers an automatic, timed IP lockdown to mitigate malicious access.
* **Remote Administrative Control:** Implements full Discord Slash Commands (`/ban`, `/unban`, `/ipban`, `/unbanip`) enabling administrators to manage server security constraints without logging into the game.
* **Account Quota Enforcement:** Tiered, database-backed registration limits that prevent multi-account abuse (configurable separately for standard members and administrators).
* **Bi-directional Modern Chat Bridge:** Seamlessly synchronizes Minecraft chat with a specific Discord channel using Adventure MiniMessage to guarantee complete injection-exploit safety.
* **Automated Discord Game Logging:** Forwards detailed death events (respecting gamerules) and major player advancements directly to your community Discord server.

---

## 🛠️ Technical Architecture

* **Thread Safety:** Powered by concurrent memory primitives (`ConcurrentHashMap`) and an event-driven loop that separates synchronization overhead from the main server tick thread.
* **High-Performance Persistence:** Operates on an embedded SQLite database utilizing high-concurrency **WAL (Write-Ahead Logging)** mode.
* **Thread-Safe DB Access:** All persistence layer operations are strictly `@Synchronized` to completely eliminate `SQLITE_BUSY` exceptions during heavy parallel login spikes.
* **Exploit Immunity:** Attacker-controlled text formatting strings (usernames, chat content) are parsed via stateless strict literal placeholders (`Placeholder.unparsed`), ensuring absolute protection against unauthorized chat formatting injection.
* **Graceful Degradation:** Wrapped heavily in granular exception-handling routines to preserve server availability even in the event of an upstream Discord API interruption.

---

## ⚙️ Configuration (`config.yml`)

The plugin features a robust config layout where all in-game text formatting (MiniMessage) and Discord layout templates (Discord Markdown) are completely customizable:

```yaml
settings:
  discord-token: ""
  server-id: ""
  channel-id: ""
  auth-timeout: 30          # Seconds the player has to enter their OTP code
  max-attempts: 3           # Wrong codes before an automated IP-ban
  ban-duration: 24          # Hours an IP stays banned after max-attempts
  trust-localhost: false
  max-accounts-member: 1    
  max-accounts-admin: 3     

roles:
  member-id: "" # The Discord Role ID given to players after they successfully register via /register
  admin-id: "" # The Discord Role ID for server staff. Grants permission to bypass quotas and use admin slash commands
