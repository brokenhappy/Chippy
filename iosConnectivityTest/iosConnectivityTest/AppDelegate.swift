import UIKit
import ComposeApp

// Minimal iOS app that runs connectivity test on launch and exits
// No UI, just runs the test in the background

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Create minimal window (required for app to run)
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = UIViewController()
        window?.makeKeyAndVisible()

        // Parse arguments
        let args = CommandLine.arguments
        var instanceId = "ios-\(Int(Date().timeIntervalSince1970 * 1000) % 100000000)"
        var platforms = "jvm,ios-real-device"

        for (index, arg) in args.enumerated() {
            if arg == "--instance-id" && index + 1 < args.count {
                instanceId = args[index + 1]
            }
            if arg == "--platforms" && index + 1 < args.count {
                platforms = args[index + 1]
            }
        }

        print("[iOS-Test] Starting connectivity test")
        print("[iOS-Test] instanceId=\(instanceId), platforms=\(platforms)")

        // Run the Kotlin connectivity test
        IosConnectivityTestKt.runIosConnectivityTest(
            instanceId: instanceId,
            platforms: platforms
        ) { success, message in
            print("[iOS-Test] Result: success=\(success), message=\(message ?? "nil")")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                exit(success.boolValue ? 0 : 1)
            }
        }

        return true
    }
}
