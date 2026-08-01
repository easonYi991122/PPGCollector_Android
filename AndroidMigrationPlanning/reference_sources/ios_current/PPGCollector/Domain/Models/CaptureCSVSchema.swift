import Foundation

nonisolated enum CaptureCSVSchema {
    static let columns = [
        "schema_version",
        "session_id",
        "sample_index",
        "device_time_s",
        "host_frame_time_ns",
        "frame_sequence",
        "sample_in_frame",
        "red",
        "ir",
        "heart_rate_bpm",
        "heart_rate_valid",
        "heart_rate_time_s",
        "spo2_percent",
        "spo2_valid",
        "spo2_time_s",
        "sqi",
        "sqi_valid",
        "sqi_time_s",
        "soft_version",
        "alg_version",
        "preprocess_profile",
        "protocol_profile",
        "ratio_of_ratios",
        "ratio_of_ratios_valid",
        "ratio_of_ratios_time_s"
    ]

    static let header = columns.joined(separator: ",") + "\n"
}
