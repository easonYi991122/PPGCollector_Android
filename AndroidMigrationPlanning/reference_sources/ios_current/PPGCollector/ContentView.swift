import SwiftUI

struct ContentView: View {
    @ObservedObject var bluetoothService: BLECentralService
    @ObservedObject var captureController: CaptureSessionController
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            DeviceListView(
                bluetoothService: bluetoothService,
                captureController: captureController
            )
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .background {
                captureController.handleSceneBackground()
            }
        }
    }
}
