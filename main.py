import os
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.textinput import TextInput
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.filechooser import FileChooserListView
from kivy.utils import platform
from kivy.core.window import Window

# Dark theme background
Window.clearcolor = (0.12, 0.12, 0.12, 1)

class HermesEditor(BoxLayout):
    def __init__(self, **kwargs):
        super().__init__(orientation='vertical', spacing=0, padding=0, **kwargs)
        self.current_filepath = None
        self.request_android_permissions()

        # 1. Top Action Bar
        top_bar = BoxLayout(size_hint_y=None, height='48dp', padding=['8dp', '4dp'], spacing='6dp')
        
        self.title_label = Label(
            text="Hermes Editor - [New File]",
            color=(0.9, 0.9, 0.9, 1),
            bold=True,
            halign='left',
            valign='middle',
            size_hint_x=0.5
        )
        self.title_label.bind(size=self.title_label.setter('text_size'))
        
        btn_style = {'background_color': (0.2, 0.5, 0.85, 1), 'color': (1, 1, 1, 1), 'bold': True}
        
        btn_new = Button(text="New", size_hint_x=0.15, **btn_style)
        btn_new.bind(on_release=lambda x: self.new_file())
        
        btn_open = Button(text="Open", size_hint_x=0.17, **btn_style)
        btn_open.bind(on_release=lambda x: self.open_file_dialog())
        
        btn_save = Button(text="Save", size_hint_x=0.18, **btn_style)
        btn_save.bind(on_release=lambda x: self.save_file())

        top_bar.add_widget(self.title_label)
        top_bar.add_widget(btn_new)
        top_bar.add_widget(btn_open)
        top_bar.add_widget(btn_save)

        # 2. Main Text Area
        self.text_area = TextInput(
            text="",
            hint_text="Start typing your text here...",
            multiline=True,
            background_color=(0.18, 0.18, 0.18, 1),
            foreground_color=(0.95, 0.95, 0.95, 1),
            hint_text_color=(0.5, 0.5, 0.5, 1),
            cursor_color=(0.3, 0.7, 1, 1),
            font_size='16sp',
            padding=['12dp', '12dp', '12dp', '12dp'],
            size_hint=(1, 1)
        )

        # 3. Bottom Status / Toolbar Bar
        bottom_bar = BoxLayout(size_hint_y=None, height='40dp', padding=['6dp', '4dp'], spacing='6dp')
        
        btn_cut = Button(text="Cut", size_hint_x=0.25, background_color=(0.3, 0.3, 0.3, 1), color=(1, 1, 1, 1))
        btn_cut.bind(on_release=lambda x: self.cut_text())
        
        btn_copy = Button(text="Copy", size_hint_x=0.25, background_color=(0.3, 0.3, 0.3, 1), color=(1, 1, 1, 1))
        btn_copy.bind(on_release=lambda x: self.copy_text())
        
        btn_paste = Button(text="Paste", size_hint_x=0.25, background_color=(0.3, 0.3, 0.3, 1), color=(1, 1, 1, 1))
        btn_paste.bind(on_release=lambda x: self.paste_text())
        
        btn_clear = Button(text="Clear", size_hint_x=0.25, background_color=(0.6, 0.2, 0.2, 1), color=(1, 1, 1, 1))
        btn_clear.bind(on_release=lambda x: self.clear_text())

        bottom_bar.add_widget(btn_cut)
        bottom_bar.add_widget(btn_copy)
        bottom_bar.add_widget(btn_paste)
        bottom_bar.add_widget(btn_clear)

        # Assemble layout
        self.add_widget(top_bar)
        self.add_widget(self.text_area)
        self.add_widget(bottom_bar)

    def request_android_permissions(self):
        if platform == 'android':
            try:
                from android.permissions import request_permissions, Permission
                request_permissions([
                    Permission.READ_EXTERNAL_STORAGE,
                    Permission.WRITE_EXTERNAL_STORAGE,
                    Permission.MANAGE_EXTERNAL_STORAGE
                ])
            except Exception as e:
                print(f"Permission request error: {e}")

    def new_file(self):
        self.text_area.text = ""
        self.current_filepath = None
        self.title_label.text = "Hermes Editor - [New File]"

    def clear_text(self):
        self.text_area.text = ""

    def cut_text(self):
        self.text_area.copy()
        self.text_area.delete_selection()

    def copy_text(self):
        self.text_area.copy()

    def paste_text(self):
        self.text_area.paste()

    def get_storage_path(self):
        if platform == 'android':
            return '/storage/emulated/0'
        return os.path.expanduser('~')

    def open_file_dialog(self):
        content = BoxLayout(orientation='vertical', spacing='8dp', padding='8dp')
        
        file_chooser = FileChooserListView(
            path=self.get_storage_path(),
            filters=['*.txt', '*.py', '*.json', '*.md', '*.csv', '*.*'],
            size_hint=(1, 0.85)
        )
        
        btn_box = BoxLayout(size_hint=(1, 0.15), spacing='10dp')
        btn_cancel = Button(text="Cancel", background_color=(0.4, 0.4, 0.4, 1))
        btn_select = Button(text="Open File", background_color=(0.2, 0.6, 0.9, 1), bold=True)
        
        btn_box.add_widget(btn_cancel)
        btn_box.add_widget(btn_select)
        
        content.add_widget(file_chooser)
        content.add_widget(btn_box)

        popup = Popup(
            title="Select Text File",
            content=content,
            size_hint=(0.95, 0.9)
        )

        def do_open(instance):
            if file_chooser.selection:
                path = file_chooser.selection[0]
                try:
                    with open(path, 'r', encoding='utf-8', errors='ignore') as f:
                        self.text_area.text = f.read()
                    self.current_filepath = path
                    self.title_label.text = f"Hermes - {os.path.basename(path)}"
                except Exception as e:
                    self.show_popup("Error", f"Could not read file: {e}")
            popup.dismiss()

        btn_select.bind(on_release=do_open)
        btn_cancel.bind(on_release=popup.dismiss)
        popup.open()

    def save_file(self):
        if self.current_filepath:
            self._save_to_path(self.current_filepath)
        else:
            self.save_as_dialog()

    def save_as_dialog(self):
        content = BoxLayout(orientation='vertical', spacing='8dp', padding='8dp')
        
        file_chooser = FileChooserListView(
            path=self.get_storage_path(),
            size_hint=(1, 0.7)
        )
        
        input_box = BoxLayout(size_hint=(1, 0.15), spacing='6dp')
        filename_input = TextInput(
            text="document.txt",
            multiline=False,
            size_hint_x=0.7,
            font_size='15sp'
        )
        input_box.add_widget(Label(text="Name:", size_hint_x=0.3))
        input_box.add_widget(filename_input)
        
        btn_box = BoxLayout(size_hint=(1, 0.15), spacing='10dp')
        btn_cancel = Button(text="Cancel", background_color=(0.4, 0.4, 0.4, 1))
        btn_save = Button(text="Save Here", background_color=(0.2, 0.7, 0.3, 1), bold=True)
        
        btn_box.add_widget(btn_cancel)
        btn_box.add_widget(btn_save)
        
        content.add_widget(file_chooser)
        content.add_widget(input_box)
        content.add_widget(btn_box)

        popup = Popup(
            title="Save File As",
            content=content,
            size_hint=(0.95, 0.9)
        )

        def do_save(instance):
            target_dir = file_chooser.path
            filename = filename_input.text.strip() or "untitled.txt"
            target_path = os.path.join(target_dir, filename)
            self._save_to_path(target_path)
            popup.dismiss()

        btn_save.bind(on_release=do_save)
        btn_cancel.bind(on_release=popup.dismiss)
        popup.open()

    def _save_to_path(self, path):
        try:
            with open(path, 'w', encoding='utf-8') as f:
                f.write(self.text_area.text)
            self.current_filepath = path
            self.title_label.text = f"Hermes - {os.path.basename(path)}"
            self.show_popup("Success", f"File saved successfully:\n{os.path.basename(path)}")
        except Exception as e:
            self.show_popup("Error", f"Could not save file: {e}")

    def show_popup(self, title, message):
        content = BoxLayout(orientation='vertical', padding='10dp', spacing='10dp')
        content.add_widget(Label(text=message, halign='center', valign='middle'))
        btn = Button(text="OK", size_hint=(1, 0.3), background_color=(0.2, 0.5, 0.85, 1))
        content.add_widget(btn)
        
        pop = Popup(title=title, content=content, size_hint=(0.8, 0.4))
        btn.bind(on_release=pop.dismiss)
        pop.open()

class MainApp(App):
    def build(self):
        self.title = "Hermes Text Editor"
        return HermesEditor()

if __name__ == '__main__':
    MainApp().run()
