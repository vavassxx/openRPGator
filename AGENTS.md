# Build, test and conventions

## Build

    gradle build                    # full build (desktop/server; Android needs Android SDK)

## Tests

    gradle test                     # all JVM module unit tests

Fast smoke compile without Gradle (JDK 21, from `/tmp/luaj` with luaj-jse.jar):

    javac -cp out:luaj-jse.jar -d out $(find <module>/src/main/java -name "*.java")

Run engine-script tests with JUnit Platform standalone (classpath order matters):

    java -jar junit.jar execute --class-path "testout:out:luaj-jse.jar:junit.jar" --scan-class-path

## Notes

- No sandbox on Lua — `JsePlatform.standardGlobals()`, host owns scripts.
- Every edit to engine logic must keep `engine-script` `LuaApiTest` and `engine-network` `ProtocolTest` green.
- Push UI (`engine.notify`/`engine.dialog`) goes through `UiSink`; a network-backed sink lives in dedicated-server/desktop-client. Desktop client toast/choice UI rendering is still pending.