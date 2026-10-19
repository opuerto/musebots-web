# The learned solo models

`markov-sax.json`, `markov-piano.json`, `markov-bass.json` and `markov-drums.json` are the models the band's learned solos play
from, trained with the tools in `learning/` (see the main README, "Learning solos from real jazz").

They are derived from the **Weimar Jazz Database** (Jazzomat Research Project, Hochschule für Musik Franz Liszt Weimar,
https://jazzomat.hfm-weimar.de/) and are made available under the **Open Database License (ODbL) v1.0**:
https://opendatacommons.org/licenses/odbl/1-0/. The server offers them for download at `/models`, as the licence asks of a
site whose music is made from them.

The image includes this folder, and the server loads whichever of the four files are here. To change a model, retrain it,
copy it here under the same name, and deploy again. The corpus they were trained from (`data/`) is not shared.
