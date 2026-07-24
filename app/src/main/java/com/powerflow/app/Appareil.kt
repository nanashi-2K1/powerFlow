package com.powerflow.app

/**
 * Un appareil pilotable du prototype.
 *
 * @param nom       libelle affiche
 * @param icone     emoji affiche a gauche
 * @param broche    broche Arduino correspondante (information seulement)
 * @param allumer   caractere envoye pour allumer
 * @param eteindre  caractere envoye pour eteindre
 */
data class Appareil(
    val nom: String,
    val icone: String,
    val broche: Int,
    val allumer: Char,
    val eteindre: Char
)

/**
 * Liste des appareils du prototype.
 * Elle correspond exactement au croquis Arduino : pour en ajouter un,
 * il suffit d'ajouter une ligne ici et un case dans le croquis.
 */
object Appareils {
    val liste = listOf(
        Appareil("Lampe",        "\uD83D\uDCA1", 2, 'A', 'a'),
        Appareil("Ventilateur",  "\uD83C\uDF00", 3, 'B', 'b'),
        Appareil("Television",   "\uD83D\uDCFA", 4, 'C', 'c'),
        Appareil("Prise 4",      "\uD83D\uDD0C", 5, 'D', 'd'),
        Appareil("Prise 5",      "\uD83D\uDD0C", 6, 'E', 'e')
    )
}
