package io.github.sacha37420.coffre.core

/**
 * Erreur au message fixe, écrit pour l'utilisateur — mêmes codes que l'extension (`CoffreError`).
 * Le message ne contient jamais un secret, un contenu ni une réponse serveur recopiée.
 */
class CoffreException(val code: String, message: String) : Exception(message)
