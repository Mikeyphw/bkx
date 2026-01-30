# Typed error template

## File
`src/<tool>/core/errors.py`

## Rules
- All errors derive from `CoreError`
- Each has a stable exit code category mapping
- Include `.details` for richer CLI messaging

