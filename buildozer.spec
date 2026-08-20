[app]
icon.filename = assets/icon.png
icon.compile = 1
title = Hermes Text Editor
package.name = hermestexteditor
package.domain = com.nousresearch.hermes
source.dir = .
source.include_exts = py,png,jpg,kv,atlas
version = 0.1
requirements = python3,kivy,kivymd,pillow,materialshapes,pycairo
orientation = portrait
osx.python_version = 3
osx.kivy_version = 1.9.1
fullscreen = 0
android.permissions = READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE
android.api = 31
android.minapi = 21
android.sdk = 31
# Let buildozer auto-detect NDK (fixes compatibility issues)
android.archs = arm64-v8a, armeabi-v7a
p4a.branch = master
ios.codesign.allowed = false

[buildozer]
log_level = 2
warn_on_root = False
