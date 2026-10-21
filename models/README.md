# The learned solo models

`markov.json` is the model the band's learned solos play from (every instrument plays from it), trained with the tools in
`learning/` (see the main README, "Learning solos from real jazz"): `train --instruments all --test-fraction 0`, on all the
usable solos of the Weimar Jazz Database.

They are derived from the **Weimar Jazz Database** (Jazzomat Research Project, Hochschule für Musik Franz Liszt Weimar,
https://jazzomat.hfm-weimar.de/) and are made available under the **Open Database License (ODbL) v1.0**:
https://opendatacommons.org/licenses/odbl/1-0/. The server offers them for download at `/models`, as the licence asks of a
site whose music is made from them.

The image includes this folder and loads `markov.json`. To change the model, retrain it, copy it here under the same name, and
deploy again. The corpus they were trained from (`data/`) is not shared.
