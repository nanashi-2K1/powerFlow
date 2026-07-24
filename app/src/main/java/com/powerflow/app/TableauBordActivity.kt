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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ecran principal : connexion au HC-05 puis commande des appareils.
 */
@SuppressLint("MissingPermission")
class TableauBordActivity : AppCompatActivity() {

    private lateinit var rail: View
    private lateinit var pastille: View
    private lateinit var etatTexte: TextView
    private lateinit var boutonConnexion: MaterialButton
    private lateinit var conteneur: LinearLayout
    private lateinit var journal: TextView
    private lateinit var journalScroll: ScrollView

    private val interrupteurs = mutableListOf<SwitchMaterial>()
    private val heure = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    private lateinit var liaison: BluetoothSerial

    /** Demande de permission Bluetooth (Android 12+). */
    private val demandePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultats ->
        if (resultats.values.all { it }) {
            choisirAppareil()
        } else {
            noter("Permission Bluetooth refusee. Autorisez-la dans les reglages.")
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

        rail = findViewById(R.id.rail)
        pastille = findViewById(R.id.pastille)
        etatTexte = findViewById(R.id.etatTexte)
        boutonConnexion = findViewById(R.id.boutonConnexion)
        conteneur = findViewById(R.id.conteneurAppareils)
        journal = findViewById(R.id.journal)
        journalScroll = findViewById(R.id.journalScroll)

        liaison = BluetoothSerial(
            onEtat = { connecte, message -> majEtat(connecte, message) },
            onReception = { texte -> noter("Arduino : $texte") }
        )

        construireAppareils()
        majEtat(false, "Non connecte")

        boutonConnexion.setOnClickListener {
            if (liaison.estConnecte) {
                liaison.deconnecter()
            } else {
                lancerConnexion()
            }
        }

        noter("Appairez d'abord le HC-05 dans les reglages Bluetooth du telephone (code 1234 ou 0000).")
    }

    override fun onDestroy() {
        liaison.deconnecter()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- interface

    /** Cree une ligne par appareil declare dans Appareils.liste. */
    private fun construireAppareils() {
        val inflater = LayoutInflater.from(this)

        for (appareil in Appareils.liste) {
            val ligne = inflater.inflate(R.layout.ligne_appareil, conteneur, false)

            ligne.findViewById<TextView>(R.id.icone).text = appareil.icone
            ligne.findViewById<TextView>(R.id.nom).text = appareil.nom
            ligne.findViewById<TextView>(R.id.detail).text =
                getString(R.string.detail_appareil, appareil.broche, appareil.allumer, appareil.eteindre)

            val interrupteur = ligne.findViewById<SwitchMaterial>(R.id.interrupteur)
            interrupteur.isEnabled = false
            interrupteur.setOnCheckedChangeListener { bouton, coche ->
                // Ignore les changements provoques par le code lui-meme.
                if (!bouton.isPressed) return@setOnCheckedChangeListener

                val caractere = if (coche) appareil.allumer else appareil.eteindre
                if (liaison.envoyer(caractere)) {
                    noter("${appareil.nom} ${if (coche) "allume" else "eteint"}  ->  $caractere")
                } else {
                    // L'envoi a echoue : on remet l'interrupteur dans son etat reel.
                    bouton.isChecked = !coche
                    noter("Envoi impossible, verifiez la connexion.")
                }
            }

            interrupteurs += interrupteur
            conteneur.addView(ligne)
        }
    }

    private fun majEtat(connecte: Boolean, message: String) {
        etatTexte.text = message

        val couleur = if (connecte) R.color.pf_courant else R.color.pf_inactif
        pastille.backgroundTintList = ContextCompat.getColorStateList(this, couleur)
        rail.backgroundTintList = ContextCompat.getColorStateList(this, couleur)
        rail.animate().alpha(if (connecte) 1f else 0.35f).setDuration(300L).start()

        boutonConnexion.text = getString(
            if (connecte) R.string.se_deconnecter else R.string.se_connecter
        )

        interrupteurs.forEach { interrupteur ->
            interrupteur.isEnabled = connecte
            if (!connecte) interrupteur.isChecked = false
        }

        noter(message)
    }

    /** Ajoute une ligne horodatee au journal et defile vers le bas. */
    private fun noter(texte: String) {
        journal.append("${heure.format(Date())}  $texte\n")
        journalScroll.post { journalScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- bluetooth

    private fun adaptateur(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun lancerConnexion() {
        val adaptateur = adaptateur()
        if (adaptateur == null) {
            noter("Ce telephone n'a pas de Bluetooth.")
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

    /** Propose la liste des appareils deja appaires. */
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
