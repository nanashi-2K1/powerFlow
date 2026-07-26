package com.powerflow.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Une tuile affichée + l'appareil associé + son état courant. */
private class Tuile(val vue: View, val appareil: Appareil, var actif: Boolean = false)

/** Ce qu'un minuteur en attente doit faire une fois son délai écoulé. */
private enum class TypeMinuteur { ALLUMAGE, EXTINCTION, EXTINCTION_AUTO }

/** Un minuteur programmé pour un appareil (identifié par sa broche). */
private class EtatMinuteur(val type: TypeMinuteur, val finMillis: Long, val runnable: Runnable)

/**
 * Écran principal : connexion au HC-05 puis commande des appareils.
 * Chaque appareil est une tuile tactile — on tape la tuile entière ;
 * elle se remplit d'accent quand l'appareil est actif. Les tuiles sont
 * inactives et grisées tant que la liaison n'est pas établie.
 *
 * Les minuteurs et le suivi d'usage (onglet Historique) ne fonctionnent que
 * pendant que cet écran reste ouvert et connecté : il n'y a pas de service
 * en arrière-plan, uniquement des `Handler.postDelayed` liés au cycle de
 * vie de cette Activity (annulés dans `onDestroy`).
 */
@SuppressLint("MissingPermission")
class TableauBordActivity : AppCompatActivity() {

    private lateinit var pastille: View
    private lateinit var etatTexte: TextView
    private lateinit var boutonConnexion: MaterialButton
    private lateinit var boutonAjouter: MaterialButton
    private lateinit var conteneur: LinearLayout
    private lateinit var journal: TextView
    private lateinit var journalScroll: ScrollView
    private lateinit var pageAppareils: View
    private lateinit var pageConnexion: View
    private lateinit var pageHistorique: View
    private lateinit var navigation: BottomNavigationView
    private lateinit var conteneurHistorique: LinearLayout
    private lateinit var champSeuilAlerte: TextInputEditText
    private lateinit var boutonReinitialiserHistorique: MaterialButton

