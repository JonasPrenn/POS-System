package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.server.tenancy.Kuerzel
import com.example.vereins_kassensystem.server.tenancy.Tenant
import com.example.vereins_kassensystem.server.tenancy.TenantDirectory
import java.time.LocalDate

/**
 * Wer sich anmeldet, nennt mit dem Namen den Verein: `kassier@clunia`. Ohne Kürzel ist es der
 * erste Verein, wie vor den Vereinen; `name@system` ist die Systemverwaltung.
 *
 * Testanmeldung: `admin#kassier@clunia` mit dem Passwort des Administrators öffnet eine Sitzung
 * als `kassier` — um zu sehen, was dessen Rolle sieht. Das darf ein Administrator des Vereins und
 * ein Hauptadmin. Die Sitzung trägt den Namen dessen, der wirklich davorsitzt, in jede Zeile des
 * Protokolls; die Seite sagt es oben in Bernstein.
 */
internal class SignIn(private val directory: TenantDirectory) {

    sealed interface Outcome
    class Verein(val tenant: Tenant, val token: String, val session: WebSession, val hauptadmin: WebUser?) : Outcome
    class System(val token: String, val session: WebSession) : Outcome
    /** [tenant] null: Es ging um die Systemverwaltung. */
    class Failed(val tenant: Tenant?) : Outcome

    fun attempt(text: String, password: String): Outcome {
        val login = text.trim()
        val at = login.lastIndexOf('@')
        val kuerzel = if (at > 0) Kuerzel.normalize(login.substring(at + 1)) else null
        if (kuerzel == Kuerzel.SYSTEM) {
            val accounts = directory.system?.accounts ?: return Failed(null)
            return accounts.login(login.substring(0, at), password)?.let { (token, session) -> System(token, session) } ?: Failed(null)
        }
        // Ein unbekanntes Kürzel ist vielleicht keins: Anmeldenamen von vor den Vereinen durften ein @ enthalten.
        val named = kuerzel?.let(directory::bySlug)?.takeIf { it.info.active }
        val tenant = named ?: directory.default
        val name = if (named != null) login.substring(0, at) else login
        val accounts = tenant.web.accounts

        val hash = name.indexOf('#')
        if (hash <= 0 || hash == name.length - 1 || accounts.findByLogin(name) != null) {
            return accounts.login(name, password)?.let { (token, session) -> Verein(tenant, token, session, null) } ?: Failed(tenant)
        }
        // Beide Prüfungen immer: Die Antwortzeit soll nicht verraten, wer Administrator wovon ist.
        val acting = name.substring(0, hash)
        val local = accounts.verify(acting, password)?.takeIf { it.role == Role.ADMIN }
        val hauptadmin = directory.system?.accounts?.verify(acting, password)
        val via = local?.displayName ?: hauptadmin?.let { "${it.displayName}, Hauptadmin" } ?: return Failed(tenant)
        val user = accounts.findByLogin(name.substring(hash + 1))?.takeIf { it.usable(LocalDate.now(directory.config.zone)) } ?: return Failed(tenant)
        val (token, session) = accounts.open(user, via)
        return Verein(tenant, token, session, hauptadmin.takeIf { local == null })
    }
}
