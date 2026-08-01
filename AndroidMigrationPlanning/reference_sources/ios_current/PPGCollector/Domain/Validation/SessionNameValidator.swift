import Foundation

nonisolated enum SessionNameValidationError: LocalizedError, Equatable {
    case empty
    case tooLong
    case invalidCharacters

    var errorDescription: String? {
        switch self {
        case .empty:
            "请输入记录名称。"
        case .tooLong:
            "记录名称最多 64 个字符。"
        case .invalidCharacters:
            "记录名称只能包含英文字母、数字、下划线和连字符。"
        }
    }
}

nonisolated enum SessionNameValidator {
    static func validate(_ input: String) throws -> String {
        let name = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            throw SessionNameValidationError.empty
        }
        guard name.count <= 64 else {
            throw SessionNameValidationError.tooLong
        }

        let isValid = name.unicodeScalars.allSatisfy { scalar in
            switch scalar.value {
            case 48...57, 65...90, 95, 97...122:
                true
            case 45:
                true
            default:
                false
            }
        }
        guard isValid else {
            throw SessionNameValidationError.invalidCharacters
        }
        return name
    }
}
