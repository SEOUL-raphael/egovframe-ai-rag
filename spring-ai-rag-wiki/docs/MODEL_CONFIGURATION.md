# 모델 연결 설정

[실행 및 배포 구성](deployment.md)은 Spring AI의 모델 연결 원리를 설명한다. 이 문서는 예제에 실제 포함된 연결 구현과 설정 키를 설명한다. 공급자 이름은 구현을 식별하기 위해 사용하며, 특정 모델의 성능이나 적합성을 권고하지 않는다.

## 제공 구성

| 구성 | 선택 방식 | ChatModel 구현 | 컴파일 출력 |
|---|---|---|---|
| 로컬 추론 서버 | `ollama` 프로필 또는 프로필 미지정 | Spring AI의 `OllamaChatModel` 자동 구성 | 컴파일용 클라이언트에 JSON 형식 옵션 적용 |
| 원격 네이티브 API | `minimax` 프로필 | 예제의 `MiniMaxChatModel` | `ChatClient.entity(Draft.class)`가 JSON 스키마 지시를 추가 |

[ModelConfiguration.java](../src/main/java/com/example/wiki/ModelConfiguration.java)에서 모델 Bean을 컴파일용·답변용 `ChatClient`에 연결한다. `ollama` 이름은 별도 설정 파일 없이 기본 연결 Bean을 선택하는 데 사용한다. 기본 컴파일용 Bean의 조건은 `!minimax`이므로, 알 수 없는 프로필 이름을 지정해도 별도의 연결이 생기지 않는다. 새로운 공급자를 추가할 때는 모델 의존성, Bean 조건, 구성 속성, 컴파일 출력 옵션을 함께 변경한다.

모든 구성은 동기 텍스트 호출을 사용한다. 답변용 클라이언트에는 컴파일용 JSON 옵션을 일괄 적용하지 않는다. 구조화 결과의 유효성은 모델 선택과 출력 한도에 영향을 받으며, 스키마 지시나 JSON 옵션이 내용의 정확성을 보장하지 않는다.

## 로컬 추론 서버

로컬 서버를 준비하고 해당 서버에서 사용할 수 있는 텍스트 생성 모델을 먼저 설치한다. 예제는 모델을 자동으로 다운로드하지 않는다. 아래는 모듈 디렉터리의 `.env` 예시이며, `replace-with-installed-model-id`를 설치된 모델의 실제 ID로 바꾼다.

```properties
OLLAMA_BASE_URL=http://localhost:11434
WIKI_MODEL=replace-with-installed-model-id
```

| 환경변수 | 대응 속성 | 역할 |
|---|---|---|
| `OLLAMA_BASE_URL` | `spring.ai.ollama.base-url` | 추론 서버 주소 |
| `WIKI_MODEL` | `spring.ai.ollama.chat.options.model` | 서버에 설치된 모델 ID |

기본 모델 ID는 교체용 문자열이므로 그대로는 모델을 호출할 수 없다. `application.yml`의 모델 옵션은 온도 0.1, 컨텍스트 8,192토큰, 최대 생성 4,096토큰이다. 실제 지원 범위에 맞게 `spring.ai.ollama.chat.options.*`를 조정한다. 코드의 문자 수 제한은 토큰 수를 계산한 값이 아니다.

PowerShell에서 이전에 설정한 활성 프로필이 있으면 해당 세션에서 해제한 뒤 실행한다. `.env`에도 별도의 활성 프로필을 지정하지 않는다.

```powershell
Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
java -jar target/spring-ai-rag-wiki-0.1.0-SNAPSHOT.jar --wiki.action=compile
```

macOS/Linux에서는 `unset SPRING_PROFILES_ACTIVE` 후 같은 실행 명령을 사용한다.

## 원격 네이티브 API

접근 권한이 있는 API와 모델을 준비한다. `.env`에서 API 키와 사용 가능한 실제 모델 ID를 지정한다. 아래 값 중 키와 모델 ID는 교체용 문자열이다.

```properties
MINIMAX_API_KEY=replace-with-your-key
MINIMAX_API_URL=https://api.minimax.io/v1/text/chatcompletion_v2
MINIMAX_MODEL=replace-with-accessible-model-id
```

| 환경변수 또는 속성 | 적용 위치 | 역할 |
|---|---|---|
| `MINIMAX_API_KEY` | `wiki.minimax.api-key` | API 인증 키 |
| `MINIMAX_API_URL` | `wiki.minimax.api-url` | 네이티브 API의 전체 HTTPS 주소 |
| `MINIMAX_MODEL` | `wiki.minimax.model` | 계정이 사용할 수 있는 모델 ID |
| `wiki.minimax.max-tokens` | 어댑터 요청의 `max_tokens` | 최대 출력 토큰, 기본 8,192 |

키는 `.env` 또는 배포 환경의 비밀 설정 주입 기능으로 전달한다. `.env`는 Git 제외 대상이며 커밋하지 않는다. 활성 프로필은 다음과 같이 지정한다.

```powershell
$env:SPRING_PROFILES_ACTIVE = "minimax"
java -jar target/spring-ai-rag-wiki-0.1.0-SNAPSHOT.jar --wiki.action=compile
```

macOS/Linux에서는 `export SPRING_PROFILES_ACTIVE=minimax` 후 같은 실행 명령을 사용한다.

[MiniMaxChatModel.java](../src/main/java/com/example/wiki/MiniMaxChatModel.java)는 Spring AI의 `ChatModel`을 구현한 최소 동기 어댑터다. 텍스트 system/user/assistant 메시지, 인증 헤더, 요청 직렬화와 HTTP 응답 변환을 처리한다. HTTP 상태뿐 아니라 응답 본문의 `base_resp.status_code`를 확인하고, 빈 텍스트나 출력 한도 중단을 오류로 처리한다. `reasoning_content`는 컴파일·답변 결과로 사용하지 않는다. 스트리밍, 도구 호출, 이미지 입력, 세부 사용량 통계는 제공하지 않는다.

## 공통 실행 조건

기본 HTTP 연결 제한은 5초, 응답 읽기 제한은 120초이며 `spring.http.client.*` 속성으로 조정한다. 모델 생성 시간이 더 길면 읽기 제한과 모델 출력 한도를 함께 검토한다. API 주소만 변경해 다른 공급자에 연결할 수 있다고 가정하지 않는다. 요청·응답 계약이 다르면 호환되는 `ChatModel` 구현이 필요하다.

발행·검색은 모델 요청을 보내지 않지만 현재 CLI는 시작 시 모델 Bean을 구성하므로 선택한 연결의 필수 설정은 필요하다. 자동 테스트는 로컬 HTTP 고정 응답으로 전송·파이프라인 연결을 확인한다. 테스트 통과는 실제 모델의 답변 품질이나 처리 성능을 평가한 결과가 아니다.
