# Hermes Agent handoff

Use this project as a native Kotlin Android replacement for the old Python/Kivy Hermes Editor.

## Your task

1. Create a **new GitHub repository** or replace the contents of the existing `Text-editor` repository only after backing up its current branch.
2. Upload every project file, including `.github/workflows/android-release.yml`.
3. Commit to the `main` branch with the message: `feat: native Kotlin Hermes Editor`.
4. Confirm that the GitHub Actions workflow named **Build Hermes Editor APK** starts.
5. When the workflow finishes, report the result and provide the APK artifact location.

## Do not do these things

- Do not use, request, commit, print, or expose a personal access token, signing key, or password in project files.
- Do not add Python, Kivy, Buildozer, SDL, or Python-for-Android files.
- Do not remove the Android file-opening intent filter or IME-aware layout behavior.
- Do not claim the APK has passed device testing unless it was actually installed and tested.

## Expected result

The workflow should produce `Hermes-Editor-debug-apk`. It is for installation and testing only. A future release build must use a private signing key held by the project owner.
