package com.powerflow.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Liaison série Bluetooth (profil SPP) avec un module HC-05.
 *
 * Les callbacks sont systematiquement renvoyes sur le thread principal,
 * ils peuvent donc modifier l'interface directement.
 *
 * @param onEtat     appele a chaque changement d'etat (connecte, erreur...)
 * @param onReception appele a chaque texte recu depuis l'Arduino
 */
@SuppressLint("MissingPermission")
class BluetoothSerial(
    private val onEtat: (connecte: Boolean, message: String) -> Unit,
    private val onReception: (texte: String) -> Unit
) {

    companion object {
        /** UUID standard du Serial Port Profile : le seul que le HC-05 expose. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val ui = Handler(Looper.getMainLooper())
    private var socket: BluetoothSocket? = null
    private var sortie: OutputStream? = null

    @Volatile
    private var actif = false

    val estConnecte: Boolean
        get() = socket?.isConnected == true

    /**
     * Ouvre la connexion dans un thread dedie.
     * L'appel systeme est bloquant : il ne doit jamais tourner sur le thread principal.
     */
    fun connecter(appareil: BluetoothDevice) {
        deconnecter()
        thread(name = "powerflow-bt") {
            try {
                signaler(false, "Connexion en cours...")

                // La decouverte ralentit fortement l'etablissement du lien.
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()

                val s = appareil.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()

                socket = s
                sortie = s.outputStream
                actif = true
                signaler(true, "Connecte a ${appareil.name}")

                boucleLecture(s)
            } catch (e: IOException) {
                fermer()
                signaler(false, "Connexion impossible : ${e.message}")
            }
        }
    }

    /**
     * Lit en continu ce que renvoie l'Arduino.
     * Le croquis actuel ne repond rien : cette boucle sert au debogage
     * et detecte surtout la rupture du lien.
     */
    private fun boucleLecture(s: BluetoothSocket) {
        val tampon = ByteArray(256)
        val ligne = StringBuilder()

        while (actif) {
            try {
                val lus = s.inputStream.read(tampon)
                if (lus <= 0) break

                for (i in 0 until lus) {
                    when (val c = tampon[i].toInt().toChar()) {
                        '\n' -> {
                            val texte = ligne.toString().trim()
                            if (texte.isNotEmpty()) ui.post { onReception(texte) }
                            ligne.setLength(0)
                        }
                        '\r' -> Unit
                        else -> ligne.append(c)
                    }
                }
            } catch (e: IOException) {
                break
            }
        }

        if (actif) {
            fermer()
            signaler(false, "Liaison interrompue")
        }
    }

    /**
     * Envoie un seul caractere, sans retour a la ligne.
     * C'est exactement ce qu'attend le croquis Arduino (BT.read()).
     */
    fun envoyer(caractere: Char): Boolean {
        val out = sortie ?: return false
        return try {
            out.write(caractere.code)
            out.flush()
            true
        } catch (e: IOException) {
            fermer()
            signaler(false, "Envoi impossible : ${e.message}")
            false
        }
    }

    fun deconnecter() {
        val etaitActif = actif
        fermer()
        if (etaitActif) signaler(false, "Deconnecte")
    }

    private fun fermer() {
        actif = false
        try { socket?.close() } catch (_: IOException) { }
        socket = null
        sortie = null
    }

    private fun signaler(connecte: Boolean, message: String) {
        ui.post { onEtat(connecte, message) }
    }
}
