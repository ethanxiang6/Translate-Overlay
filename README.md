# Translate Overlay

Android app that translates whatever is on your screen and draws the translation right over the original text. The overlay ignores touches, so you keep using the app underneath as normal. Everything is configured inside this app, and you start and stop the overlay from its main screen (or the Stop button in the notification).

## How it works

1. Screen capture (MediaProjection) mirrors the display at a reduced size.
2. On-device ML Kit OCR reads text blocks. Latin, Chinese, Japanese, Korean and Devanagari are supported.
3. On-device ML Kit translation converts each block. Results are cached, so text you have already seen translates instantly.
4. A non-touchable, non-focusable overlay window draws the translated text over each block.

A cheap pixel check skips OCR while the screen is unchanged. When content moves, the old boxes hide and the new frame is scanned right away.

## Settings (all in the app)

- Source language (or auto detect) and target language
- Text type to look for when the source is auto
- Dark or light boxes, box opacity, text size
- Scans per second and minimum text size

## Build

CI builds a debug APK on every push (Actions tab, artifact `translate-overlay-debug`). Locally, with Android SDK and Gradle 8.9:

```
gradle :app:assembleDebug
```

## First run

1. Press Start overlay. Grant "Display over other apps" when asked, then press Start again.
2. Accept the screen capture prompt.
3. Language packs download once (about 30 MB per language), then everything runs offline.

## Notes

- Text under an existing box that changes while the rest of the screen stays still is not picked up until something else on screen changes.
- Apps that block screen capture (some banking and video apps) appear black to the overlay, so nothing gets translated there.
