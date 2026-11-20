// Artifact-only Gradle wrapper for the prebuilt TDLib AAR.
// AGP 8 forbids direct local .aar file dependencies inside Android library
// modules when building release AARs/lint checks; exposing tdlib.aar via a
// project dependency satisfies bundleReleaseLocalLintAar cleanly.
configurations.maybeCreate("default")
artifacts.add("default", file("../telegram/libs/tdlib.aar"))
