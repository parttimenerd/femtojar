# Bytecode Technique Snapshot

- Release: default
- Toolchains: default=javac
- Technique filter: all

| Technique                    | JDK     | Class Before | Class After |    Class Delta | Deflate Before | Deflate After |  Deflate Delta | Status |
| ---------------------------- | ------- | -----------: | ----------: | -------------: | -------------: | ------------: | -------------: | ------ |
| 01_enum_switch_if            | default |         1528 |        1412 |   -116 (-7.6%) |            818 |           746 |    -72 (-8.8%) | ok     |
| 02_string_switch_if          | default |          535 |         411 |  -124 (-23.2%) |            390 |           300 |   -90 (-23.1%) | ok     |
| 03_record_vs_class           | default |         2204 |        1335 |  -869 (-39.4%) |           1025 |           699 |  -326 (-31.8%) | ok     |
| 04_stream_vs_loop            | default |         1257 |         620 |  -637 (-50.7%) |            621 |           428 |  -193 (-31.1%) | ok     |
| 07_unmodifiable_map          | default |          688 |         614 |   -74 (-10.8%) |            430 |           385 |   -45 (-10.5%) | ok     |
| 08_inline_single_use_methods | default |         1140 |         844 |  -296 (-26.0%) |            602 |           509 |   -93 (-15.4%) | ok     |
| 09_redundant_copyof          | default |          462 |         457 |     -5 (-1.1%) |            302 |           301 |     -1 (-0.3%) | ok     |
| 11_pattern_switch            | default |          877 |         437 |  -440 (-50.2%) |            529 |           321 |  -208 (-39.3%) | ok     |
| 12_record_deconstruction     | default |         2410 |        1652 |  -758 (-31.5%) |           1092 |           780 |  -312 (-28.6%) | ok     |
| 13_foreach_vs_indexloop      | default |          611 |         526 |   -85 (-13.9%) |            420 |           379 |    -41 (-9.8%) | ok     |
| 14_boxing_vs_primitives      | default |          528 |         308 |  -220 (-41.7%) |            380 |           262 |  -118 (-31.1%) | ok     |
| 16_local_var_pressure        | default |          460 |         446 |    -14 (-3.0%) |            368 |           354 |    -14 (-3.8%) | ok     |
| 17_class_merging             | default |          981 |         350 |  -631 (-64.3%) |            381 |           239 |  -142 (-37.3%) | ok     |
| TOTAL(ok)                    | all     |        13681 |        9412 | -4269 (-31.2%) |           7358 |          5703 | -1655 (-22.5%) | ok     |
