# Third-party notices

Music Tag uses AndroidX, Jetpack Compose, Kotlin, and kotlinx.coroutines under their respective Apache 2.0 licenses. Some AndroidX components include transitive Kotlin serialization runtime components.

The anonymous NetEase Cloud Music request protocol implementation was independently written in Kotlin with behavioral reference to [SPlayer-Dev/ncm-api-rs](https://github.com/SPlayer-Dev/ncm-api-rs), distributed under the WTFPL. No source file from that project is bundled.

The filename-assisted matching and multi-source candidate selection were independently implemented in Kotlin with conceptual reference to the public `dev_1.0` branch of [xhongc/music-tag-web](https://github.com/xhongc/music-tag-web/tree/dev_1.0). No source code, bundled executables, or service credentials from that project are included.

The GUI incorporates the AndroidGUI 0.2.0 Compose Design System with its frozen 0.1.2 visual baseline (copyright 2026 Android GUI contributors, Apache-2.0). Its adapted LibChecker/AOSP palette and motion notices, LICENSE and NOTICE are retained in `core/designsystem` and packaged under `assets/licenses/androidgui`. Music Tag adds content/selection components, menu actions, shared switch reuse and system-bar color synchronization. No LibChecker branding or View/XML screen implementation is bundled.

The WAV implementation was independently written with behavioral reference to the public RIFF/WAV handling in [TagLib](https://github.com/taglib/taglib), dual-licensed under LGPL-2.1-or-later and MPL-1.1. TagLib is not included, linked, or copied into Music Tag.

Audio fingerprint generation includes Chromaprint 1.6.1 (Copyright Lukas Lalinsky, MIT) and its bundled FFmpeg resampling code (Copyright Michael Niedermayer and FFmpeg contributors, LGPL-2.1-or-later). The native library uses bundled KissFFT (Copyright Mark Borgerding, BSD-3-Clause). Source and notices are under `app/src/main/cpp/vendor/chromaprint`; the license texts are packaged under `assets/licenses/chromaprint`. The Chromaprint library is packaged as a separate ARM64 shared library. Fingerprint lookup uses the AcoustID web service; no audio file is uploaded.
