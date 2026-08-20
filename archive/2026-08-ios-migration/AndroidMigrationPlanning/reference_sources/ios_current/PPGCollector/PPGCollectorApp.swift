//
//  PPGCollectorApp.swift
//  PPGCollector
//
//  Created by Shuhan Yi on 2026/7/30.
//

import SwiftUI

@main
@MainActor
struct PPGCollectorApp: App {
    @StateObject private var bluetoothService: BLECentralService
    @StateObject private var captureController: CaptureSessionController
    @StateObject private var analysisTaskStore = CaptureSessionAnalysisTaskStore()

    init() {
        let bluetoothService = BLECentralService()
        _bluetoothService = StateObject(wrappedValue: bluetoothService)
        _captureController = StateObject(
            wrappedValue: CaptureSessionController(
                bluetoothService: bluetoothService
            )
        )
    }

    var body: some Scene {
        WindowGroup {
            ContentView(
                bluetoothService: bluetoothService,
                captureController: captureController
            )
            .environmentObject(analysisTaskStore)
        }
    }
}
