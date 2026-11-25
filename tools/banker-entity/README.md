# Banker appearance

Run `python3 tools/banker-entity/generate_texture.py` to reproduce the original 64×64 RGBA skin and its [front and back preview](banker_views.png). The renderer uses standard 64-unit humanoid UV coordinates with one raster pixel per unit.

The skin includes blocky facial features, two separate eyebrows and inward-facing pupils, hair and gray temples, herringbone cloth, shirt seams, buttons, trouser creases and leather shoes. Lapels, collar, tie, name badge, pocket square, cuffs and shoes are painted onto the standard humanoid body. Reserved UV islands supply the sculpted hair cap and compact nose in `BankerRenderer`.

The generator, pixel artwork and custom geometry are original Economy work dedicated under CC0-1.0.
