# Third-party notices

## LibChecker

The visual and interaction study for this independent Compose implementation includes LibChecker.
LibChecker is copyright its contributors and is distributed under the Apache License 2.0:
https://github.com/LibChecker/LibChecker

Fixed palette values in `AppColorSchemes.kt` and the splash background resources are adapted from
LibChecker's `app/src/main/res/values/md_themes.xml` at commit
`7ea356187e89652f486c60608560ffee628992b3`. Copyright the LibChecker contributors; Apache License 2.0.
Modifications: ported the color table to explicit Compose ColorScheme roles and local splash
resources. Preference color-role mapping and navigation behavior were independently reimplemented
in Compose from the same snapshot. No View/XML GUI, logo, or brand artwork was copied.

Motion values in `AppMotion.kt` are also adapted from that snapshot's `MainActivity.kt`,
`PreferenceItemView.kt`, `ThemeTransitionController.kt`, and dialog/blur controllers. Modifications:
expressed durations, easing, and distances as local Compose tokens; independently implemented
page transitions, grouped-shape feedback, palette fading, and animated modal presentation.
Reference motion sources (same frozen snapshot):
- https://github.com/LibChecker/LibChecker/blob/7ea356187e89652f486c60608560ffee628992b3/app/src/main/kotlin/com/absinthe/libchecker/domain/home/ui/MainActivity.kt
- https://github.com/LibChecker/LibChecker/blob/7ea356187e89652f486c60608560ffee628992b3/app/src/main/kotlin/com/absinthe/libchecker/ui/preference/view/PreferenceItemView.kt
- https://github.com/LibChecker/LibChecker/blob/7ea356187e89652f486c60608560ffee628992b3/app/src/main/kotlin/com/absinthe/libchecker/ui/base/ThemeTransitionController.kt
- https://github.com/LibChecker/LibChecker/blob/7ea356187e89652f486c60608560ffee628992b3/app/src/main/kotlin/com/absinthe/libchecker/ui/base/BaseAlertDialogBuilder.kt

Reference sources:
- https://github.com/LibChecker/LibChecker/blob/7ea356187e89652f486c60608560ffee628992b3/app/src/main/res/values/md_themes.xml
- https://github.com/LibChecker/LibChecker/blob/7ea356187e89652f486c60608560ffee628992b3/app/src/main/res/values/themes_overlay.xml
- https://github.com/LibChecker/LibChecker/blob/7ea356187e89652f486c60608560ffee628992b3/app/src/main/kotlin/com/absinthe/libchecker/view/app/InvalidatingHideBottomViewOnScrollBehavior.kt

## AndroidX, Material Components, Kotlin, and coroutines

Motion constants and the emphasized curve in `AppMotion.kt` are adapted from Material Components
for Android 1.14.0 (copyright The Android Open Source Project, Apache License 2.0) and Android 17
framework activity animation resources (copyright The Android Open Source Project, Apache License
2.0). Modifications: converted XML timing/curve values to Kotlin tokens, normalized the emphasized
path into two cubic segments, and implemented system-style page transitions with Compose.

Reference resources:
- https://github.com/material-components/material-components-android/blob/1.14.0/lib/java/com/google/android/material/motion/res/values/tokens.xml
- https://github.com/material-components/material-components-android/blob/1.14.0/lib/java/com/google/android/material/behavior/HideBottomViewOnScrollBehavior.java
- MaterialSwitch / AndroidX SwitchCompat; Android SDK Platform 37 activity_open/close resources.

The built application uses AndroidX libraries, Jetpack Compose, Material Components, Kotlin, and
kotlinx.coroutines. Their license notices are preserved by their published artifacts and packaged
metadata. The relevant projects are distributed under permissive open-source licenses, primarily
Apache License 2.0.
