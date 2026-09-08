# 실행 및 배포 구성

## 프레임워크 관점의 구성

이 예제는 별도 웹 서버를 제공하지 않는 Spring Boot CLI이다. 배포 단위는 실행용 JAR, 외부 모델 설정, 원문 디렉터리, 위키 작업 디렉터리이다.

```text
외부 설정 (프로필 / API 주소 / 모델 ID / 자격증명)
                  │
                  ▼
          Spring ChatModel Bean
                  │
           ┌──────┴──────┐
           ▼             ▼
  컴파일용 ChatClient   답변용 ChatClient
           │             ▲
     WikiCompiler     RAG Advisor
           │             ▲
           ▼             │
       WikiStore → DocumentRetriever
```

업무 로직은 `ChatModel`과 `ChatClient`를 사용한다. 모델 공급자의 인증·HTTP 경로·응답 형식은 구성 클래스와 어댑터 안에서 처리한다. 공급자를 바꾸더라도 위키 저장 형식, 검색기, Advisor 연결을 유지하는 것이 이 예제의 설계 의도이다. 공급자마다 모델 옵션과 출력 형식 지원이 다르므로 모든 기능이 설정 변경만으로 호환된다는 의미는 아니다.

| 계층 | 책임 | 교체·설정 위치 |
|---|---|---|
| 모델 연결 | 인증, 요청·응답 변환, 출력 한도 | ModelConfiguration, ChatModel 구현 |
| 지식 컴파일 | 주제별 페이지 생성 지시, 구조화 결과 변환 | WikiCompiler |
| 지식 저장 | 초안·발행본·원문 사본 보관 | WikiStore, wiki.workspace |
| 검색 | 질의에 관련된 Document 반환 | WikiDocumentRetriever |
| 답변 | 검색 문맥과 출처를 모델에 전달 | WikiAnswerer, RetrievalAugmentationAdvisor |

## 모델 연결과 프로필 구성

Spring AI가 제공하는 `ChatModel` 구현을 사용하거나, 대상 API에 맞는 어댑터를 구현해 Bean으로 등록한다. `ModelConfiguration`에서 이 Bean을 컴파일용·답변용 `ChatClient`에 연결한다. 프로필은 실행 환경에 맞는 Bean과 설정을 선택하는 단위이다.

모델 연결을 추가하거나 교체할 때는 다음 순서로 구성한다.

1. 대상 모델을 지원하는 의존성과 `ChatModel` 구현을 선택한다. 직접 구현한다면 인증, 요청·응답 변환, 오류 처리를 어댑터에 둔다.
2. 구성 클래스와 프로필 설정에서 사용할 모델 Bean을 정한다. 자동 구성과 직접 등록한 Bean이 중복되지 않도록 조정한다.
3. 컴파일용 `ChatClient`에 구조화 출력 설정을 적용한다. 현재 컴파일러는 `.call().entity(Draft.class)`로 결과를 변환하며, 모델 전용 JSON 옵션은 해당 구현의 지원 범위에 맞게 적용한다.
4. 답변용 `ChatClient`는 검색 문맥을 전달받아 텍스트 답변을 생성하도록 구성한다. 컴파일용 출력 설정을 답변에 일괄 적용하지 않는다.

이 예제에 필요한 모델 기능은 동기 텍스트 생성과 컴파일 결과의 구조화 변환이다. 모델을 교체할 때는 이러한 동작을 확인한다. 기존 프로필의 연결 계약은 [모델 연결 설정](MODEL_CONFIGURATION.md), 구체적인 설정 키는 [환경 설정 예시](../.env.example)에서 확인한다.

