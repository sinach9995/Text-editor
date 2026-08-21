import os
import sys
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.anchorlayout import AnchorLayout
from kivy.uix.textinput import TextInput
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.image import Image
from kivy.graphics import Color, RoundedRectangle
from kivy.utils import platform
from kivy.core.window import Window
from kivy.clock import mainthread

BG_DARK = (0.08, 0.09, 0.11, 1)
CARD_DARK = (0.16, 0.18, 0.22, 1)
BLUE_ACCENT = (0.22, 0.52, 0.98, 1)
TEXT_LIGHT = (0.95, 0.96, 0.98, 1)
TEXT_MUTED = (0.55, 0.58, 0.64, 1)

Window.clearcolor = BG_DARK

class RoundedButton(Button):
    def __init__(self, bg_color=BLUE_ACCENT, radius=[16,], **kwargs):
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
        super().__init__(orientation='vertical', spacing=0, padding=0, **kwargs)
        self.current_uri = None
        self.current_filename = 'untitled.txt'

        header = BoxLayout(
            size_hint_y=None, 
            height='64dp', 
            padding=['16dp', '10dp', '16dp', '6dp'], 
            spacing='12dp'
        )
        
        icon = Image(
            source='assets/icon.png', 
            size_hint=(None, None), 
            size=('36dp', '36dp'),
            allow_stretch=True
        )
        
        header_text_box = BoxLayout(orientation='vertical', spacing='2dp')
        title_label = Label(
            text='Hermes Editor',
            color=TEXT_LIGHT,
            font_size='18sp',
            bold=True,
            halign='left',
            valign='middle'
        )
        title_label.bind(size=title_label.setter('text_size'))
        
        self.status_label = Label(
            text='untitled.txt',
            color=TEXT_MUTED,
            font_size='12sp',
            halign='left',
            valign='middle'
        )
        self.status_label.bind(size=self.status_label.setter('text_size'))
        
        header_text_box.add_widget(title_label)
        header_text_box.add_widget(self.status_label)
        
        header.add_widget(icon)
        header.add_widget(header_text_box)
        self.add_widget(header)

        self.text_area = TextInput(
            text='',
            hint_text='Start typing or open a file...',
            multiline=True,
            background_normal='',
            background_active='',
            background_color=(0.11, 0.12, 0.15, 1),
            foreground_color=TEXT_LIGHT,
            hint_text_color=(0.4, 0.44, 0.5, 1),
            cursor_color=BLUE_ACCENT,
            font_size='16sp',
            padding=['16dp', '14dp', '16dp', '14dp'],
            size_hint=(1, 1)
        )
        self.add_widget(self.text_area)

        bottom_wrapper = AnchorLayout(
            anchor_x='center', 
            anchor_y='center', 
            size_hint_y=None, 
            height='80dp',
            padding=['16dp', '12dp']
        )
        
        actions_bar = BoxLayout(spacing='12dp', size_hint=(1, 1))
        
        btn_new = RoundedButton(
            text='New',
            bg_color=CARD_DARK,
            color=TEXT_LIGHT,
            font_size='15sp',
            bold=True,
            size_hint_x=0.3
        )
        btn_new.bind(on_release=lambda x: self.new_file())

        btn_open = RoundedButton(
            text='Open',
            bg_color=BLUE_ACCENT,
            color=(1, 1, 1, 1),
            font_size='15sp',
            bold=True,
            size_hint_x=0.35
        )
        btn_open.bind(on_release=lambda x: self.open_system_file_picker())

        btn_save = RoundedButton(
            text='Save',
            bg_color=BLUE_ACCENT,
            color=(1, 1, 1, 1),
            font_size='15sp',
            bold=True,
            size_hint_x=0.35
        )
        btn_save.bind(on_release=lambda x: self.save_system_file_picker())

        actions_bar.add_widget(btn_new)
        actions_bar.add_widget(btn_open)
        actions_bar.add_widget(btn_save)
        
        bottom_wrapper.add_widget(actions_bar)
        self.add_widget(bottom_wrapper)

        if platform == 'android':
            from android import activity
            activity.bind(on_activity_result=self.on_android_activity_result)
            activity.bind(on_new_intent=self.on_new_intent)
            self.check_intent()

    def new_file(self):
        self.text_area.text = ''
        self.current_uri = None
        self.current_filename = 'untitled.txt'
        self.status_label.text = 'untitled.txt'

    def check_intent(self):
        try:
            from jnius import autoclass
            PythonActivity = autoclass('org.kivy.android.PythonActivity')
            current_intent = PythonActivity.mActivity.getIntent()
            if current_intent:
                self.process_intent(current_intent)
        except Exception as e:
            print(f'Error checking startup intent: {e}')

    def on_new_intent(self, intent):
        self.process_intent(intent)

    def process_intent(self, intent):
        try:
            from jnius import autoclass
            Intent = autoclass('android.content.Intent')
            action = intent.getAction()
            if action in [Intent.ACTION_VIEW, Intent.ACTION_EDIT]:
                uri = intent.getData()
                if uri:
                    self.load_uri_content(uri)
        except Exception as e:
            print(f'Error processing intent: {e}')

    def open_system_file_picker(self):
        if platform == 'android':
            try:
                from jnius import autoclass
                Intent = autoclass('android.content.Intent')
                PythonActivity = autoclass('org.kivy.android.PythonActivity')
                
                intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.setType('text/*')
                PythonActivity.mActivity.startActivityForResult(intent, 1001)
            except Exception as e:
                print(f'Could not open file picker: {e}')
        else:
            self.status_label.text = 'Open picker simulated on desktop'

    def save_system_file_picker(self):
        if platform == 'android':
            try:
                from jnius import autoclass
                Intent = autoclass('android.content.Intent')
                PythonActivity = autoclass('org.kivy.android.PythonActivity')
                
                intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.setType('text/plain')
                intent.putExtra(Intent.EXTRA_TITLE, self.current_filename)
                PythonActivity.mActivity.startActivityForResult(intent, 1002)
            except Exception as e:
                print(f'Could not open save picker: {e}')
        else:
            self.status_label.text = 'Save picker simulated on desktop'

    @mainthread
    def on_android_activity_result(self, request_code, result_code, intent):
        if result_code != -1 or intent is None:
            return

        uri = intent.getData()
        if uri is None:
            return

        if request_code == 1001:
            self.load_uri_content(uri)
        elif request_code == 1002:
            self.save_uri_content(uri)

    def load_uri_content(self, uri):
        try:
            from jnius import autoclass
            PythonActivity = autoclass('org.kivy.android.PythonActivity')
            activity = PythonActivity.mActivity
            content_resolver = activity.getContentResolver()
            input_stream = content_resolver.openInputStream(uri)
            
            BufferedReader = autoclass('java.io.BufferedReader')
            InputStreamReader = autoclass('java.io.InputStreamReader')
            reader = BufferedReader(InputStreamReader(input_stream, 'UTF-8'))
            
            lines = []
            line = reader.readLine()
            while line is not None:
                lines.append(line)
                line = reader.readLine()
            reader.close()
            
            self.text_area.text = '
'.join(lines)
            self.current_uri = uri
            self.status_label.text = 'Opened file successfully'
        except Exception as e:
            self.status_label.text = f'Error reading: {e}'

    def save_uri_content(self, uri):
        try:
            from jnius import autoclass
            PythonActivity = autoclass('org.kivy.android.PythonActivity')
            activity = PythonActivity.mActivity
            content_resolver = activity.getContentResolver()
            output_stream = content_resolver.openOutputStream(uri)
            
            text_bytes = self.text_area.text.encode('utf-8')
            output_stream.write(text_bytes)
            output_stream.flush()
            output_stream.close()
            
            self.current_uri = uri
            self.status_label.text = 'Saved successfully'
        except Exception as e:
            self.status_label.text = f'Error saving: {e}'

class MainApp(App):
    def build(self):
        self.title = 'Hermes Text Editor'
        return HermesEditor()

if __name__ == '__main__':
    MainApp().run()
