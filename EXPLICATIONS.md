# Comment fonctionne PowerFlow — explications du code

Ce document explique le fonctionnement du projet sans supposer de
connaissances en Kotlin, en C++ Arduino ou en développement Android. Il
complète le [README](README.md), qui explique plutôt *comment l'utiliser*.

## 1. La vue d'ensemble

Trois éléments discutent entre eux :

```
[ Téléphone ]  <--- Bluetooth --->  [ HC-05 ]  <--- fils --->  [ Arduino ]  <--- fils --->  [ Relais / appareils ]
   (l'appli)                      (module radio)                (le cerveau)                (lampe, ventilateur...)
```

- **Le téléphone** affiche des boutons (tuiles) et envoie **une seule
  lettre** à chaque fois qu'on en touche un, par Bluetooth.
- **Le module HC-05** ne fait que transformer le Bluetooth en une liaison
  série classique (comme un câble), il ne "comprend" rien lui-même.
- **L'Arduino** lit les lettres une par une et allume ou éteint la broche
  électrique qui correspond.
- **Les relais** sont des interrupteurs commandés électriquement : quand
  l'Arduino met une broche à l'état HIGH (ou LOW selon le câblage), le
  relais bascule et coupe/laisse passer le courant 230 V vers l'appareil.

Retenir une seule idée : **tout le "langage" échangé se résume à des
lettres**. `A` veut dire "allume l'appareil 1", `a` veut dire "éteins
l'appareil 1", etc. C'est volontairement le protocole le plus simple
possible.

## 2. Le protocole : la langue commune

| Ce qu'on envoie | Ce qui se passe |
|---|---|
| Une majuscule (`A`, `B`, `C`...) | Allume l'appareil correspondant |
| La même lettre en minuscule (`a`, `b`, `c`...) | L'éteint |

L'Arduino répond toujours par un petit message texte, par exemple `ON:1` ou
`OFF:3`, pour confirmer ce qu'il vient de faire. L'application l'affiche
dans le journal en bas de l'écran — un peu comme un accusé de réception.

