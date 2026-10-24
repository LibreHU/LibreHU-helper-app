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

## Contenu du rapport

| Section | Contenu |
|---|---|
| Android et ROM | build, **vraie version d'Android** (depuis le niveau de SDK : certaines ROM falsifient `ro.build.version.release`, celle de Jancar annonce 12 sur Android 9), toutes les propriétés, ligne du noyau, partitions, fstab, montages, SELinux, services, HAL, processus, applis installées et applis du fabricant de la ROM |

`README.txt` résume l'appareil, le root et les sections ; `manifest.json` liste chaque fichier avec sa taille et son
SHA-256, et ce qui a été laissé de côté et pourquoi.

**Anonymisation** (activée par défaut) : numéros de série, IMEI, adresses Wi-Fi / Bluetooth, Android ID (lus dans
les propriétés de l'appareil), toute adresse MAC et les positions GPS (trames NMEA, `Location[...]`, `lat=` / `lon=`)
sont retirés des fichiers texte.

## Analyse des trames (MCU, boîtier CAN)

`analysis/FrameGuesser.kt` devine le découpage d'un protocole série inconnu à partir d'une capture, dans les termes
des [profils MCU de LibreHU-service](https://github.com/LibreHU/LibreHU-service/blob/main/docs/mcu-profiles.md) :
en-tête, position des octets de longueur et de commande, ce que compte la longueur, checksum d'un octet (somme, xor,
complément à deux, xor inversé) avec son octet de départ et son ajustement. Il reconnaît les protocoles déjà connus de
LibreHU (Jancar JAC_V1, Hiworld) et produit un **brouillon de profil** importable dans le service. Les boîtiers CAN
(Raise, Hiworld…) varient justement selon ces paramètres
([rétro-ingénierie CANBOX, XDA](https://xdaforums.com/t/canbox-can-decoder-reverse-engineering.4057759/page-5)).
Testé par `FrameGuesserTest` (JAC_V1, Hiworld, Hiworld dans les trames MCU 0x10, protocole xor inconnu avec du bruit,
octets aléatoires).

## En cours

Les onglets suivants arrivent : compatibilité de l'appareil et dépannage, tutos (désactiver les applis du fabricant,
avec ou sans root), journaux, matériel (ports série, I²C, USB, PCIe, puce MFi CarPlay), recherche de la MCU et du
protocole du boîtier CAN, sauvegarde des APK du fabricant, images des partitions et full dump (fichier scatter
MediaTek / Autochips).

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
