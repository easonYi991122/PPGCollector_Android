import Foundation

nonisolated struct SciPyPeak: Equatable, Sendable {
    let index: Int
    let height: Double
    let prominence: Double
    let leftBaseIndex: Int
    let rightBaseIndex: Int
    let width: Double?
    let widthHeight: Double?
    let leftIntersection: Double?
    let rightIntersection: Double?
}

nonisolated struct SciPyPeakDetectionResult: Equatable, Sendable {
    let peaks: [SciPyPeak]

    var indices: [Int] {
        peaks.map(\.index)
    }
}

/// The subset of SciPy `find_peaks` used by the frozen HR and SQI profiles.
///
/// Conditions are applied in SciPy order: height, distance, prominence, width.
/// Plateau midpoint, distance pruning, prominence bases, and half-prominence
/// width interpolation intentionally follow SciPy 1.17.1 semantics.
nonisolated enum SciPyPeakDetector {
    static func findPeaks(
        values: [Double],
        distance: Int,
        minimumHeight: Double? = nil,
        minimumProminence: Double,
        minimumWidth: Double? = nil
    ) -> SciPyPeakDetectionResult {
        var candidateIndices = localMaximumIndices(values)
        if let minimumHeight {
            candidateIndices.removeAll {
                values[$0] < minimumHeight
            }
        }
        candidateIndices = applyMinimumDistance(
            candidateIndices,
            values: values,
            distance: max(1, distance)
        )

        var peaks: [SciPyPeak] = []
        for index in candidateIndices {
            let prominence = peakProminence(
                values: values,
                peakIndex: index
            )
            guard prominence.value >= minimumProminence else {
                continue
            }
            let width = peakWidth(
                values: values,
                peakIndex: index,
                prominence: prominence
            )
            if let minimumWidth, width.value < minimumWidth {
                continue
            }
            peaks.append(
                SciPyPeak(
                    index: index,
                    height: values[index],
                    prominence: prominence.value,
                    leftBaseIndex: prominence.leftBaseIndex,
                    rightBaseIndex: prominence.rightBaseIndex,
                    width: minimumWidth == nil ? nil : width.value,
                    widthHeight:
                        minimumWidth == nil ? nil : width.height,
                    leftIntersection:
                        minimumWidth == nil
                        ? nil
                        : width.leftIntersection,
                    rightIntersection:
                        minimumWidth == nil
                        ? nil
                        : width.rightIntersection
                )
            )
        }
        return SciPyPeakDetectionResult(peaks: peaks)
    }

    private static func localMaximumIndices(
        _ values: [Double]
    ) -> [Int] {
        guard values.count >= 3 else {
            return []
        }
        var peaks: [Int] = []
        var index = 1
        while index < values.count - 1 {
            guard values[index - 1] < values[index] else {
                index += 1
                continue
            }
            var plateauEnd = index
            while plateauEnd + 1 < values.count,
                  values[plateauEnd + 1] == values[index] {
                plateauEnd += 1
            }
            if plateauEnd + 1 < values.count,
               values[plateauEnd + 1] < values[index] {
                peaks.append((index + plateauEnd) / 2)
            }
            index = plateauEnd + 1
        }
        return peaks
    }

    private static func applyMinimumDistance(
        _ indices: [Int],
        values: [Double],
        distance: Int
    ) -> [Int] {
        guard indices.count > 1 else {
            return indices
        }
        var keep = Array(repeating: true, count: indices.count)
        let priorityOrder = indices.indices.sorted {
            let left = values[indices[$0]]
            let right = values[indices[$1]]
            if left == right {
                return $0 < $1
            }
            return left < right
        }
        for position in priorityOrder.reversed() where keep[position] {
            var neighbor = position - 1
            while neighbor >= 0,
                  indices[position] - indices[neighbor] < distance {
                keep[neighbor] = false
                neighbor -= 1
            }
            neighbor = position + 1
            while neighbor < indices.count,
                  indices[neighbor] - indices[position] < distance {
                keep[neighbor] = false
                neighbor += 1
            }
        }
        return indices.indices.compactMap {
            keep[$0] ? indices[$0] : nil
        }
    }

    private static func peakProminence(
        values: [Double],
        peakIndex: Int
    ) -> Prominence {
        let peakValue = values[peakIndex]
        var leftBaseIndex = peakIndex
        var leftMinimum = peakValue
        var left = peakIndex
        while left >= 0, values[left] <= peakValue {
            if values[left] < leftMinimum {
                leftMinimum = values[left]
                leftBaseIndex = left
            }
            left -= 1
        }

        var rightBaseIndex = peakIndex
        var rightMinimum = peakValue
        var right = peakIndex
        while right < values.count, values[right] <= peakValue {
            if values[right] < rightMinimum {
                rightMinimum = values[right]
                rightBaseIndex = right
            }
            right += 1
        }
        return Prominence(
            value: peakValue - max(leftMinimum, rightMinimum),
            leftBaseIndex: leftBaseIndex,
            rightBaseIndex: rightBaseIndex
        )
    }

    private static func peakWidth(
        values: [Double],
        peakIndex: Int,
        prominence: Prominence
    ) -> Width {
        let height =
            values[peakIndex] - prominence.value * 0.5
        var left = peakIndex
        while prominence.leftBaseIndex < left,
              height < values[left] {
            left -= 1
        }
        var leftIntersection = Double(left)
        if values[left] < height {
            leftIntersection +=
                (height - values[left])
                / (values[left + 1] - values[left])
        }

        var right = peakIndex
        while right < prominence.rightBaseIndex,
              height < values[right] {
            right += 1
        }
        var rightIntersection = Double(right)
        if values[right] < height {
            rightIntersection -=
                (height - values[right])
                / (values[right - 1] - values[right])
        }
        return Width(
            value: rightIntersection - leftIntersection,
            height: height,
            leftIntersection: leftIntersection,
            rightIntersection: rightIntersection
        )
    }
}

private nonisolated struct Prominence {
    let value: Double
    let leftBaseIndex: Int
    let rightBaseIndex: Int
}

private nonisolated struct Width {
    let value: Double
    let height: Double
    let leftIntersection: Double
    let rightIntersection: Double
}
