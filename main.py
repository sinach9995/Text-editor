import os
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.anchorlayout import AnchorLayout
from kivy.uix.textinput import TextInput
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.image import Image
from kivy.uix.dropdown import DropDown
from kivy.uix.popup import Popup
from kivy.graphics import Color, RoundedRectangle
from kivy.utils import platform
from kivy.core.window import Window
from kivy.clock import mainthread, Clock
from kivy.storage.jsonstore import JsonStorage

# Theme Definitions
THEMES = {
    'dark': {
        'bg': (0.07, 0.08, 0.1, 1),
        'card': (0.15, 0.17, 0.22, 1),
        'accent': (0.2, 0.5, 1.0, 1),
        'text': (0.95, 0.96, 0.98, 1),
        'muted': (0.5, 0.55, 0.6, 1),
        'input_bg': (0.1, 0.11, 0.14, 1)
    },
    'light': {
        'bg': (0.96, 0.97, 0.99, 1),
        'card': (0.9, 0.92, 0.95, 1),
        'accent': (0.1, 0.35, 0.8, 1),
        'text': (0.1, 0.12, 0.15, 1),
        'muted': (0.4, 0.45, 0.5, 1),
        'input_bg': (1, 1, 1, 1)
    }
}

class RoundedButton(Button):
    def __init__(self, bg_color=(0.2, 0.5, 1.0, 1), radius=[18,], **kwargs):
        super().__init__(**kwargs)
        self.background_normal = ''
        self.background_down = ''
        self.background_color = (0, 0, 0, 0)
        self.bg_color = bg_color
        self.radius = radius
        self.bind(pos=self.update_canvas, size=self.update_canvas)

    def update_canvas(self, *args):
        self.canvas.before.clear()
        with self.canvas.before:
            Color(*self.bg_color)
            RoundedRectangle(pos=self.pos, size=self.size, radius=self.radius)

