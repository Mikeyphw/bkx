# CLI command template

## File
`src/<tool>/cli/nouns/<noun>.py`

## Responsibilities
- parse args
- call Spec builder
- call service
- render Result
- map typed error → exit code

## Do not
- add business logic
- call ports directly

