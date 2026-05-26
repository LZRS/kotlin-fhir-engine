import SwiftUI
import engineKmpAppKit

@main
struct iOSApp: App {
    init() {
        // Register the BGTaskScheduler handler. Must happen before the first scene is created —
        // the OS silently drops registrations that arrive after launch completes.
        DemoIosBackgroundSyncKt.setupBackgroundSync(taskIdentifier: DemoIosBackgroundSyncKt.DEMO_BG_SYNC_TASK_ID)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onAppear {
                    // Submit the first background sync request to the OS.
                    // The manager auto-reschedules on every subsequent wake.
                    DemoIosBackgroundSyncKt.scheduleBackgroundSync(taskIdentifier: DemoIosBackgroundSyncKt.DEMO_BG_SYNC_TASK_ID)
                }
        }
    }
}
