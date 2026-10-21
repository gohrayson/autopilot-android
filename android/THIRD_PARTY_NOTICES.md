# Android dependencies

| Component | Version | License | Corresponding source |
| --- | --- | --- | --- |
| libmihomo-android | 0.3.11 | GPL-3.0 | https://github.com/oviron/libmihomo-android/tree/v0.3.11 |
| mihomo (embedded upstream) | 1.19.32 | GPL-3.0 | https://github.com/MetaCubeX/mihomo/tree/v1.19.32 |
| Kotlin standard library | 2.2.20 | Apache-2.0 | https://github.com/JetBrains/kotlin/tree/v2.2.20 |
| SnakeYAML | 2.4 | Apache-2.0 | https://bitbucket.org/snakeyaml/snakeyaml/src/snakeyaml-2.4/ |
| desugar_jdk_libs | 2.1.5 | GPL-2.0 with Classpath Exception / Apache-2.0, per file | https://github.com/google/desugar_jdk_libs |
| JUnit (build-time tests) | 4.13.2 | EPL-1.0 | https://github.com/junit-team/junit4/tree/r4.13.2 |
| JSON-java (build-time tests) | 20240303 | Public domain | https://github.com/stleary/JSON-java/tree/20240303 |

The native bridge's own source includes its upstream Go dependency manifest and build scripts. The pinned source tag and binary checksum are part of this build's dependency contract. Account API credentials and Android signing material are never part of the source distribution.
