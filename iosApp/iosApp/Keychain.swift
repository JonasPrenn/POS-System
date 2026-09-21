import Foundation
import Security
import Shared

/// Erfüllt `SecretStore` aus IosPlatform.kt mit dem Schlüsselbund.
///
/// Generische Passwort-Einträge unter dem Dienstnamen der App, erreichbar nach dem
/// ersten Entsperren: So ist der SumUp-Key auch für den Hintergrundplaner lesbar, landet
/// aber nicht in einer unverschlüsselten Gerätesicherung.
///
/// Der Schlüsselbund verlangt eine signierte App. Ein Build mit `CODE_SIGNING_ALLOWED=NO`
/// — so baut die Kommandozeile für den Simulator — bekommt auf jeden Zugriff -34018
/// (`errSecMissingEntitlement`). Das Protokoll unten sagt es; die Kopplung merkt es selbst,
/// weil sie das Token zurückliest. Aus Xcode gestartet ist die App signiert und es geht.
final class KeychainSecretStore: NSObject, SecretStore {

    private let service = "com.example.vereinsdeckel"

    func read(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            if status != errSecItemNotFound { report("Lesen", key, status) }
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    func write(key: String, value: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var insert = query
            insert.merge(attributes) { _, new in new }
            status = SecItemAdd(insert as CFDictionary, nil)
        }
        if status != errSecSuccess { report("Schreiben", key, status) }
    }

    /// Nie der Wert, nur der Name: Das Protokoll ist kein Ort für Geheimnisse.
    private func report(_ action: String, _ key: String, _ status: OSStatus) {
        NSLog("Schlüsselbund: %@ von '%@' scheiterte mit Status %d", action, key, status)
    }
}
