package com.example.audio

/** Réglage : couper le retour d'écoute (haut-parleur/casque) pendant l'enregistrement, pour éviter le larsen. */
object MonitorControl {
    @Volatile var muteWhileRecording: Boolean = true
}