    private lateinit var appareils: MutableList<Appareil>
    private lateinit var usage: MutableMap<Int, UsageAppareil>
    private val tuiles = mutableListOf<Tuile>()
    private val heure = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    private val heureCourte = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)

    /** Broche -> minuteur en attente (au plus un par appareil). */
    private val minuteurs = mutableMapOf<Int, EtatMinuteur>()
    /** Broche -> instant (epoch millis) où l'appareil a été allumé, s'il l'est. */
    private val sessionsEnCours = mutableMapOf<Int, Long>()
    /** Broches déjà signalées pour la session d'allumage en cours (évite de répéter l'alerte). */
    private val alerteDeclenchee = mutableSetOf<Int>()

    private val handlerMinuteur = Handler(Looper.getMainLooper())
    private val handlerTick = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            rafraichirCompteurs()
            rafraichirHistoriqueSiVisible()
            handlerTick.postDelayed(this, 1000)
        }
    }

    private lateinit var liaison: BluetoothSerial

    /** Demande de permission Bluetooth (Android 12+). */
    private val demandePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultats ->
        if (resultats.values.all { it }) {
            choisirAppareil()
        } else {
            noter("Permission Bluetooth refusée. Autorisez-la dans les réglages.")
        }
    }

    /** Demande d'activation du Bluetooth. */
    private val demandeActivation = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (adaptateur()?.isEnabled == true) verifierPermissionPuisChoisir()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tableau_bord)

        pastille = findViewById(R.id.pastille)
        etatTexte = findViewById(R.id.etatTexte)
        boutonConnexion = findViewById(R.id.boutonConnexion)
        boutonAjouter = findViewById(R.id.boutonAjouter)
        conteneur = findViewById(R.id.conteneurAppareils)
        journal = findViewById(R.id.journal)
        journalScroll = findViewById(R.id.journalScroll)
        pageAppareils = findViewById(R.id.pageAppareils)
        pageConnexion = findViewById(R.id.pageConnexion)
        pageHistorique = findViewById(R.id.pageHistorique)
        navigation = findViewById(R.id.navigation)
        conteneurHistorique = findViewById(R.id.conteneurHistorique)
        champSeuilAlerte = findViewById(R.id.champSeuilAlerte)
        boutonReinitialiserHistorique = findViewById(R.id.boutonReinitialiserHistorique)

        navigation.setOnItemSelectedListener { item ->
            pageAppareils.visibility = if (item.itemId == R.id.ongletAppareils) View.VISIBLE else View.GONE
            pageConnexion.visibility = if (item.itemId == R.id.ongletConnexion) View.VISIBLE else View.GONE
            pageHistorique.visibility = if (item.itemId == R.id.ongletHistorique) View.VISIBLE else View.GONE
            if (item.itemId == R.id.ongletHistorique) construireHistorique()
            true
        }

        appareils = AppareilsStore.charger(this)
        usage = UsageStore.charger(this)

        liaison = BluetoothSerial(
            onEtat = { connecte, message -> majEtat(connecte, message) },
            onReception = { texte -> noter("Arduino : $texte") }
        )

        construireAppareils()
        majEtat(false, "Non connecté")

        boutonConnexion.setOnClickListener {
            if (liaison.estConnecte) {
                liaison.deconnecter()
            } else {
                lancerConnexion()
            }
        }

        boutonAjouter.setOnClickListener { ouvrirDialogueAppareil(null) }
        boutonAjouter.setOnLongClickListener { confirmerReinitialisation(); true }

        champSeuilAlerte.setText(UsageStore.seuilAlerteMinutes(this).toString())
        champSeuilAlerte.setOnFocusChangeListener { _, aLeFocus ->
            if (!aLeFocus) {
                val minutes = champSeuilAlerte.text?.toString()?.trim()?.toIntOrNull()
                if (minutes != null && minutes > 0) {
                    UsageStore.definirSeuilAlerte(this, minutes)
                    alerteDeclenchee.clear()
                } else {
                    champSeuilAlerte.setText(UsageStore.seuilAlerteMinutes(this).toString())
                }
            }
        }

        boutonReinitialiserHistorique.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.historique_reinitialiser_titre)
                .setMessage(R.string.historique_reinitialiser_detail)
                .setPositiveButton(R.string.historique_reinitialiser) { _, _ ->
                    usage.clear()
                    UsageStore.sauvegarder(this, usage)
                    construireHistorique()
                }
                .setNegativeButton(R.string.annuler, null)
                .show()
        }

        handlerTick.post(tick)

        noter("Appairez d'abord le HC-05 dans les réglages Bluetooth du téléphone (code 1234 ou 0000).")
    }

    override fun onDestroy() {
        handlerTick.removeCallbacksAndMessages(null)
        handlerMinuteur.removeCallbacksAndMessages(null)
        liaison.deconnecter()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- interface

    /** (Re)crée une grille de 2 tuiles par ligne, une par appareil de la liste courante. */
    private fun construireAppareils() {
        conteneur.removeAllViews()
        tuiles.clear()
        val inflater = LayoutInflater.from(this)

        appareils.chunked(2).forEach { paire ->
            val ligne = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }

            paire.forEachIndexed { index, appareil ->
                val vue = inflater.inflate(R.layout.ligne_appareil, ligne, false)

                vue.findViewById<ImageView>(R.id.icone).setImageResource(appareil.icone)
                vue.findViewById<TextView>(R.id.nom).text = appareil.nom
                vue.findViewById<TextView>(R.id.detail).text =
                    getString(R.string.detail_appareil, appareil.broche, appareil.allumer, appareil.eteindre)

                val tuile = Tuile(vue, appareil)

                vue.setOnClickListener {
                    if (!liaison.estConnecte) return@setOnClickListener
                    basculerAppareil(tuile, !tuile.actif)
                }

                vue.setOnLongClickListener {
                    ouvrirDialogueAppareil(appareil)
                    true
                }

                vue.findViewById<ImageButton>(R.id.boutonMinuteur).setOnClickListener {
                    if (!liaison.estConnecte) {
                        Toast.makeText(this, R.string.minuteur_non_connecte, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    ouvrirDialogueMinuteur(appareil)
                }

                tuiles += tuile

                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (index == 0) params.marginEnd = dp(12)
                ligne.addView(vue, params)
                styleTuile(tuile, connecte = liaison.estConnecte)
            }

            if (paire.size == 1) {
                // Nombre impair d'appareils : un espace invisible occupe la
                // deuxième colonne pour garder la même largeur de carte.
                ligne.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            }

            conteneur.addView(ligne)
        }

        rafraichirCompteurs()
    }

    /** Ouvre le dialogue d'ajout (existant == null) ou de modification/suppression. */
    private fun ouvrirDialogueAppareil(existant: Appareil?) {
        val vue = LayoutInflater.from(this).inflate(R.layout.dialogue_appareil, null)
        val champNom = vue.findViewById<TextInputEditText>(R.id.champNom)
        val champIcone = vue.findViewById<AutoCompleteTextView>(R.id.champIcone)
        val champBroche = vue.findViewById<TextInputEditText>(R.id.champBroche)
        val champAllumer = vue.findViewById<TextInputEditText>(R.id.champAllumer)
        val champEteindre = vue.findViewById<TextInputEditText>(R.id.champEteindre)

        champIcone.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, Pictos.liste.map { it.libelle })
        )

        if (existant != null) {
            champNom.setText(existant.nom)
            champIcone.setText(Pictos.libelleDe(existant.icone), false)
            champBroche.setText(existant.broche.toString())
            champAllumer.setText(existant.allumer.toString())
            champEteindre.setText(existant.eteindre.toString())
        } else {
            champIcone.setText(Pictos.liste.first().libelle, false)
            val brocheLibre = (AppareilsStore.BROCHE_MIN..AppareilsStore.BROCHE_MAX)
                .firstOrNull { pin -> appareils.none { it.broche == pin } }
            if (brocheLibre != null) champBroche.setText(brocheLibre.toString())
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existant == null) R.string.ajouter_appareil else R.string.modifier_appareil)
            .setView(vue)
            .setNegativeButton(R.string.annuler, null)
            .setPositiveButton(if (existant == null) R.string.ajouter else R.string.enregistrer, null)

        if (existant != null) {
            builder.setNeutralButton(R.string.supprimer) { _, _ ->
                annulerMinuteur(existant.broche, notifier = false)
                sessionsEnCours.remove(existant.broche)
                appareils.removeAll { it.broche == existant.broche }
                AppareilsStore.sauvegarder(this, appareils)
                construireAppareils()
                construireHistorique()
            }
        }

        val dialogue = builder.show()

        // Bouton positif intercepté à la main : en cas d'erreur de saisie, le
        // dialogue doit rester ouvert au lieu de se fermer.
        dialogue.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nom = champNom.text?.toString()?.trim().orEmpty()
            val icone = Pictos.parLibelle(champIcone.text?.toString()?.trim().orEmpty())
            val broche = champBroche.text?.toString()?.trim()?.toIntOrNull()
            val allumer = champAllumer.text?.toString()?.trim()?.singleOrNull()
            val eteindre = champEteindre.text?.toString()?.trim()?.singleOrNull()

            val erreur = validerAppareil(nom, icone, broche, allumer, eteindre, existant)
            if (erreur != null) {
                Toast.makeText(this, erreur, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val nouveau = Appareil(nom, icone!!, broche!!, allumer!!, eteindre!!)
            if (existant != null) {
                val index = appareils.indexOfFirst { it.broche == existant.broche }
                appareils[index] = nouveau
            } else {
                appareils += nouveau
            }
            AppareilsStore.sauvegarder(this, appareils)
            construireAppareils()
            construireHistorique()
            dialogue.dismiss()
        }
    }

    /** Renvoie un message d'erreur si la saisie est invalide, sinon null. */
    private fun validerAppareil(
        nom: String,
        icone: Int?,
        broche: Int?,
        allumer: Char?,
        eteindre: Char?,
        existant: Appareil?
    ): String? {
        if (nom.isEmpty()) return getString(R.string.erreur_nom)
        if (icone == null) return getString(R.string.erreur_icone)
        if (broche == null || broche !in AppareilsStore.BROCHE_MIN..AppareilsStore.BROCHE_MAX) {
            return getString(R.string.erreur_broche, AppareilsStore.BROCHE_MIN, AppareilsStore.BROCHE_MAX)
        }
        if (allumer == null || eteindre == null || !allumer.isLetter() || !eteindre.isLetter()) {
            return getString(R.string.erreur_caractere)
        }
        if (allumer == eteindre) return getString(R.string.erreur_caractere_identique)

        val autres = appareils.filter { it.broche != existant?.broche }
        if (autres.any { it.broche == broche }) return getString(R.string.erreur_broche_utilisee)

        val caracteresPris = autres.flatMap { listOf(it.allumer, it.eteindre) }.toSet()
        if (allumer in caracteresPris || eteindre in caracteresPris) {
            return getString(R.string.erreur_caractere_utilise)
        }
        return null
    }

    private fun confirmerReinitialisation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reinitialiser_titre)
            .setMessage(R.string.reinitialiser_detail)
            .setPositiveButton(R.string.reinitialiser_titre) { _, _ ->
                minuteurs.keys.toList().forEach { annulerMinuteur(it, notifier = false) }
                sessionsEnCours.clear()
                appareils = AppareilsStore.reinitialiser(this)
                construireAppareils()
                construireHistorique()
            }
            .setNegativeButton(R.string.annuler, null)
            .show()
    }

    /** Applique l'apparence d'une tuile selon son état et la liaison. */
    private fun styleTuile(tuile: Tuile, connecte: Boolean) {
        val allume = connecte && tuile.actif
        val vue = tuile.vue

        vue.isClickable = connecte
        vue.alpha = if (connecte) 1f else 0.55f
        vue.setBackgroundResource(
            if (allume) R.drawable.fond_appareil_on else R.drawable.fond_appareil_off
        )
        // setBackgroundResource réinitialise le padding : on le remet.
        val h = dp(16); val v = dp(14)
        vue.setPadding(h, v, h, v)

        val blanc = 0xFFFFFFFF.toInt()
        val encre = ContextCompat.getColor(this, R.color.pf_texte)
        val faible = ContextCompat.getColor(this, R.color.pf_texte_faible)
        val inactif = ContextCompat.getColor(this, R.color.pf_inactif)

        vue.findViewById<TextView>(R.id.nom).setTextColor(if (allume) blanc else encre)
        vue.findViewById<TextView>(R.id.detail).setTextColor(if (allume) 0xB3FFFFFF.toInt() else faible)

        val etat = vue.findViewById<TextView>(R.id.etat)
        etat.setText(if (tuile.actif) R.string.etat_allume else R.string.etat_eteint)
        etat.setTextColor(if (allume) 0xB3FFFFFF.toInt() else faible)

        vue.findViewById<View>(R.id.point).backgroundTintList =
            android.content.res.ColorStateList.valueOf(if (allume) blanc else inactif)

        val teintePicto = when {
            allume -> blanc
            connecte -> ContextCompat.getColor(this, R.color.pf_icone)
            else -> inactif
        }
        vue.findViewById<ImageView>(R.id.icone).imageTintList =
            android.content.res.ColorStateList.valueOf(teintePicto)

        vue.findViewById<ImageButton>(R.id.boutonMinuteur).apply {
            isEnabled = connecte
            imageTintList = android.content.res.ColorStateList.valueOf(teintePicto)
        }
    }

    private fun majEtat(connecte: Boolean, message: String) {
        etatTexte.text = message

        val couleur = if (connecte) R.color.pf_courant else R.color.pf_inactif
        pastille.backgroundTintList = ContextCompat.getColorStateList(this, couleur)

        boutonConnexion.text = getString(
            if (connecte) R.string.se_deconnecter else R.string.se_connecter
        )

        tuiles.forEach { tuile ->
            if (!connecte && tuile.actif) {
                enregistrerUsage(tuile.appareil.broche, false)
            }
            if (!connecte) tuile.actif = false
            styleTuile(tuile, connecte)
        }

        noter(message)
    }

    /** Ajoute une ligne horodatée au journal et défile vers le bas. */
    private fun noter(texte: String) {
        journal.append("${heure.format(Date())}  $texte\n")
        journalScroll.post { journalScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(valeur: Int): Int =
        (valeur * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- allumer / éteindre

    /** Envoie la commande de bascule ; si l'envoi réussit, met à jour tuile, journal et usage. */
    private fun basculerAppareil(tuile: Tuile, nouvelEtat: Boolean): Boolean {
        val appareil = tuile.appareil
        val caractere = if (nouvelEtat) appareil.allumer else appareil.eteindre
        if (!liaison.envoyer(caractere)) {
            noter("Envoi impossible, vérifiez la connexion.")
            return false
        }
        tuile.actif = nouvelEtat
        styleTuile(tuile, connecte = true)
        noter("${appareil.nom} ${if (nouvelEtat) "allumé" else "éteint"}  ->  $caractere")
        enregistrerUsage(appareil.broche, nouvelEtat)
        return true
    }

    // ---------------------------------------------------------------- minuteur

    /** Ouvre le statut du minuteur en cours pour cet appareil, ou le formulaire pour en créer un. */
    private fun ouvrirDialogueMinuteur(appareil: Appareil) {
        val actif = minuteurs[appareil.broche]
        if (actif != null) {
            val restant = (actif.finMillis - System.currentTimeMillis()).coerceAtLeast(0)
            val actionRes = if (actif.type == TypeMinuteur.ALLUMAGE) R.string.minuteur_allumer else R.string.minuteur_eteindre
            AlertDialog.Builder(this)
                .setTitle(R.string.minuteur_statut_titre)
                .setMessage(getString(R.string.minuteur_statut_detail, getString(actionRes), formatCompteARebours(restant)))
                .setPositiveButton(R.string.minuteur_annuler) { _, _ -> annulerMinuteur(appareil.broche, notifier = true) }
                .setNegativeButton(R.string.minuteur_fermer, null)
                .show()
            return
        }

        val vue = LayoutInflater.from(this).inflate(R.layout.dialogue_minuteur, null)
        val groupeAction = vue.findViewById<RadioGroup>(R.id.groupeAction)
        val champDelaiValeur = vue.findViewById<TextInputEditText>(R.id.champDelaiValeur)
        val champDelaiUnite = vue.findViewById<AutoCompleteTextView>(R.id.champDelaiUnite)
        val caseAutoOff = vue.findViewById<CheckBox>(R.id.caseAutoOff)
        val conteneurAutoOff = vue.findViewById<View>(R.id.conteneurAutoOff)
        val champAutoOffMinutes = vue.findViewById<TextInputEditText>(R.id.champAutoOffMinutes)

        val uniteMinutes = getString(R.string.minuteur_unite_minutes)
        val uniteSecondes = getString(R.string.minuteur_unite_secondes)
        champDelaiUnite.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf(uniteMinutes, uniteSecondes))
        )
        champDelaiUnite.setText(uniteMinutes, false)

        fun majVisibilite() {
            val estAllumer = groupeAction.checkedRadioButtonId == R.id.radioAllumer
            caseAutoOff.visibility = if (estAllumer) View.VISIBLE else View.GONE
            conteneurAutoOff.visibility = if (estAllumer && caseAutoOff.isChecked) View.VISIBLE else View.GONE
        }
        majVisibilite()
        groupeAction.setOnCheckedChangeListener { _, _ -> majVisibilite() }
        caseAutoOff.setOnCheckedChangeListener { _, _ -> majVisibilite() }

        val dialogue = AlertDialog.Builder(this)
            .setTitle(getString(R.string.minuteur_titre) + " · " + appareil.nom)
            .setView(vue)
            .setNegativeButton(R.string.annuler, null)
            .setPositiveButton(R.string.minuteur_programmer, null)
            .show()

        dialogue.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val allumer = groupeAction.checkedRadioButtonId == R.id.radioAllumer
            val valeur = champDelaiValeur.text?.toString()?.trim()?.toIntOrNull()
            val enSecondes = champDelaiUnite.text?.toString() == uniteSecondes
            val autoOffDemande = allumer && caseAutoOff.isChecked
            val autoOffMinutes = champAutoOffMinutes.text?.toString()?.trim()?.toIntOrNull()

            if (valeur == null || valeur <= 0) {
                Toast.makeText(this, R.string.minuteur_erreur_delai, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (autoOffDemande && (autoOffMinutes == null || autoOffMinutes <= 0)) {
                Toast.makeText(this, R.string.minuteur_erreur_auto_off, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val delaiMillis = if (enSecondes) valeur * 1_000L else valeur * 60_000L
            programmerMinuteur(appareil, allumer, delaiMillis, if (autoOffDemande) autoOffMinutes else null)
            dialogue.dismiss()
        }
    }

    /** Programme l'envoi différé d'une commande, avec extinction automatique optionnelle enchaînée. */
    private fun programmerMinuteur(appareil: Appareil, allumer: Boolean, delaiMillis: Long, autoOffMinutes: Int?) {
        annulerMinuteur(appareil.broche, notifier = false)

        val runnablePrincipal = Runnable {
            minuteurs.remove(appareil.broche)
            val tuile = tuiles.firstOrNull { it.appareil.broche == appareil.broche }
            if (tuile != null && liaison.estConnecte) {
                basculerAppareil(tuile, allumer)
            } else {
                noter("${appareil.nom} : minuteur déclenché, envoi impossible (non connecté).")
            }

            if (allumer && autoOffMinutes != null) {
                val delaiAutoOff = autoOffMinutes * 60_000L
                val runnableAutoOff = Runnable {
                    minuteurs.remove(appareil.broche)
                    val tuileVisee = tuiles.firstOrNull { it.appareil.broche == appareil.broche }
                    if (tuileVisee != null && liaison.estConnecte) {
                        basculerAppareil(tuileVisee, false)
                    } else {
                        noter("${appareil.nom} : extinction automatique, envoi impossible (non connecté).")
                    }
                    rafraichirCompteurs()
                }
                handlerMinuteur.postDelayed(runnableAutoOff, delaiAutoOff)
                minuteurs[appareil.broche] = EtatMinuteur(
                    TypeMinuteur.EXTINCTION_AUTO,
                    System.currentTimeMillis() + delaiAutoOff,
                    runnableAutoOff
                )
                noter(getString(R.string.minuteur_journal_auto_off_programme, appareil.nom, formatDuree(delaiAutoOff)))
            }
            rafraichirCompteurs()
        }

        handlerMinuteur.postDelayed(runnablePrincipal, delaiMillis)
        minuteurs[appareil.broche] = EtatMinuteur(
            if (allumer) TypeMinuteur.ALLUMAGE else TypeMinuteur.EXTINCTION,
            System.currentTimeMillis() + delaiMillis,
            runnablePrincipal
        )

        noter(
            getString(
                R.string.minuteur_journal_programme,
                appareil.nom,
                getString(if (allumer) R.string.minuteur_allumer else R.string.minuteur_eteindre),
                formatDuree(delaiMillis)
            )
        )
        rafraichirCompteurs()
    }

    /** Annule le minuteur en attente pour cette broche, s'il y en a un. */
    private fun annulerMinuteur(broche: Int, notifier: Boolean) {
        val etat = minuteurs.remove(broche) ?: return
        handlerMinuteur.removeCallbacks(etat.runnable)
        if (notifier) {
            appareils.firstOrNull { it.broche == broche }?.let {
                noter(getString(R.string.minuteur_journal_annule, it.nom))
            }
        }
        rafraichirCompteurs()
    }

    /** Met à jour, sur chaque tuile, le petit texte de minuteur ou d'alerte d'usage prolongé. */
    private fun rafraichirCompteurs() {
        val seuilMillis = UsageStore.seuilAlerteMinutes(this) * 60_000L

        tuiles.forEach { tuile ->
            val broche = tuile.appareil.broche
            val compteur = tuile.vue.findViewById<TextView>(R.id.compteur)
            val allume = liaison.estConnecte && tuile.actif
            val debutSession = sessionsEnCours[broche]
            val enAlerte = debutSession != null && (System.currentTimeMillis() - debutSession) >= seuilMillis

            when {
                enAlerte -> {
                    if (broche !in alerteDeclenchee) {
                        alerteDeclenchee += broche
                        val minutes = UsageStore.seuilAlerteMinutes(this)
                        val message = getString(R.string.alerte_journal, tuile.appareil.nom, minutes)
                        noter(message)
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                    compteur.text = getString(R.string.alerte_compte, formatDuree(System.currentTimeMillis() - debutSession!!))
                    compteur.setTextColor(ContextCompat.getColor(this, R.color.pf_alerte))
                    compteur.visibility = View.VISIBLE
                }
                minuteurs[broche] != null -> {
                    val etatMinuteur = minuteurs.getValue(broche)
                    val restant = (etatMinuteur.finMillis - System.currentTimeMillis()).coerceAtLeast(0)
                    val libelleRes = when (etatMinuteur.type) {
                        TypeMinuteur.ALLUMAGE -> R.string.minuteur_compte_allumage
                        TypeMinuteur.EXTINCTION -> R.string.minuteur_compte_extinction
                        TypeMinuteur.EXTINCTION_AUTO -> R.string.minuteur_compte_auto_off
                    }
                    compteur.text = getString(libelleRes, formatCompteARebours(restant))
                    compteur.setTextColor(
                        if (allume) 0xB3FFFFFF.toInt() else ContextCompat.getColor(this, R.color.pf_texte_faible)
                    )
                    compteur.visibility = View.VISIBLE
                }
                else -> compteur.visibility = View.GONE
            }
        }
    }

    // ---------------------------------------------------------------- usage / historique

    /** Démarre ou clôture une session d'allumage pour cette broche, et persiste le cumul. */
    private fun enregistrerUsage(broche: Int, allume: Boolean) {
        val maintenant = System.currentTimeMillis()
        val actuel = usage[broche] ?: UsageAppareil()

        if (allume) {
            sessionsEnCours[broche] = maintenant
            usage[broche] = actuel.copy(derniereMiseEnMarche = maintenant)
        } else {
            val debut = sessionsEnCours.remove(broche)
            val dureeSession = if (debut != null) (maintenant - debut).coerceAtLeast(0) else 0L
            usage[broche] = actuel.copy(
                dureeTotaleMillis = actuel.dureeTotaleMillis + dureeSession,
                derniereExtinction = maintenant
            )
            alerteDeclenchee.remove(broche)
        }

        UsageStore.sauvegarder(this, usage)
        rafraichirHistoriqueSiVisible()
    }

    /** (Re)construit la liste de l'onglet Historique à partir des appareils et de l'usage courants. */
    private fun construireHistorique() {
        conteneurHistorique.removeAllViews()
        val inflater = LayoutInflater.from(this)

        appareils.forEach { appareil ->
            val vue = inflater.inflate(R.layout.ligne_historique, conteneurHistorique, false)
            vue.findViewById<ImageView>(R.id.icone).setImageResource(appareil.icone)
            vue.findViewById<TextView>(R.id.nom).text = appareil.nom

            val donnees = usage[appareil.broche]
            val debutSession = sessionsEnCours[appareil.broche]
            val dureeAffichee = (donnees?.dureeTotaleMillis ?: 0L) +
                (debutSession?.let { System.currentTimeMillis() - it } ?: 0L)

            vue.findViewById<TextView>(R.id.dureeTotale).text =
                getString(R.string.historique_temps_total, formatDuree(dureeAffichee))

            val statut = vue.findViewById<TextView>(R.id.statut)
            when {
                debutSession != null -> {
                    statut.text = getString(R.string.historique_en_cours, formatDuree(System.currentTimeMillis() - debutSession))
                    statut.setTextColor(ContextCompat.getColor(this, R.color.pf_courant))
                }
                donnees?.derniereExtinction != null -> {
                    statut.text = getString(R.string.historique_derniere_activite, heureCourte.format(Date(donnees.derniereExtinction)))
                    statut.setTextColor(ContextCompat.getColor(this, R.color.pf_texte_faible))
                }
                else -> {
                    statut.text = getString(R.string.historique_jamais_utilise)
                    statut.setTextColor(ContextCompat.getColor(this, R.color.pf_texte_faible))
                }
            }

            conteneurHistorique.addView(vue)
        }
    }

    private fun rafraichirHistoriqueSiVisible() {
        if (::pageHistorique.isInitialized && pageHistorique.visibility == View.VISIBLE) {
            construireHistorique()
        }
    }

    /** Formate une durée en "X j Y h", "X h Y min" ou "X min". */
    private fun formatDuree(millis: Long): String {
        val totalMinutes = millis / 60_000
        val jours = totalMinutes / (60 * 24)
        val heures = (totalMinutes % (60 * 24)) / 60
        val minutes = totalMinutes % 60
        return when {
            jours > 0 -> "$jours j $heures h"
            heures > 0 -> "$heures h $minutes min"
            minutes > 0 -> "$minutes min"
            else -> "< 1 min"
        }
    }

    /** Formate un temps restant en "mm:ss" pour l'affichage du compte à rebours. */
    private fun formatCompteARebours(millisRestants: Long): String {
        val totalSecondes = (millisRestants / 1000).coerceAtLeast(0)
        val minutes = totalSecondes / 60
        val secondes = totalSecondes % 60
        return String.format(Locale.FRANCE, "%d:%02d", minutes, secondes)
    }

    // ---------------------------------------------------------------- bluetooth

    private fun adaptateur(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun lancerConnexion() {
        val adaptateur = adaptateur()
        if (adaptateur == null) {
            noter("Ce téléphone n'a pas de Bluetooth.")
            return
        }
        if (!adaptateur.isEnabled) {
            demandeActivation.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        verifierPermissionPuisChoisir()
    }

    private fun verifierPermissionPuisChoisir() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manquantes = listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (manquantes.isNotEmpty()) {
                demandePermission.launch(manquantes.toTypedArray())
                return
            }
        }
        choisirAppareil()
    }

    /** Propose la liste des appareils déjà appairés. */
    private fun choisirAppareil() {
        val appaires: List<BluetoothDevice> = adaptateur()?.bondedDevices?.toList().orEmpty()

        if (appaires.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.aucun_appareil)
                .setMessage(R.string.aucun_appareil_detail)
                .setPositiveButton(R.string.ouvrir_reglages) { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                .setNegativeButton(R.string.annuler, null)
                .show()
            return
        }

        val libelles = appaires.map { "${it.name ?: "Inconnu"}\n${it.address}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.choisir_module)
            .setItems(libelles) { _, position -> liaison.connecter(appaires[position]) }
            .setNegativeButton(R.string.annuler, null)
            .show()
    }
}
