# Code Review Skill Mandates

## Review Objectives
1. **Correctness**: Does the code fulfill the requirements and handle edge cases?
2. **Maintainability**: Is the code readable, well-documented, and following Kotlin/Compose idioms?
3. **Performance**: Are there any potential bottlenecks, especially in SMB streaming or UI rendering?
4. **Security**: Are credentials handled safely? Is there any risk of sensitive data leakage?

## Project-Specific Checklist
- **Media3**: Is `androidx.media3` used correctly? Are resources (like ExoPlayer) properly released?
- **Compose**: Are recompositions optimized? Is the theme (`VPTheme`) followed?
- **SMB**: Is `SmbDataSource` used for network content? Is error handling robust for network failures?
- **Architecture**: Does the change align with the MVVM pattern defined in `GEMINI.md`?

## Feedback Guidelines
- Be constructive and specific.
- Categorize comments: `[Critical]`, `[Suggestion]`, `[Nitpick]`.
- Always provide a rationale for requested changes.