class HermesEditor(BoxLayout):
    def __init__(self, **kwargs):
        super().__init__(orientation='vertical', **kwargs)
        self.store = JsonStorage(os.path.join(App.get_running_app().user_data_dir, 'settings.json'))
        self.current_theme = self.store.get('theme')['type'] if self.store.exists('theme') else 'dark'
        self.current_uri = None
        self.cache_path = os.path.join(App.get_running_app().user_data_dir, '.cache.txt')
        
        # UI Setup
        self.build_ui()
        self.apply_theme(self.current_theme)
        
        # Lifecycle
        if platform == 'android':
            from android import activity
            from android.permissions import request_permissions, Permission
            request_permissions([Permission.READ_EXTERNAL_STORAGE, Permission.WRITE_EXTERNAL_STORAGE])
            activity.bind(on_activity_result=self.on_android_activity_result)
            activity.bind(on_new_intent=self.on_new_intent)
            Window.bind(on_keyboard_height=self.on_keyboard_height)
            Window.bind(on_request_close=self.on_back_pressed)
            self.check_intent()
        
        # Load Cache
        self.load_cache()
        Clock.schedule_interval(self.save_cache, 10) # Auto-save every 10s

    def build_ui(self):
        self.clear_widgets()
        t = THEMES[self.current_theme]
        Window.clearcolor = t['bg']

        # 1. Harmonious Header
        self.header = BoxLayout(size_hint_y=None, height='64dp', padding=['16dp', '8dp'], spacing='12dp')
        icon = Image(source='assets/icon.png', size_hint=(None, None), size=('36dp', '36dp'))
        
        title_box = BoxLayout(orientation='vertical')
        self.lbl_title = Label(text="Hermes Editor", color=t['text'], font_size='18sp', bold=True, halign='left')
        self.lbl_title.bind(size=self.lbl_title.setter('text_size'))
        self.lbl_status = Label(text="untitled.txt", color=t['muted'], font_size='12sp', halign='left')
        self.lbl_status.bind(size=self.lbl_status.setter('text_size'))
        title_box.add_widget(self.lbl_title)
        title_box.add_widget(self.lbl_status)
        
        # Menu Button (Dots)
        btn_menu = Button(text="⋮", size_hint=(None, 1), width='48dp', background_normal='', background_color=(0,0,0,0), color=t['text'], font_size='24sp', bold=True)
        btn_menu.bind(on_release=self.open_menu)
        
        self.header.add_widget(icon)
        self.header.add_widget(title_box)
        self.header.add_widget(btn_menu)
        self.add_widget(self.header)

        # 2. Input Area
        self.text_area = TextInput(
            text="", multiline=True, background_normal='', background_active='',
            foreground_color=t['text'], cursor_color=t['accent'], font_size='16sp',
            padding=['16dp', '16dp'], size_hint=(1, 1)
        )
        self.add_widget(self.text_area)

        # 3. Bottom Action Bar (1/4 area)
        self.footer_wrapper = AnchorLayout(anchor_x='center', anchor_y='center', size_hint_y=None, height='120dp', padding=['20dp', '20dp'])
        self.actions_bar = BoxLayout(spacing='15dp', size_hint=(1, None), height='50dp')
        
        self.btn_new = RoundedButton(text="New", bg_color=t['card'], color=t['text'], font_size='15sp', bold=True)
        self.btn_new.bind(on_release=lambda x: self.new_file())
        
        self.btn_open = RoundedButton(text="Open", bg_color=t['accent'], color=(1,1,1,1), font_size='15sp', bold=True)
        self.btn_open.bind(on_release=lambda x: self.open_system_picker())
        
        self.btn_save = RoundedButton(text="Save", bg_color=t['accent'], color=(1,1,1,1), font_size='15sp', bold=True)
        self.btn_save.bind(on_release=lambda x: self.save_system_picker())

        self.actions_bar.add_widget(self.btn_new)
        self.actions_bar.add_widget(self.btn_open)
        self.actions_bar.add_widget(self.btn_save)
        self.footer_wrapper.add_widget(self.actions_bar)
        self.add_widget(self.footer_wrapper)

    def apply_theme(self, theme_name):
        self.current_theme = theme_name
        t = THEMES[theme_name]
        self.text_area.background_color = t['input_bg']
        self.text_area.foreground_color = t['text']
        self.text_area.cursor_color = t['accent']
        self.lbl_title.color = t['text']
        self.lbl_status.color = t['muted']
        self.btn_new.bg_color = t['card']
        self.btn_new.color = t['text']
        self.btn_open.bg_color = t['accent']
        self.btn_save.bg_color = t['accent']
        Window.clearcolor = t['bg']
        self.store.put('theme', type=theme_name)

    def open_menu(self, btn):
        menu = DropDown()
        t = THEMES[self.current_theme]
        
        lbl = "Switch to Light" if self.current_theme == 'dark' else "Switch to Dark"
        btn_theme = Button(text=lbl, size_hint_y=None, height='50dp', background_normal='', background_color=t['card'], color=t['text'])
        btn_theme.bind(on_release=lambda x: [self.apply_theme('light' if self.current_theme == 'dark' else 'dark'), menu.dismiss()])
        
        btn_about = Button(text="About", size_hint_y=None, height='50dp', background_normal='', background_color=t['card'], color=t['text'])
        btn_about.bind(on_release=lambda x: [self.show_about(), menu.dismiss()])
        
        menu.add_widget(btn_theme)
        menu.add_widget(btn_about)
        menu.open(btn)

    def show_about(self):
        t = THEMES[self.current_theme]
        content = BoxLayout(orientation='vertical', padding='20dp', spacing='10dp')
        content.add_widget(Label(text="Hermes Text Editor v0.4", bold=True, color=t['text']))
        content.add_widget(Label(text="Developers:\nSina Chaghimirza & Hermes Agent", halign='center', color=t['muted']))
        btn_close = RoundedButton(text="Close", size_hint=(1, None), height='45dp', bg_color=t['accent'])
        content.add_widget(btn_close)
        
        popup = Popup(title='About', content=content, size_hint=(0.8, 0.4), background_color=t['bg'], title_color=t['text'])
        btn_close.bind(on_release=popup.dismiss)
        popup.open()

    def on_keyboard_height(self, window, height):
        # Auto-sticky buttons above keyboard
        if height > 0:
            self.footer_wrapper.height = '60dp'
            self.footer_wrapper.padding = ['10dp', '5dp']
        else:
            self.footer_wrapper.height = '120dp'
            self.footer_wrapper.padding = ['20dp', '20dp']

    def on_back_pressed(self, *args):
        self.save_cache()
        return False # Allow close

    def save_cache(self, *args):
        try:
            with open(self.cache_path, 'w', encoding='utf-8') as f:
                f.write(self.text_area.text)
        except: pass

    def load_cache(self):
        if os.path.exists(self.cache_path) and not self.current_uri:
            try:
                with open(self.cache_path, 'r', encoding='utf-8') as f:
                    self.text_area.text = f.read()
            except: pass

    def new_file(self):
        self.text_area.text = ""
        self.current_uri = None
        self.lbl_status.text = "untitled.txt"

    def open_system_picker(self):
        if platform == 'android':
            from jnius import autoclass
            Intent = autoclass('android.content.Intent')
            PythonActivity = autoclass('org.kivy.android.PythonActivity')
            intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.setType("text/*")
            PythonActivity.mActivity.startActivityForResult(intent, 1001)

    def save_system_picker(self):
        if platform == 'android':
            from jnius import autoclass
            Intent = autoclass('android.content.Intent')
            PythonActivity = autoclass('org.kivy.android.PythonActivity')
            intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.setType("text/plain")
            intent.putExtra(Intent.EXTRA_TITLE, "document.txt")
            PythonActivity.mActivity.startActivityForResult(intent, 1002)

    @mainthread
    def on_android_activity_result(self, request_code, result_code, intent):
        if result_code == -1 and intent:
            uri = intent.getData()
            if request_code == 1001: self.load_uri(uri)
            elif request_code == 1002: self.save_uri(uri)

    def on_new_intent(self, intent): self.process_intent(intent)
    def check_intent(self):
        from jnius import autoclass
        intent = autoclass('org.kivy.android.PythonActivity').mActivity.getIntent()
        if intent: self.process_intent(intent)

    def process_intent(self, intent):
        if intent.getAction() in ["android.intent.action.VIEW", "android.intent.action.EDIT"]:
            uri = intent.getData()
            if uri: self.load_uri(uri)

    def load_uri(self, uri):
        try:
            from jnius import autoclass
            activity = autoclass('org.kivy.android.PythonActivity').mActivity
            stream = activity.getContentResolver().openInputStream(uri)
            bytes_data = stream.readAllBytes() if hasattr(stream, 'readAllBytes') else stream.read()
            self.text_area.text = bytes_data.decode('utf-8')
            self.current_uri = uri
            self.lbl_status.text = "File opened"
        except Exception as e: self.lbl_status.text = f"Error: {e}"

    def save_uri(self, uri):
        try:
            from jnius import autoclass
            activity = autoclass('org.kivy.android.PythonActivity').mActivity
            stream = activity.getContentResolver().openOutputStream(uri)
            stream.write(self.text_area.text.encode('utf-8'))
            stream.close()
            self.current_uri = uri
            self.lbl_status.text = "Saved"
        except Exception as e: self.lbl_status.text = f"Error: {e}"

class MainApp(App):
    def build(self):
        self.title = "Hermes Text Editor"
        Window.softinput_mode = "resize"
        return HermesEditor()

if __name__ == '__main__':
    MainApp().run()
