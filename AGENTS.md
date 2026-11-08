# AGENTS.md

Read the Wonderhoy Labs unification skill, [.claude/skills/wonderhoy-unification/SKILL.md](.claude/skills/wonderhoy-unification/SKILL.md),
before changing anything. It's the same in every Wonderhoy Labs repository. This file only adds what's particular
to Akiyama.

## What Akiyama is

Full-duplex networking over a USB cable, the USB sibling of Saki. On macOS and Linux it speaks Android Open
Accessory, so either end can have the network. Windows is server-only and goes through `adb reverse`, because
`nusb` can't send device-level control requests there. Named for Akiyama Mizuki of 25-ji, Nightcord de.

## Layout

| Path                   | What                                                                                            |
| ---------------------- | ----------------------------------------------------------------------------------------------- |
| `crates/akiyama-core/` | Protocol, NAT, DNS resolver and session logic, shared by everything, and the library others use |
| `crates/akiyama/`      | The command-line tool: USB through `nusb`, adb on Windows, and the TUN device                   |
| `crates/akiyama-jni/`  | Lets the Android app use the Rust NAT                                                           |
| `android/`             | The Android app, which builds `akiyama-jni` with cargo-ndk                                      |
| `docs/protocol.md`     | The wire protocol                                                                               |

## Build, test and format

```bash
pnpm install && pnpm unify
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cd android && ./gradlew spotlessApply :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

From the Mac, a static Linux build needs no C toolchain:
`CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER=rust-lld cargo build --release -p akiyama --target x86_64-unknown-linux-musl`.

## Things to know

- **`akiyama-core` is a library other programs use** by a Cargo git dependency on a release tag. Every public item
  has a doc comment (`#![warn(missing_docs)]`), `examples/in_process.rs` has to keep working, and the README's "Use
  it in your app" has to match the API. Changing the API bumps the minor version.
- **The direction rides in the accessory's serial string.** `akiyama-server` makes the phone connect its VPN by
  itself; `akiyama-client` makes it ask for a tap on Share.
- **Speed is good enough.** About 210 Mbit/s down and 360 up on the Pixel. Don't keep tuning it unasked.
- **Windows has never been run**, and the README has to keep saying so. Neither has the final direction switching
  on a Mac.
- **Test UI on the emulator** (AVD `Medium_Phone_API_37.0`). `ScreenTest` draws every state and saves pictures to
  the app's `files/shots`. Run it with `am instrument`, not Gradle's connected task, which uninstalls the app and
  its pictures. Debug builds take `dev_accept_share` to share without a tap.
- Never replace the Android signing key. CI signs with the `AKIYAMA_KEYSTORE_BASE64`, `AKIYAMA_KEYSTORE_PASSWORD`,
  `AKIYAMA_KEY_ALIAS` and `AKIYAMA_KEY_PASSWORD` secrets.

## Exceptions to the guideline

None.
