## Project commands

Fill in these lines in each project. If a line is empty, ask me before you guess.

- Install: `python -m pip install -e "host[dev,windows-media]"`. The phone app needs the Android SDK with platform 36.
- Test: `python -m pytest -q host/tests -rs` for the PC app. `cd android && ./gradlew testDebugUnitTest` for the phone app.
- Lint: `cd android && ./gradlew lint`. The PC app has no linter.
- Build: `cd android && ./gradlew assembleDebug`. The Windows exe and the release APK are built by CI only, in `.github/workflows/build.yml`.
- Run locally: `cd host && python run_host.py` for the PC app. Install `android/app/build/outputs/apk/debug/app-debug.apk` on the phone.
- Workspaces or folders: `host/` is the PC app (Windows and Hyprland). `android/` is the phone app. `docs/` holds the protocol and screenshots.

## Branch rules

- main: the live branch. Only finished and checked changes go here.
- staging: the preview branch. Push here first.
- backup: a copy of main from before the latest push. Use it to roll back.

If a project has no staging branch, say so and ask me before you push to main.

## Push order for every change

1. Run the test, lint and build commands from the list above.
2. Point backup at the current main: `git push origin origin/main:refs/heads/backup --force`
3. Push the change to staging: `git push origin HEAD:staging`
4. Stop. Tell me what to check on staging. Wait for my OK.
5. After my OK, push the same commit to main: `git push origin HEAD:main`

Rules:

- Never push to main if tests, lint or the build fail.
- Never force-push main or staging.
- The backup push in step 2 is the only force-push you may run without asking.
- If I say "push fast", you may do steps 3 and 5 together for that one change.
- Never commit secrets, keys, tokens or .env files.

## Roll back main

```bash
git push origin origin/backup:refs/heads/main --force-with-lease
```

Ask me before you run it.

## Notes by project type

### Mobile apps

- Support small and large screens, safe areas and dark mode.
- Never commit keystores, provisioning profiles, google-services files or API keys.
- Raise the version and build number for every release build.
- Ask before you add a native dependency or change permissions.
- Handle offline state and slow networks in every screen that loads data.

### Backend and APIs

- Validate all input on the server.
- Never log passwords, tokens or personal data.
- Keep error messages clear and free of internal details.

## Code rules for every project

- Keep data access in one layer, such as a services folder or an API client.
- Keep shared logic in one place. Do not copy it between files.

## Design

- Use the design tokens and token classes of the project. Do not add raw hex values.
- If the project has no design system, ask me for a direction before you build a new UI.

## Project notes

Add facts that only apply to this project here:

- Stack: PC app in Python 3.10+ with tkinter, cryptography, psutil, Pillow and segno, plus pycaw and winrt on Windows. Phone app in plain Java with no libraries, minSdk 16 (Android 4.1), targetSdk 36.
- Hosting and deploy method: GitHub Actions builds every branch and takes phone screenshots on an emulator. A push to main releases only when CHANGELOG.md has a new version at the top. The release holds MobileDockHost.exe, the APK and latest.json. The PC app updates itself from the release. The phone gets its update from the PC.
- Data and money rules: No money and no paid features. Pairing keys stay on the PC and the phone. The apps contact only GitHub releases and, for search icons, the preset's own site.
- Special constraints: Keep Android 4.1 support. The signing keystore and its password live only in GitHub secrets and with the owner. Never commit them. Never put the owner's personal email, real PC name or IP address in the repo. Commits are authored as Benacsek with the GitHub noreply address. A release changes both apps, so add a CHANGELOG version only for changes meant to ship.

## Coding guidelines

These rules reduce common coding mistakes. Merge them with the project notes above. If a project note conflicts with a rule here, the project note wins.

These rules favor care over speed. For trivial tasks, use your judgment.

### 1. Think before coding

Do not assume. Do not hide confusion. Show the tradeoffs.

- State your assumptions before you implement. If you are unsure, ask me.
- If a request has more than one meaning, list the options. Do not pick one silently.
- If a simpler approach exists, say so. Push back when it is warranted.
- If something is unclear, stop. Name what confuses you. Ask me.

### 2. Simplicity first

Write the minimum code that solves the problem. Add nothing speculative.

- Add no features beyond what I asked for.
- Add no abstractions for code that is used once.
- Add no flexibility or configuration I did not request.
- Add no error handling for scenarios that cannot happen.
- If you write 200 lines and 50 would do, rewrite it.
- Ask yourself: would a senior engineer call this overcomplicated? If yes, simplify.

### 3. Surgical changes

Touch only what you must. Clean up only your own mess.

- Follow the existing structure, naming, style and patterns of the project, even if you would do it differently.
- Do not improve adjacent code, comments or formatting.
- Do not refactor code that works.
- Do not rewrite files I did not ask about.
- If you see unrelated dead code, tell me. Do not delete it.
- Remove imports, variables and functions that your own change made unused.
- Keep pre-existing dead code unless I ask you to remove it.
- Add dependencies only when the gain is clear. Ask first.
- Every changed line must trace back to my request.

### 4. Goal-driven execution

Define success first. Loop until you verify it.

- "Add validation" means: write tests for invalid input, then make them pass.
- "Fix the bug" means: write a test that reproduces it, then make it pass.
- "Refactor X" means: confirm the tests pass before and after.
- For tasks with several steps, state a short plan before you start:

```
1. [Step] -> verify: [check]
2. [Step] -> verify: [check]
3. [Step] -> verify: [check]
```

- Weak goals like "make it work" force constant questions. Turn them into checks you can run.

### How to tell these rules work

- Diffs contain fewer unnecessary changes.
- Code gets rewritten less often because of overcomplication.
- Clarifying questions come before the implementation, not after a mistake.
