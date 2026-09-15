import SwiftUI
import Shared

/// Der Einstieg. Baut das Kotlin-Objekt in `init`, also vor dem Ende des App-Starts —
/// der Hintergrundplaner registriert sich dabei, und das muss so früh passieren.
///
/// `sumUp: nil` heißt: kein Kartenterminal. Sobald das SumUp-iOS-SDK per Swift Package
/// eingebunden ist, kommt hier eine `SumUpBridge`-Umsetzung hinein (siehe
/// SumUpPayments.ios.kt für den Vertrag) und die Karte ist wieder da.
@main
struct iOSApp: App {
    private let app = IosApp(secrets: KeychainSecretStore(), sumUp: nil)

    var body: some Scene {
        WindowGroup {
            ContentView(app: app)
                .ignoresSafeArea()
        }
    }
}
