# Applied Energistics 2 terminal icons

Textures © 2013–2015 AlgorithmX2 et al., distributed under
[CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/) ([license text](LICENSE.txt)).

Source: [Applied Energistics 2 Unofficial, GTNH](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial),
revision `151550f6d558a663eee792f0bfa22e12a53c0e54`.
See the upstream [texture license declaration](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial/blob/151550f6d558a663eee792f0bfa22e12a53c0e54/README.md#license)
and [states.png atlas](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial/blob/151550f6d558a663eee792f0bfa22e12a53c0e54/src/main/resources/assets/appliedenergistics2/textures/guis/states.png).

`terminal.mjs` embeds unmodified 16 × 16 pixel crops as PNG data URLs.
The theme scales them with nearest-neighbor rendering. Atlas indices count across 16 columns.

| Use                                                | Atlas index |
| -------------------------------------------------- | ----------- |
| All resources                                      | 18          |
| Stored resources                                   | 16          |
| Craftable resources                                | 19          |
| Sort by name                                       | 64          |
| Sort by quantity                                   | 65          |
| Sort by registry ID (AE2's mod symbol)             | 69          |
| Pending CPU resources (AE2 crafting status symbol) | 226         |
| Crafting marker                                    | 178         |

The existing `assets/favicon.ico`, also displayed in the site header, matches atlas tile 178
and carries the same texture attribution and license.
