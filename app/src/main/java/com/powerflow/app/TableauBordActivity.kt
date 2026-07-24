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
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Une tuile affichée + l'appareil associé + son état courant. */
private class Tuile(val vue: View, val appareil: Appareil, var actif: Boolean = false)

/**
 * Écran principal : connexion au HC-05 puis commande des appareils.
 * Chaque appareil est une tuile tactile — on tape la tuile entière ;
 * elle se remplit d'accent quand l'appareil est actif. Les tuiles sont
 * inactives et grisées tant que la liaison n'est pas établie.
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

    private lateinit var appareils: MutableList<Appareil>
    private val tuiles = mutableListOf<Tuile>()
    private val heure = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

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

        appareils = AppareilsStore.charger(this)

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

        noter("Appairez d'abord le HC-05 dans les réglages Bluetooth du téléphone (code 1234 ou 0000).")
    }

    override fun onDestroy() {
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

                    val nouvelEtat = !tuile.actif
                    val caractere = if (nouvelEtat) appareil.allumer else appareil.eteindre
                    if (liaison.envoyer(caractere)) {
                        tuile.actif = nouvelEtat
                        styleTuile(tuile, connecte = true)
                        noter("${appareil.nom} ${if (nouvelEtat) "allumé" else "éteint"}  ->  $caractere")
                    } else {
                        noter("Envoi impossible, vérifiez la connexion.")
                    }
                }

                vue.setOnLongClickListener {
                    ouvrirDialogueAppareil(appareil)
                    true
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
                appareils.removeAll { it.broche == existant.broche }
                AppareilsStore.sauvegarder(this, appareils)
                construireAppareils()
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
                appareils = AppareilsStore.reinitialiser(this)
                construireAppareils()
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
    }

    private fun majEtat(connecte: Boolean, message: String) {
        etatTexte.text = message

        val couleur = if (connecte) R.color.pf_courant else R.color.pf_inactif
        pastille.backgroundTintList = ContextCompat.getColorStateList(this, couleur)

        boutonConnexion.text = getString(
            if (connecte) R.string.se_deconnecter else R.string.se_connecter
        )

        tuiles.forEach { tuile ->
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
