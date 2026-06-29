import Foundation
#if canImport(HealthKit)
import HealthKit
#endif

/// Reads Health (HealthKit) data for the agent's `read_health` tool. Requesting
/// authorization triggers the standard iOS Health permission sheet (the user's
/// "confirm"); once granted, queries run silently. Supports last-night sleep plus
/// heart rate, HRV, resting heart rate, steps, and active energy.
enum IOSHealthService {

    /// Entry point for the agent tool. `inputJSON` is the tool-call argument string:
    /// `{ "metrics": ["sleep","heart_rate",...], "date": "yyyy-MM-dd" }` (all optional;
    /// default = last night's sleep). Returns a JSON string the model reads.
    static func query(inputJSON: String) async -> String {
        #if canImport(HealthKit)
        guard HKHealthStore.isHealthDataAvailable() else {
            return json(["ok": false, "error": "此设备不支持 HealthKit（健康数据不可用）。"])
        }

        let request = parseInput(inputJSON)
        let store = HKHealthStore()

        // Request read authorization for the requested metrics. This shows the system
        // Health permission sheet on first use; the user confirms there.
        let readTypes = Set(request.metrics.flatMap { $0.objectTypes })
        guard !readTypes.isEmpty else {
            return json(["ok": false, "error": "未指定可识别的健康指标。"])
        }
        do {
            try await store.requestAuthorization(toShare: [], read: readTypes)
        } catch {
            return json(["ok": false, "error": "申请健康权限失败：\(error.localizedDescription)"])
        }

        let window = request.window
        var result: [String: Any] = [
            "ok": true,
            "date": request.dateLabel,
            "window": [
                "start": Self.iso(window.start),
                "end": Self.iso(window.end)
            ]
        ]

        for metric in request.metrics {
            switch metric {
            case .sleep:
                result["sleep"] = await Self.readSleep(store: store, window: window)
            case .heartRate:
                result["heart_rate"] = await Self.readQuantityStats(
                    store: store, identifier: .heartRate, unit: HKUnit.count().unitDivided(by: .minute()),
                    window: window, label: "bpm"
                )
            case .hrv:
                result["hrv"] = await Self.readQuantityStats(
                    store: store, identifier: .heartRateVariabilitySDNN, unit: .secondUnit(with: .milli),
                    window: window, label: "ms"
                )
            case .restingHeartRate:
                result["resting_heart_rate"] = await Self.readQuantityStats(
                    store: store, identifier: .restingHeartRate, unit: HKUnit.count().unitDivided(by: .minute()),
                    window: request.dayWindow, label: "bpm"
                )
            case .steps:
                result["steps"] = await Self.readQuantitySum(
                    store: store, identifier: .stepCount, unit: .count(),
                    window: request.dayWindow, label: "步"
                )
            case .activeEnergy:
                result["active_energy"] = await Self.readQuantitySum(
                    store: store, identifier: .activeEnergyBurned, unit: .kilocalorie(),
                    window: request.dayWindow, label: "kcal"
                )
            }
        }

        return json(result)
        #else
        return json(["ok": false, "error": "HealthKit 在当前构建中不可用。"])
        #endif
    }

    // MARK: - Input

    private enum Metric: String, CaseIterable {
        case sleep, heartRate = "heart_rate", hrv, restingHeartRate = "resting_heart_rate"
        case steps, activeEnergy = "active_energy"

        #if canImport(HealthKit)
        var objectTypes: [HKObjectType] {
            switch self {
            case .sleep:
                return [HKObjectType.categoryType(forIdentifier: .sleepAnalysis)].compactMap { $0 }
            case .heartRate:
                return [HKObjectType.quantityType(forIdentifier: .heartRate)].compactMap { $0 }
            case .hrv:
                return [HKObjectType.quantityType(forIdentifier: .heartRateVariabilitySDNN)].compactMap { $0 }
            case .restingHeartRate:
                return [HKObjectType.quantityType(forIdentifier: .restingHeartRate)].compactMap { $0 }
            case .steps:
                return [HKObjectType.quantityType(forIdentifier: .stepCount)].compactMap { $0 }
            case .activeEnergy:
                return [HKObjectType.quantityType(forIdentifier: .activeEnergyBurned)].compactMap { $0 }
            }
        }
        #endif
    }

