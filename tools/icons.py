#!/usr/bin/env python3
"""Material Symbols(Rounded, Apache 2.0) SVG 를 안드로이드 벡터 드로어블로 변환"""
import re, sys, urllib.request, os
ICONS = """arrow_back ink_pen ink_highlighter ink_eraser lasso_select shapes text_fields pan_tool image undo redo
layers touch_app more_vert add bookmark bookmark:fill close search settings picture_as_pdf share delete content_copy
palette visibility visibility_off keyboard_arrow_up keyboard_arrow_down workspace_premium auto_stories check
star star:fill sort upload_file download file_open description my_location view_agenda grid_view edit
brush format_color_fill gesture info lock arrow_forward note_stack restore drive_file_rename_outline""".split()
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
for spec in ICONS:
    name, _, var = spec.partition(':')
    fname = f"{name}_fill1_24px.svg" if var == 'fill' else f"{name}_24px.svg"
    url = f"https://raw.githubusercontent.com/google/material-design-icons/master/symbols/web/{name}/materialsymbolsrounded/{fname}"
    try:
        svg = urllib.request.urlopen(url).read().decode()
    except Exception as e:
        print("FAIL", spec, e); continue
    paths = re.findall(r'<path d="([^"]+)"', svg)
    res = name + ("_filled" if var == 'fill' else "")
    body = "\n".join(f'        <path android:fillColor="#FF000000" android:pathData="{d}" />' for d in paths)
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="960" android:viewportHeight="960">
    <group android:translateY="960">
{body}
    </group>
</vector>
'''
    open(os.path.join(out, f"ic_{res}.xml"), "w").write(xml)
    print("ok", res)
