# Third-party notices

Music Tag uses AndroidX, Jetpack Compose, Kotlin, and kotlinx.coroutines under their respective Apache 2.0 licenses. Some AndroidX components include transitive Kotlin serialization runtime components.

The anonymous NetEase Cloud Music request protocol implementation was independently written in Kotlin with behavioral reference to [SPlayer-Dev/ncm-api-rs](https://github.com/SPlayer-Dev/ncm-api-rs), distributed under the WTFPL. No source file from that project is bundled.

The GUI incorporates the AndroidGUI 0.2.0 Compose Design System with its frozen 0.1.2 visual baseline (copyright 2026 Android GUI contributors, Apache-2.0). Its adapted LibChecker/AOSP palette and motion notices, LICENSE and NOTICE are retained in `core/designsystem` and packaged under `assets/licenses/androidgui`. Music Tag adds content/selection components, menu actions, shared switch reuse and system-bar color synchronization; the source adaptations are documented in `docs/design-system.md`. No LibChecker branding or View/XML screen implementation is bundled.

The WAV implementation was independently written with behavioral reference to the public RIFF/WAV handling in [TagLib](https://github.com/taglib/taglib), dual-licensed under LGPL-2.1-or-later and MPL-1.1. TagLib is not included, linked, or copied into Music Tag.
