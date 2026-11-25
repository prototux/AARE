# Card editor

A web page to design AARE's [custom cards](../../docs/custom-cards.md) by eye instead of by hand: open
`index.html` in a browser. It is one file, with nothing to install and nothing sent anywhere.

1. *New from…* one of AARE's clocks, or blank. Add layers, drag them on the card, change them on the
   right. *Strip* and *Tall* show the card in the other shapes it can have.
2. *Save file*, and copy it to the phone.
3. In AARE, *Car screen > Clock and custom cards > Import cards*.

Arrow keys move the selected layer (with Shift by 10), Delete removes it, Ctrl+Z and Ctrl+Y undo and
redo, Ctrl+S saves. Tapping a condition under *Only with* goes from "doesn't matter" to "only with it"
to "only without it" (crossed out). The fonts are the computer's, so texts can be a little wider or
narrower than on the phone. The page is in the app's 20 languages, by the browser's.

## Changing it

`index.html` is put together by `build.py` (Python 3, nothing to install) from `template.html` (the
page), `words.txt` (its words in every language), `icons.json` (the app's icons) and the app's own
designs, names and font. Change those, then run `build.py`. `CardEditorTest` checks that it was run,
and that the editor knows the same values, conditions, tokens, fonts, icons and colours as the app.