Voir le tableau complet des lettres/broches dans le [README](README.md#protocole).

## 3. Le croquis Arduino (`arduino/PowerFlow/PowerFlow.ino`)

Arduino exécute du code écrit dans un langage proche du C++, mais très
simplifié. Un croquis (*sketch*) contient toujours deux fonctions
obligatoires :

- **`setup()`** : exécutée **une seule fois** au démarrage. Ici, elle
  déclare chaque broche comme une sortie électrique (`OUTPUT`) et éteint
  tout par précaution.
- **`loop()`** : exécutée **en boucle, indéfiniment**. C'est le cœur du
  programme.

```cpp
const int broches[NOMBRE_APPAREILS] = { 2, 3, 4, 5, 6, 7, 8, 9 };
const char CMD_ALLUMER[]  = "ABCDEFGH";
const char CMD_ETEINDRE[] = "abcdefgh";
```

Ces trois lignes forment une sorte de tableau à trois colonnes : la broche
`2` correspond à la lettre `A` (allumer) et `a` (éteindre), la broche `3` à
`B`/`b`, etc. C'est la même position dans les trois tableaux qui relie une
broche à ses deux lettres — c'est pour ça qu'il ne faut jamais changer
l'ordre d'un tableau sans changer les autres en même temps.

Dans `loop()` :

```cpp
if (!BT.available()) return;   // rien reçu ? on ne fait rien, on reboucle
char commande = BT.read();     // sinon on lit UN caractère

for (int i = 0; i < NOMBRE_APPAREILS; i++) {
  if (commande == CMD_ALLUMER[i]) { ... allume la broche i ... }
  if (commande == CMD_ETEINDRE[i]) { ... éteint la broche i ... }
}
```

En français : *"Tant qu'il n'y a rien à lire, ne rien faire. Dès qu'une
lettre arrive, regarder dans le tableau si elle correspond à un appareil
connu, et si oui, agir."* Cette boucle tourne des milliers de fois par
seconde ; ce n'est pas grave qu'elle ne fasse "rien" la plupart du temps.

Un dernier détail électronique important : `RELAIS_ACTIF_BAS`. Certaines
cartes relais s'activent quand la broche est à `LOW` (0 V) plutôt qu'à
`HIGH` (5 V) — c'est écrit sur la carte ou sa notice. Cette variable
inverse la logique sans toucher au reste du code.

## 4. L'application Android

L'application est écrite en **Kotlin**, le langage utilisé aujourd'hui pour
développer sur Android. Un fichier `.kt` peut définir des **classes**
(un peu comme des "moules" décrivant un objet et ce qu'il sait faire) et des
**fonctions** (des blocs de code réutilisables qu'on peut appeler par leur
nom).

### `Appareil.kt` — la fiche d'identité de chaque appareil

```kotlin
data class Appareil(
    val nom: String,
    @DrawableRes val icone: Int,
    val broche: Int,
    val allumer: Char,
    val eteindre: Char
)
```

Une `data class` est une classe qui ne sert qu'à regrouper des informations
liées — ici, tout ce qu'il faut savoir sur un appareil : son nom affiché,
son icône (un pictogramme vectoriel, comme `ic_lampe.xml`), sa broche
Arduino et les deux caractères à envoyer. C'est l'équivalent, côté
téléphone, du tableau `broches`/`CMD_ALLUMER` vu côté Arduino : les deux
doivent rester cohérents.

`icone` est un simple numéro identifiant un dessin vectoriel compilé dans
l'application (`R.drawable.ic_lampe`, `R.drawable.ic_prise`...) plutôt
qu'un caractère emoji : ça permet au code de le teinter dynamiquement
(blanc quand la tuile est active, gris sinon), ce qu'on ne peut pas faire
avec du texte.

`AppareilsStore`, dans le même fichier, se charge de **sauvegarder** cette
liste sur le téléphone (via les `SharedPreferences`, un petit espace de
stockage propre à l'application) pour qu'elle soit toujours là au
prochain lancement, même après avoir ajouté ou modifié un appareil. Comme
ce numéro d'icône peut changer d'une compilation à l'autre, on sauvegarde
plutôt son **nom** (`"ic_lampe"`) et on le retrouve au chargement grâce au
petit registre `Pictos`. Les données sont converties en texte au format
**JSON** — un format universel pour écrire des informations structurées,
ex. :

```json
{"nom": "Lampe", "icone": "ic_lampe", "broche": 2, "allumer": "A", "eteindre": "a"}
```

### `BluetoothSerial.kt` — la liaison radio

Ce fichier gère uniquement la connexion Bluetooth, indépendamment du reste.
Deux points à comprendre :

- Se connecter en Bluetooth peut prendre une ou deux secondes et **bloque**
  le programme pendant ce temps. Si on le faisait directement, l'écran de
  l'application se figerait. La solution : lancer cette attente dans un
  **thread** séparé (`thread(name = "powerflow-bt") { ... }`), c'est-à-dire
  une tâche qui s'exécute "en parallèle" pendant que l'écran reste réactif.
- La fonction `envoyer(caractere: Char)` fait exactement ce que l'Arduino
  attend : elle écrit un seul caractère sur la liaison, sans rien ajouter
  derrière.

### `AccueilActivity.kt` — l'écran d'accueil

Le plus simple des deux écrans : il affiche le logo et un bouton, avec une
petite animation d'apparition, puis ouvre le tableau de bord au clic sur
*Commencer*. Une "Activity", en Android, correspond à un écran complet de
l'application.

### `TableauBordActivity.kt` — l'écran principal

C'est le fichier le plus gros, parce qu'il fait le lien entre tout le
reste : la liste des appareils (`Appareil.kt`), la liaison Bluetooth
(`BluetoothSerial.kt`) et l'affichage (les tuiles à l'écran).

Idée générale de ce qui se passe quand on touche une tuile :

```kotlin
vue.setOnClickListener {
    if (!liaison.estConnecte) return@setOnClickListener   // pas connecté : on ignore le clic

    val nouvelEtat = !tuile.actif                          // on inverse l'état actuel
    val caractere = if (nouvelEtat) appareil.allumer else appareil.eteindre
    if (liaison.envoyer(caractere)) {                       // on envoie la lettre par Bluetooth
        tuile.actif = nouvelEtat                            // et on met la tuile à jour visuellement
        ...
    }
}
```

Un `setOnClickListener` est une fonction qu'on "attache" à un bouton et qui
s'exécute automatiquement à chaque appui — on ne l'appelle jamais
soi-même, le système Android le fait pour nous au bon moment. C'est ce
qu'on appelle un **callback** : "rappelle-moi quand cet évènement arrive".

Le reste du fichier gère surtout des cas pratiques :

- demander l'activation du Bluetooth et les autorisations nécessaires
  (obligatoires depuis Android 12) ;
- proposer la liste des appareils Bluetooth déjà appairés au moment de se
  connecter ;
- ouvrir un formulaire (`ouvrirDialogueAppareil`) pour ajouter, modifier ou
  supprimer un appareil, avec des vérifications (`validerAppareil`) pour
  éviter par exemple deux appareils sur la même broche ;
- basculer entre les trois onglets de l'écran (`pageAppareils` /
  `pageConnexion` / `pageHistorique`) en changeant simplement leur
  `visibility` (`VISIBLE` ou `GONE`) au clic sur la barre de navigation du
  bas — les trois pages existent en permanence dans la mémoire de l'écran,
  seule leur visibilité change, ce qui évite d'avoir à gérer plusieurs
  écrans (`Activity`) séparés pour un simple changement d'onglet.

### Le minuteur : exécuter du code "plus tard"

Le bouton ⏱ sur chaque tuile programme une action différée (allumer,
éteindre, ou éteindre automatiquement après une durée). Il n'y a rien de
magique côté Arduino : au moment voulu, l'application envoie exactement le
même caractère que si l'utilisateur avait appuyé sur la tuile.

Le mécanisme Android qui permet ça s'appelle un `Handler` :

```kotlin
private val handlerMinuteur = Handler(Looper.getMainLooper())

handlerMinuteur.postDelayed(runnablePrincipal, delaiMillis)
```

`postDelayed` veut dire : "exécute ce bloc de code (`runnablePrincipal`)
dans `delaiMillis` millisecondes, mais uniquement si l'application est
toujours ouverte". C'est une minuterie logicielle, pas un vrai minuteur
matériel : si l'utilisateur ferme l'application ou que le téléphone la met
en veille profonde, le décompte s'arrête. Pour annuler un minuteur avant
qu'il ne se déclenche, on utilise `handlerMinuteur.removeCallbacks(...)`
(fait par la fonction `annulerMinuteur`).

Le même principe (`handlerTick`, dans la propriété `tick`) fait "battre"
l'écran toutes les secondes pour rafraîchir les décomptes affichés et
vérifier si un appareil est allumé depuis trop longtemps
(`rafraichirCompteurs`) — c'est ce qui alimente l'onglet **Historique** et
l'alerte de fonctionnement prolongé, stockés par `UsageStore.kt` (même
principe de sauvegarde en JSON que `AppareilsStore` dans `Appareil.kt`).

