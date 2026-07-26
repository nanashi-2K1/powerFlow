package com.powerflow.app

import android.content.Context
import org.json.JSONObject

/**
 * Statistiques d'usage cumulées pour un appareil (indexées par broche).
 *
 * @param dureeTotaleMillis    somme du temps passé allumé, toutes sessions confondues
 * @param derniereMiseEnMarche horodatage (epoch millis) du dernier allumage
 * @param derniereExtinction   horodatage (epoch millis) de la dernière extinction
 */
data class UsageAppareil(
    val dureeTotaleMillis: Long = 0L,
    val derniereMiseEnMarche: Long? = null,
    val derniereExtinction: Long? = null
)

/**
 * Persiste les statistiques d'usage et le seuil d'alerte sur le téléphone
 * (SharedPreferences), séparément de la liste des appareils.
 *
 * Ce suivi ne fonctionne que pendant que l'application est ouverte et
 * connectée : il n'y a pas de service en arrière-plan. Si l'application est
 * fermée pendant qu'un appareil est allumé, la session en cours n'est pas
 * comptabilisée (mais les sessions déjà terminées restent enregistrées).
 */
object UsageStore {

    const val SEUIL_ALERTE_DEFAUT_MINUTES = 60

    private const val PREFS = "powerflow_usage"
    private const val CLE_DONNEES = "donnees"
    private const val CLE_SEUIL_ALERTE = "seuil_alerte_minutes"

    fun charger(context: Context): MutableMap<Int, UsageAppareil> {
        val brut = prefs(context).getString(CLE_DONNEES, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(brut)
            val resultat = mutableMapOf<Int, UsageAppareil>()
            obj.keys().forEach { cle ->
                val o = obj.getJSONObject(cle)
                resultat[cle.toInt()] = UsageAppareil(
                    dureeTotaleMillis = o.optLong("duree", 0L),
                    derniereMiseEnMarche = o.optLong("debut", -1L).takeIf { it >= 0 },
                    derniereExtinction = o.optLong("fin", -1L).takeIf { it >= 0 }
                )
            }
            resultat
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun sauvegarder(context: Context, donnees: Map<Int, UsageAppareil>) {
        val obj = JSONObject()
        donnees.forEach { (broche, usage) ->
            val entree = JSONObject().put("duree", usage.dureeTotaleMillis)
            usage.derniereMiseEnMarche?.let { entree.put("debut", it) }
            usage.derniereExtinction?.let { entree.put("fin", it) }
            obj.put(broche.toString(), entree)
        }
        prefs(context).edit().putString(CLE_DONNEES, obj.toString()).apply()
    }

    fun seuilAlerteMinutes(context: Context): Int =
        prefs(context).getInt(CLE_SEUIL_ALERTE, SEUIL_ALERTE_DEFAUT_MINUTES)

    fun definirSeuilAlerte(context: Context, minutes: Int) {
        prefs(context).edit().putInt(CLE_SEUIL_ALERTE, minutes).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
