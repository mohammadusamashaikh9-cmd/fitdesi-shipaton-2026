@rem Gradle start-up script for Windows
@if "%DEBUG%"=="" @echo off
@setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo ERROR: JAVA_HOME is not set and no java executable was found on PATH. 1>&2
echo Install JDK 17 or use the JDK bundled with Android Studio. 1>&2
goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%\bin\java.exe

if exist "%JAVA_EXE%" goto execute

echo ERROR: JAVA_HOME does not point to a valid JDK: %JAVA_HOME% 1>&2
goto fail

:execute
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

"%JAVA_EXE%" -Xmx64m -Xms64m "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
if %ERRORLEVEL% equ 0 goto end

:fail
exit /b 1

:end
@endlocal
