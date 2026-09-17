# Hermes Text Editor

A clean, private, offline-focused text and Markdown editor for Android.

Hermes Text Editor is made for distraction-free writing: open a file, write comfortably, save safely, and return to your work whenever you need it.

## Features

- Create and edit plain-text files
- Open `.txt`, `.md`, and `.markdown` files with Android's file picker
- Open supported files directly from your Android file manager
- Markdown Preview for headings, emphasis, lists, links, quotes, code blocks, and more
- Edit the original Markdown source whenever needed
- Save the current file normally
- Press and hold **Save ▲** to use **Save as…** or **Export as plain text…**
- Save copies as either `.txt` or `.md`
- Export a Markdown document to a separate readable `.txt` copy without Markdown symbols
- Automatic local draft recovery
- Long filenames stay on one line and can be swiped sideways to reveal the full name
- Light and Dark themes
- English and فارسی app interface
- Right-to-left and left-to-right text direction controls
- Local direction suggestion when a clearly Persian or English document does not match the current writing direction
- Simple in-app Help & Guide

## Markdown support

Markdown files open in Preview mode. Use the three-dot menu to switch between **Preview Markdown** and **Edit Markdown**.

Saving keeps the original Markdown source unchanged. If you need a readable text-only copy, press and hold **Save ▲** and choose **Export as plain text…**.

## Text direction

Hermes can suggest the suitable writing direction when you open a clearly Persian or English document:

- Persian text can use Right-to-Left (RTL)
- English text can use Left-to-Right (LTR)

The suggestion appears only when the current writing direction does not match the document. You can always choose the direction yourself from the three-dot menu.

## Privacy

Hermes Text Editor is designed to work offline.

- No ads
- No analytics
- No tracking
- No WebView
- No Internet permission

Your files remain on your device. The app accesses a file only when you choose it through Android's file picker or share/open it from your file manager.

## Install

1. Open the latest [GitHub Release](https://github.com/sinach9995/Text-editor/releases/latest).
2. Download the signed APK.
3. Open the downloaded APK on your Android device and follow Android's installation steps.
4. If Google Play Protect recommends a scan for a new release, choose **Scan app**.

> **Note:** GitHub APKs are installed directly rather than through Google Play. Android may show a scan recommendation for a newly published build. This does not change the app's offline/privacy design.

## Requirements

- Android 6.0 (API 23) or later

## Independent project notice

**Hermes Text Editor is an independent project by Sina Chaghamirza under AsoraKSH.**

It was created with assistance from the Hermes Agent AI and Notion AI as development tools. It is **not** built, maintained, endorsed, sponsored, or affiliated with any Hermes team, organization, company, or product. Use of those AI tools does not imply an official relationship or endorsement.

## Development

This repository contains the Android source code and GitHub Actions configuration used to build signed release APK and AAB files.

For security, signing keys, passwords, tokens, and GitHub Actions secret values are never stored in this repository.
