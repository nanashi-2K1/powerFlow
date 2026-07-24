package com.powerflow.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/** Ecran d'accueil : logo PowerFlow et acces au tableau de bord. */
class AccueilActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accueil)

        // Animation d'ouverture : le logo et le titre montent en fondu.
        listOf<View>(
            findViewById(R.id.logo),
            findViewById(R.id.titre),
            findViewById(R.id.sousTitre),
            findViewById(R.id.boutonCommencer)
        ).forEachIndexed { index, vue ->
            vue.alpha = 0f
            vue.translationY = 24f
            vue.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(120L * index)
                .setDuration(420L)
                .start()
        }

        findViewById<Button>(R.id.boutonCommencer).setOnClickListener {
            startActivity(Intent(this, TableauBordActivity::class.java))
        }
    }
}
