from kivymd.app import MDApp
from kivymd.uix.screen import MDScreen
from kivymd.uix.textfield import MDTextField
from kivymd.uix.button import MDRaisedButton, MDIconButton
from kivymd.uix.toolbar import MDTopAppBar
from kivymd.uix.filemanager import MDFileManager
from kivy.uix.boxlayout import BoxLayout
from kivy.core.window import Window
from kivy.utils import platform
import os

class TextEditor(MDApp):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.file_manager = MDFileManager(
            exit_manager=self.exit_manager,
            select_path=self.select_path,
        )
        self.current_path = None

    def build(self):
        self.theme_cls.primary_palette = "Blue"
        self.theme_cls.theme_style = "Light"
        
        layout = BoxLayout(orientation='vertical')
        
        self.toolbar = MDTopAppBar(
            title="Hermes Text Editor",
            elevation=4,
            pos_hint={"top": 1},
            right_action_items=[
                ["file-plus", lambda x: self.new_file()],
                ["folder-open", lambda x: self.open_file_manager()],
                ["content-save", lambda x: self.save_file()],
            ],
        )
        
        self.text_input = MDTextField(
            multiline=True,
            hint_text="Start typing here...",
            mode="fill",
            size_hint_y=None,
            height=Window.height - 150, 
        )
        
        action_layout = BoxLayout(size_hint_y=None, height="50dp", spacing="10dp", padding="10dp")
        action_layout.add_widget(MDRaisedButton(text="Cut", on_release=lambda x: self.cut_text()))
        action_layout.add_widget(MDRaisedButton(text="Copy", on_release=lambda x: self.copy_text()))
        action_layout.add_widget(MDRaisedButton(text="Paste", on_release=lambda x: self.paste_text()))
        
        layout.add_widget(self.toolbar)
        layout.add_widget(self.text_input)
        layout.add_widget(action_layout)
        
        return layout

    def open_file_manager(self):
        path = os.path.expanduser("~") if platform != "android" else "/storage/emulated/0"
        self.file_manager.show(path)

    def select_path(self, path):
        self.exit_manager()
        self.current_path = path
        self.toolbar.title = os.path.basename(path)
        try:
            with open(path, 'r') as f:
                self.text_input.text = f.read()
        except Exception as e:
            print(f"Error reading file: {e}")

    def exit_manager(self, *args):
        self.file_manager.close()

    def save_file(self):
        if not self.current_path:
            self.current_path = os.path.join(os.getcwd(), "untitled.txt")
        
        try:
            with open(self.current_path, 'w') as f:
                f.write(self.text_input.text)
            self.toolbar.title = os.path.basename(self.current_path)
            print(f"Saved to {self.current_path}")
        except Exception as e:
            print(f"Error saving file: {e}")

    def new_file(self):
        self.text_input.text = ""
        self.current_path = None
        self.toolbar.title = "New File"

    def cut_text(self):
        self.text_input.copy(self.text_input.selection_text)
        self.text_input.delete_selection()

    def copy_text(self):
        self.text_input.copy(self.text_input.selection_text)

    def paste_text(self):
        self.text_input.paste()

if __name__ == "__main__":
    TextEditor().run()
