# LibreHU Helper

Appli de **diagnostic** pour porter [LibreHU](https://github.com/LibreHU) sur d'autres autoradios Android : autre ROM,
autre version d'Android, autre MCU, autre boîtier CAN, autre voiture. Elle rassemble les informations techniques dans
un **rapport zip**, exporté dans le dossier choisi (une clé USB par exemple, facultatif). **Lecture seule** : rien
n'est modifié sur l'autoradio.

Thème clair / sombre et couleur d'accent de [LibreHU Launcher](https://github.com/LibreHU/LibreHU-Launcher-App)
(ou mode nuit d'Android sans lui), interface en français et en anglais, mise à jour par
[LibreHU Apps](https://github.com/LibreHU/LibreHU-OTA-app).

## Root

Facultatif : sans root, le rapport est fait avec ce qu'Android laisse lire (moins de détails). Recherche en deux temps :

1. **le `su` de la ROM** : beaucoup de ROM d'origine d'autoradios ont un root d'usine discret. L'appli cherche les
   fichiers nommés `su` dans les emplacements habituels, le PATH, et parcourt `/system`, `/vendor`, `/oem`, `/jancar`,
   `/product`, `/odm`… ;
2. **les gestionnaires de root** : KernelSU Next, KernelSU, APatch, Magisk (et ses variantes), SuperSU, Superuser.
   Leurs points d'entrée sont essayés (applet `magisk su`, crochet noyau de KernelSU / APatch sur `/system/bin/su`,
   `/su/bin/su` de SuperSU…).

Chaque candidat est essayé avec `su -c` puis avec la convention AOSP `su 0` ; le premier qui donne `uid=0` sert.
Le rapport (`root.txt`) liste tout ce qui a été trouvé et essayé. Avec KernelSU / KernelSU Next / APatch, autoriser
LibreHU Helper dans la liste *Superuser* du gestionnaire puis **Relancer la recherche**.

## Onglets

| Onglet | Contenu |
|---|---|
| **Rapport** | root, sections à collecter, anonymisation, export zip (dossier choisi ou dossier de l'appli) |
| **Compatibilité** | évaluation point par point (version d'Android réelle, architecture, famille de SoC, root, service du fabricant, ports série tenus, démarrage vérifié, SELinux, écran, stockage, disposition des partitions), avec ce qui peut poser problème et quoi essayer, et un dépannage de base |
| **Matériel** | scan des bus I²C (lectures seules, comme `i2cdetect -r`), recherche de la **puce Apple MFi** de CarPlay (adresses 0x10 / 0x11, registres lus comme le fait [LibreHU-xcertplay](https://github.com/LibreHU/LibreHU-xcertplay-app)), trafic des UART d'après les compteurs du noyau, **recherche de la MCU et du boîtier CAN** et de leur protocole (y compris un boîtier CAN relayé dans les trames de la MCU, comme sur Jancar), capture des **touches au volant** (trames vues seulement pendant l'appui, et événements du noyau pour les touches câblées) |
| **APK du fabricant** | détection des applis du fabricant (voir plus bas) et sauvegarde de leurs APK (base, splits, odex / vdex) dans un zip |
| **Full dump** | copie des partitions, une par zip (une clé FAT32 limite un fichier à 4 Go) ; sur base **MediaTek / Autochips**, choix du **fichier scatter** du firmware pour ajouter les partitions sans lien by-name (preloader…), lues à leur adresse |
| **Tutos** | désactiver les applis du fabricant pour que LibreHU-service prenne la main, **sans root** (`adb shell pm disable-user --user 0`) ou **avec root** (`su -c pm disable`), avec les commandes pour les applis trouvées sur l'autoradio, revenir en arrière (`pm enable`), LibreHU Launcher comme écran d'accueil. Commandes de [developer.android.com/tools/adb](https://developer.android.com/tools/adb). L'appli n'exécute rien elle-même. |

**Jamais dumpées** : `userdata` (données personnelles) et les partitions propres à l'autoradio (`nvram`, `nvdata`,
`nvcfg`, `proinfo`, `persist`, `metazone`, `protect1/2`, `otp`, `frp` : IMEI, adresses MAC, calibration). Rien n'est
jamais écrit sur l'autoradio ; `vbmeta` et dm-verity ne sont jamais touchés.

## Contenu du rapport

| Section | Contenu |
|---|---|
| Android et ROM | build, **vraie version d'Android** (depuis le niveau de SDK : certaines ROM falsifient `ro.build.version.release`, celle de Jancar annonce 12 sur Android 9), toutes les propriétés, ligne du noyau, partitions, fstab, montages, SELinux, services, HAL, processus, applis installées et applis du fabricant |
| Journaux | logcat (tous les buffers avec root, sinon les lignes de l'appli seulement), journal du noyau, démarrage précédent (pstore, last_kmsg), plantages, 3 derniers tombstones |
| Noyau et matériel | config du noyau, modules, **device tree** (texte et `.dtb` binaire lisible par `dtc`), périphériques d'entrée et keylayouts du fabricant, périphériques I²C / USB / PCI connus du noyau, ports série et leurs compteurs de trafic (`/proc/tty/driver`), son (cartes, `tinymix`), vidéo, GPIO, CPU, écran, services audio / entrées / Bluetooth / alimentation. Rien n'est sondé. |
| Fichiers du fabricant | liste et petits fichiers texte (≤ 256 Ko) de `/jancar`, `/oem`, `/custom`, `/cust`… (boîtier CAN, touches, tactile, réglages voiture), audio policy, codecs, `build.prop` |
| Scan matériel (désactivé par défaut) | les scans de l'onglet Matériel, les captures série en hexadécimal et les **brouillons de profils MCU** (`scan/draft-profiles.json`, format LibreHU-service) |
| Images noyau et recovery (désactivé par défaut) | `boot`, `recovery`, `dtbo`, `vbmeta`, `vendor_boot`, `lk`, `logo`… (root), pour un portage TWRP ou noyau |

`README.txt` résume l'appareil, le root et les sections ; `manifest.json` liste chaque fichier avec sa taille et son
SHA-256, et ce qui a été laissé de côté et pourquoi.

**Anonymisation** (activée par défaut) : numéros de série, IMEI, adresses Wi-Fi / Bluetooth, Android ID (lus dans
les propriétés de l'appareil), toute adresse MAC et les positions GPS (trames NMEA, `Location[...]`, `lat=` / `lon=`)
sont retirés des fichiers texte. Les images et les APK ne sont pas modifiés.

## Applis du fabricant

Les noms de paquets changent d'un fabricant et d'un firmware à l'autre ; il n'existe pas de liste officielle. Seuls
quelques préfixes sont confirmés : `com.jancar.` (Jancar, dump de l'UJC201), `com.autochips.` / `com.mediatek.`,
`com.syu.` (FYT / Joying : `com.syu.ms`, [hvdwolf/FYTuis7862BinRepo](https://github.com/hvdwolf/FYTuis7862BinRepo)),
`android.microntek.` (Microntek MTC : [f1xpl/MtcdTools](https://github.com/f1xpl/MtcdTools)). La détection
(`device/VendorApps.kt`) les complète par des indices notés : appli système ni AOSP ni Google, APK dans
`priv-app` / `/vendor` / `/oem`…, uid système, mots du nom (can, mcu, ivi, radio, bt…), processus qui tient un port
série. Chaque appli reçoit un rôle (service MCU, boîtier CAN, Bluetooth, radio, touches, launcher, caméra…) et
l'appli LibreHU qui le reprend.

## Analyse des trames (MCU, boîtier CAN)

`analysis/FrameGuesser.kt` devine le découpage d'un protocole série inconnu à partir d'une capture, dans les termes
des [profils MCU de LibreHU-service](https://github.com/LibreHU/LibreHU-service/blob/main/docs/mcu-profiles.md) :
en-tête, position des octets de longueur et de commande, ce que compte la longueur, checksum d'un octet (somme, xor,
complément à deux, xor inversé) avec son octet de départ et son ajustement. Il reconnaît les protocoles déjà connus de
LibreHU (Jancar JAC_V1, Hiworld), y compris l'un dans l'autre, et produit un **brouillon de profil** importable dans
le service. Les boîtiers CAN (Raise, Hiworld…) varient justement selon ces paramètres
([rétro-ingénierie CANBOX, XDA](https://xdaforums.com/t/canbox-can-decoder-reverse-engineering.4057759/page-5)). Le
xor inversé (vu sur des boîtiers Raise) n'existe pas encore dans les profils du service : un brouillon qui l'utilise
devra attendre son ajout. Testé par `FrameGuesserTest` (JAC_V1, Hiworld, Hiworld dans les trames MCU 0x10,
protocole xor inconnu avec du bruit, octets aléatoires).

## Code natif

`app/src/main/cpp/hwprobe.c` (JNI) : scan I²C par lectures, lecture de registres I²C (`I2C_RDWR`, qui marche même
quand un pilote du noyau tient l'adresse), lecture brute d'un port série à une vitesse donnée (réglages remis après).
Lancé en root par `app_process` (`hw/HwProbe.kt`, même principe que l'`AtcHelper` de LibreHU-service), ou dans
l'appli quand les périphériques sont lisibles sans root.

**Pas encore testé sur un autoradio.**

## Appli téléphone (LibreHU Bridge)

Réglable depuis le téléphone par l'appli LibreHU Bridge (Android et iPhone), à travers LibreHU-service : les sections du rapport, l'anonymisation, le root et le rapport écrit dans le dossier de l'appli.
Fournisseur `<paquet>.bridge` (`bridge/BridgeProvider.kt`), réservé aux applis signées avec la clé LibreHU (permission
`org.librehu.permission.BRIDGE`, vérifiée dans `call()` lui-même) ; format décrit dans
[LibreHU-service, docs/bridge.md](https://github.com/LibreHU/LibreHU-service/blob/main/docs/bridge.md).

## Compiler

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

La CI (`.github/workflows/build.yml`) publie à chaque push la prerelease `latest-main` (APK
`LibreHU-Helper-main.apk` et `release.txt`, lus par LibreHU Apps). L'APK est signé avec la clé commune des applis
LibreHU (`keystore/`) et **optimisé par R8** (sans renommage, `app/proguard-rules.pro`) : en cas de « class / method
not found », regarder `usage.txt` dans l'artefact *R8 reports* de la CI.

## Licence

GPL-2.0, comme les autres applis LibreHU.

**Style rétro et police de LibreHU Launcher** : quand le launcher est en style rétro (les couleurs d'un afficheur
d'autoradio tirées de sa couleur d'accent, couleur RGB comprise), cette appli prend sa palette (jour ou nuit) ; quand
la **police d'afficheur à segments** y est choisie (dans tous les styles), cette appli la prend aussi, en majuscules
sans accents (« LibreHU Seg14 », tirée de DSEG14 © keshikan, SIL Open Font License 1.1, voir
`third_party/DSEG-LICENSE.txt`). Le launcher publie le style, la police et la palette par
`content://org.librehu.launcher.theme/theme` (colonnes `style`, `font`, `background`, `surface`, `surface_high`,
`on_accent`, `text`, `text_dim`) et par la diffusion `org.librehu.action.THEME_CHANGED` (mêmes noms en extras).
