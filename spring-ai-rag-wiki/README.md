# Spring AI LLM Wiki RAG 예제

원문을 편집 가능한 Markdown 위키로 컴파일하고, 발행된 페이지를 Spring AI의 `DocumentRetriever`와 `RetrievalAugmentationAdvisor`에 연결하는 CLI 예제이다. 원문에서 파생 지식을 관리하는 방법을 RAG의 여러 구성 중 하나로 설명한다. 위키에 벡터 검색을 적용하는 구성도 가능하며, 이 예제는 작은 문서 모음에서 흐름을 확인하기 위해 키워드 검색을 사용한다.

```text
원문 → 위키 초안 생성 → 사람 편집 → 발행
                                    │
질문 → 위키 페이지 검색 → 검색 문맥 구성 → 답변
```

컴파일 단계에서 조건·예외·절차를 주제별로 정리하고 관련 페이지를 Markdown 링크로 연결하도록 지시한다. 생성된 내용을 직접 편집한 뒤 발행하며, 원문 변경 시 새 초안을 생성한다. 내부 링크는 사람이 페이지 관계를 확인하기 위한 표현이며 자동 탐색 기능을 제공하지 않는다.

| 자료 | 내용 |
|---|---|
| [기술 가이드](docs/springai-llm-wiki-rag.md) | 도입 이유와 참고 근거, 파이프라인, 구성요소별 구현 |
| [실행 및 배포](docs/deployment.md) | 모델 Bean 연결, 외부 설정, 저장 경로와 실행 명령 |
| [모델 연결 설정](docs/MODEL_CONFIGURATION.md) | 제공 연결 구현의 설정 키와 실행 조건 |
| [가상 원문](examples/sources/) | 신청 안내와 예외 안내 두 개의 Markdown 문서 |

## 실행 준비

Java 17 이상과 Maven 3.9.x를 사용한다. 부모 POM은 전자정부 표준프레임워크 5.0.0이며 Spring Boot 3.5.6과 Spring AI 1.0.1을 사용한다. 아래 명령은 이 모듈 디렉터리에서 실행한다.

```powershell
cd spring-ai-rag-wiki
mvn -B package
```

모델 없이 동작하는 자동 테스트는 임시 파일과 로컬 HTTP 고정 응답을 사용한다. 실제 컴파일과 답변에는 동기 텍스트 생성이 가능한 모델 연결을 준비해야 한다. 컴파일은 구조화된 JSON 결과를 요구하므로 선택한 모델의 출력 지원을 확인한다.

기본 `chat-model` 프로필의 [공통 모델 연결 설정](docs/MODEL_CONFIGURATION.md)을 확인하고 [.env.example](.env.example)을 참고해 모듈 디렉터리에 `.env`를 작성한다. 모델 ID의 `replace-with-...` 값은 반드시 해당 서버에서 사용할 수 있는 실제 ID로 교체한다. 인증이 필요한 연결은 사용 권한과 자격증명도 필요하다. 프로필 이름을 임의로 추가하는 것만으로 새로운 모델 연결이 생성되지는 않는다.

## 실행 순서

아래 명령은 `LLM_BASE_URL`, `LLM_COMPLETIONS_PATH`, `LLM_API_KEY`, `LLM_MODEL`을 준비한 같은 셸에서 실행한다.

```powershell
$env:SPRING_PROFILES_ACTIVE = "chat-model"

# 원문에서 초안 생성: 출력되는 DRAFT ID를 기록한다.
java -jar target/spring-ai-rag-wiki-0.1.0-SNAPSHOT.jar --wiki.action=compile

# workspace/drafts/<draft-id>/*.md를 읽고 편집한 뒤 실제 ID로 발행한다.
java -jar target/spring-ai-rag-wiki-0.1.0-SNAPSHOT.jar --wiki.action=publish --wiki.draft=실제_초안_ID

# 현재 발행본에서 검색되는 페이지와 출처를 확인한다.
java -jar target/spring-ai-rag-wiki-0.1.0-SNAPSHOT.jar --wiki.action=search --wiki.query="재신청 예외"

# 현재 발행본을 검색한 뒤 문맥과 출처를 사용해 답변한다.
java -jar target/spring-ai-rag-wiki-0.1.0-SNAPSHOT.jar --wiki.action=ask --wiki.query="재신청 예외 조건은 무엇인가요?"
```

`search`는 검색 결과를 살펴보기 위한 선택 단계이며 `ask`가 내부에서 검색을 수행한다. 발행과 검색은 모델 HTTP 요청을 보내지 않지만 CLI 시작 시 모델 Bean의 설정은 필요하다. 원문을 바꾼 경우 컴파일부터 다시 실행하고 새 초안을 편집·발행한다.

## 구성과 범위

- `WikiCompiler`: 원문과 출처 ID를 모델에 전달하고 `ChatClient`의 `.call().entity(...)`로 위키 초안을 생성한다.
- `WikiStore`: 원문 사본·해시, 초안, 발행 이력을 파일로 보관한다. 발행할 때 검색 가능한 파일 형식을 확인한 후 현재 버전 포인터를 바꾼다.
- `WikiDocumentRetriever`: 키워드 일치 점수로 최대 3개 전체 페이지를 선택한다.
- `WikiAnswerer`: Spring AI Advisor를 통해 검색 문맥을 답변용 `ChatClient`에 전달한다.
- `ChatCompletionsConfiguration`: 공통 외부 설정으로 Chat Completions 호환 API를 연결한다.
- `ModelConfiguration`: `ChatModel`을 컴파일용·답변용 `ChatClient`에 주입한다.

예제 자료는 가상 업무 안내이며 실제 행정 기준이 아니다. 생성 결과는 누락·왜곡될 수 있고 출처 표시는 의미적 정확성을 보증하지 않는다. 처음부터 전체 파이프라인을 살펴볼 수 있도록 단일 사용자 CLI로 구성했으며, 운영 서비스의 인증·동시 편집·증분 병합은 구현 범위 밖이다. 자세한 입력·출력 제한과 운영 시 고려사항은 기술 가이드를 따른다.

## 라이선스

저장소의 [Apache License 2.0](../LICENSE.txt)을 따른다.
