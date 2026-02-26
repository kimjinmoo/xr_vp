# TDD (Test-Driven Development) Skill Mandates

## Core Principles
1. **Red-Green-Refactor**: Always follow the Red-Green-Refactor cycle. No production code should be written without a failing test first.
2. **Small Steps**: Write the smallest possible test case that fails, then write the minimal code to make it pass.
3. **Continuous Validation**: Run existing tests frequently to ensure no regressions are introduced.

## Testing Standards for VP Project
- **Unit Tests**: Use JUnit 4/5 and MockK (if available) for business logic in ViewModels and Services.
- **Compose Tests**: Use `androidx.compose.ui.test` for UI components.
- **Media3 Testing**: Validate ExoPlayer state transitions and `SmbDataSource` behavior using mock data sources.
- **Bug Fixes**: Every reported bug must have a reproduction test case before the fix is applied.

## Workflow
1. **Identify**: Define the behavior to be implemented or the bug to be fixed.
2. **Test**: Write a failing test in `app/src/test` or `app/src/androidTest`.
3. **Implement**: Write only enough code to pass the test.
4. **Refactor**: Clean up the code while keeping the test passing.
