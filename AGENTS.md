# InkVox Project Guidelines

## Project Direction

InkVox is a lightweight voice-first input method for Android e-ink devices. Its primary use case is capturing short notes, annotations, and thoughts while reading. The project focuses on proving that the core voice-input experience can be reliable, responsive, and resource-efficient. It is not intended to become a full-featured general-purpose keyboard.

## Platform and Compatibility

- Target Android 10 and later on e-ink devices (while preserving `minSdk 26` compatibility).
- Prioritize real-world usability on the selected target e-ink device, reading app, and note-taking app.
- Standard Android devices may be used for development and debugging, but they do not replace testing on actual e-ink hardware.
- Do not add speculative compatibility layers for untested devices, vendor-specific Android variants, or host applications.

## Product Principles

- **Voice first:** Recording must remain the most prominent and direct action, with as few steps as possible between opening the input method and speaking.
- **E-ink first:** Use high contrast, infrequent redraws, and no continuous animation. Important states must never rely on color alone.
- **Fast and lightweight:** Treat startup time, responsiveness, memory, CPU usage, battery consumption, and device temperature as core product concerns. Do not use the microphone or perform unnecessary background work while idle.
- **Reliable and recoverable:** Cancellation, failures, input-target changes, and input-method switches must not damage committed text, submit duplicate text, or send stale results to the wrong field.
- **Private and transparent:** Do not retain recordings or recognized text by default. Logs must not contain sensitive data, and any external processing must be clearly disclosed to the user.
- **Focused and restrained:** Build only what is necessary to validate the current product goals. Avoid architecture, configuration systems, and extension points created solely for hypothetical future needs.

## Development Guidelines

- Prefer Android platform capabilities and dependencies already used by the project. Add a dependency only when it provides a clear, immediate, and measurable benefit.
- Keep responsibilities and data lifecycles clear without introducing abstractions for imagined future implementations.
- Review every change that handles audio, recognized text, input-field content, or credentials for privacy and data-leak risks.
- Every asynchronous operation must account for cancellation, timeouts, input-target changes, lifecycle termination, and late callbacks.
- UI changes must be checked for grayscale clarity, adequate touch targets, enlarged system fonts, and accessibility labels.

## Build and Verification

- Assemble debug APK: `./gradlew assembleDebug`
- Verify the release build: `./gradlew check assembleRelease`
- Run static checks and tests: `./gradlew check`
- Clean workspace: `./gradlew clean`

## Release Process

- Every push to `main` runs the release verification build in GitHub Actions without publishing an artifact.
- Before a release, increment `versionCode`, set `versionName`, and push those changes to `main`.
- Create and push a tag matching `v<versionName>` (for example, `v0.1.0`). The workflow builds a signed APK, verifies its signature, and publishes it to GitHub Releases with generated release notes.
- The repository must define `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` as GitHub Actions secrets.
- Never commit a keystore or signing credential. Keep a secure backup of the release keystore; losing it prevents compatible upgrades.

## Validation Priorities

- Validate the core workflow on actual target e-ink hardware across reading and note-taking scenarios.
- Cover recording start and stop, cancellation, failure recovery, repeated input, input-target changes, and sensitive input fields.
- Base performance decisions on measurements from real hardware, including end-to-end latency, CPU, memory, battery use, device temperature, and screen refresh behavior.
- Changes involving state, lifecycle, or text submission must be checked for microphone leaks, stale or duplicate submissions, incorrect input targets, and loss of previously committed text.
- Before release, verify that logs and persisted data contain no audio, recognized text, input content, API keys, or passwords.

## Release Scope

The initial release succeeds when the core voice-input workflow is dependable and demonstrates that InkVox is lighter and more convenient than a general-purpose input method on the target e-ink device. Unless the product direction explicitly changes, do not add a complete keyboard, multilingual input, account synchronization, AI rewriting, complex settings, vendor-specific refresh APIs, or other nonessential features.
