# Hermes Editor (native Android)

A lightweight, native Kotlin Android text editor. This replaces the old Python/Kivy build.

## Build it in GitHub

1. Create an empty GitHub repository, then upload these files to its `main` branch.
2. GitHub Actions runs automatically after the push. Or open **Actions** → **Build Hermes Editor APK** → **Run workflow**.
3. Download **Hermes-Editor-debug-apk** from the completed workflow's Artifacts section.

No personal access token is needed for GitHub Actions to build this project. A token is only needed by an external agent that must push changes into your repository. Do not put any token in this project.

## Release signing

The included workflow intentionally produces a debug APK for testing. Before publishing or distributing broadly, create your own release keystore and add it as private GitHub repository secrets. Never commit a keystore or passwords.

## Core behavior

- Native Android file open/save dialogs
- Opens text files sent from a file manager
- Light/dark themes saved between launches
- Draft cache and automatic recovery
- Action bar moves above the real keyboard using Android IME insets
- New/Open prompts before discarding unsaved text