관련 계약: [Spring AI 1.0.1 ChatModel](https://github.com/spring-projects/spring-ai/blob/v1.0.1/spring-ai-model/src/main/java/org/springframework/ai/chat/model/ChatModel.java).

## 빌드

Java 17 이상과 Maven 3.9.x가 필요하다. 모듈 디렉터리에서 실행한다.

```powershell
mvn -B test package
```

배포 파일은 `target/spring-ai-rag-wiki-0.1.0-SNAPSHOT.jar`이다. 부모 POM은 전자정부프레임워크 5.0.0이며 Spring Boot 3.5.6과 Spring AI 1.0.1을 사용한다. 시스템 전역 Java 설정을 바꾸지 않아도 작업 프로세스에 적절한 JAVA_HOME을 지정해 빌드할 수 있다.

## 외부 설정

실행 환경별 값은 JAR 외부에서 주입한다. 아래 항목은 설정의 역할을 설명하며, 실제 속성 이름은 선택한 모델 구현과 프로필 설정을 따른다.

| 설정 항목 | 역할 | 적용 위치 |
|---|---|---|
| 활성 프로필 | 사용할 모델 Bean과 환경 설정 선택 | `SPRING_PROFILES_ACTIVE` |
| API 주소 | 외부 서비스 또는 내부 추론 서버 연결 | 모델 구현의 연결 설정 |
| 모델 ID | 해당 서버에서 사용할 모델 지정 | 모델 구현의 모델 옵션 |
| 자격증명 | 인증이 필요한 서버에 접근 | 환경변수 또는 비밀 설정 주입 |
| 요청 제한 | 연결·응답 대기 시간과 출력량 조정 | HTTP 클라이언트 설정 및 모델 옵션 |
| 원문·작업 경로 | 입력 문서와 위키 발행 이력 보관 | `wiki.sources`, `wiki.workspace` |

로컬 실행에서는 [환경 설정 예시](../.env.example)를 참고해 모듈 디렉터리에 `.env`를 준비한다. 사용할 프로필이 참조하는 실제 설정 키를 사용한다. Spring Config Import가 `.env`를 properties 형식으로 읽으므로 `export`나 셸 표현식 없이 `이름=값`으로 저장한다. 기존 파일의 설정은 보존하며 `.env`는 Git 제외 대상이다.

배포 환경에서는 같은 설정을 환경변수나 비밀 설정 주입 기능으로 제공한다. 자격증명은 JAR, 이미지, 소스, 명령행 인자에 포함하지 않는다. API 경로의 해석 방식과 요청·응답 계약은 선택한 `ChatModel` 구현을 따른다.

## 실행 명령

아래 명령은 모델 연결 설정을 준비한 상태에서 모듈 디렉터리에서 실행한다. 먼저 `구성한_프로필_이름`을 실제 구현된 프로필 이름으로 바꿔 지정한다. 새 이름을 지정하는 것만으로 모델 연결이 생성되지는 않는다. 이후 명령은 같은 PowerShell 세션의 활성 프로필을 사용한다.

```powershell
$env:SPRING_PROFILES_ACTIVE = "구성한_프로필_이름"
```

발행 명령의 `실제_초안_ID`도 컴파일 결과에 표시된 값으로 바꾼다.

```powershell
# 원문에서 새 위키 초안 생성
java -jar target/spring-ai-rag-wiki-0.1.0-SNAPSHOT.jar --wiki.action=compile

# 초안을 읽고 편집한 뒤 실제 DRAFT ID로 발행
java -jar target/spring-ai-rag-wiki-0.1.0-SNAPSHOT.jar --wiki.action=publish --wiki.draft=실제_초안_ID

# 검색만 확인 (모델 HTTP 호출 없음)
java -jar target/spring-ai-rag-wiki-0.1.0-SNAPSHOT.jar --wiki.action=search --wiki.query="재신청 예외"

# 검색 문맥으로 답변 생성
java -jar target/spring-ai-rag-wiki-0.1.0-SNAPSHOT.jar --wiki.action=ask --wiki.query="재신청 예외 조건은 무엇인가요?"
```

발행·검색은 모델 HTTP 요청을 보내지 않는다. 다만 최소 CLI는 시작 시 모델 Bean도 구성하므로 선택한 프로필의 설정값은 필요하다.

## 파일 보관과 갱신

원문 경로와 작업 경로를 외부 디렉터리로 지정할 수 있다.

```powershell
java -jar target/spring-ai-rag-wiki-0.1.0-SNAPSHOT.jar --wiki.action=compile --wiki.sources=D:/wiki-data/sources --wiki.workspace=D:/wiki-data/workspace
```

후속 발행·검색·답변 명령에도 동일한 `wiki.workspace`를 지정한다. 작업 디렉터리는 원문 사본을 포함하므로 원문과 같은 범위에서 관리한다. 재배포 시 JAR만 바꾸고 작업 디렉터리를 유지한다.

새 원문을 컴파일하면 별도 초안이 만들어진다. 새 초안을 편집·발행할 때만 `current.txt`가 새 발행본을 가리킨다. 이전 발행본은 자동 삭제하지 않는다. 전체 작업 디렉터리를 함께 백업하면 원문 사본과 발행 이력을 보관할 수 있다.

여러 프로세스의 동시 편집·발행을 조정하는 기능은 제공하지 않는다. 이 예제는 한 담당자가 순차적으로 컴파일·발행하는 실행을 전제로 한다.

## 모델 호출 한도와 장애 처리

연결 제한은 5초, 응답 읽기 제한은 120초이다. 모델에 따라 `--spring.http.client.read-timeout=300s`로 변경할 수 있다. 출력 토큰 한도는 선택한 모델 구현의 옵션으로 지정한다. 속성 이름과 허용 범위는 구현별로 다르며, 문서량과 구조화 결과 크기를 고려해 설정한다.

모델 연결 계층에서는 API 오류, 빈 응답, 출력 한도에 따른 중단의 처리 정책을 구성한다. 이러한 오류의 표시·처리 방식은 선택한 어댑터에서 확인한다. 컴파일 결과가 JSON 변환이나 저장 시 파일 계약을 만족하지 못하면 명령이 실패한다. 실패한 컴파일을 자동 발행하지 않으며 기존 발행본은 유지한다. 인증·모델 ID·할당량·네트워크 오류는 모델 연결 계층에서, 출력 형식과 초안 내용은 컴파일·편집 단계에서 확인한다.

### Windows 개발 환경의 인증서 저장소

기관 네트워크의 인증서가 Windows에는 신뢰되지만 새 JDK 저장소에는 없으면 PKIX 오류가 발생할 수 있다. Windows에서 승인된 신뢰 저장소를 사용하는 실행 예시는 다음과 같다. 이 옵션은 Windows용이며 시스템 전역 설정이나 인증서를 변경하지 않는다.

```powershell
java -Dfile.encoding=UTF-8 -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE -jar target/spring-ai-rag-wiki-0.1.0-SNAPSHOT.jar --wiki.action=compile
```

다른 운영 환경에서는 운영자가 관리하는 JVM 신뢰 저장소를 지정한다. 인증서 검증을 끄는 구성은 사용하지 않는다. [Java 17 SunMSCAPI 문서](https://docs.oracle.com/en/java/javase/17/security/oracle-providers.html)

구체적인 프로필과 설정 키는 [모델 연결 설정](MODEL_CONFIGURATION.md)에서 확인한다. 고정 응답 테스트는 구성요소 연결을 확인하며 실제 모델의 생성 품질을 평가하지 않는다.
