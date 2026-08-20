import os
import sys
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.textinput import TextInput
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.utils import platform
from kivy.core.window import Window
from kivy.clock import mainthread

# Modern Dark Theme Palette
BG_DARK = (0.10, 0.11, 0.13, 1)      # Deep Charcoal
CARD_DARK = (0.15, 0.17, 0.20, 1)    # Card Background
BLUE_ACCENT = (0.20, 0.50, 0.95, 1)  # Modern Vercel/Linear Blue
TEXT_LIGHT = (0.95, 0.96, 0.98, 1)   # Crisp White
TEXT_MUTED = (0.60, 0.64, 0.70, 1)   # Soft Gray
DANGER_RED = (0.85, 0.25, 0.25, 1)   # Red Action

Window.clearcolor = BG_DARK

class HermesEditor(BoxLayout):
    def __init__(self, **kwargs):
        super().__init__(orientation='vertical', spacing=0, padding=0, **kwargs)
        self.current_uri = None
        self.current_filename = "untitled.txt"

        # 1. Top Header Bar
        header = BoxLayout(
            size_hint_y=None, 
            height='56dp', 
            padding=['16dp', '8dp'], 
            spacing='10dp'
        )
        
        self.title_label = Label(
            text="Hermes Editor",
            color=TEXT_LIGHT,
            font_size='18sp',
            bold=True,
            halign='left',
            valign='middle',
            size_hint_x=0.4
        )
        self.title_label.bind(size=self.title_label.setter('text_size'))

        # Modern Rounded/Pill Buttons
        btn_new = Button(
            text="New",
            size_hint_x=0.2,
            background_normal='',
            background_color=CARD_DARK,
            color=TEXT_LIGHT,
            bold=True,
            font_size='14sp'
        )
        btn_new.bind(on_release=lambda x: self.new_file())

        btn_open = Button(
            text="Open",
            size_hint_x=0.2,
            background_normal='',
            background_color=BLUE_ACCENT,
            color=(1, 1, 1, 1),
            bold=True,
            font_size='14sp'
        )
        btn_open.bind(on_release=lambda x: self.open_system_file_picker())

        btn_save = Button(
            text="Save",
            size_hint_x=0.2,
            background_normal='',
            background_color=BLUE_ACCENT,
            color=(1, 1, 1, 1),
            bold=True,
            font_size='14sp'
        )
        btn_save.bind(on_release=lambda x: self.save_system_file_picker())

        header.add_widget(self.title_label)
        header.add_widget(btn_new)
        header.add_widget(btn_open)
        header.add_widget(btn_save)

        # 2. Status Sub-Bar
        sub_bar = BoxLayout(size_hint_y=None, height='28dp', padding=['16dp', '0dp'])
        self.status_label = Label(
            text="Editing: untitled.txt",
            color=TEXT_MUTED,
            font_size='12sp',
            halign='left',
            valign='middle'
        )
        self.status_label.bind(size=self.status_label.setter('text_size'))
        sub_bar.add_widget(self.status_label)

        # 3. Main Text Input Canvas
        self.text_area = TextInput(
            text="",
            hint_text="Type or paste your text here...",
            multiline=True,
            background_normal='',
            background_active='',
            background_color=CARD_DARK,
            foreground_color=TEXT_LIGHT,
            hint_text_color=TEXT_MUTED,
            cursor_color=BLUE_ACCENT,
            font_size='16sp',
            padding=['16dp', '16dp', '16dp', '16dp'],
            size_hint=(1, 1)
        )

        # 4. Bottom Toolbar
        bottom_bar = BoxLayout(size_hint_y=None, height='48dp', padding=['10dp', '6dp'], spacing='8dp')
        
        btn_cut = Button(text="Cut", background_normal='', background_color=CARD_DARK, color=TEXT_LIGHT)
        btn_cut.bind(on_release=lambda x: self.cut_text())
        
        btn_copy = Button(text="Copy", background_normal='', background_color=CARD_DARK, color=TEXT_LIGHT)
        btn_copy.bind(on_release=lambda x: self.copy_text())
        
        btn_paste = Button(text="Paste", background_normal='', background_color=CARD_DARK, color=TEXT_LIGHT)
        btn_paste.bind(on_release=lambda x: self.paste_text())
        
        btn_clear = Button(text="Clear", background_normal='', background_color=DANGER_RED, color=(1, 1, 1, 1))
        btn_clear.bind(on_release=lambda x: self.clear_text())

        bottom_bar.add_widget(btn_cut)
        bottom_bar.add_widget(btn_copy)
        bottom_bar.add_widget(btn_paste)
        bottom_bar.add_widget(btn_clear)

        # Assemble Main UI
        self.add_widget(header)
        self.add_widget(sub_bar)
        self.add_widget(self.text_area)
        self.add_widget(bottom_bar)

        # Bind Android Activity Result for Native System File Picker
        if platform == 'android':
            from android import activity
            activity.bind(on_activity_result=self.on_android_activity_result)
            self.request_android_permissions()

    def request_android_permissions(self):
        if platform == 'android':
            try:
                from android.permissions import request_permissions, Permission
                request_permissions([
                    Permission.READ_EXTERNAL_STORAGE,
                    Permission.WRITE_EXTERNAL_STORAGE
                ])
            except Exception as e:
                print(f"Permission error: {e}")

    def new_file(self):
        self.text_area.text = ""
        self.current_uri = None
        self.current_filename = "untitled.txt"
        self.status_label.text = "Editing: untitled.txt"

    def clear_text(self):
        self.text_area.text = ""

    def cut_text(self):
        self.text_area.copy()
        self.text_area.delete_selection()

    def copy_text(self):
        self.text_area.copy()

    def paste_text(self):
        self.text_area.paste()

    # --- Android Native System File Picker (Storage Access Framework) ---
    def open_system_file_picker(self):
        if platform == 'android':
            try:
                from jnius import autoclass
                Intent = autoclass('android.content.Intent')
                PythonActivity = autoclass('org.kivy.android.PythonActivity')
                
                intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.setType("text/*")
                
                # Request code 1001 for Open
                PythonActivity.mActivity.startActivityForResult(intent, 1001)
            except Exception as e:
                self.show_popup("Error", f"Could not launch system picker: {e}")
        else:
            self.show_popup("System Picker", "On Android, this opens native Google Files / Samsung My Files!")

    def save_system_file_picker(self):
        if platform == 'android':
            try:
                from jnius import autoclass
                Intent = autoclass('android.content.Intent')
                PythonActivity = autoclass('org.kivy.android.PythonActivity')
                
                intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.setType("text/plain")
                intent.putExtra(Intent.EXTRA_TITLE, self.current_filename)
                
                # Request code 1002 for Save
                PythonActivity.mActivity.startActivityForResult(intent, 1002)
            except Exception as e:
                self.show_popup("Error", f"Could not launch system save picker: {e}")
        else:
            self.show_popup("System Saver", "On Android, this lets you pick location & filename via Android Files!")

    @mainthread
    def on_android_activity_result(self, request_code, result_code, intent):
        if result_code != -1: # RESULT_OK == -1
            return

        if intent is None:
            return

        uri = intent.getData()
        if uri is None:
            return

        from jnius import autoclass
        PythonActivity = autoclass('org.kivy.android.PythonActivity')
        activity = PythonActivity.mActivity

        if request_code == 1001: # OPEN DOCUMENT
            try:
                content_resolver = activity.getContentResolver()
                input_stream = content_resolver.openInputStream(uri)
                
                # Read text using Java BufferedReader / Scanner or bytes
                BufferedReader = autoclass('java.io.BufferedReader')
                InputStreamReader = autoclass('java.io.InputStreamReader')
                
                reader = BufferedReader(InputStreamReader(input_stream, "UTF-8"))
                lines = []
                line = reader.readLine()
                while line is not None:
                    lines.append(line)
                    line = reader.readLine()
                reader.close()
                
                full_text = "\n".join(lines)
                self.text_area.text = full_text
                self.current_uri = uri
                self.status_label.text = f"Opened file successfully!"
            except Exception as e:
                self.show_popup("Read Error", f"Failed to read file: {e}")

        elif request_code == 1002: # SAVE / CREATE DOCUMENT
            try:
                content_resolver = activity.getContentResolver()
                output_stream = content_resolver.openOutputStream(uri)
                
                text_bytes = self.text_area.text.encode('utf-8')
                output_stream.write(text_bytes)
                output_stream.flush()
                output_stream.close()
                
                self.current_uri = uri
                self.status_label.text = "File saved successfully!"
                self.show_popup("Saved", "File saved successfully!")
            except Exception as e:
                self.show_popup("Save Error", f"Failed to save file: {e}")

    def show_popup(self, title, message):
        content = BoxLayout(orientation='vertical', padding='16dp', spacing='12dp')
        content.add_widget(Label(text=message, color=TEXT_LIGHT, halign='center', valign='middle'))
        
        btn = Button(
            text="OK", 
            size_hint=(1, 0.35), 
            background_normal='',
            background_color=BLUE_ACCENT, 
            color=(1, 1, 1, 1),
            bold=True
        )
        content.add_widget(btn)
        
        pop = Popup(
            title=title, 
            content=content, 
            size_hint=(0.85, 0.35),
            background_color=CARD_DARK
        )
        btn.bind(on_release=pop.dismiss)
        pop.open()

class MainApp(App):
    def build(self):
        self.title = "Hermes Text Editor"
        return HermesEditor()

if __name__ == '__main__':
    MainApp().run()
