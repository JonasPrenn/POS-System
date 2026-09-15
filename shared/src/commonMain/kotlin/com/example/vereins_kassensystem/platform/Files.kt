package com.example.vereins_kassensystem.platform

/*
 * Ein paar Dateioperationen, die die Sicherung braucht und die es in der
 * Standardbibliothek nicht gemeinsam gibt. Bewusst nur das Nötigste statt einer
 * Dateisystem-Bibliothek: sechs Funktionen auf zwei Plattformen sind schneller
 * geschrieben als eine Abhängigkeit gepflegt.
 */

/** Wo die Datenbankdatei liegt; die Sicherung liest sie, die Wiederherstellung ersetzt sie. */
expect fun databaseFilePath(): String

expect fun fileExists(path: String): Boolean
expect fun readFile(path: String): ByteArray?
expect fun writeFile(path: String, bytes: ByteArray): Boolean
expect fun deleteFile(path: String): Boolean
expect fun moveFile(from: String, to: String): Boolean
