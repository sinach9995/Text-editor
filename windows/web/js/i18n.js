export const STRINGS = {
  en: {
    app_name: 'Hermes Text Editor',
    recent_tab: 'Recent',
    recent_title: 'Recent',
    recent_open_now: 'Open',
    recent_empty: 'No recent files yet.',
    recent_forget: 'Remove from recent',
    ui_new: 'New',
    ui_open: 'Open',
    ui_save: 'Save',
    ui_save_as: 'Save as…',
    ui_export_txt: 'Export as plain text…',
    new_txt: 'New text file (.txt)',
    new_md: 'New Markdown file (.md)',
    theme_to_light: 'Light theme',
    theme_to_dark: 'Dark theme',
    edit_md: 'Edit Markdown',
    preview_md: 'Preview Markdown',
    preview_short: 'Preview',
    edit_short: 'Edit',
    dir_menu: 'Text direction',
    dir_ltr: 'Left-to-right',
    dir_rtl: 'Right-to-left',
    dir_sug_title_fa: 'This file appears to be Persian.',
    dir_sug_text_fa: 'Switch writing direction to right-to-left?',
    dir_sug_go_rtl: 'Switch to RTL',
    dir_sug_keep_ltr: 'Keep LTR',
    dir_sug_title_en: 'This file appears to be English.',
    dir_sug_text_en: 'Switch writing direction to left-to-right?',
    dir_sug_go_ltr: 'Switch to LTR',
    dir_sug_keep_rtl: 'Keep RTL',
    find: 'Find',
    find_query: 'Word or phrase',
    match_count: '%1$d / %2$d',
    no_matches: 'No matches',
    previous_match: 'Previous match',
    next_match: 'Next match',
    font_size: 'Font size',
    discard: 'Discard',
    discard_title: 'Discard unsaved changes?',
    discard_open: 'Your unsaved changes in this tab will be lost if you continue.',
    discard_clear: 'This will clear the editor. Your last saved file will not be changed.',
    save_and_close: 'Save & Close',
    close: 'Close',
    cancel: 'Cancel',
    cont: 'Continue',
    undo: 'Undo',
    redo: 'Redo',
    more_menu: 'More',
    help: 'Help & Guide',
    about: 'About',
    preview_empty: 'Nothing to preview.',
    hint: 'Start writing…',
    saveas_title: 'Choose format',
    saveas_txt: 'Text (.txt)',
    saveas_md: 'Markdown (.md)',
    table_add_row: '+ Row',
    table_del_row: '− Row',
    table_add_col: '+ Col',
    table_del_col: '− Col',
    table_raw: 'Raw',
    table_grid: 'Table',
    err_open: 'Could not open file',
    err_file_too_large: 'File is larger than 5 MB',
    err_read_only_save_as: 'Could not overwrite file, choose Save as…',
    saved: 'Saved: %1$s',
    saved_copy: 'Saved copy: %1$s',
    saved_deduped: 'Saved: %1$s (closed duplicate tab)',
    exported: 'Exported: %1$s',
    save_failed: 'Could not save this file. Use Save as… to create a copy.',
    tab_limit_reached: 'Close a tab first: up to 10 documents can stay open',
    tab_memory_reached: 'Close a tab first: open documents have reached the 6 MB memory limit',
    about_body: 'Version %1$s (Windows)\n\nCreated by Hermes Agent and Notion AI\nwith help, direction, and oversight by\nSina Chaghamirza · AsoraKSH',
    help_intro: 'Hermes Text Editor for Windows keeps your files local and offline — no telemetry, no cloud sync, no remote requests.',
    help_items: [
      {
        title: 'Opening Markdown & Text files',
        body: 'Click Open (Ctrl+O), drag and drop a .md, .markdown, or .txt file onto the window, or set Hermes as the Windows "Open with" app for Markdown files. Existing .md files open straight into Markdown Preview so tables, headings, lists, and code render cleanly.'
      },
      {
        title: 'Preview and Edit Markdown',
        body: 'Use the Preview / Edit pill in the header (or Ctrl+E) to switch between rendered Preview and live-styled Markdown source. Your scroll position and text direction stay per tab.'
      },
      {
        title: 'Saving & Exporting',
        body: 'Click the Save floppy icon (or Ctrl+S) to save safely with atomic writes. Right-click Save or click its arrow (or Ctrl+Shift+S) for Save as… (.txt / .md) and Export as plain text… (which strips Markdown syntax and formats tables row by row).'
      },
      {
        title: 'Tabs & Recent page',
        body: 'Click the Hermes icon in the top-left corner to open Recent, listing open documents and up to 12 recently opened files. Up to 10 tabs can stay open at once with automatic local draft recovery.'
      },
      {
        title: 'Find, Zoom, Language & Direction',
        body: 'Use Ctrl+F for Find, Ctrl+Plus / Ctrl+Minus / Ctrl+MouseWheel to zoom text size (13–30px), and the three-dot menu to toggle Light/Dark theme, English/فارسی interface, or LTR/RTL text direction.'
      },
      {
        title: 'Keyboard shortcuts',
        body: 'Ctrl+N: New .txt • Ctrl+Shift+N: New .md • Ctrl+O: Open • Ctrl+S: Save • Ctrl+Shift+S: Save as… • Ctrl+E: Toggle Preview/Edit • Ctrl+F: Find • Ctrl+W: Close tab • Ctrl+Tab: Next tab • Ctrl+Z / Ctrl+Y: Undo / Redo'
      }
    ]
  },
  fa: {
    app_name: 'Hermes Text Editor',
    recent_tab: 'اخیر',
    recent_title: 'اخیر',
    recent_open_now: 'باز است',
    recent_empty: 'هنوز فایلی باز نشده است.',
    recent_forget: 'حذف از فهرست اخیر',
    ui_new: 'جدید',
    ui_open: 'باز کردن',
    ui_save: 'ذخیره',
    ui_save_as: 'ذخیره با نام دیگر…',
    ui_export_txt: 'خروجی متن ساده…',
    new_txt: 'فایل متنی جدید (.txt)',
    new_md: 'فایل Markdown جدید (.md)',
    theme_to_light: 'پوسته روشن',
    theme_to_dark: 'پوسته تیره',
    edit_md: 'ویرایش Markdown',
    preview_md: 'پیش‌نمایش Markdown',
    preview_short: 'پیش‌نمایش',
    edit_short: 'ویرایش',
    dir_menu: 'جهت متن',
    dir_ltr: 'چپ‌به‌راست',
    dir_rtl: 'راست‌به‌چپ',
    dir_sug_title_fa: 'به نظر می‌رسد این فایل فارسی است.',
    dir_sug_text_fa: 'جهت نوشتن را راست‌به‌چپ تنظیم کنم؟',
    dir_sug_go_rtl: 'راست‌به‌چپ شود',
    dir_sug_keep_ltr: 'چپ‌به‌راست بماند',
    dir_sug_title_en: 'به نظر می‌رسد این فایل انگلیسی است.',
    dir_sug_text_en: 'جهت نوشتن را چپ‌به‌راست تنظیم کنم؟',
    dir_sug_go_ltr: 'چپ‌به‌راست شود',
    dir_sug_keep_rtl: 'راست‌به‌چپ بماند',
    find: 'یافتن',
    find_query: 'واژه یا عبارت',
    match_count: '%1$d / %2$d',
    no_matches: 'موردی پیدا نشد',
    previous_match: 'نتیجهٔ قبلی',
    next_match: 'نتیجهٔ بعدی',
    font_size: 'اندازهٔ متن',
    discard: 'دور انداختن',
    discard_title: 'تغییرات ذخیره‌نشده دور انداخته شود؟',
    discard_open: 'در صورت ادامه، تغییرات ذخیره‌نشدهٔ این تب از بین می‌رود.',
    discard_clear: 'این کار ویرایشگر را پاک می‌کند. آخرین فایل ذخیره‌شدهٔ شما تغییر نمی‌کند.',
    save_and_close: 'ذخیره و بستن',
    close: 'بستن',
    cancel: 'لغو',
    cont: 'ادامه',
    undo: 'بازگرداندن',
    redo: 'تکرار تغییر',
    more_menu: 'بیشتر',
    help: 'راهنما',
    about: 'درباره',
    preview_empty: 'چیزی برای پیش‌نمایش وجود ندارد.',
    hint: 'شروع به نوشتن…',
    saveas_title: 'قالب را انتخاب کنید',
    saveas_txt: 'متن (.txt)',
    saveas_md: 'Markdown (.md)',
    table_add_row: '+ ردیف',
    table_del_row: '− ردیف',
    table_add_col: '+ ستون',
    table_del_col: '− ستون',
    table_raw: 'متن خام',
    table_grid: 'جدول',
    err_open: 'فایل باز نشد',
    err_file_too_large: 'فایل بزرگ‌تر از ۵ مگابایت است',
    err_read_only_save_as: 'بازنویسی مستقیم ممکن نشد؛ از «ذخیره با نام دیگر…» استفاده کنید',
    saved: 'ذخیره شد: %1$s',
    saved_copy: 'نسخهٔ کپی ذخیره شد: %1$s',
    saved_deduped: 'ذخیره شد: %1$s (تب تکراری بسته شد)',
    exported: 'خروجی ساخته شد: %1$s',
    save_failed: 'این فایل ذخیره نشد. برای ساختن یک کپی، از ذخیره با نام دیگر… استفاده کنید.',
    tab_limit_reached: 'اول یک تب را ببندید؛ حداکثر ۱۰ سند می‌تواند باز بماند',
    tab_memory_reached: 'اول یک تب را ببندید؛ متن تب‌های باز به سقف حافظه (۶ مگابایت) رسیده است',
    about_body: 'نسخه %1$s (ویندوز)\n\nساخته‌شده توسط Hermes Agent و Notion AI\nبا کمک، راهنمایی و نظارت\nSina Chaghamirza · AsoraKSH',
    help_intro: 'Hermes Text Editor برای ویندوز کاملاً آفلاین و محلی است — بدون ردیابی، همگام‌سازی ابری یا درخواست اینترنتی.',
    help_items: [
      {
        title: 'باز کردن فایل‌های Markdown و متنی',
        body: 'روی «باز کردن» (Ctrl+O) بزنید، فایل .md، .markdown یا .txt را روی پنجره بکشید و رها کنید، یا در ویندوز Hermes را به‌عنوان برنامهٔ پیش‌فرض باز کردن فایل‌های Markdown انتخاب کنید. فایل‌های موجود .md مستقیم در پیش‌نمایش باز می‌شوند تا جدول‌ها و عنوان‌ها درست نمایش داده شوند.'
      },
      {
        title: 'پیش‌نمایش و ویرایش Markdown',
        body: 'از دکمهٔ پیش‌نمایش / ویرایش در نوار بالا (یا Ctrl+E) برای جابه‌جایی بین نمایش قالب‌بندی‌شده و متن اصلی Markdown استفاده کنید.'
      },
      {
        title: 'ذخیره و خروجی متن ساده',
        body: 'با دکمهٔ فلاپی ذخیره (یا Ctrl+S) فایل را امن ذخیره کنید. با کلیک راست روی ذخیره یا باز کردن منوی آن (یا Ctrl+Shift+S) به «ذخیره با نام دیگر…» و «خروجی متن ساده…» دسترسی دارید.'
      },
      {
        title: 'تب‌ها و صفحهٔ اخیر',
        body: 'روی آیکون هرمس در گوشهٔ نوار بزنید تا فهرست «اخیر» باز شود. تا ۱۰ سند می‌تواند هم‌زمان باز بماند و پیش‌نویس‌ها محلی ذخیره می‌شوند.'
      },
      {
        title: 'یافتن، بزرگ‌نمایی، زبان و جهت متن',
        body: 'با Ctrl+F در سند جستجو کنید، با Ctrl+Plus / Ctrl+Minus یا Ctrl+چرخ ماوس اندازهٔ متن را تنظیم کنید و از منوی سه‌نقطه پوسته، زبان یا جهت متن را تغییر دهید.'
      },
      {
        title: 'کلیدهای میان‌بر',
        body: 'Ctrl+N: فایل متنی جدید • Ctrl+Shift+N: Markdown جدید • Ctrl+O: باز کردن • Ctrl+S: ذخیره • Ctrl+Shift+S: ذخیره با نام دیگر • Ctrl+E: پیش‌نمایش/ویرایش • Ctrl+F: یافتن • Ctrl+W: بستن تب • Ctrl+Z / Ctrl+Y: بازگرداندن / تکرار'
      }
    ]
  }
};

export function t(lang, key, ...args) {
  const dict = STRINGS[lang] || STRINGS.en;
  let value = dict[key] ?? STRINGS.en[key] ?? key;
  if (typeof value !== 'string') return value;
  args.forEach((arg, idx) => {
    const pos = idx + 1;
    value = value.replaceAll(`%${pos}$s`, String(arg)).replaceAll(`%${pos}$d`, String(arg));
  });
  return value;
}
