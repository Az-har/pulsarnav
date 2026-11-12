# Contributing to PulsarNav

PulsarNav is open for community contributions. Whether you ride a Pulsar NS200, N250, Dominar 400, or Rouser, your input and improvements are welcome.

To keep the project reliable and secure for riders, the `master` branch is protected. Only `@Az-har` reviews and merges into `master` on this repository. All contributions happen through forks and pull requests.

---

## Contribution Workflow

### 1. Fork the Repository
Click the **Fork** button at the top-right of [Az-har/pulsarnav](https://github.com/Az-har/pulsarnav) to create your own copy on GitHub.

### 2. Clone Your Fork
```bash
git clone https://github.com/<your-username>/pulsarnav.git
cd pulsarnav
```

### 3. Create a Feature Branch
Always create a dedicated branch for your change:
```bash
git checkout -b feature/your-feature-name
# or for bug fixes:
git checkout -b fix/issue-description
```

Never commit directly to your fork's `master` branch to avoid merge conflicts when syncing with upstream.

### 4. Build and Verify Locally
Make sure you have JDK 17 installed and Android SDK configured:

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Run Android Lint
./gradlew lintDebug

# Build release APK
./gradlew assembleRelease
```

### 5. Push and Open a Pull Request
Push your branch to your GitHub fork:
```bash
git push origin feature/your-feature-name
```
Then go to the [PulsarNav GitHub Repository](https://github.com/Az-har/pulsarnav) and click **New Pull Request** comparing your branch to `master`.

Fill out the Pull Request template describing:
- What changed
- Why it is needed
- Which bike model / Android version you tested on

---

## Guidelines for Contributors

- **Preserve OTC Bluetooth Telematics Protocol**: The 48-byte Bluetooth Low Energy frame layout communicated to the bike's digital cluster must remain strictly backward-compatible.
- **Offline-First & Privacy**: PulsarNav has zero analytics, zero external tracking, and zero cloud server dependencies. Keep it completely local.
- **Tone & Style**: Keep code, comments, commit messages, and documentation simple, concise, and technical. Do not add emoji spam or AI marketing fluff.
- **No Secrets**: Never commit private keystores, tokens, or personal identifiers. Configure `RELEASE_*` secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) and `REQUIRE_PRIVATE_RELEASE_SIGNING=true` before distributing private releases; the fallback keystore in the repo is strictly for reproducible open-source CI builds.

---

## Branch Protection & Security Policy

- Direct pushes to `master` are blocked.
- Force-pushes (`git push --force`) and branch deletions are disabled.
- Automated GitHub Actions CI runs on every pull request to ensure the APK compiles cleanly.
- Pull requests are reviewed and merged exclusively by `@Az-har`.
