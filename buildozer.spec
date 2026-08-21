[app]
title = Hermes Text Editor
package.name = hermestexteditor
package.domain = com.nousresearch.hermes
source.dir = .
source.include_exts = py,png,jpg,kv,atlas
version = 0.3

requirements = python3,kivy,pyjnius

orientation = portrait
fullscreen = 0

android.permissions = READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE

android.api = 34
android.minapi = 21
android.ndk = 25b
android.archs = arm64-v8a

icon.filename = assets/icon.png
icon.compile = 1

p4a.branch = master
ios.codesign.allowed = false

[buildozer]
log_level = 2
warn_on_root = False
