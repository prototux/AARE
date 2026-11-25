#!/usr/bin/env python3
"""Puts index.html together from template.html, words.txt, icons.json and the app's own designs, names and font.

Run it after changing any of them; CardEditorTest checks that it was run. No arguments, nothing to install.
"""
import base64, html, json, re
from pathlib import Path

here = Path(__file__).resolve().parent
main = here.parents[1] / "app/src/main"
LANGS = "en es ar cs da de fi fr hu it nb nl pl pt ro ru sv uk vi zh".split()
# The editor's word -> the app's string, so both say the same.
APP = {"sampleEvent": "faces_sample_event", "sampleSong": "faces_sample_song", "sampleArtist": "faces_sample_artist",
       "noEvents": "dash_no_events", "today": "dash_event_today"}
designs = re.search(r"BUNDLED = listOf\((.*?)\)", (main / "java/net/prototux/aare/faces/FaceStore.kt").read_text("utf-8"))[1]
designs = re.findall(r'"(\w+)"', designs)
APP.update({f"face_{d}": f"face_{d}" for d in designs})


def strings(lang):
    text = (main / "res" / ("values" if lang == "en" else f"values-{lang}") / "strings.xml").read_text("utf-8")
    return {k: html.unescape(v).replace("\\'", "'") for k, v in re.findall(r'<string name="(\w+)">(.*?)</string>', text)}


rows = {}
for line in (here / "words.txt").read_text("utf-8").splitlines():
    if line and not line.startswith("#"):
        key, words = line.split(" = ", 1)
        rows[key] = [w.strip() for w in words.split(" | ")]
        assert len(rows[key]) == len(LANGS), f"{key}: {len(rows[key])} words for {len(LANGS)} languages"
app = [strings(l) for l in LANGS]
for key, name in APP.items():
    rows[key] = [s[name] for s in app]
# Where to import, as the app calls it.
rows["help"] = [t.replace("{page}", f'{s["setup_page_car_screen_title"]} > {s["faces_page_title"]}') for t, s in zip(rows["help"], app)]

font = base64.b64encode((main / "res/font/dseg7_classic_regular.ttf").read_bytes()).decode()
dump = lambda v: json.dumps(v, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
page = (here / "template.html").read_text("utf-8")
for mark, value in {
    "/*FONT*/": "/* DSEG7 Classic, SIL Open Font License 1.1: see app/src/main/assets/licenses */\n@font-face { font-family:DSEG7; src:url(data:font/ttf;base64," + font + "); }",
    "/*DESIGNS*/null": dump({d: json.loads((main / f"assets/faces/{d}.json").read_text("utf-8")) for d in designs}),
    "/*ICONS*/null": dump(json.loads((here / "icons.json").read_text("utf-8"))),
    "/*WORDS*/null": dump({"langs": LANGS, "rows": rows}),
}.items():
    assert page.count(mark) == 1, mark
    page = page.replace(mark, value)
(here / "index.html").write_text(page, "utf-8")
