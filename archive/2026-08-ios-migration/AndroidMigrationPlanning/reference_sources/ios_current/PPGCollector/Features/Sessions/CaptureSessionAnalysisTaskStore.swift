import Combine
import Foundation

@MainActor
final class CaptureSessionAnalysisTaskStore: ObservableObject {
    enum Status: Equatable {
        case running
        case completed(URL)
        case cancelled
        case failed(String)
    }

    struct Snapshot: Equatable {
        let sessionID: String
        let sessionBaseName: String
        var progress: CaptureSessionAnalysisProgress?
        var status: Status

        var isRunning: Bool {
            if case .running = status {
                return true
            }
            return false
        }
    }

    @Published private(set) var snapshots: [String: Snapshot] = [:]
    private var tasks: [String: Task<Void, Never>] = [:]

    func snapshot(for session: StoredCaptureSession) -> Snapshot? {
        snapshots[session.id]
    }

    var runningSnapshots: [Snapshot] {
        snapshots.values.filter(\.isRunning).sorted {
            $0.sessionBaseName < $1.sessionBaseName
        }
    }

    func start(_ session: StoredCaptureSession) {
        guard snapshot(for: session)?.isRunning != true else {
            return
        }

        let runID = UUID()
        snapshots[session.id] = Snapshot(
            sessionID: session.id,
            sessionBaseName: session.baseName,
            progress: nil,
            status: .running
        )
        taskRunIDs[session.id] = runID
        let worker = Task.detached(priority: .userInitiated) { [weak self] in
            try CaptureSessionAnalysisService.analyzeAndSave(
                session: session,
                cancellationCheck: { try Task.checkCancellation() },
                progress: { update in
                    Task { @MainActor [weak self] in
                        self?.update(
                            sessionID: session.id,
                            runID: runID,
                            progress: update
                        )
                    }
                }
            )
        }

        tasks[session.id] = Task { @MainActor [weak self] in
            guard let self else {
                worker.cancel()
                return
            }
            do {
                let url = try await withTaskCancellationHandler(
                    operation: { try await worker.value },
                    onCancel: { worker.cancel() }
                )
                self.finish(
                    sessionID: session.id,
                    runID: runID,
                    status: .completed(url)
                )
            } catch is CancellationError {
                self.finish(
                    sessionID: session.id,
                    runID: runID,
                    status: .cancelled
                )
            } catch {
                self.finish(
                    sessionID: session.id,
                    runID: runID,
                    status: .failed(error.localizedDescription)
                )
            }
        }
    }

    func cancel(_ session: StoredCaptureSession) {
        cancel(sessionID: session.id)
    }

    func cancel(sessionID: String) {
        tasks[sessionID]?.cancel()
    }

    func clearFinished(for session: StoredCaptureSession) {
        guard snapshot(for: session)?.isRunning != true else {
            return
        }
        snapshots[session.id] = nil
    }

    private func update(
        sessionID: String,
        runID: UUID,
        progress: CaptureSessionAnalysisProgress
    ) {
        guard var snapshot = snapshots[sessionID], snapshot.isRunning,
              runID == activeRunID(for: sessionID) else {
            return
        }
        snapshot.progress = progress
        snapshots[sessionID] = snapshot
    }

    private func finish(
        sessionID: String,
        runID: UUID,
        status: Status
    ) {
        guard var snapshot = snapshots[sessionID], snapshot.isRunning,
              runID == activeRunID(for: sessionID) else {
            return
        }
        snapshot.status = status
        snapshot.progress = nil
        snapshots[sessionID] = snapshot
        tasks[sessionID] = nil
        taskRunIDs[sessionID] = nil
    }

    private func activeRunID(for sessionID: String) -> UUID? {
        taskRunIDs[sessionID]
    }

    private var taskRunIDs: [String: UUID] = [:]
}
