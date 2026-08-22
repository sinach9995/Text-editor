[app]
title = Hermes Text Editor
package.name = hermestexteditor
package.domain = com.nousresearch.hermes
source.dir = .
source.include_exts = py,png,jpg,kv,atlas
version = 0.5
requirements = python3,kivy,pyjnius,pillow
orientation = portrait
android.permissions = READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE
android.api = 34
android.minapi = 21
android.ndk = 25b
android.archs = arm64-v8a
android.manifest.template = templates/AndroidManifest.xml
android.debuggable = 0
android.release = 1
android.skip_update_check = 1
android.accept_sdk_license = 1
# Splash screen configuration - disable P4A default loader
android.show_loading = 0
android.presplash = assets/presplash.png
icon.filename = assets/icon.png
icon.compile = 1
p4a.branch = master

[buildozer]
log_level = 2
warn_on_root = False