    private struct ParsedRequest {
        let metrics: [Metric]
        let dateLabel: String
        let window: (start: Date, end: Date)      // overnight window (sleep / HR / HRV)
        let dayWindow: (start: Date, end: Date)   // full prior day (steps / energy / resting HR)
    }

    private static func parseInput(_ inputJSON: String) -> ParsedRequest {
        let obj = (try? JSONSerialization.jsonObject(with: Data(inputJSON.utf8))) as? [String: Any] ?? [:]

        var metrics: [Metric] = []
        if let names = obj["metrics"] as? [String] {
            metrics = names.compactMap { Metric(rawValue: $0.lowercased()) }
        } else if let single = obj["metric"] as? String, let m = Metric(rawValue: single.lowercased()) {
            metrics = [m]
        }
        if metrics.isEmpty { metrics = [.sleep] }

        let cal = Calendar.current
        // The "morning you woke up". Default = today; an explicit yyyy-MM-dd is honored.
        let morning: Date
        var label = "昨晚"
        if let dateString = obj["date"] as? String,
           let parsed = Self.dateFormatter.date(from: dateString) {
            morning = cal.startOfDay(for: parsed)
            label = dateString
        } else {
            morning = cal.startOfDay(for: Date())
        }
        let noon = cal.date(byAdding: .hour, value: 12, to: morning) ?? morning
        let window = (start: noon.addingTimeInterval(-24 * 3600), end: noon)
        let priorDayStart = cal.date(byAdding: .day, value: -1, to: morning) ?? morning
        let dayWindow = (start: priorDayStart, end: morning)
        return ParsedRequest(metrics: metrics, dateLabel: label, window: window, dayWindow: dayWindow)
    }

    // MARK: - Queries

    #if canImport(HealthKit)
    private static func readSleep(store: HKHealthStore, window: (start: Date, end: Date)) async -> [String: Any] {
        guard let sleepType = HKObjectType.categoryType(forIdentifier: .sleepAnalysis) else {
            return ["error": "睡眠类型不可用"]
        }
        let samples = await categorySamples(store: store, type: sleepType, window: window)
        if samples.isEmpty {
            return ["available": false, "note": "该时间段没有睡眠记录（可能未佩戴设备或未授权）"]
        }

        var asleepSeconds = 0.0
        var inBedSeconds = 0.0
        var stageSeconds: [String: Double] = [:]
        var earliest = Date.distantFuture
        var latest = Date.distantPast

        for sample in samples {
            let duration = sample.endDate.timeIntervalSince(sample.startDate)
            let value = sample.value
            if value == HKCategoryValueSleepAnalysis.inBed.rawValue {
                inBedSeconds += duration
            } else if Self.asleepValues.contains(value) {
                asleepSeconds += duration
                stageSeconds[Self.stageLabel(value), default: 0] += duration
                earliest = min(earliest, sample.startDate)
                latest = max(latest, sample.endDate)
            }
        }

        var out: [String: Any] = [
            "available": true,
            "asleep_minutes": Int((asleepSeconds / 60).rounded()),
            "asleep_hours": Self.round1(asleepSeconds / 3600)
        ]
        if inBedSeconds > 0 { out["in_bed_minutes"] = Int((inBedSeconds / 60).rounded()) }
        if earliest != .distantFuture { out["fell_asleep"] = Self.iso(earliest) }
        if latest != .distantPast { out["woke_up"] = Self.iso(latest) }
        if !stageSeconds.isEmpty {
            out["stages_minutes"] = stageSeconds.mapValues { Int(($0 / 60).rounded()) }
        }
        return out
    }

