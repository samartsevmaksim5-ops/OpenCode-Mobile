<p align="center">
  <a href="https://opencode.ai">
    <picture>
      <source srcset="https://raw.githubusercontent.com/anomalyco/opencode/dev/packages/console/app/src/asset/logo-ornate-dark.svg" media="(prefers-color-scheme: dark)">
      <source srcset="https://raw.githubusercontent.com/anomalyco/opencode/dev/packages/console/app/src/asset/logo-ornate-light.svg" media="(prefers-color-scheme: light)">
      <img src="https://raw.githubusercontent.com/anomalyco/opencode/dev/packages/console/app/src/asset/logo-ornate-light.svg" alt="OpenCode logo">
    </picture>
  </a>
</p>

<p align="center">OpenCode on your Android phone. The real one - the original server and web UI, running in a Linux environment on the device itself.</p>

<p align="center">
  <a href="https://t.me/OpenCodeMob"><img alt="Telegram" src="https://img.shields.io/badge/telegram-%40OpenCodeMob-2AABEE?style=flat-square&logo=telegram&labelColor=2AABEE" /></a>
  <a href="https://github.com/samartsevmaksim5-ops/OpenCode-Mobile/releases"><img alt="APK release" src="https://img.shields.io/github/v/release/samartsevmaksim5-ops/OpenCode-Mobile?style=flat-square&label=apk" /></a>
  <a href="https://github.com/samartsevmaksim5-ops/OpenCode-Mobile/actions/workflows/build-apk.yml"><img alt="Build status" src="https://img.shields.io/github/actions/workflow/status/samartsevmaksim5-ops/OpenCode-Mobile/build-apk.yml?style=flat-square&branch=main" /></a>
</p>

<p align="center">По-русски: новости и обсуждение - <a href="https://t.me/OpenCodeMob">Telegram @OpenCodeMob</a>.</p>

---

### Installation (Android APK)

Grab the APK from [**Releases**](https://github.com/samartsevmaksim5-ops/OpenCode-Mobile/releases): every build produces an `ota-app-...` release with `opencode-android-<version>.apk` inside. Install it on an arm64 phone (Android 8 or newer); Android will ask to allow installs from the browser. The first launch unpacks the Linux environment (about a minute) and starts the server - after that you are in OpenCode.

Updates are OTA: the app checks the same Releases page and can update itself, the server included, without losing your profiles.

> [!TIP]
> The releases also carry `manifest.json` and `manifest.json.sig` - the signed files installed apps use to verify updates.

### Building the APK yourself

1. Fork or upload this project to a **public** GitHub repository (arm64 runners are free for public repos, and the Linux userland is built on arm64).
2. Open the **Actions** tab, pick **Build APK**, press **Run workflow**. Or push a tag like `v1.0.5` and the build starts on its own.
3. In 40-90 minutes the **Releases** page has the APK. Add the `ANDROID_KEYSTORE_*` secrets to sign with your own key; otherwise the public development key from the repository is used.

### How it works

Inside the app an Ubuntu 24.04 (arm64) userland runs via `proot`, the original `opencode serve` runs in it, and the app shows the original OpenCode web interface in a WebView. No root required.

```
Android app (Kotlin) -- WebView with the OpenCode UI
             |                        ^
             v                        | http://127.0.0.1:4096
     Foreground service -- proot -- Ubuntu 24.04 arm64
                             |- opencode serve
                             |- node, npm, python3, uv, git, ripgrep, curl
                             \- preview, android-open, android-save, android-notify, android-widget
```

### What works

- **The original OpenCode interface and server** from upstream, not a re-implementation.
- **Sign-in with Google by choice.** The Console account unlocks Zen and paid models; profiles keep separate sessions, keys and history.
- **Connectors.** Provider API keys (Anthropic, OpenAI, Google, xAI, Groq, OpenRouter, DeepSeek, Mistral and more) and custom OpenAI-compatible endpoints, stored the way the CLI stores them (`auth.json`, `opencode.json`).
- **Plugins.** Install from a file on the phone, from a link, or as an npm package; they land in the profile's plugins directory.
- **Rules (README.md)** for all models or for one specific model, wired into `instructions` and the system prompt the way upstream expects.
- **Floating preview windows.** `preview 5173` opens a window the person can move, resize, minimize and keep several of.
- **Home-screen widgets** the agent creates and updates itself with `android-widget`.
- **Files in Downloads, background work, terminal keys row, floating menu** and the other mobile conveniences.
- **OTA server updates.** New OpenCode builds arrive without reinstalling the app.

### Agents

OpenCode includes two built-in agents you can switch between with the `Tab` key.

- **build** - Default, full-access agent for development work
- **plan** - Read-only agent for analysis and code exploration
  - Denies file edits by default
  - Asks permission before running bash commands
  - Ideal for exploring unfamiliar codebases or planning changes

Also included is a **general** subagent for complex searches and multistep tasks.
This is used internally and can be invoked using `@general` in messages.

Learn more about [agents](https://opencode.ai/v2/docs/agents).

### Documentation

For more info on how to configure OpenCode, [**head over to our docs**](https://opencode.ai/v2/docs).

### Contributing

Issues and pull requests are welcome; for quick feedback, find us in [Telegram](https://t.me/OpenCodeMob).

### Building on OpenCode

OpenCodeMob is an Android port/shell for running OpenCode. If you are working on a project that's related to OpenCode and is using "opencode" as part of its name, for example "opencode-dashboard" or "opencode-mobile", please add a note to your README to clarify that it is not built by the OpenCode team and is not affiliated with us in any way. This project is not built by the OpenCode team and is not affiliated with them.

---

**Join the community** [Telegram @OpenCodeMob](https://t.me/OpenCodeMob) | [OpenCode Discord](https://discord.gg/opencode) | [X.com](https://x.com/opencode)
