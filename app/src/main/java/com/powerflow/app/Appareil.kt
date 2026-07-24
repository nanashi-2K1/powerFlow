package com.powerflow.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Un appareil pilotable du prototype.
 *
 * @param nom       libelle affiche
 * @param icone     emoji affiche a gauche
 * @param broche    broche Arduino correspondante (2 a 9)
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
 * Liste des appareils, personnalisable depuis l'application (bouton "+" et
 * appui long sur une tuile) et conservee sur le telephone entre deux
 * lancements (SharedPreferences).
 *
 * Le croquis Arduino fourni reconnait les broches 2 a 9 (10 et 11 sont
 * prises par le HC-05, 0 et 1 par le port USB), avec les caracteres A-H /
 * a-h dans l'ordre des broches. Ajouter un appareil ici ne le rend
 * pilotable que si un relais est reellement cable sur la broche choisie ;
 * changer un caractere ne prend effet que s'il correspond a celui attendu
 * par le croquis televerse sur la carte.
 */
object AppareilsStore {

    const val BROCHE_MIN = 2
    const val BROCHE_MAX = 9

    val defaut = listOf(
        Appareil("Lampe",        "💡", 2, 'A', 'a'),
        Appareil("Ventilateur",  "🌀", 3, 'B', 'b'),
        Appareil("Television",   "📺", 4, 'C', 'c'),
        Appareil("Prise 4",      "🔌", 5, 'D', 'd'),
        Appareil("Prise 5",      "🔌", 6, 'E', 'e')
    )

    private const val PREFS = "powerflow_appareils"
    private const val CLE_LISTE = "liste"

    fun charger(context: Context): MutableList<Appareil> {
        val brut = prefs(context).getString(CLE_LISTE, null) ?: return defaut.toMutableList()
        return try {
            val tableau = JSONArray(brut)
            (0 until tableau.length()).map { i ->
                val o = tableau.getJSONObject(i)
                Appareil(
                    nom = o.getString("nom"),
                    icone = o.getString("icone"),
                    broche = o.getInt("broche"),
                    allumer = o.getString("allumer")[0],
                    eteindre = o.getString("eteindre")[0]
                )
            }.toMutableList()
        } catch (e: Exception) {
            defaut.toMutableList()
        }
    }

    fun sauvegarder(context: Context, appareils: List<Appareil>) {
        val tableau = JSONArray()
        appareils.forEach { a ->
            tableau.put(
                JSONObject()
                    .put("nom", a.nom)
                    .put("icone", a.icone)
                    .put("broche", a.broche)
                    .put("allumer", a.allumer.toString())
                    .put("eteindre", a.eteindre.toString())
            )
        }
        prefs(context).edit().putString(CLE_LISTE, tableau.toString()).apply()
    }

    fun reinitialiser(context: Context): MutableList<Appareil> {
        prefs(context).edit().remove(CLE_LISTE).apply()
        return defaut.toMutableList()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