### Les fichiers `.xml` du dossier `res/`

Ils ne contiennent aucune logique : ce sont des fichiers de **description
visuelle** (quel texte, quelle couleur, quelle taille, quelle position)
que le code Kotlin vient ensuite remplir et rendre interactif via des
identifiants (`R.id.xxx`). C'est la séparation classique en développement
d'interface : le "quoi afficher" d'un côté, le "que faire quand on clique"
de l'autre.

Un dossier mérite une explication : `res/values-night/`. Android permet de
déclarer plusieurs versions d'un même fichier de ressources, chacune dans un
dossier au nom qualifié (`-night`, mais aussi `-fr`, `-large`...) ; le
système choisit automatiquement la bonne version selon le contexte — ici,
selon que le mode sombre du téléphone est activé ou non. `values-night/colors.xml`
redéfinit exactement les mêmes noms de couleur (`pf_fond`, `pf_texte`...)
avec des valeurs adaptées à un fond sombre ; comme tout le reste de
l'application référence ces couleurs par leur nom (`@color/pf_texte`) et
jamais par leur valeur exacte, le changement de thème est automatique et
ne demande aucune modification du code Kotlin.

## 5. Petit lexique

| Terme | Explication simple |
|---|---|
| Broche (*pin*) | Une des connexions électriques de l'Arduino, utilisable en entrée ou en sortie |
| Relais | Interrupteur commandé électriquement, qui permet à un petit signal (5 V) de couper un circuit plus puissant (230 V) sans contact direct |
| Bluetooth SPP | Le mode Bluetooth qui simule un simple câble série — pas de son, pas de fichiers, juste des caractères qui circulent dans les deux sens |
| Thread | Une tâche qui s'exécute "en parallèle" du reste du programme, pour ne pas bloquer l'écran pendant une opération longue |
| Callback / listener | Une fonction qu'on ne lance pas nous-même : le système l'appelle automatiquement quand un évènement précis se produit (un clic, une donnée reçue...) |
| JSON | Un format de texte standard pour écrire des informations structurées, lisible à la fois par un humain et par un programme |
| SharedPreferences | Le "carnet" où une application Android range de petites données entre deux lancements |
| data class (Kotlin) | Une classe qui sert uniquement à regrouper des informations liées, sans comportement complexe |
| Handler / postDelayed | Un mécanisme Android pour exécuter du code après un délai, tant que l'application reste ouverte (pas un vrai minuteur matériel) |
