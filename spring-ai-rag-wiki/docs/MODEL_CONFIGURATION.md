# 모델 연결 설정

이 예제는 `ChatModel`을 주입받는 위키 컴파일·답변 파이프라인과 모델 연결 구성을 분리한다.
기본 `chat-model` 프로필은 Chat Completions 호환 API에 연결한다.
공급자별 프로필·환경변수·기본 모델 대신 같은 `LLM_*` 설정을 사용한다.

## 구성요소

| 구성요소 | 역할 |
| --- | --- |
| [application-chat-model.yml](../src/main/resources/application-chat-model.yml) | 공통 외부 설정을 `wiki.llm.*`에 매핑 |
| [ChatCompletionsConfiguration.java](../src/main/java/com/example/wiki/ChatCompletionsConfiguration.java) | 선택한 호환 API를 Spring AI `ChatModel` Bean으로 연결 |
| [ModelConfiguration.java](../src/main/java/com/example/wiki/ModelConfiguration.java) | 컴파일용·답변용 `ChatClient`에 같은 모델 Bean 주입 |
| [CompleteTextChatModel.java](../src/main/java/com/example/wiki/CompleteTextChatModel.java) | 빈 응답·출력 한도 중단을 거부하고 정상 응답의 메타데이터 보존 |

기본 연결 구현은 Spring AI 1.0.1의 `OpenAiApi`·`OpenAiChatModel`을 사용한다.
이 이름은 사용하는 API 프로토콜 구현을 식별하며, 특정 회사의 서버나 모델을 선택하는 설정이 아니다.
프로토콜을 지원하는 서버의 주소·경로·모델 ID를 명시적으로 주입한다.
다른 API 계약을 사용하는 모델은 그 계약을 지원하는 `ChatModel` Bean을 연결하고 필요한 의존성·구성을 추가한다.

참고: [Spring AI 1.0.1 API 구현](https://github.com/spring-projects/spring-ai/blob/v1.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/api/OpenAiApi.java),
[ChatModel 구현](https://github.com/spring-projects/spring-ai/blob/v1.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java).

## 외부 설정

모듈 디렉터리의 [환경 설정 예시](../.env.example)를 참고해 `.env`를 작성한다.
아래 주소·모델 ID·키는 설명용 값이므로 실제 사용할 서버의 값으로 교체한다.

```properties
LLM_BASE_URL=https://model-gateway.example.com
LLM_COMPLETIONS_PATH=/v1/chat/completions
LLM_API_KEY=replace-with-your-key
LLM_MODEL=replace-with-your-model-id
LLM_MAX_TOKENS=8192
```

| 환경변수 | 대응 속성 | 역할 |
| --- | --- | --- |
| `LLM_BASE_URL` | `wiki.llm.base-url` | 경로를 제외한 서버의 HTTPS 주소 |
| `LLM_COMPLETIONS_PATH` | `wiki.llm.completions-path` | 생성 API 경로, 기본 `/v1/chat/completions` |
| `LLM_API_KEY` | `wiki.llm.api-key` | Bearer 인증 값 |
| `LLM_MODEL` | `wiki.llm.model` | 서버에서 사용할 수 있는 모델 ID |
| `LLM_MAX_TOKENS` | `wiki.llm.max-tokens` | `max_tokens` 출력 한도, 기본 8,192 |
| `LLM_TEMPERATURE` | `wiki.llm.temperature` | 선택한 모델이 지원할 때만 지정하는 선택 옵션 |

서버 주소·모델 ID·키에는 공급자 기본값이 없다. 필수 설정이 비어 있으면 시작 시 오류를 반환한다.
인증이 없는 로컬 호환 API에서도 SDK 구성에 필요한 키 문자열은 지정해야 하며, 서버가 무시하는 임의의 값을 사용할 수 있다.
로컬 테스트의 HTTP 허용 범위와 주소 형식은 연결 구성 코드가 확인한다.

주소와 경로는 합쳐서 하나의 요청 URL을 구성한다.
예를 들어 위 설정은 `https://model-gateway.example.com/v1/chat/completions`를 호출한다.
`/v1`을 주소와 경로에 중복 지정하지 않는다.
선택한 API가 `max_tokens` 또는 `temperature`를 지원하는지 확인하며, 온도는 미설정 시 전송하지 않는다.

`.env`는 Spring Config Import의 properties 형식으로 읽으므로 `export` 없이 `이름=값`으로 작성한다.
운영 환경에서는 같은 이름의 환경변수나 비밀 설정 주입 기능을 사용한다.
실제 자격증명은 소스·커밋·명령행 인자에 넣지 않는다.

## 실행

다른 활성 프로필이 없으면 기본 `chat-model` 프로필이 사용된다.
명시적으로 선택하려면 같은 셸에서 다음과 같이 설정한다.

```powershell
$env:SPRING_PROFILES_ACTIVE = "chat-model"
java -jar target/spring-ai-rag-wiki-0.1.0-SNAPSHOT.jar --wiki.action=compile
```

macOS/Linux에서는 `export SPRING_PROFILES_ACTIVE=chat-model`을 사용한다.
`chat-model`은 API 연결을 구성하는 프로필 이름이며 모델 ID는 `LLM_MODEL`에서 선택한다.
새 프로필 이름만 지정해도 새로운 모델 연결이 생기는 것은 아니다.

## 출력과 지원 범위

컴파일용 클라이언트는 `.call().entity(Draft.class)`의 스키마 지시와 변환기를 사용한다.
공급자별 JSON 모드 옵션은 기본으로 강제하지 않는다.
답변용 클라이언트는 위키 검색 문맥을 입력받아 텍스트를 생성한다.

이 예제는 동기 텍스트 생성만 사용한다.
API 오류, 빈 응답, 출력 한도 등으로 완료되지 않은 응답을 처리하며, JSON 변환 실패 시 초안을 발행하지 않는다.
스트리밍·도구 호출·멀티모달 입력은 위키 파이프라인의 구현 범위 밖이다.
API에 고유한 추가 필드나 인증 방식은 호환 여부를 별도로 확인한다.

자동 테스트는 로컬 고정 응답 서버에서 전체 파이프라인과 주소·경로·모델 ID 교체를 확인한다.
테스트 통과는 특정 실제 모델의 품질이나 모든 API의 호환성을 보장하지 않는다.
