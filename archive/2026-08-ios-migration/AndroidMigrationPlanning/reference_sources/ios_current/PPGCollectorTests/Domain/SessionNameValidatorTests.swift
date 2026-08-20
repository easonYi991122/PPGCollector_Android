import Testing
@testable import PPGCollector

struct SessionNameValidatorTests {
    @Test
    func acceptsAndNormalizesSupportedNames() throws {
        #expect(try SessionNameValidator.validate("  CUP_001-A  ") == "CUP_001-A")
        #expect(
            try SessionNameValidator.validate(String(repeating: "a", count: 64))
                == String(repeating: "a", count: 64)
        )
    }

    @Test
    func rejectsEmptyLongAndUnsafeNames() {
        #expect(throws: SessionNameValidationError.empty) {
            try SessionNameValidator.validate(" \n ")
        }
        #expect(throws: SessionNameValidationError.tooLong) {
            try SessionNameValidator.validate(String(repeating: "a", count: 65))
        }
        #expect(throws: SessionNameValidationError.invalidCharacters) {
            try SessionNameValidator.validate("../CUP 001")
        }
        #expect(throws: SessionNameValidationError.invalidCharacters) {
            try SessionNameValidator.validate("测试_001")
        }
    }
}
