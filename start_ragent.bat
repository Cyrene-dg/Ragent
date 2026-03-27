@echo off
setlocal enabledelayedexpansion
set CP=
for /f "usebackq delims=" %%i in ("G:\Ragent2026\ragent\bootstrap_cp.txt") do set CP=%%i
set CP=G:\Ragent2026\ragent\bootstrap\target\classes;G:\Ragent2026\ragent\framework\target\classes;G:\Ragent2026\ragent\infra-ai\target\classes;!CP!
if "%SILICONFLOW_API_KEY%"=="" (
  echo [WARN] SILICONFLOW_API_KEY is empty. Embedding calls to SiliconFlow may fail.
)
G:\jdk17_2026\bin\java.exe -Dfile.encoding=UTF-8 -DSILICONFLOW_API_KEY="%SILICONFLOW_API_KEY%" -cp "!CP!" com.nageoffer.ai.ragent.RagentApplication
