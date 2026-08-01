from tools.ppg_monitor.timing import TimingTracker


def test_timing_tracker_reports_cadence_error_and_gap_time() -> None:
    tracker = TimingTracker(expected_period_us=5000.0)
    tracker.observe(10, 1.000000)
    tracker.observe(11, 1.005100)
    tracker.observe(13, 1.015000)

    summary = tracker.as_dict()
    assert summary["intervals"] == 2
    assert abs(float(summary["mean_abs_error_us"]) - 100.0) < 0.001
    assert abs(float(summary["max_abs_error_us"]) - 100.0) < 0.001


def test_timing_tracker_handles_sequence_wrap() -> None:
    tracker = TimingTracker(expected_period_us=5000.0)
    tracker.observe(0xFFFFFFFF, 2.000)
    tracker.observe(0, 2.005)

    assert tracker.as_dict()["intervals"] == 1
    assert float(tracker.as_dict()["max_abs_error_us"]) < 0.001
