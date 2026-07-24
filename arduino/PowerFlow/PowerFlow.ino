/*
  PowerFlow - prototype de maison connectee
  ------------------------------------------
  Recoit des caracteres depuis l'application Android via un module HC-05
  et commute les sorties correspondantes.

  Protocole (identique a l'application) :
    A / a  ->  appareil 1  (Lampe)         broche 2
    B / b  ->  appareil 2  (Ventilateur)   broche 3
    C / c  ->  appareil 3  (Television)    broche 4
    D / d  ->  appareil 4                  broche 5
    E / e  ->  appareil 5                  broche 6
  Majuscule = allumer, minuscule = eteindre.

  Cablage du HC-05 :
    HC-05 TX  ->  Arduino broche 10 (RX)
    HC-05 RX  ->  Arduino broche 11 (TX)  *** via pont diviseur 1k / 2k ***
    HC-05 VCC ->  5 V        HC-05 GND -> GND

  La broche RX du HC-05 fonctionne en 3,3 V. Un pont diviseur de tension
  entre la broche 11 et le RX evite de l'endommager a la longue.
*/

#include <SoftwareSerial.h>

SoftwareSerial BT(10, 11);   // RX, TX

const int NOMBRE_APPAREILS = 5;
const int broches[NOMBRE_APPAREILS] = { 2, 3, 4, 5, 6 };

// Caracteres reconnus, dans le meme ordre que les broches.
const char CMD_ALLUMER[]  = "ABCDE";
const char CMD_ETEINDRE[] = "abcde";

/*
  La plupart des modules relais du commerce sont actifs a l'etat BAS :
  une broche a LOW ferme le relais. Mettre true dans ce cas.
  Pour des LED branchees directement, laisser false.
*/
const bool RELAIS_ACTIF_BAS = false;

void ecrireSortie(int index, bool actif) {
  bool niveau = RELAIS_ACTIF_BAS ? !actif : actif;
  digitalWrite(broches[index], niveau ? HIGH : LOW);
}

void setup() {
  for (int i = 0; i < NOMBRE_APPAREILS; i++) {
    pinMode(broches[i], OUTPUT);
    ecrireSortie(i, false);          // tout eteint au demarrage
  }

  BT.begin(9600);                    // vitesse par defaut du HC-05
  Serial.begin(9600);                // moniteur serie, pour le debogage
  Serial.println("PowerFlow pret");
}

void loop() {
  if (!BT.available()) return;

  char commande = BT.read();

  for (int i = 0; i < NOMBRE_APPAREILS; i++) {
    if (commande == CMD_ALLUMER[i]) {
      ecrireSortie(i, true);
      BT.print("ON:");  BT.println(i + 1);     // accuse de reception
      Serial.print("Appareil allume : "); Serial.println(i + 1);
      return;
    }
    if (commande == CMD_ETEINDRE[i]) {
      ecrireSortie(i, false);
      BT.print("OFF:"); BT.println(i + 1);
      Serial.print("Appareil eteint : "); Serial.println(i + 1);
      return;
    }
  }
}
