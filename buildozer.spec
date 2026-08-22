[app]
title = Hermes Text Editor
package.name = hermestexteditor
package.domain = com.nousresearch.hermes
source.dir = .
source.include_exts = py,png,jpg,kv,atlas
version = 0.6
requirements = python3,kivy,pyjnius,pillow
orientation = portrait
android.permissions = READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE
android.api = 34
android.minapi = 21
# Pinning NDK 25b and using develop branch for better API 34 / toolchain support
android.ndk = 25b
android.archs = arm64-v8a
android.manifest.template = templates/AndroidManifest.xml
android.debuggable = 0
android.release = 1
android.skip_update_check = 1
android.accept_sdk_license = 1
# Loader-less startup
android.show_loading = 0
android.meta_data = "pyo.show_loading=0"
icon.filename = assets/icon.png
icon.compile = 1
p4a.branch = develop

[buildozer]
log_level = 2
warn_on_root = False
