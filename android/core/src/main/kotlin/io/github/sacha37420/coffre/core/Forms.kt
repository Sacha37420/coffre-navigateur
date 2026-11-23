package io.github.sacha37420.coffre.core

/**
 * Analyse d'un formulaire pour la saisie automatique, sur un arbre neutre (testable sur la JVM).
 * L'application convertit l'AssistStructure d'Android vers ces nœuds.
 */
object Forms {
    /** Un nœud de l'écran, réduit à ce qui sert à reconnaître les champs. */
    class Node(
        val id: Any? = null,                // identifiant d'autoremplissage (AutofillId côté Android)
        val hints: List<String> = emptyList(),
        val htmlTag: String? = null,
        val htmlType: String? = null,       // attribut type d'un <input>
        val htmlAutocomplete: String? = null,
        val inputPassword: Boolean = false, // InputType mot de passe (champ natif)
        val idEntry: String? = null,        // nom de ressource ou name/id HTML
        val webDomain: String? = null,
        val webScheme: String? = null,
        val visible: Boolean = true,
        val children: List<Node> = emptyList(),
    )

    class Form(val origin: String?, val username: Any?, val passwords: List<Any?>, val creation: Boolean)

    private val USER_HINTS = setOf("username", "emailaddress", "email", "phone", "newusername")
    private val USER_WORDS = Regex("user|mail|login|identif|compte|account|courriel", RegexOption.IGNORE_CASE)

    private fun isPassword(n: Node) = n.inputPassword || n.htmlType.equals("password", true) ||
        n.hints.any { it.lowercase().contains("password") }

    private fun isTextual(n: Node) = n.id != null && !isPassword(n) && (n.htmlTag == null || n.htmlTag.equals("input", true)) &&
        (n.htmlType == null || n.htmlType.lowercase() in setOf("text", "email", "tel", ""))

    /**
     * Rend le formulaire reconnu, ou null s'il n'y a pas de champ mot de passe. `origin` : celle du premier
     * domaine web rencontré (https seulement), null pour un écran sans domaine. Un champ placé sous un autre
     * domaine que le premier (cadre d'une autre origine) est ignoré.
     */
    fun parse(root: Node): Form? {
        val flat = mutableListOf<Pair<Node, String?>>()
        fun walk(n: Node, domain: String?) {
            if (!n.visible) return
            val d = n.webDomain?.takeIf { it.isNotEmpty() }?.let { (n.webScheme ?: "https").lowercase() + "://" + it.lowercase() } ?: domain
            flat.add(n to d)
            n.children.forEach { walk(it, d) }
        }
        walk(root, null)
        val firstDomain = flat.firstNotNullOfOrNull { it.second }
        val inScope = flat.filter { it.second == firstDomain }.map { it.first }
        val passwords = inScope.filter { it.id != null && isPassword(it) }
        if (passwords.isEmpty()) return null
        val firstPw = inScope.indexOf(passwords.first())
        val before = inScope.take(firstPw).filter { isTextual(it) }
        val user = before.lastOrNull { n -> n.hints.any { it.lowercase() in USER_HINTS } || USER_WORDS.containsMatchIn("${n.idEntry} ${n.htmlAutocomplete}") }
            ?: before.lastOrNull()
        val creation = passwords.size >= 2 || passwords.any { it.htmlAutocomplete.equals("new-password", true) || it.hints.any { h -> h.equals("newPassword", true) } }
        val origin = firstDomain?.takeIf { it.startsWith("https://") }
        return Form(origin, user?.id, passwords.map { it.id }, creation)
    }

    /** Navigateurs dont on accepte le domaine annoncé (si, en plus, l'appli est système ou vient du Play Store). */
    val BROWSERS = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "com.microsoft.emmx", "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
        "com.brave.browser", "com.sec.android.app.sbrowser", "com.vivaldi.browser", "org.chromium.chrome",
    )
    private val TRUSTED_INSTALLERS = setOf("com.android.vending")

    /**
     * Le domaine annoncé par cette application est-il crédible ? Une appli quelconque peut se dire « banque.fr » ;
     * seul un navigateur connu, authentique (système ou installé depuis le Play Store), est cru.
     */
    fun trustedBrowser(packageName: String, isSystemApp: Boolean, installer: String?): Boolean =
        packageName in BROWSERS && (isSystemApp || installer in TRUSTED_INSTALLERS)
}
