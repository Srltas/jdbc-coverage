---
name: commit
description: >
  Create git commits following Conventional Commit style for this project.
  Use when the user asks to commit, save changes, or says "커밋", "커밋해줘", "/commit".
trigger: always
---

# Commit Skill — Conventional Commits (Lightweight)

## Commit Message Format

```
<type>: <short summary>
```

- **Subject line only** — body는 정말 필요한 경우에만 추가
- Subject는 **영어**, body는 한글 허용
- Subject는 **소문자**로 시작, 마침표 없음
- 최대 **50자** (subject)

## Types

| Type | When |
|---|---|
| `feat` | 새 기능, 새 모듈, 새 클래스 추가 |
| `fix` | 버그 수정 |
| `refactor` | 기능 변경 없이 코드 구조 개선 |
| `docs` | 문서, 주석, README, AGENT.md 변경 |
| `test` | 테스트 추가/수정 |
| `chore` | 빌드 설정, 의존성, CI 설정 등 |
| `style` | 포맷팅, 세미콜론 등 코드 의미 변경 없음 |

## Scope (Optional)

복잡한 변경일 때만 scope 사용:

```
feat(resolver): add inheritance chain resolution
fix(parser): handle generic type parameters
```

주요 scope: `spec`, `parser`, `resolver`, `detector`, `report`, `cli`

## Rules

1. **하나의 커밋 = 하나의 논리적 변경** — 여러 기능을 한 커밋에 넣지 않기
2. **동작하는 상태에서 커밋** — 빌드가 깨진 상태로 커밋하지 않기
3. **"what" 보다 "why"** — 코드를 보면 what은 알 수 있으므로, 왜 이 변경이 필요한지 담기
4. Body가 필요한 경우 (드묾):
   - breaking change가 있을 때
   - 변경 이유가 코드만으로 이해하기 어려울 때

## Examples

```
feat: add jdbc spec extractor from openjdk source
feat(parser): add source file parsing with javaparser
fix(resolver): handle diamond inheritance correctly
refactor: extract method signature matching to separate class
docs: update roadmap in AGENT.md
chore: add javaparser dependency to build.gradle.kts
test: add connection interface analysis tests
```

## Anti-patterns (하지 말 것)

```
# Too vague
fix: fix bug
feat: add feature
chore: update

# Too verbose
feat: add the ability to parse java source files and extract method signatures from them

# Mixed language in subject
feat: spec 추출기 추가

# Multiple changes
feat: add parser and fix resolver and update docs
```

## Process

1. `git status`와 `git diff`로 변경사항 확인
2. 변경의 성격에 맞는 type 선택
3. 50자 이내의 간결한 영어 subject 작성
4. 관련 파일만 staging (`git add -A` 지양, 구체적 파일 지정)
5. 커밋 생성
