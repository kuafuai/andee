#!/usr/bin/env python3
"""Finder window geometry for the Andee drag-to-Applications DMG.

Keep these icon centres aligned with make-background.py. dmgbuild writes the
Finder .DS_Store directly, so the layout survives mounting on modern macOS.
"""
import os

app = os.environ['ANDEE_APP']

files = [app]
symlinks = {'Applications': '/Applications'}

# dmgbuild exec()s this file, so __file__ is not defined; make-dmg.sh passes
# the path in, the same way it passes the app.
background = os.environ['ANDEE_BACKGROUND']
icon = os.environ['ANDEE_VOLUME_ICON']
# dmgbuild stores an alias containing the volume name for the background.
# Update make-dmg.sh's volume name and rebuild the image if it changes.

window_rect = ((200, 120), (820, 500))
default_view = 'icon-view'
icon_size = 104
icon_locations = {
    'Andee.app': (225, 246),
    'Applications': (595, 246),
}
arrange_by = None  # "not arranged" — the layout is hand-placed, not sorted

show_status_bar = False
show_toolbar = False
show_sidebar = False
show_pathbar = False
show_tab_view = False

# UDZO (zlib) mounts faster than dmgbuild's UDBZ default; jpackage's dmg was
# already UDZO, so the finished image behaves the same as before.
format = 'UDZO'
