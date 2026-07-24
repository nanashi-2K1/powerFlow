# PowerFlow

Prototype de maison connectée : une application Android pilote des appareils
branchés sur un Arduino, via un module Bluetooth HC-05.

## Contenu

| Dossier | Rôle |
|---|---|
| `app/` | Application Android (Kotlin) |
| `arduino/PowerFlow/` | Croquis Arduino |
| `.github/workflows/` | Compilation automatique de l'APK |

## Obtenir l'APK sans rien installer

1. Créer un dépôt sur github.com (public ou privé).
2. Y déposer **tout le contenu de ce dossier**, en gardant l'arborescence.
3. La compilation démarre seule. Suivre l'onglet **Actions** (3 à 5 minutes).
4. Récupérer `PowerFlow.apk` :
   - dans l'onglet **Releases** — lien direct, ouvrable depuis le téléphone ;
   - ou en bas de la page du workflow, section *Artifacts*.
5. Sur le téléphone, ouvrir le fichier et autoriser l'installation depuis
   une source inconnue.

Pour relancer une compilation sans modifier le code :
onglet **Actions** → *Construire l'APK* → **Run workflow**.

## Compiler en local

Il faut le JDK 17 et le SDK Android. Depuis VS Code ou Android Studio :

```bash
gradle assembleDebug
# APK dans app/build/outputs/apk/debug/
```

## Premier essai

1. Téléverser `arduino/PowerFlow/PowerFlow.ino` sur la carte.
   **Débrancher le HC-05 pendant le téléversement** : il occupe les broches série.
2. Alimenter le montage, puis appairer le HC-05 dans les réglages Bluetooth
   du téléphone (code `1234` ou `0000`).
3. Ouvrir PowerFlow → *Commencer* → *Se connecter* → choisir le HC-05.
4. Les interrupteurs deviennent actifs une fois la liaison établie.

## Protocole

| Appareil | Broche | Allumer | Éteindre |
|---|---|---|---|
| Lampe | 2 | `A` | `a` |
| Ventilateur | 3 | `B` | `b` |
| Télévision | 4 | `C` | `c` |
| Prise 4 | 5 | `D` | `d` |
| Prise 5 | 6 | `E` | `e` |

L'Arduino renvoie `ON:1` ou `OFF:1` en accusé de réception ; l'application
l'affiche dans le journal, en bas de l'écran.

## Modifier la liste des appareils

Deux fichiers à garder synchronisés :

- `app/src/main/java/com/powerflow/app/Appareil.kt` — objet `Appareils.liste`
- `arduino/PowerFlow/PowerFlow.ino` — tableaux `broches`, `CMD_ALLUMER`, `CMD_ETEINDRE`

## Notes de câblage

- La broche RX du HC-05 est en 3,3 V : intercaler un pont diviseur
  (1 kΩ / 2 kΩ) entre la broche 11 de l'Arduino et le RX du module.
- Si les relais s'inversent (allumés au repos), passer `RELAIS_ACTIF_BAS`
  à `true` dans le croquis : la plupart des cartes relais du commerce
  sont actives à l'état bas.
