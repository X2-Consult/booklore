"""Builds the TROVE logo lockups: the mark plus the wordmark and tagline, text outlined from Montserrat.

Needs mark.json from generate-mark.py and Montserrat-VF.ttf (SIL OFL) from github.com/google/fonts/tree/main/ofl/montserrat."""
import json
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

MARK = json.load(open('mark.json'))['d']
STOPS = [(0, '#0a66ff'), (0.55, '#10a6ee'), (1, '#1ed3bf')]

def font(weight):
    return instantiateVariableFont(TTFont('Montserrat-VF.ttf'), {'wght': weight})

def text_path(f, text, size, x, baseline, tracking=0.0):
    """Outline `text` at `size` px, starting at x, with extra letter spacing `tracking` (in em)."""
    cmap = f.getBestCmap(); gs = f.getGlyphSet(); upm = f['head'].unitsPerEm
    hmtx = f['hmtx']; scale = size / upm
    pen = SVGPathPen(gs)
    cursor = x
    for ch in text:
        g = cmap[ord(ch)]
        tp = TransformPen(pen, (scale, 0, 0, -scale, cursor, baseline))
        gs[g].draw(tp)
        cursor += hmtx[g][0] * scale + tracking * size
    return pen.getCommands(), cursor - tracking * size

bold, regular = font(600), font(500)
# Layout on a 124-unit mark: wordmark cap height fills roughly the mark's middle band.
word, word_end = text_path(bold, 'TROVE', 64, 150, 74, tracking=0.28)
tag, tag_end = text_path(regular, 'Your library. Your server. Your books.', 16.5, 153, 108)
width = round(max(word_end, tag_end) + 6)

def svg(text_fill, tag_fill):
    grad = ''.join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in STOPS)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} 124" role="img" aria-label="Trove">'
            f'<defs><linearGradient id="g" gradientUnits="userSpaceOnUse" x1="6" y1="118" x2="118" y2="10">{grad}</linearGradient></defs>'
            f'<path fill="url(#g)" fill-rule="evenodd" d="{MARK}"/>'
            f'<path fill="{text_fill}" d="{word}"/>'
            f'<path fill="{tag_fill}" d="{tag}"/></svg>')

open('trove-logo-light.svg', 'w').write(svg('#111827', '#4b5563'))   # for light backgrounds
open('trove-logo-dark.svg', 'w').write(svg('#f8fafc', '#cbd5e1'))    # for dark backgrounds
print('width', width, 'word_end', word_end, 'tag_end', tag_end)
