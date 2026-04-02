import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @State private var showCompose = false

    var body: some View {
        ZStack {
            // Match Material3 default background so the transition is seamless
            Color(red: 0.99, green: 0.96, blue: 0.99)
                .ignoresSafeArea()

            if showCompose {
                ComposeView()
                    .ignoresSafeArea()
            }
        }
        .onAppear {
            // Defer Compose initialization to the next run loop iteration
            // so the background color renders first instead of a black screen.
            DispatchQueue.main.async {
                showCompose = true
            }
        }
    }
}
