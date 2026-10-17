# UXGPT for Android

Capacitor Android shell for [UXGPT](https://uxgpt.vercel.app), the AI crypto trading assistant.

- Download the latest signed APK: https://github.com/faltusabhateam-max/uxgpt-android/releases/latest/download/UXGPT.apk
- The app loads `https://uxgpt.vercel.app/chat` inside a WebView and identifies itself with the `UXGPTApp/1.0` user-agent token. App routes are only served to the app.
- Builds run on GitHub Actions (`.github/workflows/release.yml`) on every `v*` tag. Signing material is stored only in repository Actions secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and is never committed.

## Enabling the Actions build
The workflow lives in `ci/release.yml` because the token used to create this repo lacked the `workflow` scope.
To enable it: `gh auth refresh -h github.com -s workflow`, then `git mv ci/release.yml .github/workflows/release.yml && git commit -m "Enable release workflow" && git push`, then push a tag (`git tag v1.0.1 && git push --tags`).
