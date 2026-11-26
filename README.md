# GitHub Manager (Android) v1.1

Native Kotlin + Jetpack Compose app that talks to the real GitHub REST API. No mock data.
UI languages: English and Arabic (RTL), switchable in Account > Settings.

## Build
1. Push this folder to a GitHub repository (branch `main`).
2. `.github/workflows/build.yml` builds `app-debug.apk` and uploads it as an artifact (installs Gradle 8.9 itself, no wrapper needed).
3. Download the artifact and install the APK.

## Sign in
- **GitHub login (Device Flow):** create an OAuth App at github.com/settings/developers, tick *Enable Device Flow*, paste its Client ID in the app.
- **Token:** classic token with scopes `repo workflow delete_repo read:org notifications gist admin:repo_hook`.
- Several accounts can be added and switched in Account. Tokens are stored with EncryptedSharedPreferences (Android Keystore).

## Features
- Repositories: search, filters, create, edit, archive, fork, template, transfer, delete with name confirmation, star.
- Files: browse, create, edit, rename, delete, upload from phone. Branches, tags, compare, merge. Commits with diff.
- Upload: whole project from a phone folder through a background queue (WorkManager) that resumes after network loss, with secret scanner and Git LFS (auto above 50 MB or by patterns).
- Local tab (JGit): clone, fetch, pull, commit, push, checkout, merge, interactive conflict resolution (mine / theirs / manual edit).
- Issues, pull requests (review, merge, update branch), milestones, labels, collaborators, webhooks, deploy keys.
- Actions: runs, jobs, logs, cancel, re-run, dispatch, add workflow, artifacts download and install. Secrets (libsodium sealed box), variables, Android signing helper.
- Releases with assets. Security alerts (Dependabot, code scanning, secret scanning). Insights (languages, contributors, traffic).
- Gists, code search, starred repositories, notifications inbox.
- Background notifications (GitHub inbox and finished builds you opened) every ~15 minutes.
- Encrypted local backup of settings and accounts (tokens only if you set a password and opt in).

## Live Build (new)
- Actions tab: a banner appears when a build is running; also a **Live view** button and an icon inside every run.
- Real status every 3 s (no fake bar): elapsed time, steps done / total, jobs and steps with state.
- Project type is detected from real repo files and artifact names: Web, Android, Flutter, Windows, Linux, macOS.
- Web: the repo files at that commit render in a WebView and refresh on every new commit; if sources need a build, the preview comes from the built artifact.
- Android / Flutter: APK artifact with Download and install. Windows / Linux / macOS: download; a screenshot shows if the workflow uploads an artifact named `preview` or `screenshot`.

## Known limits
- Dynamic sentences that include numbers or names are shown in English even in Arabic mode.
- Background alerts are polled by WorkManager (about every 15 minutes), not instant push.
- Large non-LFS files over 90 MB are skipped; LFS needs LFS quota on the account.
- Pull request conflicts: the PR screen has a "Resolve conflicts here" button (clone, merge, resolve, push). It works for branches in the same repository, not for fork PRs.
- Never compiled in the authoring environment: the first Actions build may need small fixes.
