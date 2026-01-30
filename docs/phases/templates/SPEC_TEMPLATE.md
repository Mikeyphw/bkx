# Spec template

## File
`src/<tool>/core/specs/<name>.py`

## Requirements
- immutable/frozen
- normalized via a `normalize()` function or constructor normalization
- no IO

## Skeleton
- Fields:
  - ...
- `normalize(self, defaults, tool_detector)`:
  - resolve defaults
  - validate
  - compute derived fields

