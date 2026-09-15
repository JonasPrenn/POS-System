import SwiftUI
import Shared

/// Hängt den Compose-View-Controller aus dem geteilten Modul in die SwiftUI-Szene.
/// Compose kümmert sich selbst um die sicheren Bereiche, deshalb `ignoresSafeArea`
/// beim Aufrufer.
struct ContentView: UIViewControllerRepresentable {
    let app: IosApp

    func makeUIViewController(context: Context) -> UIViewController {
        app.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
