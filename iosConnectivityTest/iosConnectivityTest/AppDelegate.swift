import UIKit
import ConnectivityTest

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Parse arguments
        let args = CommandLine.arguments
        var instanceId = "ios-\(Int(Date().timeIntervalSince1970 * 1000) % 100000000)"
        var platforms = "jvm,ios-real-device"
        var controlHost: String? = nil
        var controlPort: Int32 = 0

        for (index, arg) in args.enumerated() {
            if arg == "--instance-id" && index + 1 < args.count {
                instanceId = args[index + 1]
            }
            if arg == "--platforms" && index + 1 < args.count {
                platforms = args[index + 1]
            }
            if arg == "--control-host" && index + 1 < args.count {
                controlHost = args[index + 1]
            }
            if arg == "--control-port" && index + 1 < args.count {
                controlPort = Int32(args[index + 1]) ?? 0
            }
        }

        print("[iOS-Test] Starting connectivity test")
        print("[iOS-Test] instanceId=\(instanceId), platforms=\(platforms), control=\(controlHost ?? "none"):\(controlPort)")

        guard let host = controlHost, controlPort > 0 else {
            print("[iOS-Test] ERROR: --control-host and --control-port are required")
            exit(1)
            return true
        }

        let uiState = IosConnectivityTestKt.runIosConnectivityTest(
            instanceId: instanceId,
            platforms: platforms,
            controlHost: host,
            controlPort: controlPort
        ) { success, message in
            print("[iOS-Test] Result: success=\(success), message=\(message ?? "nil")")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                exit(success.boolValue ? 0 : 1)
            }
        }

        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = ConnectivityTestViewControllerKt.ConnectivityTestViewController(uiState: uiState)
        window?.makeKeyAndVisible()

        return true
    }
}