    private static let asleepValues: Set<Int> = {
        var v: Set<Int> = [HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue]
        if #available(iOS 16.0, *) {
            v.formUnion([
                HKCategoryValueSleepAnalysis.asleepCore.rawValue,
                HKCategoryValueSleepAnalysis.asleepDeep.rawValue,
                HKCategoryValueSleepAnalysis.asleepREM.rawValue
            ])
        }
        return v
    }()

    private static func stageLabel(_ value: Int) -> String {
        if #available(iOS 16.0, *) {
            switch value {
            case HKCategoryValueSleepAnalysis.asleepCore.rawValue: return "core"
            case HKCategoryValueSleepAnalysis.asleepDeep.rawValue: return "deep"
            case HKCategoryValueSleepAnalysis.asleepREM.rawValue: return "rem"
            default: break
            }
        }
        return "asleep"
    }

    private static func categorySamples(
        store: HKHealthStore, type: HKCategoryType, window: (start: Date, end: Date)
    ) async -> [HKCategorySample] {
        let predicate = HKQuery.predicateForSamples(withStart: window.start, end: window.end, options: [])
        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: HKObjectQueryNoLimit, sortDescriptors: nil) { _, samples, _ in
                continuation.resume(returning: (samples as? [HKCategorySample]) ?? [])
            }
            store.execute(query)
        }
    }

    private static func readQuantityStats(
        store: HKHealthStore, identifier: HKQuantityTypeIdentifier, unit: HKUnit,
        window: (start: Date, end: Date), label: String
    ) async -> [String: Any] {
        guard let type = HKObjectType.quantityType(forIdentifier: identifier) else {
            return ["error": "指标不可用"]
        }
        let predicate = HKQuery.predicateForSamples(withStart: window.start, end: window.end, options: [])
        let stats: HKStatistics? = await withCheckedContinuation { continuation in
            let query = HKStatisticsQuery(quantityType: type, quantitySamplePredicate: predicate,
                                          options: [.discreteAverage, .discreteMin, .discreteMax]) { _, result, _ in
                continuation.resume(returning: result)
            }
            store.execute(query)
        }
        guard let stats else { return ["available": false] }
        var out: [String: Any] = ["available": true, "unit": label]
        if let avg = stats.averageQuantity()?.doubleValue(for: unit) { out["avg"] = Self.round1(avg) }
        if let mn = stats.minimumQuantity()?.doubleValue(for: unit) { out["min"] = Self.round1(mn) }
        if let mx = stats.maximumQuantity()?.doubleValue(for: unit) { out["max"] = Self.round1(mx) }
        if out["avg"] == nil && out["min"] == nil { out["available"] = false }
        return out
    }

    private static func readQuantitySum(
        store: HKHealthStore, identifier: HKQuantityTypeIdentifier, unit: HKUnit,
        window: (start: Date, end: Date), label: String
    ) async -> [String: Any] {
        guard let type = HKObjectType.quantityType(forIdentifier: identifier) else {
            return ["error": "指标不可用"]
        }
        let predicate = HKQuery.predicateForSamples(withStart: window.start, end: window.end, options: [])
        let stats: HKStatistics? = await withCheckedContinuation { continuation in
            let query = HKStatisticsQuery(quantityType: type, quantitySamplePredicate: predicate,
                                          options: [.cumulativeSum]) { _, result, _ in
                continuation.resume(returning: result)
            }
            store.execute(query)
        }
        guard let total = stats?.sumQuantity()?.doubleValue(for: unit) else { return ["available": false] }
        return ["available": true, "unit": label, "total": Self.round1(total)]
    }
    #endif

    // MARK: - Helpers

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    private static func iso(_ date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.string(from: date)
    }

    private static func round1(_ value: Double) -> Double {
        (value * 10).rounded() / 10
    }

    private static func json(_ object: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let string = String(data: data, encoding: .utf8) else {
            return "{\"ok\":false,\"error\":\"序列化失败\"}"
        }
        return string
    }
}
