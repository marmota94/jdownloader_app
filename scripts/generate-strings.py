from pathlib import Path
from xml.sax.saxutils import escape
root = Path(__file__).resolve().parents[1]
rows = [line.split('\t') for line in (root / 'scripts/strings-source.tsv').read_text(encoding='utf-8').splitlines()]
for column, folder in enumerate(['values', 'values-es', 'values-de'], 1):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    for row in rows:
        text = escape(row[column]).replace("'", "\\'").replace('’', "\\'")
        lines.append(f'    <string name="{row[0]}">{text}</string>')
    lines.append('</resources>')
    (root / f'app/src/main/res/{folder}/strings.xml').write_text('\n'.join(lines) + '\n', encoding='utf-8')
