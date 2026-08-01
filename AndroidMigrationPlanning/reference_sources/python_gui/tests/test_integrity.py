from ppg_monitor.integrity import SequenceTracker


def test_sequence_tracker_counts_missing_and_duplicates() -> None:
    tracker = SequenceTracker()
    for value in (10, 11, 14, 14, 15):
        tracker.observe(value)

    assert tracker.missing == 2
    assert tracker.duplicates == 1
    assert tracker.out_of_order == 0


def test_sequence_tracker_accepts_uint32_wrap() -> None:
    tracker = SequenceTracker()
    for value in (0xFFFFFFFE, 0xFFFFFFFF, 0, 1):
        tracker.observe(value)

    assert tracker.missing == 0
    assert tracker.duplicates == 0
    assert tracker.out_of_order == 0


def test_sequence_tracker_reports_out_of_order_without_cascade() -> None:
    tracker = SequenceTracker()
    for value in (100, 99, 101):
        tracker.observe(value)

    assert tracker.missing == 0
    assert tracker.out_of_order == 1
