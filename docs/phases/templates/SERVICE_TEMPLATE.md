# Service template

## File
`src/<tool>/core/services/<name>.py`

## Responsibilities
- accept Spec
- build ExecutionPlan (optional)
- execute steps via ports
- return Result
- raise typed errors only

## Checklist
- [ ] no rendering
- [ ] no parsing
- [ ] no UI imports

