@echo off
setlocal
if defined JAVA_HOME (
  set "GUARD_JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  set "GUARD_JAVA=java.exe"
)
"%GUARD_JAVA%" "-Dorg.gradle.appname=gradlew" -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %errorlevel%
